package com.rinha.server.service;

import com.rinha.index.IvfVectorIndex;
import com.rinha.index.ReferenceDataset;
import com.rinha.index.SearchResult;
import com.rinha.index.VectorIndex;
import com.rinha.perf.PerfLog;
import com.rinha.score.FraudScorer;
import com.rinha.server.IndexHolder;
import com.rinha.server.ReadyState;
import com.rinha.vector.VectorizationStrategy;

/**
 * Lógica pura do POST /fraud-score, sem dependência de servidor HTTP.
 * Recebe o body em byte[] (offset/len arbitrários) e scratch buffers do
 * caller. Invocado pelo servidor NIO.
 *
 * Retorno do {@link #handle}:
 *   0..K  → fraudCount; caller usa pra indexar resposta pré-renderizada
 *   -1    → loading (ainda não pronto)
 *   -2    → bad request (parser falhou)
 */
public final class FraudScoreService {

    public static final int LOADING = -1;
    public static final int BAD_REQUEST = -2;

    private final ReadyState ready;
    private final IndexHolder holder;
    private final VectorizationStrategy strategy;
    private final FraudScorer scorer;
    private final int topK;

    public FraudScoreService(ReadyState ready,
                             IndexHolder holder,
                             VectorizationStrategy strategy,
                             FraudScorer scorer) {
        this.ready = ready;
        this.holder = holder;
        this.strategy = strategy;
        this.scorer = scorer;
        this.topK = scorer.topK();
    }

    public int topK() {
        return topK;
    }

    public int handle(byte[] body, int off, int len,
                      double[] queryScratch,
                      SearchResult resultScratch) {
        if (!ready.isReady()) return LOADING;

        long tStart = PerfLog.now();
        VectorIndex index = holder.index();
        ReferenceDataset dataset = holder.dataset();
        if (index == null || dataset == null) return LOADING;

        try {
            long tVec0 = PerfLog.now();
            strategy.vectorize(body, off, len, queryScratch, 0);
            PerfLog.log("vectorize", tVec0);

            resultScratch.clear();
            long tSearch0 = PerfLog.now();
            index.searchTopK(queryScratch, topK, resultScratch);
            PerfLog.log("search", tSearch0);

            long tScore0 = PerfLog.now();

            // Repair sem gate: scanAllWithBboxPrune devolve o top-K exato-por-
            // distância (poda por bbox é lower-bound correto), então roda em
            // TODA request quando BOUNDARY_FALLBACK está ligado. O gate antigo
            // (fraudCount in {2,3}) decidia o repair com base na contagem
            // APROXIMADA do fast pass — quando ela errava, casos de borda não
            // eram reparados. Sem gate, o resultado é exato pra 100% das queries.
            VectorIndex fallbackIndex = holder.fallbackIndex();
            if (fallbackIndex instanceof IvfVectorIndex ivfRepair) {
                long tFb0 = PerfLog.now();
                ivfRepair.scanAllWithBboxPrune(queryScratch, topK, resultScratch);
                PerfLog.log("repair", tFb0);
            }

            int fraudCount = scorer.countFrauds(resultScratch, dataset);
            PerfLog.log("score", tScore0);
            PerfLog.log("total", tStart);
            return fraudCount;
        } catch (RuntimeException ex) {
            return BAD_REQUEST;
        }
    }
}
