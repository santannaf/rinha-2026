package com.rinha.index;

import com.rinha.distance.DistanceMetric;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Random;

/**
 * IVF (Inverted File Index) — versão funcional com persistência.
 *
 * Esquema:
 *  1. Amostra {@code numCentroids} centróides aleatoriamente do dataset.
 *  2. Refina via mini-batch k-means (10 iters × 50k samples).
 *  3. Para cada vetor i, calcula o centróide mais próximo e adiciona
 *     i na lista invertida desse centróide.
 *  4. Em searchTopK, encontra os {@code nProbe} centróides mais próximos
 *     do query e varre apenas os vetores dessas listas, mantendo top-K.
 *
 * Layout interno após build (ou load):
 *  - centroids: double[K * DIM] (heap, ~459 KB pra K=4096; precisão p/ k-means).
 *  - postingStart: int[K+1] (heap, ~16 KB; offsets cumulativos).
 *  - postingList: IntBuffer de tamanho N (~12 MB pra 3M). Heap quando
 *    construído na hora, mmap'd quando carregado do .idx — neste último caso
 *    duas instâncias compartilham as páginas via page cache do kernel.
 *
 * Serialização: ver {@link #save(Path)} e {@link #loadMmap(DistanceMetric, int, Path, ReferenceDataset)}.
 * Reusar um .idx pré-construído tira ~6 min de startup com 0.6 CPU cap.
 */
public final class IvfVectorIndex implements VectorIndex {

    /** Magic do arquivo .idx: "RVII" (Rinha Vector Index, Ivf). */
    private static final int MAGIC = ('R') | ('V' << 8) | ('I' << 16) | ('I' << 24);
    /** v2: adicionou bboxMin/bboxMax por cluster pra repair com bbox-prune. */
    private static final int VERSION = 2;
    private static final int HEADER_BYTES = 32;

    private static final int KMEANS_ITERATIONS = 10;
    private static final int KMEANS_SAMPLE_SIZE = 50_000;

    private final DistanceMetric metric;
    private final int numCentroids;
    private final int nProbe;
    private final long seed;

    private ReferenceDataset dataset;
    private double[] centroids;       // numCentroids * DIM (heap)
    private int[] postingStart;       // numCentroids + 1 (heap)
    private IntBuffer postingList;    // tamanho N (heap ou mmap)
    // Bounding box por cluster: min[c*DIM+d] / max[c*DIM+d] em float32 (~460 KB
    // pra K=4096). Permite distance(query, bbox) como lower-bound para podar
    // o cluster inteiro durante o scan-all-with-bbox-prune do repair.
    private float[] bboxMin;
    private float[] bboxMax;
    // Early-stop class-aware: sai do scan dos nProbe clusters cedo se
    // o top-K já está cheio e tem unanimidade de classe (todos fraud ou
    // todos legit). Toggle via IVF_EARLY_STOP env. Quando ligado, salva
    // ~30-50% do varrer das posting lists em queries "fáceis".
    private volatile boolean earlyStop = false;

    public IvfVectorIndex(DistanceMetric metric, int numCentroids, int nProbe, long seed) {
        this.metric = metric;
        this.numCentroids = numCentroids;
        this.nProbe = nProbe;
        this.seed = seed;
    }

    /**
     * Construtor para instâncias carregadas via {@link #loadMmap}. O caller
     * já decidiu sobre centróides/postings; numCentroids é só usado pra
     * preservar o getter, e o seed fica 0 (não vai re-construir nada).
     */
    private IvfVectorIndex(DistanceMetric metric, int nProbe,
                           ReferenceDataset dataset,
                           double[] centroids, int[] postingStart, IntBuffer postingList,
                           float[] bboxMin, float[] bboxMax) {
        this.metric = metric;
        this.numCentroids = postingStart.length - 1;
        this.nProbe = nProbe;
        this.seed = 0L;
        this.dataset = dataset;
        this.centroids = centroids;
        this.postingStart = postingStart;
        this.postingList = postingList;
        this.bboxMin = bboxMin;
        this.bboxMax = bboxMax;
    }

    /**
     * Devolve uma instância nova compartilhando centróides / postings com esta,
     * mas configurada com outro {@code nProbe}. Usado pelo boundary fallback:
     * mesmo índice, busca com mais probes nos casos ambíguos. Zero overhead de
     * memória — postingList já é IntBuffer (heap ou mmap).
     */
    public void setEarlyStop(boolean enabled) {
        this.earlyStop = enabled;
    }

    public IvfVectorIndex withNProbe(int newNProbe) {
        if (dataset == null || centroids == null || postingStart == null || postingList == null) {
            throw new IllegalStateException("withNProbe requires build()/loadMmap first");
        }
        return new IvfVectorIndex(metric, newNProbe, dataset,
                centroids, postingStart, postingList, bboxMin, bboxMax);
    }

    @Override
    public void build(ReferenceDataset dataset) {
        this.dataset = dataset;
        int n = dataset.size();
        int effective = Math.min(numCentroids, Math.max(1, n));
        int dim = ReferenceDataset.DIM;

        Random rnd = new Random(seed);
        centroids = new double[effective * dim];
        FloatBuffer flat = dataset.flatFloats();
        for (int c = 0; c < effective; c++) {
            int src = n == 0 ? 0 : rnd.nextInt(n);
            int srcOff = src * dim;
            int dstOff = c * dim;
            for (int d = 0; d < dim; d++) {
                centroids[dstOff + d] = flat.get(srcOff + d);
            }
        }

        refineKMeans(flat, n, effective, dim, rnd);

        // Atribuição final de todos os N vetores ao centróide mais próximo.
        int[] assignment = new int[n];
        int[] sizes = new int[effective];
        for (int i = 0; i < n; i++) {
            int best = 0;
            double bestD = Double.POSITIVE_INFINITY;
            int offI = i * dim;
            for (int c = 0; c < effective; c++) {
                double d = metric.distance(flat, offI, centroids, c * dim);
                if (d < bestD) {
                    bestD = d;
                    best = c;
                }
            }
            assignment[i] = best;
            sizes[best]++;
        }

        postingStart = new int[effective + 1];
        for (int c = 0; c < effective; c++) {
            postingStart[c + 1] = postingStart[c] + sizes[c];
        }
        int[] heapList = new int[n];
        int[] cursor = new int[effective];
        for (int i = 0; i < n; i++) {
            int c = assignment[i];
            heapList[postingStart[c] + cursor[c]++] = i;
        }
        postingList = IntBuffer.wrap(heapList);

        // Bounding box por cluster: min/max de cada dimensão sobre os vetores
        // atribuídos. Permite lower-bound rápida (distance até bbox) pra podar
        // clusters inteiros no repair scan-all.
        bboxMin = new float[effective * dim];
        bboxMax = new float[effective * dim];
        java.util.Arrays.fill(bboxMin, Float.POSITIVE_INFINITY);
        java.util.Arrays.fill(bboxMax, Float.NEGATIVE_INFINITY);
        for (int i = 0; i < n; i++) {
            int c = assignment[i];
            int srcOff = i * dim;
            int dstOff = c * dim;
            for (int d = 0; d < dim; d++) {
                float v = flat.get(srcOff + d);
                if (v < bboxMin[dstOff + d]) bboxMin[dstOff + d] = v;
                if (v > bboxMax[dstOff + d]) bboxMax[dstOff + d] = v;
            }
        }
    }

    /**
     * Mini-batch k-means: refina os centróides iniciais usando uma amostra
     * pequena do dataset por iteração. Custo total ~ ITER × SAMPLE × K × D.
     */
    private void refineKMeans(FloatBuffer flat, int n, int k, int dim, Random rnd) {
        if (KMEANS_ITERATIONS <= 0 || KMEANS_SAMPLE_SIZE <= 0 || n == 0) return;
        int sampleSize = Math.min(KMEANS_SAMPLE_SIZE, n);

        double[] sum = new double[k * dim];
        int[] counts = new int[k];
        long t0 = System.currentTimeMillis();

        for (int iter = 0; iter < KMEANS_ITERATIONS; iter++) {
            java.util.Arrays.fill(sum, 0.0);
            java.util.Arrays.fill(counts, 0);

            for (int s = 0; s < sampleSize; s++) {
                int i = rnd.nextInt(n);
                int offI = i * dim;
                int best = 0;
                double bestD = Double.POSITIVE_INFINITY;
                for (int c = 0; c < k; c++) {
                    double d = metric.distance(flat, offI, centroids, c * dim);
                    if (d < bestD) {
                        bestD = d;
                        best = c;
                    }
                }
                int offSum = best * dim;
                for (int d = 0; d < dim; d++) {
                    sum[offSum + d] += flat.get(offI + d);
                }
                counts[best]++;
            }

            for (int c = 0; c < k; c++) {
                if (counts[c] > 0) {
                    int off = c * dim;
                    double inv = 1.0 / counts[c];
                    for (int d = 0; d < dim; d++) {
                        centroids[off + d] = sum[off + d] * inv;
                    }
                }
            }
        }
        long t1 = System.currentTimeMillis();
        System.out.println("[ivf] k-means refined in " + (t1 - t0) + "ms ("
                + KMEANS_ITERATIONS + " iters, sample=" + sampleSize + ")");
    }

    @Override
    public void searchTopK(double[] query, int k, SearchResult out) {
        if (dataset == null) throw new IllegalStateException("index not built");
        int numC = postingStart.length - 1;

        // 1. Top-nProbe centróides mais próximos.
        int probes = Math.min(nProbe, numC);
        int[] bestC = new int[probes];
        double[] bestCd = new double[probes];
        int filled = 0;
        double worst = Double.POSITIVE_INFINITY;
        for (int c = 0; c < numC; c++) {
            double d = metric.distance(query, centroids, 0, c * ReferenceDataset.DIM);
            if (filled < probes) {
                bestC[filled] = c;
                bestCd[filled] = d;
                filled++;
                if (filled == probes) worst = maxOf(bestCd, probes);
            } else if (d < worst) {
                int worstPos = 0;
                double wv = bestCd[0];
                for (int i = 1; i < probes; i++) {
                    if (bestCd[i] > wv) { wv = bestCd[i]; worstPos = i; }
                }
                bestC[worstPos] = c;
                bestCd[worstPos] = d;
                worst = maxOf(bestCd, probes);
            }
        }

        // 2. Varre as posting lists.
        int[] heapIdx = out.indices();
        double[] heapDist = out.distances();
        int heapSize = 0;
        double heapWorst = Double.POSITIVE_INFINITY;
        FloatBuffer flat = dataset.flatFloats();
        int dim = ReferenceDataset.DIM;
        boolean es = earlyStop;
        // Early-stop só vale após processar pelo menos metade dos probes —
        // garante que tenha varrido clusters suficientes pro top-K refletir
        // a vizinhança real e não só os 5 primeiros vetores do cluster mais
        // próximo. Em K=5 + nProbe=2, isso ainda permite sair após o 1º cluster.
        int esThreshold = Math.max(1, (filled + 1) / 2);
        for (int p = 0; p < filled; p++) {
            int c = bestC[p];
            int start = postingStart[c];
            int end = postingStart[c + 1];
            for (int j = start; j < end; j++) {
                int i = postingList.get(j);
                int off = i * dim;
                if (heapSize < k) {
                    // Heap ainda enchendo — sem cutoff, computa full distance.
                    double d = metric.distance(query, flat, off);
                    heapIdx[heapSize] = i;
                    heapDist[heapSize] = d;
                    heapSize++;
                    if (heapSize == k) heapWorst = maxOf(heapDist, k);
                } else {
                    // Heap cheio — early-exit se distância parcial já passa worst.
                    double d = metric.distanceWithCutoff(query, flat, off, heapWorst);
                    if (d >= heapWorst) continue;
                    int worstPos = 0;
                    double wv = heapDist[0];
                    for (int q = 1; q < k; q++) {
                        if (heapDist[q] > wv) { wv = heapDist[q]; worstPos = q; }
                    }
                    heapIdx[worstPos] = i;
                    heapDist[worstPos] = d;
                    heapWorst = maxOf(heapDist, k);
                }
            }
            // Early-stop class-aware: top-K cheio + unanimidade → sai cedo.
            // Aposta: se varremos clusters suficientes e todos os top-K caem
            // na mesma classe, é improvável que clusters mais distantes
            // tragam vetor mais próximo da classe oposta. Salva CPU sem
            // mudar a decisão final na maioria dos casos.
            if (es && heapSize == k && (p + 1) >= esThreshold
                    && unanimousClass(heapIdx, heapSize, dataset)) {
                break;
            }
        }

        sortAscending(heapIdx, heapDist, heapSize);
        out.setSize(heapSize);
    }

    private static boolean unanimousClass(int[] idx, int size, ReferenceDataset ds) {
        boolean first = ds.isFraud(idx[0]);
        for (int q = 1; q < size; q++) {
            if (ds.isFraud(idx[q]) != first) return false;
        }
        return true;
    }

    /**
     * Repair: dado um {@link SearchResult} já preenchido (seed da fast pass),
     * varre TODOS os clusters podando por lower-bound em bbox. Refina o top-K
     * só com candidatos cuja bbox-distance é menor que o pior atual.
     *
     * Truque dos top-5 da Rinha (silent-index, rinha-fraud-cpp): a bbox prune
     * faz "brute force scan-all" custar quase nada na prática — quase todos os
     * clusters falham o teste após o fast pass tigthtened o cutoff. Só os
     * clusters com algum vetor potencialmente competitivo entram no SIMD loop.
     *
     * Premissa: bbox-distance euclidiana faz sentido como lower-bound só pra
     * métricas Euclidean-like. Para Manhattan/Cosine o resultado é over-estimate
     * — não quebra correctness, só não aproveita o prune.
     */
    public void scanAllWithBboxPrune(double[] query, int k, SearchResult inOut) {
        if (bboxMin == null || bboxMax == null) {
            throw new IllegalStateException("scanAllWithBboxPrune requires bbox built/loaded");
        }
        int numC = postingStart.length - 1;
        int[] heapIdx = inOut.indices();
        double[] heapDist = inOut.distances();
        int heapSize = inOut.size();
        double heapWorst = heapSize >= k ? maxOf(heapDist, k) : Double.POSITIVE_INFINITY;
        FloatBuffer flat = dataset.flatFloats();
        int dim = ReferenceDataset.DIM;

        for (int c = 0; c < numC; c++) {
            double bbLow = bboxLowerBoundSq(query, c * dim, dim);
            if (bbLow >= heapWorst) continue;

            int start = postingStart[c];
            int end = postingStart[c + 1];
            for (int j = start; j < end; j++) {
                int i = postingList.get(j);
                // Dedup: vetores do cluster podem já estar no heap (vindo da
                // fast pass se este cluster foi probado). Inserir duplicado
                // bagunça o countFrauds depois — então pula.
                boolean dup = false;
                for (int q = 0; q < heapSize; q++) {
                    if (heapIdx[q] == i) { dup = true; break; }
                }
                if (dup) continue;
                int off = i * dim;
                if (heapSize < k) {
                    double d = metric.distance(query, flat, off);
                    heapIdx[heapSize] = i;
                    heapDist[heapSize] = d;
                    heapSize++;
                    if (heapSize == k) heapWorst = maxOf(heapDist, k);
                } else {
                    double d = metric.distanceWithCutoff(query, flat, off, heapWorst);
                    if (d >= heapWorst) continue;
                    int worstPos = 0;
                    double wv = heapDist[0];
                    for (int q = 1; q < k; q++) {
                        if (heapDist[q] > wv) { wv = heapDist[q]; worstPos = q; }
                    }
                    heapIdx[worstPos] = i;
                    heapDist[worstPos] = d;
                    heapWorst = maxOf(heapDist, k);
                }
            }
        }

        sortAscending(heapIdx, heapDist, heapSize);
        inOut.setSize(heapSize);
    }

    /**
     * Squared Euclidean lower-bound: distância do query até o ponto mais
     * próximo da bbox. Em cada dim, contribui (q-min)^2 se q<min, (q-max)^2
     * se q>max, 0 se dentro do intervalo.
     */
    private double bboxLowerBoundSq(double[] query, int off, int dim) {
        double sum = 0.0;
        for (int d = 0; d < dim; d++) {
            double q = query[d];
            float lo = bboxMin[off + d];
            float hi = bboxMax[off + d];
            if (q < lo) {
                double diff = lo - q;
                sum += diff * diff;
            } else if (q > hi) {
                double diff = q - hi;
                sum += diff * diff;
            }
        }
        return sum;
    }

    /**
     * Salva o índice construído em {@code path} no formato .idx binário.
     *
     * Layout (little-endian):
     *   Header (32 bytes):
     *     0-3   : magic "RVII"
     *     4-7   : version (uint32) + reserved
     *     8-11  : numCentroids (uint32)
     *     12-15 : dim (uint32)
     *     16-19 : numVectors (uint32)
     *     20-31 : reserved
     *   centroids:    numCentroids * dim * float32 (LE) — downcast de double.
     *   postingStart: (numCentroids+1) * int32 (LE)
     *   postingList:  numVectors * int32 (LE)
     *
     * Centróides são gravados como float32 (precisão suficiente; metade do tamanho
     * vs double). Promovidos pra double[] no load.
     */
    public void save(Path path) throws IOException {
        if (dataset == null || centroids == null || postingStart == null || postingList == null) {
            throw new IllegalStateException("save() requires build() first");
        }
        int k = numCentroids();
        int dim = ReferenceDataset.DIM;
        int n = dataset.size();

        Files.createDirectories(path.toAbsolutePath().getParent());
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(
                path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {

            ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            header.putInt(MAGIC);
            header.putInt(VERSION);
            header.putInt(k);
            header.putInt(dim);
            header.putInt(n);
            // 12 bytes reservados — zeros.
            out.write(header.array());

            // centroids: float32 chunks.
            int chunkFloats = 1024;
            ByteBuffer fbuf = ByteBuffer.allocate(chunkFloats * 4).order(ByteOrder.LITTLE_ENDIAN);
            int totalF = k * dim;
            int writtenF = 0;
            while (writtenF < totalF) {
                int take = Math.min(chunkFloats, totalF - writtenF);
                fbuf.clear();
                for (int i = 0; i < take; i++) {
                    fbuf.putFloat((float) centroids[writtenF + i]);
                }
                out.write(fbuf.array(), 0, take * 4);
                writtenF += take;
            }

            // postingStart: int32.
            ByteBuffer ibuf = ByteBuffer.allocate((k + 1) * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i <= k; i++) ibuf.putInt(postingStart[i]);
            out.write(ibuf.array());

            // postingList: int32 chunks.
            int chunkInts = 4096;
            ByteBuffer lbuf = ByteBuffer.allocate(chunkInts * 4).order(ByteOrder.LITTLE_ENDIAN);
            int writtenL = 0;
            while (writtenL < n) {
                int take = Math.min(chunkInts, n - writtenL);
                lbuf.clear();
                for (int i = 0; i < take; i++) {
                    lbuf.putInt(postingList.get(writtenL + i));
                }
                out.write(lbuf.array(), 0, take * 4);
                writtenL += take;
            }

            // bboxMin + bboxMax: cada um k*dim floats LE (~230 KB pra k=4096).
            ByteBuffer bbBuf = ByteBuffer.allocate(k * dim * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < k * dim; i++) bbBuf.putFloat(bboxMin[i]);
            out.write(bbBuf.array());
            bbBuf.clear();
            for (int i = 0; i < k * dim; i++) bbBuf.putFloat(bboxMax[i]);
            out.write(bbBuf.array());
        }
    }

    /**
     * Carrega um índice .idx via mmap. centroids + postingStart vão pra heap
     * (pequenos: ~475 KB juntos); postingList vira IntBuffer direto sobre o
     * arquivo mapeado — duas instâncias compartilham essas páginas via page
     * cache.
     */
    public static IvfVectorIndex loadMmap(DistanceMetric metric, int nProbe,
                                          Path path, ReferenceDataset dataset) throws IOException {
        return loadMmap(metric, nProbe, path, dataset, false, false);
    }

    /**
     * Variante com hints. {@code prefetch=true} pre-fault todas as páginas
     * (kernel paga page cache). {@code hugepage=true} pede MADV_HUGEPAGE.
     */
    public static IvfVectorIndex loadMmap(DistanceMetric metric, int nProbe,
                                          Path path, ReferenceDataset dataset,
                                          boolean prefetch, boolean hugepage) throws IOException {
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            long fileSize = ch.size();
            if (fileSize < HEADER_BYTES) {
                throw new IOException(".idx too small: " + fileSize);
            }
            MappedByteBuffer mapped = ch.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
            mapped.order(ByteOrder.LITTLE_ENDIAN);

            if (hugepage) {
                boolean ok = com.rinha.dataset.NativeMemAdvise.hugePage(mapped);
                System.out.println("[ivf] madvise HUGEPAGE: " + (ok ? "ok" : "skipped"));
            }
            if (prefetch) {
                com.rinha.dataset.NativeMemAdvise.willNeed(mapped);
                long t0 = System.currentTimeMillis();
                mapped.load();
                System.out.println("[ivf] pre-fault " + (fileSize >> 20)
                        + " MiB in " + (System.currentTimeMillis() - t0) + "ms");
            }

            int magic = mapped.getInt(0);
            if (magic != MAGIC) {
                throw new IOException("Bad .idx magic: not a RVII index");
            }
            int version = mapped.getInt(4) & 0xff;
            if (version != VERSION) {
                throw new IOException("Unsupported .idx version: " + version);
            }
            int k = mapped.getInt(8);
            int dim = mapped.getInt(12);
            int n = mapped.getInt(16);
            if (dim != ReferenceDataset.DIM) {
                throw new IOException("Dim mismatch in .idx: " + dim + " vs " + ReferenceDataset.DIM);
            }
            if (n != dataset.size()) {
                throw new IOException(".idx built for N=" + n + " but dataset has N=" + dataset.size());
            }

            long centroidsBytes = (long) k * dim * 4;
            long postingStartBytes = (long) (k + 1) * 4;
            long postingListBytes = (long) n * 4;
            long bboxBytes = (long) k * dim * 4;  // cada um — temos 2 (min,max)
            long expected = HEADER_BYTES + centroidsBytes + postingStartBytes
                    + postingListBytes + 2 * bboxBytes;
            if (fileSize < expected) {
                throw new IOException(".idx truncated: have " + fileSize + ", expected " + expected);
            }

            // centroids: float32 no disco -> double[] em memória (459 KB pra k=4096).
            double[] centroids = new double[k * dim];
            mapped.position(HEADER_BYTES);
            ByteBuffer centroidsRegion = mapped.slice().order(ByteOrder.LITTLE_ENDIAN);
            centroidsRegion.limit((int) centroidsBytes);
            FloatBuffer centroidsFB = centroidsRegion.asFloatBuffer();
            for (int i = 0; i < k * dim; i++) {
                centroids[i] = centroidsFB.get(i);
            }

            // postingStart: int32 no disco -> int[] em memória (16 KB).
            int[] postingStart = new int[k + 1];
            mapped.position(HEADER_BYTES + (int) centroidsBytes);
            ByteBuffer psRegion = mapped.slice().order(ByteOrder.LITTLE_ENDIAN);
            psRegion.limit((int) postingStartBytes);
            IntBuffer psIB = psRegion.asIntBuffer();
            for (int i = 0; i <= k; i++) {
                postingStart[i] = psIB.get(i);
            }

            // postingList: int32 no disco, mmap direto via IntBuffer (12 MB).
            int plStart = HEADER_BYTES + (int) centroidsBytes + (int) postingStartBytes;
            mapped.position(plStart);
            ByteBuffer plRegion = mapped.slice().order(ByteOrder.LITTLE_ENDIAN);
            plRegion.limit((int) postingListBytes);
            IntBuffer postingList = plRegion.asIntBuffer();

            // bboxMin + bboxMax: float32 -> heap float[] (~460 KB total).
            int bbMinStart = plStart + (int) postingListBytes;
            int bbMaxStart = bbMinStart + (int) bboxBytes;
            float[] bboxMin = new float[k * dim];
            float[] bboxMax = new float[k * dim];
            mapped.position(bbMinStart);
            ByteBuffer bbMinRegion = mapped.slice().order(ByteOrder.LITTLE_ENDIAN);
            bbMinRegion.limit((int) bboxBytes);
            FloatBuffer bbMinFB = bbMinRegion.asFloatBuffer();
            for (int i = 0; i < k * dim; i++) bboxMin[i] = bbMinFB.get(i);

            mapped.position(bbMaxStart);
            ByteBuffer bbMaxRegion = mapped.slice().order(ByteOrder.LITTLE_ENDIAN);
            bbMaxRegion.limit((int) bboxBytes);
            FloatBuffer bbMaxFB = bbMaxRegion.asFloatBuffer();
            for (int i = 0; i < k * dim; i++) bboxMax[i] = bbMaxFB.get(i);

            return new IvfVectorIndex(metric, nProbe, dataset,
                    centroids, postingStart, postingList, bboxMin, bboxMax);
        }
    }

    private int numCentroids() {
        return postingStart.length - 1;
    }

    private static double maxOf(double[] a, int size) {
        double m = a[0];
        for (int i = 1; i < size; i++) if (a[i] > m) m = a[i];
        return m;
    }

    private static void sortAscending(int[] idxArr, double[] distArr, int size) {
        for (int i = 1; i < size; i++) {
            double d = distArr[i];
            int ix = idxArr[i];
            int j = i - 1;
            while (j >= 0 && distArr[j] > d) {
                distArr[j + 1] = distArr[j];
                idxArr[j + 1] = idxArr[j];
                j--;
            }
            distArr[j + 1] = d;
            idxArr[j + 1] = ix;
        }
    }

    @Override
    public String name() {
        return "IVF";
    }
}
