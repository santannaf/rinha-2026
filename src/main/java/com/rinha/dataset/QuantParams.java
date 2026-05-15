package com.rinha.dataset;

/**
 * Parâmetros de quantização single-global-scale: mapeia float ∈ [minVal, maxVal]
 * em short ∈ [-32767, 32767]. Por usar único scale pra todas dimensões,
 * a métrica L2² em int é proporcional à L2² em float — ranking lossless,
 * não precisa rerank pra top-K.
 *
 *   scale  = (maxVal - minVal) / 65534
 *   center = (maxVal + minVal) / 2
 *   q(x)   = round((x - center) / scale)
 *   inv(q) = q * scale + center
 */
public record QuantParams(float minVal, float maxVal) {

    public static final int Q_MIN = -32767;
    public static final int Q_MAX = 32767;
    public static final int Q_RANGE = Q_MAX - Q_MIN; // 65534

    public float scale() {
        float range = maxVal - minVal;
        return range == 0f ? 1f : range / Q_RANGE;
    }

    public float center() {
        return (maxVal + minVal) * 0.5f;
    }

    public short quantize(float x) {
        float q = (x - center()) / scale();
        int r = Math.round(q);
        if (r < Q_MIN) return (short) Q_MIN;
        if (r > Q_MAX) return (short) Q_MAX;
        return (short) r;
    }

    public short quantize(double x) {
        return quantize((float) x);
    }

    public float dequantize(short q) {
        return q * scale() + center();
    }
}
