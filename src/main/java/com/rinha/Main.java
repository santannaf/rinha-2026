package com.rinha;

import com.rinha.config.AppConfig;
import com.rinha.dataset.DatasetLoader;
import com.rinha.distance.DistanceMetric;
import com.rinha.index.IndexFactory;
import com.rinha.index.IvfQuantizedVectorIndex;
import com.rinha.index.IvfVectorIndex;
import com.rinha.index.ReferenceDataset;
import com.rinha.index.VectorIndex;
import com.rinha.perf.PerfLog;
import com.rinha.score.FraudScorer;
import com.rinha.server.HttpServer;
import com.rinha.server.IndexHolder;
import com.rinha.server.ReadyState;
import com.rinha.server.ServerFactory;
import com.rinha.vector.McCRiskMap;
import com.rinha.vector.Normalization;
import com.rinha.vector.SchemaAwareVectorizationStrategy;
import com.rinha.vector.VectorizationStrategy;
import com.rinha.warmup.Warmup;

public final class Main {

    static void main(String[] args) throws Exception {
        long t0 = System.currentTimeMillis();
        AppConfig cfg = AppConfig.fromEnv();
        PerfLog.configure(cfg.perfLog());
        System.out.println("[main] " + cfg);

        Normalization norm = Normalization.fromClasspath("normalization.json");
        McCRiskMap mccRisk = McCRiskMap.fromClasspath("mcc_risk.json");
        System.out.println("[main] normalization loaded, mcc entries=" + mccRisk.size());

        VectorizationStrategy strategy = new SchemaAwareVectorizationStrategy(norm, mccRisk);
        DistanceMetric metric = DistanceMetric.byName(cfg.distanceMetric());
        FraudScorer scorer = new FraudScorer(cfg.topK(), cfg.threshold());

        ReadyState ready = new ReadyState();
        IndexHolder holder = new IndexHolder();

        HttpServer server = ServerFactory.create(cfg, ready, holder, strategy, scorer);
        server.start();

        Runtime.getRuntime().addShutdownHook(
                Thread.ofPlatform().unstarted(() -> {
                    try { server.stop(); } catch (Exception ignored) {}
                }));

        Thread loader = Thread.ofPlatform().name("loader").start(() -> {
            try {
                long tl = System.currentTimeMillis();
                ReferenceDataset dataset = DatasetLoader.load(cfg);
                System.out.println("[main] dataset loaded: size=" + dataset.size()
                        + " in " + (System.currentTimeMillis() - tl) + "ms");

                long tb = System.currentTimeMillis();
                VectorIndex index;
                String idxPath = cfg.ivfIndexPath();
                boolean wantQuantized = "IVF_Q16".equals(cfg.vectorIndex())
                        && idxPath != null && !idxPath.isEmpty()
                        && cfg.qbinPath() != null && !cfg.qbinPath().isEmpty();
                if (wantQuantized
                        && java.nio.file.Files.exists(java.nio.file.Path.of(idxPath))
                        && java.nio.file.Files.exists(java.nio.file.Path.of(cfg.qbinPath()))) {
                    System.out.println("[main] loading IVF_Q16 index from " + idxPath
                            + " + " + cfg.qbinPath());
                    index = IvfQuantizedVectorIndex.loadMmap(
                            metric, cfg.ivfNProbe(),
                            java.nio.file.Path.of(idxPath),
                            java.nio.file.Path.of(cfg.qbinPath()),
                            cfg.mmapPrefetch(), cfg.mmapHugepage());
                    System.out.println("[main] index loaded: " + index.name()
                            + " in " + (System.currentTimeMillis() - tb) + "ms");
                    if (index instanceof IvfQuantizedVectorIndex qix) {
                        qix.setEarlyStop(cfg.ivfEarlyStop());
                        if (cfg.ivfEarlyStop()) {
                            System.out.println("[main] IVF_Q16 early-stop enabled");
                        }
                    }
                    holder.set(index, null, dataset);
                    long tw0 = System.currentTimeMillis();
                    long iters0 = new Warmup(index, dataset, cfg.warmupWorkers(),
                            cfg.warmupIterations(), cfg.topK(), cfg.indexSeed()).run();
                    System.out.println("[main] warmup done: iterations=" + iters0
                            + " in " + (System.currentTimeMillis() - tw0) + "ms");
                    ready.markReady();
                    System.out.println("[main] READY (total "
                            + (System.currentTimeMillis() - t0) + "ms)");
                    return;
                }

                boolean wantPersist = idxPath != null && !idxPath.isEmpty()
                        && "IVF".equals(cfg.vectorIndex());
                if (wantPersist && java.nio.file.Files.exists(java.nio.file.Path.of(idxPath))) {
                    System.out.println("[main] loading IVF index from " + idxPath);
                    index = IvfVectorIndex.loadMmap(metric, cfg.ivfNProbe(),
                            java.nio.file.Path.of(idxPath), dataset,
                            cfg.mmapPrefetch(), cfg.mmapHugepage());
                    System.out.println("[main] index loaded: " + index.name()
                            + " in " + (System.currentTimeMillis() - tb) + "ms");
                } else {
                    index = IndexFactory.create(cfg, metric);
                    index.build(dataset);
                    System.out.println("[main] index built: " + index.name()
                            + " in " + (System.currentTimeMillis() - tb) + "ms");
                    if (wantPersist && index instanceof IvfVectorIndex ivf) {
                        long ts = System.currentTimeMillis();
                        ivf.save(java.nio.file.Path.of(idxPath));
                        System.out.println("[main] index saved to " + idxPath
                                + " in " + (System.currentTimeMillis() - ts) + "ms");
                    }
                }

                // Early-stop class-aware: liga via env IVF_EARLY_STOP.
                if (index instanceof IvfVectorIndex ivfPrim) {
                    ivfPrim.setEarlyStop(cfg.ivfEarlyStop());
                    if (cfg.ivfEarlyStop()) {
                        System.out.println("[main] IVF early-stop enabled");
                    }
                }

                // Boundary fallback: deriva do IVF primário com nProbe maior.
                // Compartilha centroids/postings — overhead zero de memória.
                VectorIndex fallbackIndex = null;
                if (cfg.boundaryFallback() && index instanceof IvfVectorIndex primaryIvf) {
                    fallbackIndex = primaryIvf.withNProbe(cfg.boundaryFallbackNProbe());
                    System.out.println("[main] boundary fallback: IVF with nProbe="
                            + cfg.boundaryFallbackNProbe());
                } else if (cfg.boundaryFallback()) {
                    System.out.println("[main] boundary fallback skipped: primary is not IVF");
                }

                long tw = System.currentTimeMillis();
                long iters = new Warmup(index, dataset, cfg.warmupWorkers(),
                        cfg.warmupIterations(), cfg.topK(), cfg.indexSeed()).run();
                System.out.println("[main] warmup done: iterations=" + iters
                        + " in " + (System.currentTimeMillis() - tw) + "ms");

                holder.set(index, fallbackIndex, dataset);
                ready.markReady();
                System.out.println("[main] READY (total " + (System.currentTimeMillis() - t0) + "ms)");
            } catch (Exception ex) {
                System.err.println("[main] startup failed");
                ex.printStackTrace();
            }
        });

        loader.join();
        Thread.currentThread().join();
    }
}
