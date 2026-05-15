package com.rinha.score;

import com.rinha.index.ReferenceDataset;
import com.rinha.index.SearchResult;
import com.rinha.model.FraudScoreResponse;

/**
 * Regras do enunciado:
 *   fraud_score = qtd_fraudes_entre_os_K / (double) K
 *   approved    = fraud_score < threshold
 *
 * Trabalha sobre o SearchResult já preenchido pelo VectorIndex.
 */
public final class FraudScorer {

    private final int topK;
    private final double threshold;

    public FraudScorer(int topK, double threshold) {
        this.topK = topK;
        this.threshold = threshold;
    }

    public int topK() { return topK; }

    public double threshold() { return threshold; }

    public FraudScoreResponse score(SearchResult result, ReferenceDataset dataset) {
        int fraudCount = countFrauds(result, dataset);
        double fraudScore = ((double) fraudCount) / topK;
        boolean approved = fraudScore < threshold;
        return new FraudScoreResponse(approved, fraudScore);
    }

    /**
     * Conta apenas — útil pra decidir se vale acionar fallback exato (boundary).
     * Sem alocação.
     */
    public int countFrauds(SearchResult result, ReferenceDataset dataset) {
        int[] indices = result.indices();
        int size = result.size();
        int fraudCount = 0;
        for (int i = 0; i < size; i++) {
            if (dataset.isFraud(indices[i])) fraudCount++;
        }
        return fraudCount;
    }

    public FraudScoreResponse scoreFromCount(int fraudCount) {
        double fraudScore = ((double) fraudCount) / topK;
        boolean approved = fraudScore < threshold;
        return new FraudScoreResponse(approved, fraudScore);
    }
}
