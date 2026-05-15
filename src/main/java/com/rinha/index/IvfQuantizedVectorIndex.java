package com.rinha.index;

import com.rinha.dataset.BinaryQuantizedDataset;
import com.rinha.dataset.QuantParams;
import com.rinha.distance.DistanceMetric;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.MappedByteBuffer;
import java.nio.ShortBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Variante quantizada do IVF: reuso do {@code .idx} v2 existente (centroids
 * float, postings int) e dataset {@code .qbin} (vetores int16).
 *
 * Distância L2² usa scale GLOBAL único, então
 *   Σ(q_a - q_b)² × scale²   é proporcional a   Σ(x_a - x_b)²
 * → ranking lossless sem precisar rerank.
 *
 * Acumula em {@code long} pra evitar overflow:
 *   max diff = 65534 → max diff² = 4.3e9
 *   14 dims × max = 6e10 (não cabe em int32, cabe em long)
 *
 * Memória: dataset shorts ~84 MiB (vs float 168 MiB). Centroids + postings
 * ainda float/int do .idx original (~12.5 MiB), shared via page cache.
 */
public final class IvfQuantizedVectorIndex implements VectorIndex {

    private final DistanceMetric centroidMetric;
    private final int nProbe;
    private final QuantizedReferenceDataset qDataset;
    private final double[] centroidsD;     // K * DIM, float→double na load
    private final int[] postingStart;       // K+1
    private final IntBuffer postingList;    // N (heap ou mmap)
    private volatile boolean earlyStop = false;

    private static final int MAGIC = ('R') | ('V' << 8) | ('I' << 16) | ('I' << 24);
    private static final int VERSION = 2;
    private static final int HEADER_BYTES = 32;

    private IvfQuantizedVectorIndex(DistanceMetric centroidMetric, int nProbe,
                                    QuantizedReferenceDataset qDataset,
                                    double[] centroidsD,
                                    int[] postingStart, IntBuffer postingList) {
        this.centroidMetric = centroidMetric;
        this.nProbe = nProbe;
        this.qDataset = qDataset;
        this.centroidsD = centroidsD;
        this.postingStart = postingStart;
        this.postingList = postingList;
    }

    public void setEarlyStop(boolean enabled) { this.earlyStop = enabled; }

    /**
     * Carrega ambos os arquivos: {@code .idx} v2 (centroids+postings float/int)
     * e {@code .qbin} (vetores int16). Validação cruzada: count tem que bater.
     */
    public static IvfQuantizedVectorIndex loadMmap(DistanceMetric centroidMetric,
                                                   int nProbe,
                                                   Path idxPath,
                                                   Path qbinPath,
                                                   boolean prefetch,
                                                   boolean hugepage) throws IOException {
        QuantizedReferenceDataset qds = BinaryQuantizedDataset.mmap(qbinPath, prefetch, hugepage);

        try (FileChannel ch = FileChannel.open(idxPath, StandardOpenOption.READ)) {
            long fileSize = ch.size();
            if (fileSize < HEADER_BYTES) {
                throw new IOException(".idx too small: " + fileSize);
            }
            MappedByteBuffer mapped = ch.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
            mapped.order(ByteOrder.LITTLE_ENDIAN);

            if (hugepage) {
                boolean ok = com.rinha.dataset.NativeMemAdvise.hugePage(mapped);
                System.out.println("[qivf] madvise HUGEPAGE: " + (ok ? "ok" : "skipped"));
            }
            if (prefetch) {
                com.rinha.dataset.NativeMemAdvise.willNeed(mapped);
                long t0 = System.currentTimeMillis();
                mapped.load();
                System.out.println("[qivf] pre-fault " + (fileSize >> 20)
                        + " MiB in " + (System.currentTimeMillis() - t0) + "ms");
            }

            int magic = mapped.getInt(0);
            if (magic != MAGIC) {
                throw new IOException("Bad .idx magic");
            }
            int version = mapped.getInt(4) & 0xff;
            if (version != VERSION) {
                throw new IOException("Unsupported .idx version: " + version);
            }
            int k = mapped.getInt(8);
            int dim = mapped.getInt(12);
            int n = mapped.getInt(16);
            if (dim != ReferenceDataset.DIM) {
                throw new IOException("Dim mismatch in .idx");
            }
            if (n != qds.size()) {
                throw new IOException(".idx N=" + n + " != .qbin N=" + qds.size());
            }

            long centroidsBytes = (long) k * dim * 4;
            long postingStartBytes = (long) (k + 1) * 4;
            long postingListBytes = (long) n * 4;

            // centroids: float32 → double[]
            double[] centroidsD = new double[k * dim];
            mapped.position(HEADER_BYTES);
            ByteBuffer cR = mapped.slice().order(ByteOrder.LITTLE_ENDIAN);
            cR.limit((int) centroidsBytes);
            FloatBuffer cFB = cR.asFloatBuffer();
            for (int i = 0; i < k * dim; i++) centroidsD[i] = cFB.get(i);

            // postingStart: int32 → int[]
            int[] postingStart = new int[k + 1];
            mapped.position(HEADER_BYTES + (int) centroidsBytes);
            ByteBuffer psR = mapped.slice().order(ByteOrder.LITTLE_ENDIAN);
            psR.limit((int) postingStartBytes);
            IntBuffer psIB = psR.asIntBuffer();
            for (int i = 0; i <= k; i++) postingStart[i] = psIB.get(i);

            // postingList: int32 mmap → IntBuffer direto
            int plStart = HEADER_BYTES + (int) centroidsBytes + (int) postingStartBytes;
            mapped.position(plStart);
            ByteBuffer plR = mapped.slice().order(ByteOrder.LITTLE_ENDIAN);
            plR.limit((int) postingListBytes);
            IntBuffer postingList = plR.asIntBuffer();

            return new IvfQuantizedVectorIndex(centroidMetric, nProbe, qds,
                    centroidsD, postingStart, postingList);
        }
    }

    @Override
    public void build(ReferenceDataset dataset) {
        throw new UnsupportedOperationException(
                "IvfQuantizedVectorIndex só suporta loadMmap (use BuildIvfIndex + BuildQuantizedDataset)");
    }

    @Override
    public String name() {
        return "IVF_Q16(nProbe=" + nProbe + ")";
    }

    @Override
    public void searchTopK(double[] query, int k, SearchResult out) {
        int dim = ReferenceDataset.DIM;
        int numC = postingStart.length - 1;

        QuantParams params = qDataset.params();
        short[] qq = new short[dim];
        for (int d = 0; d < dim; d++) qq[d] = params.quantize(query[d]);

        // 2. Top-nProbe centroides — usa métrica float (centroides ainda float).
        int probes = Math.min(nProbe, numC);
        int[] bestC = new int[probes];
        double[] bestCd = new double[probes];
        int filled = 0;
        double worst = Double.POSITIVE_INFINITY;
        for (int c = 0; c < numC; c++) {
            double d = centroidMetric.distance(query, centroidsD, 0, c * dim);
            if (filled < probes) {
                bestC[filled] = c;
                bestCd[filled] = d;
                filled++;
                if (filled == probes) worst = maxOf(bestCd, probes);
            } else if (d < worst) {
                int wp = 0; double wv = bestCd[0];
                for (int i = 1; i < probes; i++) {
                    if (bestCd[i] > wv) { wv = bestCd[i]; wp = i; }
                }
                bestC[wp] = c;
                bestCd[wp] = d;
                worst = maxOf(bestCd, probes);
            }
        }

        // 3. Varre posting lists usando distância INT (long accumulator).
        int[] heapIdx = out.indices();
        // Reusa distances[] do SearchResult como long-as-double pra ranking
        // — só importa ordem relativa, não valor absoluto.
        double[] heapDist = out.distances();
        int heapSize = 0;
        long heapWorst = Long.MAX_VALUE;
        boolean es = earlyStop;
        int esThreshold = Math.max(1, (filled + 1) / 2);
        ShortBuffer flat = qDataset.flatShorts();

        // Caminho scalar único. Tentamos Vector API (jdk.incubator.vector)
        // pra acelerar via AVX2, mas em native-image GraalVM SVM o codegen
        // cai num fallback interpretado lentíssimo (p99 viu 1.2s no bench).
        // Voltamos pra scalar; pra ganho SIMD real, JNI/Panama com intrinsics
        // explícitos seria o caminho — fora do escopo agora.
        for (int p = 0; p < filled; p++) {
            int c = bestC[p];
            int start = postingStart[c];
            int end = postingStart[c + 1];
            for (int j = start; j < end; j++) {
                int i = postingList.get(j);
                int off = i * dim;
                long dist = l2sqScalar(qq, flat, off, dim);
                if (heapSize < k) {
                    heapIdx[heapSize] = i;
                    heapDist[heapSize] = dist;
                    heapSize++;
                    if (heapSize == k) heapWorst = maxLongOf(heapDist, k);
                } else if (dist < heapWorst) {
                    int wp = 0; long wv = (long) heapDist[0];
                    for (int q = 1; q < k; q++) {
                        long v = (long) heapDist[q];
                        if (v > wv) { wv = v; wp = q; }
                    }
                    heapIdx[wp] = i;
                    heapDist[wp] = dist;
                    heapWorst = maxLongOf(heapDist, k);
                }
            }
            if (es && heapSize == k && (p + 1) >= esThreshold
                    && unanimousClass(heapIdx, heapSize, qDataset)) {
                break;
            }
        }

        sortAscendingLong(heapIdx, heapDist, heapSize);
        out.setSize(heapSize);
    }

    private static long l2sqScalar(short[] q, ShortBuffer flat, int offB, int dim) {
        long sum = 0;
        for (int d = 0; d < dim; d++) {
            int diff = q[d] - flat.get(offB + d);
            sum += (long) diff * diff;
        }
        return sum;
    }

    private static double maxOf(double[] a, int size) {
        double m = a[0];
        for (int i = 1; i < size; i++) if (a[i] > m) m = a[i];
        return m;
    }

    private static long maxLongOf(double[] a, int size) {
        long m = (long) a[0];
        for (int i = 1; i < size; i++) {
            long v = (long) a[i];
            if (v > m) m = v;
        }
        return m;
    }

    private static boolean unanimousClass(int[] idx, int size, QuantizedReferenceDataset ds) {
        boolean first = ds.isFraud(idx[0]);
        for (int q = 1; q < size; q++) {
            if (ds.isFraud(idx[q]) != first) return false;
        }
        return true;
    }

    /** Insertion sort por distance ascendente (K pequeno). */
    private static void sortAscendingLong(int[] idx, double[] dist, int size) {
        for (int i = 1; i < size; i++) {
            long dv = (long) dist[i];
            int iv = idx[i];
            int j = i - 1;
            while (j >= 0 && (long) dist[j] > dv) {
                dist[j + 1] = dist[j];
                idx[j + 1] = idx[j];
                j--;
            }
            dist[j + 1] = dv;
            idx[j + 1] = iv;
        }
    }
}
