package com.rinha.index;

/**
 * Resultado top-K compacto. Arrays primitivos, sem boxing.
 *
 * indices[k]   = índice no ReferenceDataset
 * distances[k] = distância correspondente
 * size         = quantidade efetiva (<= K)
 *
 * O hot path do score só precisa de indices[] para olhar labels.
 */
public final class SearchResult {

    private final int[] indices;
    private final double[] distances;
    private int size;

    public SearchResult(int capacity) {
        this.indices = new int[capacity];
        this.distances = new double[capacity];
        this.size = 0;
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

    public void clear() {
        size = 0;
    }

    public void setSize(int n) {
        this.size = n;
    }
}
