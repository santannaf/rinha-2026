package com.rinha.warmup;

import com.rinha.index.ReferenceDataset;
import com.rinha.index.SearchResult;
import com.rinha.index.VectorIndex;

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Warmup com amostras REAIS do dataset, em pool de threads.
 *
 * Por que amostras reais: o caminho de busca do índice (especialmente
 * IVF/LSH/HNSW) tem comportamento dependente da distribuição dos dados.
 * Vetores totalmente aleatórios podem cair em buckets/centróides
 * atípicos e fazer o JIT C2 perfilar e compilar um caminho diferente
 * do real. Isso degradaria a latência sob carga.
 */
public final class Warmup {

    private final VectorIndex index;
    private final ReferenceDataset dataset;
    private final int workers;
    private final int iterationsPerWorker;
    private final int topK;
    private final long seed;

    public Warmup(VectorIndex index, ReferenceDataset dataset, int workers, int iterationsPerWorker, int topK, long seed) {
        this.index = index;
        this.dataset = dataset;
        this.workers = workers;
        this.iterationsPerWorker = iterationsPerWorker;
        this.topK = topK;
        this.seed = seed;
    }

    /**
     * Roda o warmup. Retorna o total de buscas executadas.
     */
    public long run() throws InterruptedException {
        if (dataset.size() == 0 || iterationsPerWorker <= 0 || workers <= 0) {
            return 0L;
        }
        CountDownLatch latch = new CountDownLatch(workers);
        AtomicLong counter = new AtomicLong();
        Thread[] threads = new Thread[workers];
        for (int w = 0; w < workers; w++) {
            final long wSeed = seed + 1000L * w;
            threads[w] = Thread.ofPlatform().name("warmup-" + w).unstarted(() -> {
                try {
                    Random rnd = new Random(wSeed);
                    double[] query = new double[ReferenceDataset.DIM];
                    SearchResult result = new SearchResult(topK);
                    int n = dataset.size();
                    for (int it = 0; it < iterationsPerWorker; it++) {
                        int sampleIdx = rnd.nextInt(n);
                        dataset.copyVector(sampleIdx, query);
                        // Pequeno jitter para não medir exatamente o vizinho trivial.
                        for (int d = 0; d < ReferenceDataset.DIM; d++) {
                            query[d] += (rnd.nextDouble() - 0.5) * 0.01;
                        }
                        index.searchTopK(query, topK, result);
                        counter.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
            threads[w].start();
        }
        latch.await();
        return counter.get();
    }
}
