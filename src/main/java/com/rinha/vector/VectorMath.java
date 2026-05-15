package com.rinha.vector;

public final class VectorMath {
    private VectorMath() {}

    /**
     * Limita um valor ao intervalo [0.0, 1.0]. NaN é tratado como 0.0
     * (igual ao Clamp01 da referência .NET).
     */
    public static double clamp(double x) {
        if (Double.isNaN(x)) return 0.0;
        if (x < 0.0) return 0.0;
        if (x > 1.0) return 1.0;
        return x;
    }

    /**
     * Quantiza para 4 casas decimais (×10000, round half-away-from-zero, ÷10000).
     * As referências em {@code references.json.gz} são armazenadas com precisão
     * de 4 casas decimais; quantizar a query para a mesma precisão alinha empates
     * de distância com o gabarito que rotulou {@code test-data.json}.
     */
    public static double quantize4(double x) {
        if (Double.isNaN(x)) return 0.0;
        return Math.round(x * 10000.0) / 10000.0;
    }
}
