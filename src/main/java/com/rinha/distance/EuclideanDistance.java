package com.rinha.distance;

import java.nio.FloatBuffer;

/**
 * Distância Euclidiana ao quadrado. Como sqrt é monotônico, omitimos para
 * acelerar comparações. Versões unrolled para 14 dimensões — o JIT C2
 * auto-vetoriza bem com AVX2/AVX-512.
 *
 * O dataset usa {@link FloatBuffer} (heap ou mmap'd); {@code get(int)} é
 * intrinsificado em ambos os modos.
 */
public final class EuclideanDistance implements DistanceMetric {

    @Override
    public double distance(double[] q, FloatBuffer flat, int offset) {
        double d0 = q[0]  - flat.get(offset);
        double d1 = q[1]  - flat.get(offset + 1);
        double d2 = q[2]  - flat.get(offset + 2);
        double d3 = q[3]  - flat.get(offset + 3);
        double d4 = q[4]  - flat.get(offset + 4);
        double d5 = q[5]  - flat.get(offset + 5);
        double d6 = q[6]  - flat.get(offset + 6);
        double d7 = q[7]  - flat.get(offset + 7);
        double d8 = q[8]  - flat.get(offset + 8);
        double d9 = q[9]  - flat.get(offset + 9);
        double d10 = q[10] - flat.get(offset + 10);
        double d11 = q[11] - flat.get(offset + 11);
        double d12 = q[12] - flat.get(offset + 12);
        double d13 = q[13] - flat.get(offset + 13);
        return d0 * d0 + d1 * d1 + d2 * d2 + d3 * d3
             + d4 * d4 + d5 * d5 + d6 * d6 + d7 * d7
             + d8 * d8 + d9 * d9 + d10 * d10 + d11 * d11
             + d12 * d12 + d13 * d13;
    }

    /**
     * Early-exit após 8 dims: se a soma parcial dos quadrados já é ≥ cutoff,
     * retorna sem computar as 6 dims restantes. Caller só precisa checar
     * {@code retorno < cutoff}. Empiricamente vence ~40-60% das vezes no
     * IVF quando o heap top-5 está cheio.
     */
    @Override
    public double distanceWithCutoff(double[] q, FloatBuffer flat, int offset, double cutoff) {
        double d0 = q[0]  - flat.get(offset);
        double d1 = q[1]  - flat.get(offset + 1);
        double d2 = q[2]  - flat.get(offset + 2);
        double d3 = q[3]  - flat.get(offset + 3);
        double d4 = q[4]  - flat.get(offset + 4);
        double d5 = q[5]  - flat.get(offset + 5);
        double d6 = q[6]  - flat.get(offset + 6);
        double d7 = q[7]  - flat.get(offset + 7);
        double partial = d0 * d0 + d1 * d1 + d2 * d2 + d3 * d3
                       + d4 * d4 + d5 * d5 + d6 * d6 + d7 * d7;
        if (partial >= cutoff) return partial;
        double d8 = q[8]  - flat.get(offset + 8);
        double d9 = q[9]  - flat.get(offset + 9);
        double d10 = q[10] - flat.get(offset + 10);
        double d11 = q[11] - flat.get(offset + 11);
        double d12 = q[12] - flat.get(offset + 12);
        double d13 = q[13] - flat.get(offset + 13);
        return partial + d8 * d8 + d9 * d9 + d10 * d10 + d11 * d11
             + d12 * d12 + d13 * d13;
    }

    @Override
    public double distance(double[] a, double[] b, int oa, int ob) {
        double d0 = a[oa]      - b[ob];
        double d1 = a[oa + 1]  - b[ob + 1];
        double d2 = a[oa + 2]  - b[ob + 2];
        double d3 = a[oa + 3]  - b[ob + 3];
        double d4 = a[oa + 4]  - b[ob + 4];
        double d5 = a[oa + 5]  - b[ob + 5];
        double d6 = a[oa + 6]  - b[ob + 6];
        double d7 = a[oa + 7]  - b[ob + 7];
        double d8 = a[oa + 8]  - b[ob + 8];
        double d9 = a[oa + 9]  - b[ob + 9];
        double d10 = a[oa + 10] - b[ob + 10];
        double d11 = a[oa + 11] - b[ob + 11];
        double d12 = a[oa + 12] - b[ob + 12];
        double d13 = a[oa + 13] - b[ob + 13];
        return d0 * d0 + d1 * d1 + d2 * d2 + d3 * d3
             + d4 * d4 + d5 * d5 + d6 * d6 + d7 * d7
             + d8 * d8 + d9 * d9 + d10 * d10 + d11 * d11
             + d12 * d12 + d13 * d13;
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
        return "EUCLIDEAN";
    }
}
