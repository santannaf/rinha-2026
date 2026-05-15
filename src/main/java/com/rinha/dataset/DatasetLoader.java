package com.rinha.dataset;

import com.rinha.config.AppConfig;
import com.rinha.index.ReferenceDataset;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.zip.GZIPInputStream;

/**
 * Carrega o dataset de referência a partir de:
 *  1. {@code config.datasetPath()} se setado e existir;
 *  2. caso contrário, {@code references.json.gz} no classpath;
 *  3. caso contrário e {@code DEV_FALLBACK=true}, gera dataset sintético.
 *
 * Formato esperado:
 *   - JSON array top-level: [ {"vector":[..14..],"label":"fraud|legit"}, ... ]
 *   - ou NDJSON (uma linha por registro).
 *
 * Estratégia: parser de tokens streaming customizado, escrevendo direto
 * em chunks de double[] e byte[] (sem construir árvore intermediária).
 *
 * Memória durante o load:
 *   - Chunks de DIM * CHUNK_RECORDS doubles cada (default ~7MB por chunk).
 *   - No final, copia tudo para um único double[] contíguo.
 *   - Pico ≈ 1.5x do tamanho final (chunks + array consolidado).
 */
public final class DatasetLoader {

    public static final String CLASSPATH_RESOURCE = "references.json.gz";

    private static final int CHUNK_RECORDS = 65_536;
    private static final int DIM = ReferenceDataset.DIM;

    private DatasetLoader() {}

    public static ReferenceDataset load(AppConfig cfg) throws IOException {
        String path = cfg.datasetPath();
        if (path != null && path.endsWith(".bin")) {
            Path p = Path.of(path);
            if (Files.exists(p)) {
                // mmap por padrão: page cache compartilha as páginas entre
                // processos que abrirem a mesma inode, e o cgroup v2 conta
                // uma vez só. Essencial pra fechar 2 instâncias em 350 MiB.
                // MMAP=false força leitura streaming pra heap (debug/legado).
                if (cfg.mmap()) {
                    System.out.println("[dataset] mmap'ing " + path
                            + " (prefetch=" + cfg.mmapPrefetch()
                            + " hugepage=" + cfg.mmapHugepage() + ")");
                    return BinaryDataset.mmap(p, cfg.mmapPrefetch(), cfg.mmapHugepage());
                }
                System.out.println("[dataset] reading " + path + " into heap (MMAP=false)");
                try (InputStream in = new BufferedInputStream(new FileInputStream(p.toFile()), 1 << 20)) {
                    return BinaryDataset.read(in);
                }
            }
            System.out.println("[dataset] DATASET_PATH .bin set but not found: " + path);
        }
        InputStream raw = openInputStream(cfg);
        if (raw == null) {
            if (!cfg.devFallback()) {
                throw new IOException("references.json(.gz) not found and DEV_FALLBACK=false");
            }
            System.out.println("[dataset] no references found, generating synthetic dev dataset of " + cfg.devDatasetSize());
            return generateSynthetic(cfg.devDatasetSize(), cfg.indexSeed());
        }
        try (InputStream in = wrapIfGzip(raw)) {
            return streamParse(in);
        }
    }

    private static InputStream openInputStream(AppConfig cfg) throws IOException {
        String path = cfg.datasetPath();
        if (path != null && !path.isEmpty()) {
            Path p = Path.of(path);
            if (Files.exists(p)) {
                return new BufferedInputStream(new FileInputStream(p.toFile()));
            }
            System.out.println("[dataset] DATASET_PATH set but not found: " + path);
        }
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        InputStream cp = cl.getResourceAsStream(CLASSPATH_RESOURCE);
        if (cp != null) return new BufferedInputStream(cp);
        return null;
    }

    /**
     * Detecta gzip pelos 2 primeiros bytes (0x1f 0x8b). Mantém compatibilidade
     * com arquivo .json puro também.
     */
    private static InputStream wrapIfGzip(InputStream in) throws IOException {
        PushbackInputStream pb = new PushbackInputStream(in, 2);
        int b1 = pb.read();
        int b2 = pb.read();
        if (b1 == -1) return pb;
        if (b2 != -1) pb.unread(b2);
        pb.unread(b1);
        if (b1 == 0x1f && b2 == 0x8b) {
            return new GZIPInputStream(pb);
        }
        return pb;
    }

    /**
     * Parser de tokens streaming. Não constrói árvore intermediária.
     * Suporta:
     *   - array top-level [{...},{...}]
     *   - NDJSON (objetos separados por newline)
     */
    private static ReferenceDataset streamParse(InputStream in) throws IOException {
        ChunkedFloatBuffer flatBuf = new ChunkedFloatBuffer(CHUNK_RECORDS * DIM);
        ChunkedByteBuffer labelBuf = new ChunkedByteBuffer(CHUNK_RECORDS);

        TokenReader r = new TokenReader(in);
        r.skipWs();

        int first = r.peek();
        if (first == '[') {
            r.next();
            r.skipWs();
            if (r.peek() == ']') {
                r.next();
                return finalize(flatBuf, labelBuf, 0);
            }
            int count = 0;
            while (true) {
                parseRecord(r, flatBuf, labelBuf);
                count++;
                r.skipWs();
                int c = r.peek();
                if (c == ',') { r.next(); r.skipWs(); continue; }
                if (c == ']') { r.next(); break; }
                throw new IOException("Expected , or ] at record " + count);
            }
            return finalize(flatBuf, labelBuf, count);
        }

        // NDJSON: lê objetos um a um.
        int count = 0;
        while (r.peek() != -1) {
            r.skipWs();
            if (r.peek() == -1) break;
            parseRecord(r, flatBuf, labelBuf);
            count++;
            r.skipWs();
        }
        return finalize(flatBuf, labelBuf, count);
    }

    private static ReferenceDataset finalize(ChunkedFloatBuffer flatBuf, ChunkedByteBuffer labelBuf, int size) {
        float[] flat = flatBuf.toArray();
        byte[] labels = labelBuf.toArray();
        return new ReferenceDataset(flat, labels, size);
    }

    /**
     * Lê um registro: { "vector": [d0..d13], "label": "fraud|legit" }.
     * Ordem das chaves pode variar. Outros campos são ignorados.
     */
    private static void parseRecord(TokenReader r, ChunkedFloatBuffer flatBuf, ChunkedByteBuffer labelBuf) throws IOException {
        r.skipWs();
        r.expect('{');
        double[] vec = new double[DIM];
        boolean vecSeen = false;
        byte label = ReferenceDataset.LABEL_LEGIT;
        boolean labelSeen = false;

        while (true) {
            r.skipWs();
            if (r.peek() == '}') { r.next(); break; }
            String key = r.readString();
            r.skipWs();
            r.expect(':');
            r.skipWs();
            switch (key) {
                case "vector" -> {
                    r.expect('[');
                    for (int i = 0; i < DIM; i++) {
                        r.skipWs();
                        vec[i] = r.readNumber();
                        r.skipWs();
                        if (i < DIM - 1) r.expect(',');
                    }
                    r.skipWs();
                    r.expect(']');
                    vecSeen = true;
                }
                case "label" -> {
                    String s = r.readString();
                    label = "fraud".equals(s) ? ReferenceDataset.LABEL_FRAUD : ReferenceDataset.LABEL_LEGIT;
                    labelSeen = true;
                }
                default -> r.skipValue();
            }
            r.skipWs();
            int c = r.peek();
            if (c == ',') { r.next(); continue; }
            if (c == '}') { r.next(); break; }
            throw new IOException("Expected , or } in record");
        }

        if (!vecSeen) throw new IOException("Record missing 'vector'");
        if (!labelSeen) throw new IOException("Record missing 'label'");
        flatBuf.appendAll(vec);
        labelBuf.append(label);
    }

    private static ReferenceDataset generateSynthetic(int n, long seed) {
        Random rnd = new Random(seed);
        float[] flat = new float[n * DIM];
        byte[] labels = new byte[n];
        for (int i = 0; i < n; i++) {
            int off = i * DIM;
            for (int d = 0; d < DIM; d++) {
                flat[off + d] = rnd.nextFloat();
            }
            labels[i] = (rnd.nextDouble() < 0.15) ? ReferenceDataset.LABEL_FRAUD : ReferenceDataset.LABEL_LEGIT;
        }
        return new ReferenceDataset(flat, labels, n);
    }

    /**
     * Tokenizer mínimo orientado a stream. Não constrói árvore;
     * cliente sabe o esperado e chama readNumber/readString/skipValue.
     */
    private static final class TokenReader {
        private final InputStream in;
        private int peek = -2;

        TokenReader(InputStream in) {
            this.in = in;
        }

        int peek() throws IOException {
            if (peek == -2) peek = in.read();
            return peek;
        }

        int next() throws IOException {
            int c = peek();
            peek = -2;
            return c;
        }

        void expect(char c) throws IOException {
            int got = next();
            if (got != c) throw new IOException("Expected '" + c + "' got " + (char) got);
        }

        void skipWs() throws IOException {
            while (true) {
                int c = peek();
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') { next(); continue; }
                return;
            }
        }

        String readString() throws IOException {
            expect('"');
            StringBuilder sb = new StringBuilder(16);
            while (true) {
                int c = next();
                if (c == -1) throw new IOException("Unterminated string");
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    int esc = next();
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            char[] hex = new char[4];
                            for (int i = 0; i < 4; i++) hex[i] = (char) next();
                            sb.append((char) Integer.parseInt(new String(hex), 16));
                        }
                        default -> throw new IOException("Bad escape \\" + (char) esc);
                    }
                } else {
                    sb.append((char) c);
                }
            }
        }

        double readNumber() throws IOException {
            StringBuilder sb = new StringBuilder(8);
            int c = peek();
            if (c == '-' || c == '+') { sb.append((char) next()); c = peek(); }
            while (c != -1 && (Character.isDigit(c) || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-')) {
                sb.append((char) next());
                c = peek();
            }
            return Double.parseDouble(sb.toString());
        }

        /**
         * Pula um valor JSON arbitrário (chave desconhecida). Não materializa.
         */
        void skipValue() throws IOException {
            skipWs();
            int c = peek();
            if (c == '{') { skipObject(); return; }
            if (c == '[') { skipArray(); return; }
            if (c == '"') { readString(); return; }
            // número/bool/null: consome até delimitador.
            while (true) {
                int cc = peek();
                if (cc == -1 || cc == ',' || cc == '}' || cc == ']' || cc == ' '
                        || cc == '\n' || cc == '\t' || cc == '\r') return;
                next();
            }
        }

        private void skipObject() throws IOException {
            expect('{');
            skipWs();
            if (peek() == '}') { next(); return; }
            while (true) {
                skipWs();
                readString();
                skipWs();
                expect(':');
                skipWs();
                skipValue();
                skipWs();
                int c = next();
                if (c == ',') continue;
                if (c == '}') return;
                throw new IOException("Expected , or } in skipObject");
            }
        }

        private void skipArray() throws IOException {
            expect('[');
            skipWs();
            if (peek() == ']') { next(); return; }
            while (true) {
                skipValue();
                skipWs();
                int c = next();
                if (c == ',') { skipWs(); continue; }
                if (c == ']') return;
                throw new IOException("Expected , or ] in skipArray");
            }
        }
    }

    /**
     * Buffer de floats em chunks. Recebe um array de doubles (precisão do parser
     * de JSON) e narrow-cast para float. Concatena em um float[] contíguo ao final.
     */
    private static final class ChunkedFloatBuffer {
        private final int chunkSize;
        private float[] current;
        private int currentPos;
        private long total;
        private final java.util.ArrayList<float[]> filled = new java.util.ArrayList<>();

        ChunkedFloatBuffer(int chunkSize) {
            this.chunkSize = chunkSize;
            this.current = new float[chunkSize];
        }

        void appendAll(double[] arr) {
            int written = 0;
            while (written < arr.length) {
                int avail = chunkSize - currentPos;
                int toCopy = Math.min(arr.length - written, avail);
                for (int i = 0; i < toCopy; i++) {
                    current[currentPos + i] = (float) arr[written + i];
                }
                currentPos += toCopy;
                written += toCopy;
                total += toCopy;
                if (currentPos == chunkSize) {
                    filled.add(current);
                    current = new float[chunkSize];
                    currentPos = 0;
                }
            }
        }

        float[] toArray() {
            if (total > Integer.MAX_VALUE) throw new IllegalStateException("dataset too large");
            int n = (int) total;
            float[] out = new float[n];
            int pos = 0;
            for (int i = 0; i < filled.size(); i++) {
                float[] chunk = filled.get(i);
                System.arraycopy(chunk, 0, out, pos, chunkSize);
                pos += chunkSize;
            }
            if (currentPos > 0) {
                System.arraycopy(current, 0, out, pos, currentPos);
            }
            return out;
        }
    }

    private static final class ChunkedByteBuffer {
        private final int chunkSize;
        private byte[] current;
        private int currentPos;
        private long total;
        private final java.util.ArrayList<byte[]> filled = new java.util.ArrayList<>();

        ChunkedByteBuffer(int chunkSize) {
            this.chunkSize = chunkSize;
            this.current = new byte[chunkSize];
        }

        void append(byte b) {
            current[currentPos++] = b;
            total++;
            if (currentPos == chunkSize) {
                filled.add(current);
                current = new byte[chunkSize];
                currentPos = 0;
            }
        }

        byte[] toArray() {
            if (total > Integer.MAX_VALUE) throw new IllegalStateException("dataset too large");
            int n = (int) total;
            byte[] out = new byte[n];
            int pos = 0;
            for (int i = 0; i < filled.size(); i++) {
                byte[] chunk = filled.get(i);
                System.arraycopy(chunk, 0, out, pos, chunkSize);
                pos += chunkSize;
            }
            if (currentPos > 0) {
                System.arraycopy(current, 0, out, pos, currentPos);
            }
            return out;
        }
    }

}
