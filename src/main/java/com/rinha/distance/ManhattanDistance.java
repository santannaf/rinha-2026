package com.rinha.distance;

import java.nio.FloatBuffer;

public final class ManhattanDistance implements DistanceMetric {

    @Override
    public double distance(double[] q, FloatBuffer flat, int offset) {
        return Math.abs(q[0]  - flat.get(offset))
             + Math.abs(q[1]  - flat.get(offset + 1))
             + Math.abs(q[2]  - flat.get(offset + 2))
             + Math.abs(q[3]  - flat.get(offset + 3))
             + Math.abs(q[4]  - flat.get(offset + 4))
             + Math.abs(q[5]  - flat.get(offset + 5))
             + Math.abs(q[6]  - flat.get(offset + 6))
             + Math.abs(q[7]  - flat.get(offset + 7))
             + Math.abs(q[8]  - flat.get(offset + 8))
             + Math.abs(q[9]  - flat.get(offset + 9))
             + Math.abs(q[10] - flat.get(offset + 10))
             + Math.abs(q[11] - flat.get(offset + 11))
             + Math.abs(q[12] - flat.get(offset + 12))
             + Math.abs(q[13] - flat.get(offset + 13));
    }

    @Override
    public double distance(double[] a, double[] b, int oa, int ob) {
        return Math.abs(a[oa]      - b[ob])
             + Math.abs(a[oa + 1]  - b[ob + 1])
             + Math.abs(a[oa + 2]  - b[ob + 2])
             + Math.abs(a[oa + 3]  - b[ob + 3])
             + Math.abs(a[oa + 4]  - b[ob + 4])
             + Math.abs(a[oa + 5]  - b[ob + 5])
             + Math.abs(a[oa + 6]  - b[ob + 6])
             + Math.abs(a[oa + 7]  - b[ob + 7])
             + Math.abs(a[oa + 8]  - b[ob + 8])
             + Math.abs(a[oa + 9]  - b[ob + 9])
             + Math.abs(a[oa + 10] - b[ob + 10])
             + Math.abs(a[oa + 11] - b[ob + 11])
             + Math.abs(a[oa + 12] - b[ob + 12])
             + Math.abs(a[oa + 13] - b[ob + 13]);
    }

    @Override
    public double distance(FloatBuffer flat, int fo, double[] other, int oo) {
        return Math.abs(flat.get(fo)      - other[oo])
             + Math.abs(flat.get(fo + 1)  - other[oo + 1])
             + Math.abs(flat.get(fo + 2)  - other[oo + 2])
             + Math.abs(flat.get(fo + 3)  - other[oo + 3])
             + Math.abs(flat.get(fo + 4)  - other[oo + 4])
             + Math.abs(flat.get(fo + 5)  - other[oo + 5])
             + Math.abs(flat.get(fo + 6)  - other[oo + 6])
             + Math.abs(flat.get(fo + 7)  - other[oo + 7])
             + Math.abs(flat.get(fo + 8)  - other[oo + 8])
             + Math.abs(flat.get(fo + 9)  - other[oo + 9])
             + Math.abs(flat.get(fo + 10) - other[oo + 10])
             + Math.abs(flat.get(fo + 11) - other[oo + 11])
             + Math.abs(flat.get(fo + 12) - other[oo + 12])
             + Math.abs(flat.get(fo + 13) - other[oo + 13]);
    }

    @Override
    public String name() {
        return "MANHATTAN";
    }
}
