package com.rinha.index;

/**
 * Resultado top-K compacto. Arrays primitivos, sem boxing.
 *
 * indices[k]   = índice no dataset (posição no layout clustered)
 * distances[k] = distância correspondente (L2² int16, guardada como double)
 * size         = quantidade efetiva (<= K)
 *
 * probedClusters = clusters varridos pela fast pass ({@code searchTopK}). O
 * repair ({@code scanAllWithBboxPrune}) pula esses clusters, então cada cluster
 * é varrido exatamente uma vez — sem precisar de dedup por vetor.
 *
 * O hot path do score só precisa de indices[] para olhar labels.
 */
public final class SearchResult {

    private final int[] indices;
    private final double[] distances;
    private int size;

    private final int[] probedClusters;
    private int probedCount;

    // Scratch buffers reusados — uma SearchResult por thread (NIO loop tem
    // uma; cada worker de warmup cria a sua), então não há race. Mantê-los
    // aqui torna o hot path da busca IVF de fato zero-alloc: antes,
    // searchTopK/scanAllWithBboxPrune alocavam q16/bestC/bestCd por request.
    private final short[] q16 = new short[ReferenceDataset.DIM];
    private final double[] probeDist = new double[512];

    public SearchResult(int capacity) {
        this.indices = new int[capacity];
        this.distances = new double[capacity];
        this.size = 0;
        // Folga grande: nProbe realista é 2 (até ~32 em A/B). 512 cobre tudo.
        this.probedClusters = new int[512];
        this.probedCount = 0;
    }

    /** Query quantizada int16 (×QUANT_SCALE) — scratch da busca IVF. */
    public short[] q16() {
        return q16;
    }

    /** Distâncias scratch da seleção de centróides na fast pass (paralelo a probedClusters). */
    public double[] probeDist() {
        return probeDist;
    }

    public int[] indices() {
        return indices;
    }

    public double[] distances() {
        return distances;
    }

    public int size() {
        return size;
    }

    public int capacity() {
        return indices.length;
    }

    public int[] probedClusters() {
        return probedClusters;
    }

    public int probedCount() {
        return probedCount;
    }

    public void setProbedClusters(int[] src, int count) {
        int n = Math.min(count, probedClusters.length);
        System.arraycopy(src, 0, probedClusters, 0, n);
        this.probedCount = n;
    }

    /**
     * Registra o nº de clusters varridos quando {@code probedClusters()} já foi
     * preenchido in-place (caller usa o array scratch direto, sem cópia).
     */
    public void setProbedCount(int count) {
        this.probedCount = Math.min(count, probedClusters.length);
    }

    public void clear() {
        size = 0;
        probedCount = 0;
    }

    public void setSize(int n) {
        this.size = n;
    }
}
