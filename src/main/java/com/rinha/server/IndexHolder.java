package com.rinha.server;

import com.rinha.index.ReferenceDataset;
import com.rinha.index.VectorIndex;

/**
 * Container mutável para o índice e o dataset. Permite ao Main subir o
 * servidor imediatamente (com /ready retornando 503) e injetar os
 * objetos reais após load + build + warmup, sem precisar reiniciar o
 * o servidor.
 *
 * Fields são volatile: o handler de /fraud-score só os lê após
 * ReadyState.isReady() retornar true (happens-before via volatile).
 */
public final class IndexHolder {

    private volatile VectorIndex index;
    private volatile VectorIndex fallbackIndex;
    private volatile ReferenceDataset dataset;

    public void set(VectorIndex index, VectorIndex fallbackIndex, ReferenceDataset dataset) {
        this.dataset = dataset;
        this.fallbackIndex = fallbackIndex;
        this.index = index;
    }

    public VectorIndex index() {
        return index;
    }

    public VectorIndex fallbackIndex() {
        return fallbackIndex;
    }

    public ReferenceDataset dataset() {
        return dataset;
    }
}
