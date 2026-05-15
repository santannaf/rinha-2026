package com.rinha.index;

import com.rinha.config.AppConfig;
import com.rinha.distance.DistanceMetric;

public final class IndexFactory {

    private IndexFactory() {}

    public static VectorIndex create(AppConfig cfg, DistanceMetric metric) {
        return switch (cfg.vectorIndex()) {
            case "BRUTE_FORCE" -> new BruteForceVectorIndex(metric);
            case "IVF" -> new IvfVectorIndex(metric, cfg.ivfCentroids(), cfg.ivfNProbe(), cfg.indexSeed());
            default -> throw new IllegalArgumentException(
                    "Unknown VECTOR_INDEX: " + cfg.vectorIndex() + " (suportados: BRUTE_FORCE, IVF)");
        };
    }
}
