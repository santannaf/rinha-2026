package com.rinha.tools;

import com.rinha.config.AppConfig;
import com.rinha.dataset.DatasetLoader;
import com.rinha.distance.DistanceMetric;
import com.rinha.index.IvfVectorIndex;
import com.rinha.index.ReferenceDataset;
import com.rinha.index.SearchResult;
import com.rinha.index.VectorIndex;
import com.rinha.score.FraudScorer;
import com.rinha.vector.McCRiskMap;
import com.rinha.vector.Normalization;
import com.rinha.vector.SchemaAwareVectorizationStrategy;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tool offline: replica o pipeline (vectorize → search → score) contra
 * {@code requests.jsonl} + {@code expected.jsonl}, categoriza cada caso
 * em TP/TN/FP/FN e grava:
 *   - failures.jsonl: uma linha por FP+FN com raw input + vetor + top-K + score.
 *   - summary.tsv: contagens TP/TN/FP/FN.
 *   - features.tsv: média + std de cada dimensão do vetor por grupo.
 *
 * Uso:
 *   jq -c '.entries[].request' test/test-data.json > test/requests.jsonl
 *   jq -c '.entries[].expected_approved' test/test-data.json > test/expected.jsonl
 *   DATASET_PATH=resources/references.bin IVF_INDEX_PATH=resources/references.idx \
 *     ./gradlew analyzeFailures --args="test/requests.jsonl test/expected.jsonl test/analysis"
 */
public final class AnalyzeFailures {

    private static final int DIM = ReferenceDataset.DIM;
    private static final int TOP_K = 5;
    private static final double THRESHOLD = 0.6;

    private AnalyzeFailures() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            System.err.println("usage: AnalyzeFailures <requests.jsonl> <expected.jsonl> <out-dir>");
            System.exit(1);
        }
        Path requestsPath = Path.of(args[0]);
        Path expectedPath = Path.of(args[1]);
        Path outDir = Path.of(args[2]);
        Files.createDirectories(outDir);

        AppConfig cfg = AppConfig.fromEnv();
        System.out.println("[analyze] cfg=" + cfg);

        Normalization norm = Normalization.fromClasspath("normalization.json");
        McCRiskMap mccRisk = McCRiskMap.fromClasspath("mcc_risk.json");
        SchemaAwareVectorizationStrategy strat =
                new SchemaAwareVectorizationStrategy(norm, mccRisk);
        DistanceMetric metric = DistanceMetric.byName(cfg.distanceMetric());
        FraudScorer scorer = new FraudScorer(TOP_K, THRESHOLD);

        ReferenceDataset dataset = DatasetLoader.load(cfg);
        System.out.println("[analyze] dataset size=" + dataset.size());

        VectorIndex index;
        String idxPath = cfg.ivfIndexPath();
        if (idxPath != null && !idxPath.isEmpty()
                && Files.exists(Path.of(idxPath))) {
            index = IvfVectorIndex.load(metric, cfg.ivfNProbe(),
                    Path.of(idxPath), dataset);
            System.out.println("[analyze] loaded IVF .idx from " + idxPath);
        } else {
            throw new IOException("requires IVF_INDEX_PATH pointing to a built .idx");
        }

        // Acumuladores: por grupo, soma e somaQ por dimensão pra média+std.
        // 4 grupos: 0=TN, 1=TP, 2=FP, 3=FN.
        int[] counts = new int[4];
        double[][] sums = new double[4][DIM];
        double[][] sumsSq = new double[4][DIM];

        SearchResult result = new SearchResult(TOP_K);
        double[] vec = new double[DIM];

        try (BufferedReader reqReader = Files.newBufferedReader(requestsPath, StandardCharsets.UTF_8);
             BufferedReader expReader = Files.newBufferedReader(expectedPath, StandardCharsets.UTF_8);
             BufferedWriter failOut = Files.newBufferedWriter(
                     outDir.resolve("failures.jsonl"), StandardCharsets.UTF_8)) {

            long total = 0;
            String reqLine, expLine;
            while ((reqLine = reqReader.readLine()) != null
                    && (expLine = expReader.readLine()) != null) {
                total++;
                boolean expected = "true".equals(expLine.trim());

                byte[] reqBytes = reqLine.getBytes(StandardCharsets.UTF_8);
                java.util.Arrays.fill(vec, 0.0);
                strat.vectorize(reqBytes, 0, reqBytes.length, vec, 0);

                result.clear();
                index.searchTopK(vec, TOP_K, result);
                int fraudCount = scorer.countFrauds(result, dataset);
                double predictedScore = ((double) fraudCount) / TOP_K;
                boolean predicted = predictedScore < THRESHOLD;

                int group;
                if (predicted && expected) group = 0;          // TN: legit ok
                else if (!predicted && !expected) group = 1;   // TP: fraud caught
                else if (!predicted && expected) group = 2;    // FP: legit barrada
                else group = 3;                                // FN: fraud passou

                counts[group]++;
                for (int d = 0; d < DIM; d++) {
                    sums[group][d] += vec[d];
                    sumsSq[group][d] += vec[d] * vec[d];
                }

                if (group >= 2) {
                    writeFailure(failOut, reqLine, expected, predictedScore,
                            predicted, vec, result, dataset);
                }
            }
            System.out.println("[analyze] processed " + total + " entries");
        }

        // Summary
        try (BufferedWriter w = Files.newBufferedWriter(
                outDir.resolve("summary.tsv"), StandardCharsets.UTF_8)) {
            w.write("group\tcount\trate\n");
            int n = counts[0] + counts[1] + counts[2] + counts[3];
            String[] names = {"TN", "TP", "FP", "FN"};
            for (int g = 0; g < 4; g++) {
                w.write(names[g] + "\t" + counts[g] + "\t"
                        + String.format("%.4f", counts[g] / (double) n) + "\n");
            }
        }

        // Features per group
        String[] dimNames = {
                "amt", "inst", "amt/avg", "hour", "dow", "tx_min", "km_last",
                "tx24h", "mer_avg", "online", "cardpr", "unknown_mer", "mcc_risk", "edge"
        };
        try (BufferedWriter w = Files.newBufferedWriter(
                outDir.resolve("features.tsv"), StandardCharsets.UTF_8)) {
            w.write("dim\tname\tTN_mean\tTN_std\tTP_mean\tTP_std\tFP_mean\tFP_std\tFN_mean\tFN_std\n");
            for (int d = 0; d < DIM; d++) {
                StringBuilder line = new StringBuilder();
                line.append(d).append('\t').append(d < dimNames.length ? dimNames[d] : "d" + d);
                for (int g = 0; g < 4; g++) {
                    if (counts[g] == 0) {
                        line.append("\t0\t0");
                        continue;
                    }
                    double mean = sums[g][d] / counts[g];
                    double var = sumsSq[g][d] / counts[g] - mean * mean;
                    double std = Math.sqrt(Math.max(0, var));
                    line.append(String.format("\t%.4f\t%.4f", mean, std));
                }
                line.append('\n');
                w.write(line.toString());
            }
        }

        System.out.println("[analyze] failures count: FP=" + counts[2] + " FN=" + counts[3]);
        System.out.println("[analyze] wrote " + outDir);
    }

    private static void writeFailure(BufferedWriter out, String reqLine,
                                     boolean expected, double predictedScore,
                                     boolean predicted, double[] vec,
                                     SearchResult result, ReferenceDataset ds) throws IOException {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"type\":\"").append((predicted && !expected) ? "FN" : "FP")
                .append("\",\"expected\":").append(expected)
                .append(",\"predicted\":").append(predicted)
                .append(",\"score\":").append(predictedScore)
                .append(",\"vec\":[");
        for (int d = 0; d < DIM; d++) {
            if (d > 0) sb.append(',');
            sb.append(String.format("%.4f", vec[d]));
        }
        sb.append("],\"neighbors\":[");
        int[] indices = result.indices();
        double[] dists = result.distances();
        for (int i = 0; i < result.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"i\":").append(indices[i])
                    .append(",\"d\":").append(String.format("%.4f", dists[i]))
                    .append(",\"lbl\":").append(ds.isFraud(indices[i]) ? 1 : 0)
                    .append('}');
        }
        sb.append("],\"raw\":").append(reqLine).append("}\n");
        out.write(sb.toString());
    }
}
