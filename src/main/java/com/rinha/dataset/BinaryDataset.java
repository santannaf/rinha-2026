package com.rinha.dataset;

import com.rinha.index.ReferenceDataset;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Formato binário .bin do dataset de referência — versão 2 (regiões separadas).
 *
 * Header (16 bytes, little-endian):
 *   offset 0-3   : magic = "RNHA"
 *   offset 4     : version (uint8, atualmente 2)
 *   offset 5     : dim (uint8, esperado 14)
 *   offset 6     : dtype (uint8, 0 = float32)
 *   offset 7     : flags (uint8, reservado)
 *   offset 8-11  : count (uint32 LE)
 *   offset 12-15 : reserved (uint32 LE, zerado)  — também garante que a região
 *                  de floats começa em offset alinhado a 16 bytes
 *
 * Body (em duas regiões contíguas — diferente do v1 que interleaveava):
 *   floats : count × dim × float32 LE
 *   labels : count × uint8
 *
 * Total: 16 + 4*dim*N + N bytes = 16 + 57N para dim=14.
 *
 * Por que regiões separadas? Permite que {@link #mmap(Path)} entregue um
 * {@link FloatBuffer} direto sobre a região de floats — necessário pra
 * compartilhar as páginas físicas entre containers via page cache do kernel.
 * No formato v1 (record interleaved float+label), não dá pra fatiar um
 * FloatBuffer contíguo sobre o arquivo.
 */
public final class BinaryDataset {

    public static final int HEADER_BYTES = 16;
    public static final byte VERSION = 2;
    public static final byte DTYPE_FLOAT32 = 0;
    /** dtype int16: vetores gravados como short ×ReferenceDataset.QUANT_SCALE. */
    public static final byte DTYPE_INT16 = 1;
    private static final int DIM = ReferenceDataset.DIM;
    private static final int FLOATS_PER_CHUNK = 64 * DIM;        // 64 vetores
    private static final int LABEL_CHUNK_BYTES = 4096;

    private BinaryDataset() {}

    public static void write(ReferenceDataset ds, OutputStream out) throws IOException {
        int n = ds.size();
        FloatBuffer flat = ds.flatFloats();
        byte[] labels = ds.labels();

        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        header.put((byte) 'R'); header.put((byte) 'N'); header.put((byte) 'H'); header.put((byte) 'A');
        header.put(VERSION);
        header.put((byte) DIM);
        header.put(DTYPE_FLOAT32);
        header.put((byte) 0);
        header.putInt(n);
        header.putInt(0);
        out.write(header.array());

        // Região de floats: chunks de FLOATS_PER_CHUNK floats little-endian.
        ByteBuffer buf = ByteBuffer.allocate(FLOATS_PER_CHUNK * 4).order(ByteOrder.LITTLE_ENDIAN);
        long total = (long) n * DIM;
        long written = 0;
        while (written < total) {
            int chunk = (int) Math.min(FLOATS_PER_CHUNK, total - written);
            buf.clear();
            for (int i = 0; i < chunk; i++) {
                buf.putFloat(flat.get((int) (written + i)));
            }
            out.write(buf.array(), 0, chunk * 4);
            written += chunk;
        }

        // Região de labels: bytes diretos.
        out.write(labels, 0, n);
    }

    /**
     * Lê o .bin v2 sequencialmente, materializando floats em heap. Usado quando
     * mmap não é viável (DEV_FALLBACK, classpath, NDJSON convertido on-the-fly).
     */
    public static ReferenceDataset read(InputStream in) throws IOException {
        byte[] header = in.readNBytes(HEADER_BYTES);
        int count = validateHeader(header);
        if ((header[6] & 0xff) == DTYPE_INT16) {
            // Caminho streaming-heap só suporta float32. O .bin int16 (dataset
            // clustered) é sempre carregado via mmap em runtime.
            throw new IOException("int16 .bin requires mmap (MMAP=true)");
        }
        long flatLen = (long) count * DIM;
        if (flatLen > Integer.MAX_VALUE) {
            throw new IOException("Dataset too large: " + count + " records");
        }

        float[] flat = new float[(int) flatLen];
        byte[] labels = new byte[count];

        // Lê toda a região de floats em chunks (4 bytes × N×DIM).
        byte[] floatBytes = new byte[FLOATS_PER_CHUNK * 4];
        ByteBuffer bb = ByteBuffer.wrap(floatBytes).order(ByteOrder.LITTLE_ENDIAN);
        long total = flatLen;
        long readSoFar = 0;
        while (readSoFar < total) {
            int chunkFloats = (int) Math.min(FLOATS_PER_CHUNK, total - readSoFar);
            int chunkBytes = chunkFloats * 4;
            int got = in.readNBytes(floatBytes, 0, chunkBytes);
            if (got != chunkBytes) {
                throw new IOException("Short floats region at " + readSoFar
                        + ": expected " + chunkBytes + ", got " + got);
            }
            bb.position(0);
            for (int i = 0; i < chunkFloats; i++) {
                flat[(int) (readSoFar + i)] = bb.getFloat();
            }
            readSoFar += chunkFloats;
        }

        // Lê labels.
        int labelsGot = in.readNBytes(labels, 0, count);
        if (labelsGot != count) {
            throw new IOException("Short labels region: expected " + count + ", got " + labelsGot);
        }

        return new ReferenceDataset(flat, labels, count);
    }

    /**
     * Memory-maps o arquivo {@code .bin} v2 em READ_ONLY, devolvendo um
     * {@link ReferenceDataset} cujo {@code FloatBuffer} aponta direto pra
     * página do arquivo. Labels são copiados pra heap (3 MB; pequeno demais
     * pra valer mmap, e bytes não têm endianness).
     *
     * Quando dois processos mapeiam a mesma inode RO, o kernel compartilha
     * as páginas via page cache — cgroup v2 conta as páginas uma vez só.
     * É isso que viabiliza dois backends + 350 MiB de orçamento Rinha.
     */
    public static ReferenceDataset mmap(Path path) throws IOException {
        return mmap(path, false, false);
    }

    /**
     * Variante com hints. {@code prefetch=true} chama {@code load()} +
     * MADV_WILLNEED, fazendo o kernel pre-fault todas as páginas do dataset
     * no startup (aumenta RSS inicial, mas elimina page faults no hot path).
     * {@code hugepage=true} chama MADV_HUGEPAGE — kernel tenta promover a
     * região a transparent huge pages (2 MiB), reduzindo TLB misses.
     */
    public static ReferenceDataset mmap(Path path, boolean prefetch, boolean hugepage) throws IOException {
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            long fileSize = ch.size();
            if (fileSize < HEADER_BYTES) {
                throw new IOException(".bin too small: " + fileSize);
            }
            MappedByteBuffer mapped = ch.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
            mapped.order(ByteOrder.LITTLE_ENDIAN);

            if (hugepage) {
                boolean ok = NativeMemAdvise.hugePage(mapped);
                System.out.println("[dataset] madvise HUGEPAGE: " + (ok ? "ok" : "skipped"));
            }
            if (prefetch) {
                NativeMemAdvise.willNeed(mapped);
                long t0 = System.currentTimeMillis();
                mapped.load();
                System.out.println("[dataset] pre-fault " + (fileSize >> 20)
                        + " MiB in " + (System.currentTimeMillis() - t0) + "ms");
            }

            byte[] header = new byte[HEADER_BYTES];
            mapped.get(0, header);
            int count = validateHeader(header);
            int dtype = header[6] & 0xff;
            int elemBytes = (dtype == DTYPE_INT16) ? 2 : 4;
            long flatBytes = (long) count * DIM * elemBytes;
            long expectedSize = HEADER_BYTES + flatBytes + count;
            if (fileSize < expectedSize) {
                throw new IOException(".bin truncated: have " + fileSize
                        + ", expected " + expectedSize);
            }

            // Fatia a região de vetores (float32 ou int16) sobre o arquivo.
            mapped.position(HEADER_BYTES);
            ByteBuffer flatRegion = mapped.slice().order(ByteOrder.LITTLE_ENDIAN);
            flatRegion.limit((int) flatBytes);

            // Labels: ~3 MB, copia pra heap.
            mapped.position(HEADER_BYTES + (int) flatBytes);
            byte[] labels = new byte[count];
            mapped.get(labels, 0, count);

            if (dtype == DTYPE_INT16) {
                return ReferenceDataset.quantized(flatRegion.asShortBuffer(), labels, count);
            }
            return new ReferenceDataset(flatRegion.asFloatBuffer(), labels, count);
        }
    }

    private static int validateHeader(byte[] header) throws IOException {
        if (header.length != HEADER_BYTES) {
            throw new IOException("Short header: expected " + HEADER_BYTES + " bytes, got " + header.length);
        }
        if (header[0] != 'R' || header[1] != 'N' || header[2] != 'H' || header[3] != 'A') {
            throw new IOException("Bad magic: not a .bin dataset");
        }
        int version = header[4] & 0xff;
        if (version != VERSION) {
            throw new IOException("Unsupported .bin version: " + version + " (this build expects " + VERSION + ")");
        }
        int dim = header[5] & 0xff;
        if (dim != DIM) {
            throw new IOException("Dim mismatch: expected " + DIM + ", got " + dim);
        }
        int dtype = header[6] & 0xff;
        if (dtype != DTYPE_FLOAT32 && dtype != DTYPE_INT16) {
            throw new IOException("Unsupported dtype: " + dtype);
        }
        int count = (header[8] & 0xff)
                | ((header[9] & 0xff) << 8)
                | ((header[10] & 0xff) << 16)
                | ((header[11] & 0xff) << 24);
        if (count < 0) {
            throw new IOException("Negative count");
        }
        return count;
    }
}
