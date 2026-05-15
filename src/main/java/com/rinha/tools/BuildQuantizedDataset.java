package com.rinha.tools;

import com.rinha.config.AppConfig;
import com.rinha.dataset.BinaryQuantizedDataset;
import com.rinha.dataset.DatasetLoader;
import com.rinha.dataset.QuantParams;
import com.rinha.index.QuantizedReferenceDataset;
import com.rinha.index.ReferenceDataset;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tool offline: lê o dataset float32 (.bin/.json/.gz via DATASET_PATH),
 * computa min/max global de todas as features, quantiza pra int16 e
 * escreve um .qbin com {@link QuantParams} no header.
 *
 * Uso (via gradle):
 *   DATASET_PATH=resources/references.bin \
 *     ./gradlew buildQuantizedDataset --args="resources/references.qbin"
 */
public final class BuildQuantizedDataset {

    private BuildQuantizedDataset() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: BuildQuantizedDataset <output.qbin>");
            System.exit(1);
        }
        Path output = Path.of(args[0]);

        AppConfig cfg = AppConfig.fromEnv();
        System.out.println("[qbuild] loading dataset (DATASET_PATH=" + cfg.datasetPath() + ")");
        long t0 = System.currentTimeMillis();
        ReferenceDataset ds = DatasetLoader.load(cfg);
        long tLoad = System.currentTimeMillis() - t0;
        System.out.println("[qbuild] loaded: size=" + ds.size() + " in " + tLoad + "ms");

        long t1 = System.currentTimeMillis();
        QuantParams params = computeParams(ds);
        System.out.println("[qbuild] params min=" + params.minVal()
                + " max=" + params.maxVal()
                + " scale=" + params.scale()
                + " center=" + params.center()
                + " (computed in " + (System.currentTimeMillis() - t1) + "ms)");

        long t2 = System.currentTimeMillis();
        QuantizedReferenceDataset qds = quantize(ds, params);
        System.out.println("[qbuild] quantized in " + (System.currentTimeMillis() - t2) + "ms");

        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        long t3 = System.currentTimeMillis();
        try (BufferedOutputStream out = new BufferedOutputStream(
                new FileOutputStream(output.toFile()), 1 << 20)) {
            BinaryQuantizedDataset.write(qds, out);
        }
        long fileSize = Files.size(output);
        System.out.println("[qbuild] wrote " + output + " (" + (fileSize >> 20)
                + " MiB) in " + (System.currentTimeMillis() - t3) + "ms");
    }

    private static QuantParams computeParams(ReferenceDataset ds) {
        FloatBuffer flat = ds.flatFloats();
        long total = (long) ds.size() * ReferenceDataset.DIM;
        if (total > Integer.MAX_VALUE) {
            throw new IllegalStateException("dataset too large for single buffer");
        }
        float min = Float.POSITIVE_INFINITY;
        float max = Float.NEGATIVE_INFINITY;
        int n = (int) total;
        for (int i = 0; i < n; i++) {
            float v = flat.get(i);
            if (v < min) min = v;
            if (v > max) max = v;
        }
        return new QuantParams(min, max);
    }

    private static QuantizedReferenceDataset quantize(ReferenceDataset ds, QuantParams p) {
        int n = ds.size();
        FloatBuffer flat = ds.flatFloats();
        int total = n * ReferenceDataset.DIM;
        short[] qFlat = new short[total];
        for (int i = 0; i < total; i++) {
            qFlat[i] = p.quantize(flat.get(i));
        }
        byte[] labels = ds.labels();
        byte[] labelsCopy = new byte[n];
        System.arraycopy(labels, 0, labelsCopy, 0, n);
        return new QuantizedReferenceDataset(qFlat, labelsCopy, n, p);
    }
}
