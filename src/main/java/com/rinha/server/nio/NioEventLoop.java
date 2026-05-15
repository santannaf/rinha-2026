package com.rinha.server.nio;

import com.rinha.index.ReferenceDataset;
import com.rinha.index.SearchResult;
import com.rinha.server.service.FraudScoreService;
import com.rinha.server.service.ReadyService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Iterator;

/**
 * Loop de I/O single-thread. Uma única thread platform faz accept + read
 * + write usando 1 Selector. Toda a lógica de aplicação roda na mesma
 * thread — zero context switch entre I/O e processamento.
 *
 * Justificativa: produção Rinha roda com cpus="0.4" por container,
 * então 1 thread é o caso real. Multi-worker adicionaria complexidade
 * sem ganho neste regime.
 */
final class NioEventLoop implements Runnable {

    private static final long SELECT_TIMEOUT_MS = 1000L;

    private final int port;
    private final String udsPath;
    private final ReadyService readyService;
    private final FraudScoreService scoreService;

    // scratch buffers compartilhados — única thread acessa, sem race
    private final double[] queryScratch;
    private final SearchResult resultScratch;

    private Selector selector;
    private ServerSocketChannel serverChannel;
    private Thread thread;
    private volatile boolean running;

    private static final byte[] PATH_READY = "/ready".getBytes();
    private static final byte[] PATH_FRAUD_SCORE = "/fraud-score".getBytes();

    NioEventLoop(int port,
                 String udsPath,
                 ReadyService readyService,
                 FraudScoreService scoreService) {
        this.port = port;
        this.udsPath = udsPath == null ? "" : udsPath;
        this.readyService = readyService;
        this.scoreService = scoreService;
        this.queryScratch = new double[ReferenceDataset.DIM];
        this.resultScratch = new SearchResult(scoreService.topK());
    }

    boolean isUds() {
        return !udsPath.isEmpty();
    }

    String boundDescription() {
        return isUds() ? "unix:" + udsPath : "0.0.0.0:" + port;
    }

    void start() throws IOException {
        this.selector = Selector.open();
        if (isUds()) {
            Path p = Path.of(udsPath);
            Path parent = p.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.deleteIfExists(p);
            this.serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            serverChannel.configureBlocking(false);
            serverChannel.bind(UnixDomainSocketAddress.of(p), 1024);
            // Bind cria o socket com mode 0755 (sujeito a umask). nginx
            // alpine roda workers como user `nginx`, que precisa de write
            // pra connect() em UDS. Abrimos pra 0666 — tmpfs dedicado e
            // namespace de container, security não é preocupação.
            try {
                Files.setPosixFilePermissions(p,
                        PosixFilePermissions.fromString("rw-rw-rw-"));
            } catch (UnsupportedOperationException ignored) {
                // FS não-POSIX (Windows dev): segue em frente.
            }
        } else {
            this.serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);
            serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            serverChannel.bind(new InetSocketAddress("0.0.0.0", port), 1024);
        }
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        this.running = true;
        this.thread = Thread.ofPlatform().name("nio-loop").start(this);
    }

    void stop() throws IOException, InterruptedException {
        running = false;
        if (selector != null) selector.wakeup();
        if (thread != null) thread.join(5000);
        closeQuietly(serverChannel);
        if (selector != null) {
            for (SelectionKey k : selector.keys()) {
                if (k.attachment() instanceof Connection c) closeQuietly(c.channel);
            }
            closeQuietly(selector);
        }
        if (isUds()) {
            try { Files.deleteIfExists(Path.of(udsPath)); } catch (IOException ignored) {}
        }
    }

    @Override
    public void run() {
        while (running) {
            try {
                selector.select(SELECT_TIMEOUT_MS);
            } catch (IOException ex) {
                System.err.println("[nio] select failed: " + ex.getMessage());
                continue;
            }
            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
            while (it.hasNext()) {
                SelectionKey k = it.next();
                it.remove();
                if (!k.isValid()) continue;
                try {
                    if (k.isAcceptable()) handleAccept();
                    else if (k.isReadable()) handleRead(k);
                    else if (k.isWritable()) handleWrite(k);
                } catch (IOException ex) {
                    closeConnection(k);
                }
            }
        }
    }

    private void handleAccept() throws IOException {
        SocketChannel sc;
        boolean uds = isUds();
        while ((sc = serverChannel.accept()) != null) {
            // TCP_NODELAY não se aplica em AF_UNIX — Nagle só existe em TCP.
            if (!uds) sc.setOption(StandardSocketOptions.TCP_NODELAY, true);
            sc.configureBlocking(false);
            SelectionKey k = sc.register(selector, SelectionKey.OP_READ);
            Connection conn = new Connection(sc, k);
            k.attach(conn);
        }
    }

    private void handleRead(SelectionKey k) throws IOException {
        Connection conn = (Connection) k.attachment();
        int n = conn.channel.read(conn.readBuf);
        if (n == -1) {
            closeConnection(k);
            return;
        }
        if (n == 0) return;
        conn.readBuf.flip();
        // pode haver mais de uma request acumulada (pipelining sequencial raro)
        while (true) {
            HttpRequestParser.Status s = conn.parser.feed(conn.readBuf);
            switch (s) {
                case NEED_MORE:
                    conn.readBuf.compact();
                    return;
                case REQUEST_READY:
                    route(conn);
                    conn.parser.reset();
                    if (conn.hasPendingWrite()) {
                        // a próxima request espera o write atual drenar
                        conn.readBuf.compact();
                        return;
                    }
                    if (!conn.readBuf.hasRemaining()) {
                        conn.readBuf.clear();
                        return;
                    }
                    // continua: pode ter outra request no buffer
                    break;
                case ERROR_TOO_LARGE:
                    sendAndClose(conn, PreRendered.PAYLOAD_TOO_LARGE_413);
                    return;
                case ERROR_BAD:
                    sendAndClose(conn, PreRendered.BAD_REQUEST_400);
                    return;
            }
        }
    }

    private void route(Connection conn) throws IOException {
        HttpRequestParser p = conn.parser;
        HttpRequestParser.Method method = p.method();
        byte[] path = p.pathBytes();
        int pathLen = p.pathLen();
        boolean keepAlive = p.keepAlive();

        if (method == HttpRequestParser.Method.GET
                && pathEquals(path, pathLen, PATH_READY)) {
            byte[] resp = readyService.handle() == ReadyService.READY
                    ? PreRendered.READY_200
                    : PreRendered.LOADING_503;
            sendResponse(conn, resp, !keepAlive);
            return;
        }
        if (method == HttpRequestParser.Method.POST
                && pathEquals(path, pathLen, PATH_FRAUD_SCORE)) {
            int v = scoreService.handle(p.bodyBytes(), 0, p.bodyLen(),
                    queryScratch, resultScratch);
            byte[] resp;
            boolean close = !keepAlive;
            if (v >= 0) {
                resp = PreRendered.SCORE_200[v];
            } else if (v == FraudScoreService.LOADING) {
                resp = PreRendered.LOADING_503;
            } else {
                resp = PreRendered.BAD_REQUEST_400;
                close = true;
            }
            sendResponse(conn, resp, close);
            return;
        }
        // método/rota inválidos
        if (method == HttpRequestParser.Method.OTHER
                || (method == HttpRequestParser.Method.GET
                && pathEquals(path, pathLen, PATH_FRAUD_SCORE))
                || (method == HttpRequestParser.Method.POST
                && pathEquals(path, pathLen, PATH_READY))) {
            sendAndClose(conn, PreRendered.METHOD_NOT_ALLOWED_405);
            return;
        }
        sendAndClose(conn, PreRendered.NOT_FOUND_404);
    }

    private static boolean pathEquals(byte[] buf, int len, byte[] target) {
        if (len != target.length) return false;
        for (int i = 0; i < len; i++) if (buf[i] != target[i]) return false;
        return true;
    }

    private void sendResponse(Connection conn, byte[] resp, boolean closeAfter) throws IOException {
        ByteBuffer wrap = ByteBuffer.wrap(resp);
        int written = conn.channel.write(wrap);
        if (written < resp.length) {
            conn.pendingWrite = resp;
            conn.pendingOffset = written;
            conn.closeAfterWrite = closeAfter;
            conn.key.interestOps(SelectionKey.OP_WRITE);
            return;
        }
        if (closeAfter) {
            closeConnection(conn.key);
        } else {
            // keep-alive: continua lendo
            conn.key.interestOps(SelectionKey.OP_READ);
        }
    }

    private void sendAndClose(Connection conn, byte[] resp) throws IOException {
        sendResponse(conn, resp, true);
    }

    private void handleWrite(SelectionKey k) throws IOException {
        Connection conn = (Connection) k.attachment();
        if (!conn.hasPendingWrite()) {
            conn.key.interestOps(SelectionKey.OP_READ);
            return;
        }
        ByteBuffer wrap = ByteBuffer.wrap(conn.pendingWrite,
                conn.pendingOffset,
                conn.pendingWrite.length - conn.pendingOffset);
        int n = conn.channel.write(wrap);
        conn.pendingOffset += n;
        if (conn.pendingOffset >= conn.pendingWrite.length) {
            boolean close = conn.closeAfterWrite;
            conn.pendingWrite = null;
            conn.pendingOffset = 0;
            conn.closeAfterWrite = false;
            if (close) {
                closeConnection(k);
            } else {
                conn.key.interestOps(SelectionKey.OP_READ);
            }
        }
        // senão fica registrado OP_WRITE até completar
    }

    private void closeConnection(SelectionKey k) {
        Object att = k.attachment();
        k.cancel();
        if (att instanceof Connection c) closeQuietly(c.channel);
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c == null) return;
        try { c.close(); } catch (Exception ignored) {}
    }
}
