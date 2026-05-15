package com.rinha.tools;

import com.rinha.config.AppConfig;
import com.rinha.dataset.BinaryDataset;
import com.rinha.dataset.DatasetLoader;
import com.rinha.index.ReferenceDataset;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tool offline: lê o dataset (formato JSON gz, via DATASET_PATH ou classpath)
 * e grava no formato binário .bin.
 *
 * Uso (via gradle):
 *   DATASET_PATH=resources/references.json.gz \
 *     ./gradlew convertDataset --args="resources/references.bin"
 *
 * Ou direto:
 *   DATASET_PATH=resources/references.json.gz \
 *     java -cp ... com.rinha.tools.ConvertDataset resources/references.bin
 */
public final class ConvertDataset {

    private ConvertDataset() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: ConvertDataset <output.bin>");
            System.err.println("  Reads dataset via DATASET_PATH env (or classpath fallback).");
            System.exit(1);
        }
        Path output = Path.of(args[0]);

        AppConfig cfg = AppConfig.fromEnv();
        System.out.println("[convert] reading dataset via DatasetLoader (DATASET_PATH=" + cfg.datasetPath() + ")");
        long t0 = System.currentTimeMillis();
        ReferenceDataset ds = DatasetLoader.load(cfg);
        long tLoad = System.currentTimeMillis() - t0;
        System.out.println("[convert] loaded: size=" + ds.size() + " in " + tLoad + "ms");

        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        long t1 = System.currentTimeMillis();
        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(output.toFile()), 1 << 20)) {
            BinaryDataset.write(ds, out);
        }
        long tWrite = System.currentTimeMillis() - t1;
        long size = Files.size(output);
        System.out.println("[convert] wrote " + output + ": " + size + " bytes in " + tWrite + "ms");
    }
}
