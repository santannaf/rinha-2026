package com.rinha.distance;

import java.nio.FloatBuffer;

/**
 * Distância cosseno = 1 - (dot / (|a| * |b|)).
 */
public final class CosineDistance implements DistanceMetric {

    @Override
    public double distance(double[] q, FloatBuffer flat, int offset) {
        double dot = 0.0;
        double na = 0.0;
        double nb = 0.0;
        for (int i = 0; i < 14; i++) {
            double a = q[i];
            double b = flat.get(offset + i);
            dot += a * b;
            na += a * a;
            nb += b * b;
        }
        double denom = Math.sqrt(na) * Math.sqrt(nb);
        if (denom == 0.0) return 1.0;
        return 1.0 - (dot / denom);
    }

    @Override
    public double distance(double[] a, double[] b, int oa, int ob) {
        double dot = 0.0;
        double na = 0.0;
        double nb = 0.0;
        for (int i = 0; i < 14; i++) {
            double x = a[oa + i];
            double y = b[ob + i];
            dot += x * y;
            na += x * x;
            nb += y * y;
        }
        double denom = Math.sqrt(na) * Math.sqrt(nb);
        if (denom == 0.0) return 1.0;
        return 1.0 - (dot / denom);
    }

    @Override
    public double distance(FloatBuffer flat, int fo, double[] other, int oo) {
        double dot = 0.0;
        double na = 0.0;
        double nb = 0.0;
        for (int i = 0; i < 14; i++) {
            double x = flat.get(fo + i);
            double y = other[oo + i];
            dot += x * y;
            na += x * x;
            nb += y * y;
        }
        double denom = Math.sqrt(na) * Math.sqrt(nb);
        if (denom == 0.0) return 1.0;
        return 1.0 - (dot / denom);
    }

    @Override
    public String name() {
        return "COSINE";
    }
}
