package com.rinha.index;

import java.nio.FloatBuffer;

import com.rinha.distance.DistanceMetric;

/**
 * Baseline correto: varre todos os vetores do dataset e mantém top-K
 * usando um max-heap implícito num par (int[], double[]) de tamanho K.
 *
 * Para K pequeno (5) e N grande (3M), insertion no topo é mais rápido
 * que qualquer estrutura genérica (PriorityQueue<Double> faria boxing).
 *
 * Otimizações futuras documentadas no README:
 *  - paralelizar a varredura por chunks (ForkJoinPool).
 *  - Vector API (jdk.incubator.vector) para o batch de distâncias.
 *  - early exit por bound quando a distância parcial excede o pior do top-K.
 */
public final class BruteForceVectorIndex implements VectorIndex {

    private final DistanceMetric metric;
    private ReferenceDataset dataset;

    public BruteForceVectorIndex(DistanceMetric metric) {
        this.metric = metric;
    }

    @Override
    public void build(ReferenceDataset dataset) {
        this.dataset = dataset;
    }

    @Override
    public void searchTopK(double[] query, int k, SearchResult out) {
        if (dataset == null) throw new IllegalStateException("index not built");
        if (out.capacity() < k) throw new IllegalArgumentException("SearchResult too small");

        FloatBuffer flat = dataset.flatFloats();
        int n = dataset.size();
        int dim = ReferenceDataset.DIM;

        int[] heapIdx = out.indices();
        double[] heapDist = out.distances();
        int heapSize = 0;
        double worst = Double.POSITIVE_INFINITY;

        for (int i = 0; i < n; i++) {
            int off = i * dim;
            if (heapSize < k) {
                double d = metric.distance(query, flat, off);
                insertAtEnd(heapIdx, heapDist, heapSize, i, d);
                heapSize++;
                if (heapSize == k) worst = maxInHeap(heapDist, k);
            } else {
                double d = metric.distanceWithCutoff(query, flat, off, worst);
                if (d >= worst) continue;
                replaceWorst(heapIdx, heapDist, k, i, d);
                worst = maxInHeap(heapDist, k);
            }
        }

        sortAscending(heapIdx, heapDist, heapSize);
        out.setSize(heapSize);
    }

    /**
     * Coloca (idx, d) na posição livre. Heap não-ordenada — só fazemos
     * sort no final.
     */
    private static void insertAtEnd(int[] idxArr, double[] distArr, int pos, int idx, double d) {
        idxArr[pos] = idx;
        distArr[pos] = d;
    }

    private static double maxInHeap(double[] distArr, int size) {
        double max = distArr[0];
        for (int i = 1; i < size; i++) {
            if (distArr[i] > max) max = distArr[i];
        }
        return max;
    }

    private static void replaceWorst(int[] idxArr, double[] distArr, int size, int idx, double d) {
        int worstPos = 0;
        double worstVal = distArr[0];
        for (int i = 1; i < size; i++) {
            if (distArr[i] > worstVal) {
                worstVal = distArr[i];
                worstPos = i;
            }
        }
        idxArr[worstPos] = idx;
        distArr[worstPos] = d;
    }

    /**
     * Insertion sort por distância (K pequeno).
     */
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
        return "BRUTE_FORCE";
    }
}
