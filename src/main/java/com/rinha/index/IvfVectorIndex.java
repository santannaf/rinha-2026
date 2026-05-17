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
import java.nio.ShortBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Random;

/**
 * IVF (Inverted File Index) com repair exato e layout int16 clustered.
 *
 * Build (offline, float64): k-means grosso → split de clusters grandes →
 * centroids/postingStart/postingList/bbox. Persistido via
 * {@link #writeClusteredArtifacts}.
 *
 * Runtime: o dataset clustered é int16 (×{@link ReferenceDataset#QUANT_SCALE},
 * lossless — referências já na grade de 1/10000) e as linhas estão reordenadas
 * por cluster → varredura de cluster é sequencial. A busca usa aritmética
 * inteira (acumulador long).
 *
 * {@link #searchTopK} faz a fast pass (varre os nProbe clusters mais próximos)
 * e registra os clusters varridos em {@link SearchResult}. {@link #scanAllWithBboxPrune}
 * (o repair) varre todos os OUTROS clusters podando por bounding box — pula os
 * já varridos, então cada cluster é visto uma vez (sem dedup). O resultado é o
 * top-K exato.
 */
public final class IvfVectorIndex implements VectorIndex {

    /** Magic do arquivo .idx: "RVII" (Rinha Vector Index, Ivf). */
    private static final int MAGIC = ('R') | ('V' << 8) | ('I' << 16) | ('I' << 24);
    /** v2: bboxMin/bboxMax por cluster (float32). */
    private static final int VERSION = 2;
    private static final int HEADER_BYTES = 32;

    private static final int KMEANS_ITERATIONS = 18;
    private static final int KMEANS_SAMPLE_SIZE = 128_000;
    /**
     * Clusters acima deste tamanho são divididos via sub-k-means. Cluster
     * menor → bounding box mais justa → poda mais forte no repair → menos
     * vetores varridos por request. Não afeta corretude — a busca é exata pra
     * qualquer clustering. MEDIDO: subir pra 4096 (poucos clusters grandes)
     * afrouxa as bboxes e PIORA o p90/p95 — manter pequeno.
     */
    private static final int MAX_CLUSTER_SIZE = 512;
    /** Iterações do k-means full-batch usado ao dividir um cluster grande. */
    private static final int SPLIT_KMEANS_ITERATIONS = 10;

    private final DistanceMetric metric;
    private final int numCentroids;
    private final int nProbe;
    private final long seed;

    private ReferenceDataset dataset;
    private double[] centroids;       // numCentroids * DIM (heap)
    private int[] postingStart;       // numCentroids + 1 (heap)
    private IntBuffer postingList;    // tamanho N (heap ou mmap; identidade no clustered)
    // Vetores em int16 (×QUANT_SCALE), sempre em heap short[]. Antes era um
    // ShortBuffer mmap'd sobre o .bin clustered; trocado por array no heap —
    // acesso direto sem indireção de Buffer e zero risco de page fault, que na
    // storage virtualizada lenta da Rinha jogava a cauda do p99 pra dezenas de
    // ms (e congelava o event loop single-thread numa falta de página).
    private short[] rows;
    // Bounding box por cluster em float32 (build/serialização) e int16 (busca).
    private float[] bboxMin;
    private float[] bboxMax;
    private short[] bboxMinS;
    private short[] bboxMaxS;
    // Mantido para compatibilidade do setter chamado pelo Main; ignorado —
    // com repair em toda request o early-stop seria inseguro (deixaria
    // clusters não varridos quando o repair pula os "probados").
    private volatile boolean earlyStop = false;
    // No layout CLUSTERED a posting list é a identidade (0..n-1) — o .bin já
    // está reordenado por cluster. Quando true, o scan usa o índice direto e
    // pula a leitura da região mmap da posting list (~12 MB), cortando ~1/8 do
    // tráfego de memória de um scan que é memory-bound.
    private boolean identityPostings = false;

    public IvfVectorIndex(DistanceMetric metric, int numCentroids, int nProbe, long seed) {
        this.metric = metric;
        this.numCentroids = numCentroids;
        this.nProbe = nProbe;
        this.seed = seed;
    }

    /**
     * Construtor para instâncias carregadas via {@link #loadMmap}. Deriva os
     * arrays int16 (rows do dataset, bbox quantizada) a partir do que foi lido.
     */
    private IvfVectorIndex(DistanceMetric metric, int nProbe,
                           ReferenceDataset dataset,
                           double[] centroids, int[] postingStart, IntBuffer postingList,
                           float[] bboxMin, float[] bboxMax, short[] rows) {
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
        this.rows = rows;
        this.identityPostings = isIdentity(postingList);
        quantizeBbox();
    }

    public void setEarlyStop(boolean enabled) {
        this.earlyStop = enabled;   // ignorado — ver campo earlyStop.
    }

    /**
     * Instância nova compartilhando centróides / postings / rows com esta, com
     * outro {@code nProbe}. Usado pelo boundary fallback. Zero overhead de
     * memória — postingList e rows são buffers compartilhados.
     */
    public IvfVectorIndex withNProbe(int newNProbe) {
        if (dataset == null || centroids == null || postingStart == null || postingList == null) {
            throw new IllegalStateException("withNProbe requires build()/loadMmap first");
        }
        return new IvfVectorIndex(metric, newNProbe, dataset,
                centroids, postingStart, postingList, bboxMin, bboxMax, rows);
    }

    @Override
    public void build(ReferenceDataset dataset) {
        this.dataset = dataset;
        int n = dataset.size();
        int coarseK = Math.min(numCentroids, Math.max(1, n));
        int dim = ReferenceDataset.DIM;
        FloatBuffer flat = dataset.flatFloats();
        Random rnd = new Random(seed);

        // 1. K-means grosso: coarseK centróides iniciados de amostras aleatórias.
        centroids = new double[coarseK * dim];
        for (int c = 0; c < coarseK; c++) {
            int src = n == 0 ? 0 : rnd.nextInt(n);
            int srcOff = src * dim;
            int dstOff = c * dim;
            for (int d = 0; d < dim; d++) {
                centroids[dstOff + d] = flat.get(srcOff + d);
            }
        }
        refineKMeans(flat, n, coarseK, dim, rnd);

        // 2. Atribui todos os N vetores ao centróide grosso mais próximo.
        int[] coarseAssign = new int[n];
        int[] coarseSizes = new int[coarseK];
        for (int i = 0; i < n; i++) {
            int best = 0;
            double bestD = Double.POSITIVE_INFINITY;
            int offI = i * dim;
            for (int c = 0; c < coarseK; c++) {
                double d = metric.distance(flat, offI, centroids, c * dim);
                if (d < bestD) {
                    bestD = d;
                    best = c;
                }
            }
            coarseAssign[i] = best;
            coarseSizes[best]++;
        }

        // 3. Agrupa os membros por cluster grosso (contíguo em coarseMembers).
        int[] coarseStart = new int[coarseK + 1];
        for (int c = 0; c < coarseK; c++) {
            coarseStart[c + 1] = coarseStart[c] + coarseSizes[c];
        }
        int[] coarseMembers = new int[n];
        int[] cursor = new int[coarseK];
        for (int i = 0; i < n; i++) {
            int c = coarseAssign[i];
            coarseMembers[coarseStart[c] + cursor[c]++] = i;
        }

        // 4. Clusters finais: clusters pequenos passam direto; clusters acima de
        //    MAX_CLUSTER_SIZE são divididos via sub-k-means; clusters vazios
        //    somem. Cluster menor = bounding box mais justa = poda mais forte.
        java.util.ArrayList<double[]> finalCentroids = new java.util.ArrayList<>(coarseK);
        java.util.ArrayList<int[]> finalMembers = new java.util.ArrayList<>(coarseK);
        int splitCount = 0;
        for (int c = 0; c < coarseK; c++) {
            int s = coarseStart[c];
            int e = coarseStart[c + 1];
            int size = e - s;
            if (size == 0) continue;
            if (size <= MAX_CLUSTER_SIZE) {
                double[] ctr = new double[dim];
                System.arraycopy(centroids, c * dim, ctr, 0, dim);
                finalCentroids.add(ctr);
                finalMembers.add(java.util.Arrays.copyOfRange(coarseMembers, s, e));
            } else {
                int subK = (size + MAX_CLUSTER_SIZE - 1) / MAX_CLUSTER_SIZE;
                splitCluster(flat, dim, coarseMembers, s, e, subK, seed + c,
                        finalCentroids, finalMembers);
                splitCount++;
            }
        }

        int k = finalCentroids.size();
        System.out.println("[ivf] clusters: coarse=" + coarseK + " split=" + splitCount
                + " final=" + k + " (max-cluster-size=" + MAX_CLUSTER_SIZE + ")");

        // 5. Achata os clusters finais em centroids[] / postingStart[] /
        //    postingList[] e calcula a bounding box de cada um.
        centroids = new double[k * dim];
        for (int c = 0; c < k; c++) {
            System.arraycopy(finalCentroids.get(c), 0, centroids, c * dim, dim);
        }
        postingStart = new int[k + 1];
        for (int c = 0; c < k; c++) {
            postingStart[c + 1] = postingStart[c] + finalMembers.get(c).length;
        }
        int[] heapList = new int[n];
        bboxMin = new float[k * dim];
        bboxMax = new float[k * dim];
        java.util.Arrays.fill(bboxMin, Float.POSITIVE_INFINITY);
        java.util.Arrays.fill(bboxMax, Float.NEGATIVE_INFINITY);
        for (int c = 0; c < k; c++) {
            int[] mem = finalMembers.get(c);
            int base = postingStart[c];
            int bbOff = c * dim;
            for (int t = 0; t < mem.length; t++) {
                int i = mem[t];
                heapList[base + t] = i;
                int srcOff = i * dim;
                for (int d = 0; d < dim; d++) {
                    float v = flat.get(srcOff + d);
                    if (v < bboxMin[bbOff + d]) bboxMin[bbOff + d] = v;
                    if (v > bboxMax[bbOff + d]) bboxMax[bbOff + d] = v;
                }
            }
        }
        postingList = IntBuffer.wrap(heapList);
        identityPostings = isIdentity(postingList);

        // 6. Quantiza linhas (ordem original — postingList mapeia) e bbox para
        //    int16, deixando a busca in-memory pronta (dev-fallback).
        int scale = ReferenceDataset.QUANT_SCALE;
        short[] rowsArr = new short[n * dim];
        for (int idx = 0; idx < n * dim; idx++) {
            rowsArr[idx] = (short) Math.round(flat.get(idx) * scale);
        }
        rows = rowsArr;
        quantizeBbox();
    }

    /**
     * Divide um cluster grande em {@code subK} sub-clusters via k-means
     * full-batch sobre os membros {@code members[from..to)}. Cada sub-cluster
     * não-vazio vira um cluster final (centróide + lista de membros).
     */
    private void splitCluster(FloatBuffer flat, int dim,
                              int[] members, int from, int to, int subK, long subSeed,
                              java.util.ArrayList<double[]> outCentroids,
                              java.util.ArrayList<int[]> outMembers) {
        int size = to - from;
        Random rnd = new Random(subSeed);
        double[][] sub = new double[subK][dim];
        for (int c = 0; c < subK; c++) {
            int off = members[from + rnd.nextInt(size)] * dim;
            for (int d = 0; d < dim; d++) sub[c][d] = flat.get(off + d);
        }

        int[] assign = new int[size];
        double[][] sum = new double[subK][dim];
        int[] cnt = new int[subK];
        for (int iter = 0; iter < SPLIT_KMEANS_ITERATIONS; iter++) {
            for (int t = 0; t < size; t++) {
                int off = members[from + t] * dim;
                int best = 0;
                double bestD = Double.POSITIVE_INFINITY;
                for (int c = 0; c < subK; c++) {
                    double d = metric.distance(flat, off, sub[c], 0);
                    if (d < bestD) { bestD = d; best = c; }
                }
                assign[t] = best;
            }
            for (int c = 0; c < subK; c++) {
                java.util.Arrays.fill(sum[c], 0.0);
                cnt[c] = 0;
            }
            for (int t = 0; t < size; t++) {
                int c = assign[t];
                int off = members[from + t] * dim;
                for (int d = 0; d < dim; d++) sum[c][d] += flat.get(off + d);
                cnt[c]++;
            }
            for (int c = 0; c < subK; c++) {
                if (cnt[c] > 0) {
                    double inv = 1.0 / cnt[c];
                    for (int d = 0; d < dim; d++) sub[c][d] = sum[c][d] * inv;
                }
            }
        }

        // Atribuição final + emissão dos sub-clusters não-vazios.
        int[] subSizes = new int[subK];
        for (int t = 0; t < size; t++) {
            int off = members[from + t] * dim;
            int best = 0;
            double bestD = Double.POSITIVE_INFINITY;
            for (int c = 0; c < subK; c++) {
                double d = metric.distance(flat, off, sub[c], 0);
                if (d < bestD) { bestD = d; best = c; }
            }
            assign[t] = best;
            subSizes[best]++;
        }
        for (int c = 0; c < subK; c++) {
            if (subSizes[c] == 0) continue;
            int[] mem = new int[subSizes[c]];
            int w = 0;
            for (int t = 0; t < size; t++) {
                if (assign[t] == c) mem[w++] = members[from + t];
            }
            outCentroids.add(sub[c]);
            outMembers.add(mem);
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

    /** Quantiza a bbox float32 → int16 (×QUANT_SCALE). round recupera o inteiro exato. */
    private void quantizeBbox() {
        if (bboxMin == null || bboxMax == null) return;
        int total = bboxMin.length;
        int scale = ReferenceDataset.QUANT_SCALE;
        bboxMinS = new short[total];
        bboxMaxS = new short[total];
        for (int i = 0; i < total; i++) {
            bboxMinS[i] = (short) Math.round(bboxMin[i] * scale);
            bboxMaxS[i] = (short) Math.round(bboxMax[i] * scale);
        }
    }

    @Override
    public void searchTopK(double[] query, int k, SearchResult out) {
        if (rows == null) throw new IllegalStateException("index not built/loaded");
        int dim = ReferenceDataset.DIM;
        int numC = postingStart.length - 1;

        // Query → int16 (×QUANT_SCALE). clamp01 já arredondou a 4 casas → exato.
        short[] q16 = out.q16();
        int scale = ReferenceDataset.QUANT_SCALE;
        for (int d = 0; d < dim; d++) {
            q16[d] = (short) Math.round(query[d] * scale);
        }

        // 1. nProbe centróides mais próximos (double — seleção aproximada-OK,
        //    o repair cobre o resto e torna o resultado exato). bestC/bestCd
        //    reusam os buffers scratch da SearchResult — zero alocação.
        int[] bestC = out.probedClusters();
        double[] bestCd = out.probeDist();
        int probes = Math.min(Math.min(nProbe, numC), bestC.length);
        int filled = 0;
        double worst = Double.POSITIVE_INFINITY;
        for (int c = 0; c < numC; c++) {
            double d = metric.distance(query, centroids, 0, c * dim);
            if (filled < probes) {
                bestC[filled] = c;
                bestCd[filled] = d;
                filled++;
                if (filled == probes) worst = maxOf(bestCd, probes);
            } else if (d < worst) {
                int wp = 0;
                double wv = bestCd[0];
                for (int i = 1; i < probes; i++) {
                    if (bestCd[i] > wv) { wv = bestCd[i]; wp = i; }
                }
                bestC[wp] = c;
                bestCd[wp] = d;
                worst = maxOf(bestCd, probes);
            }
        }
        // Registra os clusters varridos — o repair os pula (sem dedup).
        // bestC já É out.probedClusters() (scratch preenchido in-place, sem cópia).
        out.setProbedCount(filled);

        // 2. Varre as posting lists desses clusters (int16, acumulador long).
        int[] heapIdx = out.indices();
        double[] heapDist = out.distances();   // guarda long-as-double
        int heapSize = 0;
        long heapWorst = Long.MAX_VALUE;
        for (int p = 0; p < filled; p++) {
            int c = bestC[p];
            int start = postingStart[c];
            int end = postingStart[c + 1];
            for (int j = start; j < end; j++) {
                int i = identityPostings ? j : postingList.get(j);
                int off = i * dim;
                if (heapSize < k) {
                    long d = l2sqInt(q16, off);
                    heapIdx[heapSize] = i;
                    heapDist[heapSize] = d;
                    heapSize++;
                    if (heapSize == k) heapWorst = maxLong(heapDist, k);
                } else {
                    long d = l2sqIntCutoff(q16, off, heapWorst);
                    if (d >= heapWorst) continue;
                    int wp = 0;
                    long wv = (long) heapDist[0];
                    for (int q = 1; q < k; q++) {
                        long v = (long) heapDist[q];
                        if (v > wv) { wv = v; wp = q; }
                    }
                    heapIdx[wp] = i;
                    heapDist[wp] = d;
                    heapWorst = maxLong(heapDist, k);
                }
            }
        }

        sortAscendingLong(heapIdx, heapDist, heapSize);
        out.setSize(heapSize);
    }

    /**
     * Repair: dado um {@link SearchResult} já preenchido pela fast pass, varre
     * todos os clusters NÃO varridos por ela, podando por lower-bound de bbox.
     * Como os clusters da fast pass são pulados, cada vetor é visto uma vez só
     * — sem dedup. O resultado é o top-K exato.
     */
    public void scanAllWithBboxPrune(double[] query, int k, SearchResult inOut) {
        if (rows == null || bboxMinS == null) {
            throw new IllegalStateException("scanAllWithBboxPrune requires built/loaded index");
        }
        int dim = ReferenceDataset.DIM;
        int numC = postingStart.length - 1;

        short[] q16 = inOut.q16();
        int scale = ReferenceDataset.QUANT_SCALE;
        for (int d = 0; d < dim; d++) {
            q16[d] = (short) Math.round(query[d] * scale);
        }

        int[] probed = inOut.probedClusters();
        int probedCount = inOut.probedCount();

        int[] heapIdx = inOut.indices();
        double[] heapDist = inOut.distances();
        int heapSize = inOut.size();
        long heapWorst = heapSize >= k ? maxLong(heapDist, k) : Long.MAX_VALUE;

        for (int c = 0; c < numC; c++) {
            // Pula os clusters já varridos pela fast pass.
            boolean skip = false;
            for (int p = 0; p < probedCount; p++) {
                if (probed[p] == c) { skip = true; break; }
            }
            if (skip) continue;

            // Lower-bound da bbox com early-exit: aborta a soma quando já passa
            // do pior do heap — a maioria dos clusters morre nas primeiras dims.
            long bbLow = bboxLowerBoundInt(q16, c * dim, heapWorst);
            if (bbLow >= heapWorst) continue;

            int start = postingStart[c];
            int end = postingStart[c + 1];
            for (int j = start; j < end; j++) {
                int i = identityPostings ? j : postingList.get(j);
                int off = i * dim;
                if (heapSize < k) {
                    long d = l2sqInt(q16, off);
                    heapIdx[heapSize] = i;
                    heapDist[heapSize] = d;
                    heapSize++;
                    if (heapSize == k) heapWorst = maxLong(heapDist, k);
                } else {
                    long d = l2sqIntCutoff(q16, off, heapWorst);
                    if (d >= heapWorst) continue;
                    int wp = 0;
                    long wv = (long) heapDist[0];
                    for (int q = 1; q < k; q++) {
                        long v = (long) heapDist[q];
                        if (v > wv) { wv = v; wp = q; }
                    }
                    heapIdx[wp] = i;
                    heapDist[wp] = d;
                    heapWorst = maxLong(heapDist, k);
                }
            }
        }

        sortAscendingLong(heapIdx, heapDist, heapSize);
        inOut.setSize(heapSize);
    }

    /** L2² inteira entre query int16 e a linha em {@code rows[off..off+13]}. */
    private long l2sqInt(short[] q16, int off) {
        long sum = 0;
        for (int d = 0; d < ReferenceDataset.DIM; d++) {
            int diff = q16[d] - rows[off + d];
            sum += (long) diff * diff;
        }
        return sum;
    }

    /**
     * L2² inteira com early-exit POR DIMENSÃO: aborta a soma assim que a
     * parcial atinge {@code cutoff}. A maioria dos candidatos está longe e
     * morre nas primeiras dims — só os poucos que entram no top-K pagam as 14.
     * (Antes o corte era único, após 8 dims.)
     */
    private long l2sqIntCutoff(short[] q16, int off, long cutoff) {
        long sum = 0;
        for (int d = 0; d < ReferenceDataset.DIM; d++) {
            int diff = q16[d] - rows[off + d];
            sum += (long) diff * diff;
            if (sum >= cutoff) return sum;
        }
        return sum;
    }

    /**
     * Lower-bound inteiro: L2² do query até o ponto mais próximo da bbox do
     * cluster. Early-exit — retorna assim que a soma parcial atinge {@code limit}.
     */
    private long bboxLowerBoundInt(short[] q16, int off, long limit) {
        long sum = 0;
        for (int d = 0; d < ReferenceDataset.DIM; d++) {
            int q = q16[d];
            int lo = bboxMinS[off + d];
            int hi = bboxMaxS[off + d];
            if (q < lo) {
                int df = lo - q;
                sum += (long) df * df;
            } else if (q > hi) {
                int df = q - hi;
                sum += (long) df * df;
            }
            if (sum >= limit) return sum;
        }
        return sum;
    }

    /**
     * Salva o índice no formato .idx v2 (legado — só o caminho dev de build
     * in-memory + persist). O caminho de produção usa {@link #writeClusteredArtifacts}.
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
            out.write(header.array());

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

            ByteBuffer ibuf = ByteBuffer.allocate((k + 1) * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i <= k; i++) ibuf.putInt(postingStart[i]);
            out.write(ibuf.array());

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

            ByteBuffer bbBuf = ByteBuffer.allocate(k * dim * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < k * dim; i++) bbBuf.putFloat(bboxMin[i]);
            out.write(bbBuf.array());
            bbBuf.clear();
            for (int i = 0; i < k * dim; i++) bbBuf.putFloat(bboxMax[i]);
            out.write(bbBuf.array());
        }
    }

    /**
     * Escreve os artefatos no layout CLUSTERED: o dataset {@code .bin} com as
     * linhas reordenadas por cluster — cluster c ocupa as linhas contíguas
     * {@code [postingStart[c], postingStart[c+1])} — gravadas em int16
     * (×QUANT_SCALE), e o {@code .idx} v2 com a posting list = identidade.
     *
     * Linhas contíguas → varredura de cluster sequencial. int16 → metade dos
     * bytes no scan memory-bound do repair. É uma permutação + quantização
     * lossless: mesmas distâncias relativas, mesmo top-K. Os labels são
     * reordenados na mesma permutação → contagem de fraude idêntica.
     */
    public void writeClusteredArtifacts(ReferenceDataset original,
                                        Path idxPath, Path clusteredBinPath) throws IOException {
        if (dataset == null || centroids == null || postingStart == null || postingList == null) {
            throw new IllegalStateException("writeClusteredArtifacts requires build() first");
        }
        int k = numCentroids();
        int dim = ReferenceDataset.DIM;
        int n = original.size();
        if (n != dataset.size()) {
            throw new IllegalStateException("original size " + n
                    + " != built dataset size " + dataset.size());
        }
        int scale = ReferenceDataset.QUANT_SCALE;

        // ---- 1. dataset .bin reordenado por cluster + quantizado int16 ----
        if (clusteredBinPath.toAbsolutePath().getParent() != null) {
            Files.createDirectories(clusteredBinPath.toAbsolutePath().getParent());
        }
        FloatBuffer srcFlat = original.flatFloats();
        byte[] srcLabels = original.labels();
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(
                clusteredBinPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {

            ByteBuffer hdr = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            hdr.put((byte) 'R'); hdr.put((byte) 'N'); hdr.put((byte) 'H'); hdr.put((byte) 'A');
            hdr.put((byte) 2);     // version 2
            hdr.put((byte) dim);   // dim
            hdr.put((byte) 1);     // dtype int16
            hdr.put((byte) 0);     // flags
            hdr.putInt(n);
            hdr.putInt(0);
            out.write(hdr.array());

            // linhas int16: linha p = quantize(vetor original postingList[p]).
            int rowsPerChunk = 512;
            ByteBuffer sb = ByteBuffer.allocate(rowsPerChunk * dim * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (int p = 0; p < n; ) {
                int take = Math.min(rowsPerChunk, n - p);
                sb.clear();
                for (int r = 0; r < take; r++) {
                    int srcOff = postingList.get(p + r) * dim;
                    for (int d = 0; d < dim; d++) {
                        sb.putShort((short) Math.round(srcFlat.get(srcOff + d) * scale));
                    }
                }
                out.write(sb.array(), 0, take * dim * 2);
                p += take;
            }

            // labels: mesma permutação das linhas.
            byte[] lb = new byte[4096];
            for (int p = 0; p < n; ) {
                int take = Math.min(lb.length, n - p);
                for (int r = 0; r < take; r++) {
                    lb[r] = srcLabels[postingList.get(p + r)];
                }
                out.write(lb, 0, take);
                p += take;
            }
        }

        // ---- 2. .idx v2 com posting list = identidade ----
        if (idxPath.toAbsolutePath().getParent() != null) {
            Files.createDirectories(idxPath.toAbsolutePath().getParent());
        }
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(
                idxPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {

            ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            header.putInt(MAGIC);
            header.putInt(VERSION);
            header.putInt(k);
            header.putInt(dim);
            header.putInt(n);
            out.write(header.array());

            int chunkFloats = 1024;
            ByteBuffer cb = ByteBuffer.allocate(chunkFloats * 4).order(ByteOrder.LITTLE_ENDIAN);
            int totalF = k * dim;
            int writtenF = 0;
            while (writtenF < totalF) {
                int take = Math.min(chunkFloats, totalF - writtenF);
                cb.clear();
                for (int i = 0; i < take; i++) cb.putFloat((float) centroids[writtenF + i]);
                out.write(cb.array(), 0, take * 4);
                writtenF += take;
            }

            ByteBuffer ps = ByteBuffer.allocate((k + 1) * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i <= k; i++) ps.putInt(postingStart[i]);
            out.write(ps.array());

            // posting list = identidade (0,1,2,...,n-1) — dataset já reordenado.
            int chunkInts = 4096;
            ByteBuffer pl = ByteBuffer.allocate(chunkInts * 4).order(ByteOrder.LITTLE_ENDIAN);
            int writtenL = 0;
            while (writtenL < n) {
                int take = Math.min(chunkInts, n - writtenL);
                pl.clear();
                for (int i = 0; i < take; i++) pl.putInt(writtenL + i);
                out.write(pl.array(), 0, take * 4);
                writtenL += take;
            }

            ByteBuffer bb = ByteBuffer.allocate(k * dim * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < k * dim; i++) bb.putFloat(bboxMin[i]);
            out.write(bb.array());
            bb.clear();
            for (int i = 0; i < k * dim; i++) bb.putFloat(bboxMax[i]);
            out.write(bb.array());
        }
    }

    /**
     * Carrega um índice .idx via mmap. O {@code dataset} deve ser o .bin
     * clustered int16 (linhas já em ordem de cluster). centroids + postingStart
     * + bbox vão pra heap; postingList vira IntBuffer direto sobre o arquivo.
     */
    public static IvfVectorIndex loadMmap(DistanceMetric metric, int nProbe,
                                          Path path, ReferenceDataset dataset) throws IOException {
        return loadMmap(metric, nProbe, path, dataset, false, false);
    }

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

            double[] centroids = new double[k * dim];
            mapped.position(HEADER_BYTES);
            ByteBuffer centroidsRegion = mapped.slice().order(ByteOrder.LITTLE_ENDIAN);
            centroidsRegion.limit((int) centroidsBytes);
            FloatBuffer centroidsFB = centroidsRegion.asFloatBuffer();
            for (int i = 0; i < k * dim; i++) {
                centroids[i] = centroidsFB.get(i);
            }

            int[] postingStart = new int[k + 1];
            mapped.position(HEADER_BYTES + (int) centroidsBytes);
            ByteBuffer psRegion = mapped.slice().order(ByteOrder.LITTLE_ENDIAN);
            psRegion.limit((int) postingStartBytes);
            IntBuffer psIB = psRegion.asIntBuffer();
            for (int i = 0; i <= k; i++) {
                postingStart[i] = psIB.get(i);
            }

            int plStart = HEADER_BYTES + (int) centroidsBytes + (int) postingStartBytes;
            mapped.position(plStart);
            ByteBuffer plRegion = mapped.slice().order(ByteOrder.LITTLE_ENDIAN);
            plRegion.limit((int) postingListBytes);
            IntBuffer postingList = plRegion.asIntBuffer();

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
                    centroids, postingStart, postingList, bboxMin, bboxMax,
                    heapRows(dataset));
        }
    }

    private int numCentroids() {
        return postingStart.length - 1;
    }

    /** true se postingList[j] == j para todo j — layout clustered. Roda 1× no startup. */
    private static boolean isIdentity(IntBuffer pl) {
        int n = pl.limit();
        for (int j = 0; j < n; j++) {
            if (pl.get(j) != j) return false;
        }
        return true;
    }

    /**
     * Vetores int16 do dataset como {@code short[]} em heap. Se o dataset já é
     * heap-backed (MMAP=false → {@link com.rinha.dataset.BinaryDataset#read}),
     * devolve o array de trás — zero cópia. Só copia no fallback mmap.
     */
    private static short[] heapRows(ReferenceDataset dataset) {
        if (!dataset.quantized()) {
            throw new IllegalStateException(
                    "IVF runtime requer o dataset clustered int16 (.bin dtype int16)");
        }
        ShortBuffer sb = dataset.flatShorts();
        int n = dataset.size() * ReferenceDataset.DIM;
        if (sb.hasArray() && sb.arrayOffset() == 0 && sb.array().length == n) {
            return sb.array();
        }
        short[] r = new short[n];
        sb.get(0, r, 0, n);
        return r;
    }

    private static double maxOf(double[] a, int size) {
        double m = a[0];
        for (int i = 1; i < size; i++) if (a[i] > m) m = a[i];
        return m;
    }

    private static long maxLong(double[] a, int size) {
        long m = (long) a[0];
        for (int i = 1; i < size; i++) {
            long v = (long) a[i];
            if (v > m) m = v;
        }
        return m;
    }

    /** Insertion sort por distância ascendente (distâncias guardadas como long-as-double). */
    private static void sortAscendingLong(int[] idxArr, double[] distArr, int size) {
        for (int i = 1; i < size; i++) {
            long dv = (long) distArr[i];
            int ix = idxArr[i];
            int j = i - 1;
            while (j >= 0 && (long) distArr[j] > dv) {
                distArr[j + 1] = distArr[j];
                idxArr[j + 1] = idxArr[j];
                j--;
            }
            distArr[j + 1] = dv;
            idxArr[j + 1] = ix;
        }
    }

    @Override
    public String name() {
        return "IVF";
    }
}
