package com.rinha.tools;

import com.rinha.config.AppConfig;
import com.rinha.dataset.DatasetLoader;
import com.rinha.distance.DistanceMetric;
import com.rinha.index.IvfVectorIndex;
import com.rinha.index.ReferenceDataset;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tool offline: carrega o dataset (.bin via mmap, ou .gz streaming), constrói o
 * IVF (k-means + assignment) e grava o resultado num arquivo {@code .idx} pra
 * mmap-load no startup do server. Roda sob JVM no Dockerfile builder stage —
 * é ~10× mais rápido que o nativo dentro do container, e só roda uma vez por
 * imagem.
 *
 * Configuração via env (mesma do server): {@code DATASET_PATH}, {@code DISTANCE_METRIC},
 * {@code IVF_CENTROIDS}, {@code INDEX_SEED}, {@code MMAP}.
 *
 * Uso (via gradle):
 *   DATASET_PATH=resources/references.bin IVF_CENTROIDS=4096 \
 *     ./gradlew buildIvfIndex --args="resources/references.idx"
 */
public final class BuildIvfIndex {

    private BuildIvfIndex() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: BuildIvfIndex <output.idx> <clustered.bin>");
            System.exit(1);
        }
        Path idxOut = Path.of(args[0]);
        Path binOut = Path.of(args[1]);

        AppConfig cfg = AppConfig.fromEnv();
        System.out.println("[build-idx] cfg=" + cfg);

        long tLoad0 = System.currentTimeMillis();
        ReferenceDataset ds = DatasetLoader.load(cfg);
        System.out.println("[build-idx] dataset loaded: size=" + ds.size()
                + " in " + (System.currentTimeMillis() - tLoad0) + "ms");

        DistanceMetric metric = DistanceMetric.byName(cfg.distanceMetric());
        IvfVectorIndex ivf = new IvfVectorIndex(metric,
                cfg.ivfCentroids(), cfg.ivfNProbe(), cfg.indexSeed());

        long tBuild0 = System.currentTimeMillis();
        ivf.build(ds);
        System.out.println("[build-idx] IVF built in "
                + (System.currentTimeMillis() - tBuild0) + "ms");

        // Layout CLUSTERED: .idx (posting list = identidade) + dataset .bin com
        // as linhas reordenadas por cluster → varredura de cluster sequencial.
        long tSave0 = System.currentTimeMillis();
        ivf.writeClusteredArtifacts(ds, idxOut, binOut);
        System.out.println("[build-idx] clustered artifacts written in "
                + (System.currentTimeMillis() - tSave0) + "ms: "
                + idxOut + " (" + Files.size(idxOut) + " B), "
                + binOut + " (" + Files.size(binOut) + " B)");
    }
}
