package com.rinha.index;

public interface VectorIndex {

    void build(ReferenceDataset dataset);

    /**
     * Preenche {@code out} com os top-K vizinhos mais próximos
     * (em ordem crescente de distância). {@code out.size()} reflete
     * quantos foram encontrados (pode ser < K se o dataset for menor).
     */
    void searchTopK(double[] queryVector, int k, SearchResult out);

    String name();
}
