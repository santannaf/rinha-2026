package com.rinha.server.uds;

import com.rinha.config.AppConfig;
import com.rinha.index.ReferenceDataset;
import com.rinha.index.SearchResult;
import com.rinha.server.HttpServer;
import com.rinha.server.nio.PreRendered;
import com.rinha.server.service.FraudScoreService;
import com.rinha.server.service.ReadyService;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.Semaphore;

/**
 * Servidor HTTP/1.1 bloqueante: uma virtual thread por conexão.
 *
 * Modelo (espelha o design do Diego, p99 ~3.5ms no alvo Mac Mini / 1 CPU):
 *   - {@code ServerSocketChannel} sobre Unix Domain Socket (família UNIX),
 *     I/O BLOQUEANTE — sem Selector, sem non-blocking.
 *   - Loop de {@code accept()} bloqueante; cada conexão →
 *     {@code Thread.startVirtualThread(...)}.
 *   - Cada virtual thread faz keep-alive: loop {@code while(true)} lendo
 *     requests sequenciais na mesma conexão até EOF/erro.
 *   - Um {@link Semaphore}(1) COMPARTILHADO serializa a busca k-NN: como o
 *     orçamento Rinha é 1 CPU efetiva por container, processar uma query de
 *     cada vez evita thrash de cache e mantém a cauda do p99 baixa.
 *   - Cada conexão aloca seus PRÓPRIOS scratch buffers ({@code queryScratch} +
 *     {@link SearchResult}) — várias virtual threads ativas, sem compartilhar.
 *
 * Falha de qualquer request → conexão fechada silenciosamente.
 */
public final class UdsHttpServer implements HttpServer {

    private static final byte[] PATH_READY = "/ready".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PATH_FRAUD_SCORE = "/fraud-score".getBytes(StandardCharsets.US_ASCII);

    /** Header HTTP cabe folgado em 2 KiB; body típico ~400 B, MAX 8 KiB. */
    private static final int HEADER_MAX = 2048;
    private static final int INPUT_BUF = 768;
    private static final int OUTPUT_BUF = 512;
    private static final int ACCEPT_BACKLOG = 8192;

    private final int port;
    private final String udsPath;
    private final ReadyService readyService;
    private final FraudScoreService scoreService;

    /** Serializa a busca k-NN entre todas as conexões — 1 query por vez. */
    private final Semaphore knnSlot = new Semaphore(1);

    private volatile boolean running;
    private ServerSocketChannel serverChannel;
    private Thread acceptThread;

    public UdsHttpServer(AppConfig cfg,
                         ReadyService readyService,
                         FraudScoreService scoreService) {
        this.port = cfg.serverPort();
        this.udsPath = cfg.udsPath() == null ? "" : cfg.udsPath();
        this.readyService = readyService;
        this.scoreService = scoreService;
    }

    private boolean isUds() {
        return !udsPath.isEmpty();
    }

    private String boundDescription() {
        return isUds() ? "unix:" + udsPath : "0.0.0.0:" + port;
    }

    @Override
    public void start() {
        try {
            if (isUds()) {
                Path p = Path.of(udsPath);
                Path parent = p.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                    chmod777(parent);
                }
                Files.deleteIfExists(p);
                serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
                serverChannel.bind(UnixDomainSocketAddress.of(p), ACCEPT_BACKLOG);
                // nginx/haproxy conectam ao socket como outro user — abrimos 0777.
                chmod777(p);
            } else {
                serverChannel = ServerSocketChannel.open();
                serverChannel.bind(new InetSocketAddress("0.0.0.0", port), ACCEPT_BACKLOG);
            }
        } catch (IOException ex) {
            throw new RuntimeException("uds server start failed at " + boundDescription(), ex);
        }
        running = true;
        acceptThread = Thread.ofPlatform().name("uds-accept").start(this::acceptLoop);
        System.out.println("[server] uds listening on " + boundDescription());
    }

    @Override
    public void stop() {
        running = false;
        closeQuietly(serverChannel);
        if (acceptThread != null) {
            try {
                acceptThread.join(5000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        if (isUds()) {
            try {
                Files.deleteIfExists(Path.of(udsPath));
            } catch (IOException ignored) {
                // best-effort
            }
        }
    }

    private void acceptLoop() {
        while (running) {
            SocketChannel ch;
            try {
                ch = serverChannel.accept();
            } catch (IOException ex) {
                if (running) {
                    System.err.println("[uds] accept failed: " + ex.getMessage());
                }
                return;
            }
            Thread.startVirtualThread(() -> handleConnection(ch));
        }
    }

    private void handleConnection(SocketChannel ch) {
        // Scratch buffers PRÓPRIOS desta conexão — uma virtual thread só os
        // toca, reaproveitados pelos requests sequenciais da conexão.
        double[] queryScratch = new double[ReferenceDataset.DIM];
        SearchResult resultScratch = new SearchResult(scoreService.topK());

        try (SocketChannel c = ch;
             InputStream in = new BufferedInputStream(Channels.newInputStream(c), INPUT_BUF);
             OutputStream out = new BufferedOutputStream(Channels.newOutputStream(c), OUTPUT_BUF)) {
            serveStreams(in, out, queryScratch, resultScratch);
        } catch (Throwable ignored) {
            // conexão encerrada / cliente desconectou — nada a fazer.
        }
    }

    /**
     * Loop keep-alive: lê requests sequenciais na mesma conexão até EOF,
     * erro ou {@code Connection: close}.
     */
    private void serveStreams(InputStream in, OutputStream out,
                              double[] queryScratch, SearchResult resultScratch)
            throws IOException {
        byte[] header = new byte[HEADER_MAX];
        byte[] body = new byte[HttpRequest.MAX_BODY];

        while (true) {
            int headerLen = readHeaders(in, header);
            if (headerLen <= 0) {
                return; // EOF ou header malformado/grande demais
            }

            HttpRequest req = HttpRequest.parse(header, headerLen);
            if (req == null) {
                out.write(PreRendered.BAD_REQUEST_400);
                out.flush();
                return;
            }

            int contentLength = req.contentLength;
            if (contentLength > body.length) {
                out.write(PreRendered.PAYLOAD_TOO_LARGE_413);
                out.flush();
                return; // 413 fecha a conexão
            }
            if (contentLength > 0) {
                readFully(in, body, contentLength);
            }

            byte[] resp;
            boolean closeAfter = !req.keepAlive;

            if (req.method == HttpRequest.GET
                    && pathEquals(req.path, req.pathLen, PATH_READY)) {
                resp = readyService.handle() == ReadyService.READY
                        ? PreRendered.READY_200
                        : PreRendered.LOADING_503;
            } else if (req.method == HttpRequest.POST
                    && pathEquals(req.path, req.pathLen, PATH_FRAUD_SCORE)) {
                // A vetorização + busca k-NN rodam DENTRO do semáforo.
                knnSlot.acquireUninterruptibly();
                int v;
                try {
                    v = scoreService.handle(body, 0, contentLength,
                            queryScratch, resultScratch);
                } finally {
                    knnSlot.release();
                }
                if (v >= 0) {
                    resp = PreRendered.SCORE_200[v];
                } else if (v == FraudScoreService.LOADING) {
                    resp = PreRendered.LOADING_503;
                } else {
                    resp = PreRendered.BAD_REQUEST_400;
                    closeAfter = true;
                }
            } else if (isMethodMismatch(req)) {
                resp = PreRendered.METHOD_NOT_ALLOWED_405;
                closeAfter = true;
            } else {
                resp = PreRendered.NOT_FOUND_404;
                closeAfter = true;
            }

            out.write(resp);
            out.flush();
            if (closeAfter) {
                return;
            }
        }
    }

    /** Rota conhecida porém com método trocado (GET /fraud-score, POST /ready). */
    private static boolean isMethodMismatch(HttpRequest req) {
        return (req.method == HttpRequest.GET
                && pathEquals(req.path, req.pathLen, PATH_FRAUD_SCORE))
                || (req.method == HttpRequest.POST
                && pathEquals(req.path, req.pathLen, PATH_READY));
    }

    private static boolean pathEquals(byte[] buf, int len, byte[] target) {
        if (len != target.length) return false;
        for (int i = 0; i < len; i++) {
            if (buf[i] != target[i]) return false;
        }
        return true;
    }

    /**
     * Lê os bytes do header (até o {@code \r\n\r\n}) byte-a-byte pro {@code buf}.
     * Retorna o nº de bytes lidos (inclui o CRLFCRLF final), {@code -1} em EOF
     * antes de qualquer byte, ou {@code 0} se estourar a capacidade.
     */
    private static int readHeaders(InputStream in, byte[] buf) throws IOException {
        int n = 0;
        int state = 0; // 0=base, 1=\r, 2=\r\n, 3=\r\n\r, 4=done
        while (n < buf.length) {
            int b = in.read();
            if (b < 0) return n == 0 ? -1 : 0;
            buf[n++] = (byte) b;
            state = switch (state) {
                case 0 -> b == '\r' ? 1 : 0;
                case 1 -> b == '\n' ? 2 : 0;
                case 2 -> b == '\r' ? 3 : 0;
                case 3 -> b == '\n' ? 4 : 0;
                default -> state;
            };
            if (state == 4) return n;
        }
        return 0; // header grande demais
    }

    private static void readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int r = in.read(buf, off, len - off);
            if (r < 0) throw new IOException("unexpected EOF in body");
            off += r;
        }
    }

    private static void chmod777(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxrwxrwx"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // FS não-POSIX (Windows dev) — segue em frente.
        }
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
            // best-effort
        }
    }
}
