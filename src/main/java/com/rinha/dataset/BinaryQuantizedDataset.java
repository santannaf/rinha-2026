package com.rinha.dataset;

import com.rinha.index.QuantizedReferenceDataset;
import com.rinha.index.ReferenceDataset;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.ShortBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Formato binário .qbin do dataset quantizado int16 — versão 1.
 *
 * Header (32 bytes, little-endian):
 *   0-3   magic "RNHQ"
 *   4     version = 1
 *   5     dim = 14
 *   6     dtype = 1 (int16)
 *   7     flags (reservado)
 *   8-11  count (uint32 LE)
 *   12-15 reserved
 *   16-19 minVal (float32 LE) — global mínimo do dataset
 *   20-23 maxVal (float32 LE) — global máximo do dataset
 *   24-31 reserved
 *
 * Body (duas regiões contíguas):
 *   shorts : count × dim × int16 LE
 *   labels : count × uint8
 *
 * Total: 32 + 2*dim*N + N bytes = 32 + 29N para dim=14.
 */
public final class BinaryQuantizedDataset {

    public static final int HEADER_BYTES = 32;
    public static final byte VERSION = 1;
    public static final byte DTYPE_INT16 = 1;
    private static final int DIM = ReferenceDataset.DIM;

    private BinaryQuantizedDataset() {}

    public static void write(QuantizedReferenceDataset ds, OutputStream out) throws IOException {
        int n = ds.size();
        ShortBuffer flat = ds.flatShorts();
        byte[] labels = ds.labels();
        QuantParams p = ds.params();

        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        header.put((byte) 'R'); header.put((byte) 'N');
        header.put((byte) 'H'); header.put((byte) 'Q');
        header.put(VERSION);
        header.put((byte) DIM);
        header.put(DTYPE_INT16);
        header.put((byte) 0);
        header.putInt(n);
        header.putInt(0);
        header.putFloat(p.minVal());
        header.putFloat(p.maxVal());
        header.putInt(0);
        header.putInt(0);
        out.write(header.array());

        // shorts: chunk de 4096 shorts
        ByteBuffer buf = ByteBuffer.allocate(4096 * 2).order(ByteOrder.LITTLE_ENDIAN);
        long total = (long) n * DIM;
        long written = 0;
        while (written < total) {
            int chunk = (int) Math.min(4096, total - written);
            buf.clear();
            for (int i = 0; i < chunk; i++) {
                buf.putShort(flat.get((int) (written + i)));
            }
            out.write(buf.array(), 0, chunk * 2);
            written += chunk;
        }

        out.write(labels, 0, n);
    }

    public static QuantizedReferenceDataset mmap(Path path) throws IOException {
        return mmap(path, false, false);
    }

    public static QuantizedReferenceDataset mmap(Path path, boolean prefetch, boolean hugepage)
            throws IOException {
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            long fileSize = ch.size();
            if (fileSize < HEADER_BYTES) {
                throw new IOException(".qbin too small: " + fileSize);
            }
            MappedByteBuffer mapped = ch.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
            mapped.order(ByteOrder.LITTLE_ENDIAN);

            if (hugepage) {
                boolean ok = NativeMemAdvise.hugePage(mapped);
                System.out.println("[qdataset] madvise HUGEPAGE: " + (ok ? "ok" : "skipped"));
            }
            if (prefetch) {
                NativeMemAdvise.willNeed(mapped);
                long t0 = System.currentTimeMillis();
                mapped.load();
                System.out.println("[qdataset] pre-fault " + (fileSize >> 20)
                        + " MiB in " + (System.currentTimeMillis() - t0) + "ms");
            }

            byte[] header = new byte[HEADER_BYTES];
            mapped.get(0, header);
            ParsedHeader ph = validateHeader(header);

            long shortBytes = (long) ph.count * DIM * 2;
            long expectedSize = HEADER_BYTES + shortBytes + ph.count;
            if (fileSize < expectedSize) {
                throw new IOException(".qbin truncated: have " + fileSize
                        + ", expected " + expectedSize);
            }

            // Região de shorts → ShortBuffer mmap'd.
            mapped.position(HEADER_BYTES);
            ByteBuffer shortsRegion = mapped.slice().order(ByteOrder.LITTLE_ENDIAN);
            shortsRegion.limit((int) shortBytes);
            ShortBuffer shorts = shortsRegion.asShortBuffer();

            // Labels → heap.
            mapped.position(HEADER_BYTES + (int) shortBytes);
            byte[] labels = new byte[ph.count];
            mapped.get(labels, 0, ph.count);

            return new QuantizedReferenceDataset(shorts, labels, ph.count,
                    new QuantParams(ph.minVal, ph.maxVal));
        }
    }

    private record ParsedHeader(int count, float minVal, float maxVal) {}

    private static ParsedHeader validateHeader(byte[] header) throws IOException {
        if (header.length != HEADER_BYTES) {
            throw new IOException("Short header");
        }
        if (header[0] != 'R' || header[1] != 'N' || header[2] != 'H' || header[3] != 'Q') {
            throw new IOException("Bad magic: not a .qbin (RNHQ)");
        }
        int version = header[4] & 0xff;
        if (version != VERSION) {
            throw new IOException("Unsupported .qbin version: " + version);
        }
        int dim = header[5] & 0xff;
        if (dim != DIM) {
            throw new IOException("Dim mismatch in .qbin: " + dim + " vs " + DIM);
        }
        int dtype = header[6] & 0xff;
        if (dtype != DTYPE_INT16) {
            throw new IOException(".qbin dtype unsupported: " + dtype);
        }
        ByteBuffer b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int count = b.getInt(8);
        float minVal = b.getFloat(16);
        float maxVal = b.getFloat(20);
        return new ParsedHeader(count, minVal, maxVal);
    }
}
