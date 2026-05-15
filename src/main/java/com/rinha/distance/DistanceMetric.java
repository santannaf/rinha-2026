package com.rinha.distance;

import java.nio.FloatBuffer;

/**
 * Métrica de distância para vetores de 14 dimensões.
 *
 * O contrato garante: menor valor = mais próximo. Implementações
 * podem omitir operações monotônicas (sqrt) para acelerar a comparação.
 *
 * Não alocar no hot path. Sem streams. Sem boxing.
 *
 * Storage layout:
 *   - query: {@code double[14]} (cliente do request — só 1 vetor por request)
 *   - dataset (3M vetores): {@code FloatBuffer} flat (heap ou mmap), widening
 *     float→double on-the-fly via {@code FloatBuffer.get(int)} (intrinsificado
 *     pelo C2/Graal — sem overhead vs acesso direto a float[]).
 *   - centróides do IVF (poucos): {@code double[]} (precisão p/ k-means)
 */
public interface DistanceMetric {

    /**
     * Distância entre query e fatia {@code flat.get(offset..offset+13)} do dataset.
     * Widenia float→double por dim internamente.
     */
    double distance(double[] query, FloatBuffer flat, int offset);

    /**
     * Variante com cutoff: computa distância parcial após N dims, e se já
     * exceder {@code cutoff} retorna sem completar. Caller só precisa checar
     * {@code retorno < cutoff} — se for ≥, abandona o candidato.
     *
     * Permite early-exit no hot path do IVF: depois que o top-K está cheio,
     * a maioria dos vetores varridos vai ter distância parcial > worst-no-heap
     * já nas primeiras dims, e podemos pular as restantes. Empiricamente
     * (top-5 da Rinha em C/Rust) corta 40-60% das FMAs no scan.
     *
     * Default = sem early-exit (compatibilidade com métricas onde isso não
     * faz sentido, como Cosine que precisa de norma completa).
     */
    default double distanceWithCutoff(double[] query, FloatBuffer flat, int offset, double cutoff) {
        return distance(query, flat, offset);
    }

    /**
     * Distância entre dois vetores em {@code double[]} (usado pelo IVF
     * pra centróide×centróide na probe-list e pra centróide×vetor durante k-means).
     */
    double distance(double[] a, double[] b, int offsetA, int offsetB);

    /**
     * Distância entre vetor do dataset (float) e centróide (double).
     * Usado pelo IVF durante build/k-means.
     */
    double distance(FloatBuffer flat, int flatOffset, double[] other, int otherOffset);

    /**
     * Versão de conveniência usada em testes (double × double, ambos no offset 0).
     */
    default double distance(double[] a, double[] b) {
        return distance(a, b, 0, 0);
    }

    String name();

    static DistanceMetric byName(String name) {
        if (name == null) return new EuclideanDistance();
        return switch (name.trim().toUpperCase()) {
            case "EUCLIDEAN" -> new EuclideanDistance();
            case "EUCLIDEAN_SIMD" -> trySimdEuclidean();
            case "MANHATTAN" -> new ManhattanDistance();
            case "COSINE" -> new CosineDistance();
            default -> throw new IllegalArgumentException("Unknown DISTANCE_METRIC: " + name);
        };
    }

    /**
     * SIMD via jdk.incubator.vector. Empiricamente perde para a versão escalar
     * unrolled em dim=14 no hot path por query (JIT auto-vetoriza bem, e o
     * overhead da Vector API não compensa para vetores curtos). Permanece
     * disponível como opt-in via DISTANCE_METRIC=EUCLIDEAN_SIMD para casos
     * batch-heavy (build do índice ~1.7× mais rápido).
     */
    private static DistanceMetric trySimdEuclidean() {
        try {
            return new SimdEuclideanDistance();
        } catch (Throwable t) {
            System.err.println("[distance] SIMD unavailable (" + t.getClass().getSimpleName()
                    + "), falling back to scalar EuclideanDistance: " + t.getMessage());
            return new EuclideanDistance();
        }
    }
}
