package com.rinha.distance;

import java.nio.FloatBuffer;

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * SIMD via jdk.incubator.vector. Empiricamente perde para a versão escalar
 * unrolled em dim=14 no hot path por query (JIT auto-vetoriza bem). Disponível
 * via DISTANCE_METRIC=EUCLIDEAN_SIMD para batch-heavy (build do índice).
 */
public final class SimdEuclideanDistance implements DistanceMetric {

    private static final int DIM = 14;
    private static final VectorSpecies<Double> SPECIES = DoubleVector.SPECIES_PREFERRED;
    private static final int LANES = SPECIES.length();
    private static final int UPPER_BOUND = (DIM / LANES) * LANES;

    @Override
    public double distance(double[] query, FloatBuffer flat, int offset) {
        // Para query × FloatBuffer, a melhor estratégia continua sendo o scalar
        // unrolled. Mantemos um caminho simples (sem alocação) que deixa o
        // JIT fazer o trabalho.
        double d0 = query[0]  - flat.get(offset);
        double d1 = query[1]  - flat.get(offset + 1);
        double d2 = query[2]  - flat.get(offset + 2);
        double d3 = query[3]  - flat.get(offset + 3);
        double d4 = query[4]  - flat.get(offset + 4);
        double d5 = query[5]  - flat.get(offset + 5);
        double d6 = query[6]  - flat.get(offset + 6);
        double d7 = query[7]  - flat.get(offset + 7);
        double d8 = query[8]  - flat.get(offset + 8);
        double d9 = query[9]  - flat.get(offset + 9);
        double d10 = query[10] - flat.get(offset + 10);
        double d11 = query[11] - flat.get(offset + 11);
        double d12 = query[12] - flat.get(offset + 12);
        double d13 = query[13] - flat.get(offset + 13);
        return d0 * d0 + d1 * d1 + d2 * d2 + d3 * d3
             + d4 * d4 + d5 * d5 + d6 * d6 + d7 * d7
             + d8 * d8 + d9 * d9 + d10 * d10 + d11 * d11
             + d12 * d12 + d13 * d13;
    }

    @Override
    public double distance(double[] a, double[] b, int offsetA, int offsetB) {
        DoubleVector acc = DoubleVector.zero(SPECIES);
        int i = 0;
        for (; i < UPPER_BOUND; i += LANES) {
            DoubleVector va = DoubleVector.fromArray(SPECIES, a, offsetA + i);
            DoubleVector vb = DoubleVector.fromArray(SPECIES, b, offsetB + i);
            DoubleVector d = va.sub(vb);
            acc = d.fma(d, acc);
        }
        double sum = acc.reduceLanes(VectorOperators.ADD);
        for (; i < DIM; i++) {
            double d = a[offsetA + i] - b[offsetB + i];
            sum += d * d;
        }
        return sum;
    }

    @Override
    public double distance(FloatBuffer flat, int fo, double[] other, int oo) {
        double d0 = flat.get(fo)      - other[oo];
        double d1 = flat.get(fo + 1)  - other[oo + 1];
        double d2 = flat.get(fo + 2)  - other[oo + 2];
        double d3 = flat.get(fo + 3)  - other[oo + 3];
        double d4 = flat.get(fo + 4)  - other[oo + 4];
        double d5 = flat.get(fo + 5)  - other[oo + 5];
        double d6 = flat.get(fo + 6)  - other[oo + 6];
        double d7 = flat.get(fo + 7)  - other[oo + 7];
        double d8 = flat.get(fo + 8)  - other[oo + 8];
        double d9 = flat.get(fo + 9)  - other[oo + 9];
        double d10 = flat.get(fo + 10) - other[oo + 10];
        double d11 = flat.get(fo + 11) - other[oo + 11];
        double d12 = flat.get(fo + 12) - other[oo + 12];
        double d13 = flat.get(fo + 13) - other[oo + 13];
        return d0 * d0 + d1 * d1 + d2 * d2 + d3 * d3
             + d4 * d4 + d5 * d5 + d6 * d6 + d7 * d7
             + d8 * d8 + d9 * d9 + d10 * d10 + d11 * d11
             + d12 * d12 + d13 * d13;
    }

    @Override
    public String name() {
        return "EUCLIDEAN_SIMD(" + LANES + "x)";
    }
}
