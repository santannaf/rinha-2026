package com.rinha.vector;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public record Normalization(
        double maxAmount,
        double maxInstallments,
        double amountVsAvgRatio,
        double maxMinutes,
        double maxKm,
        double maxTxCount24h,
        double maxMerchantAvgAmount
) {

    public static Normalization defaults() {
        return new Normalization(10000.0, 12.0, 10.0, 1440.0, 1000.0, 20.0, 10000.0);
    }

    public static Normalization fromJson(String json) {
        Map<String, String> m = TinyJson.parseFlatObject(json);
        return new Normalization(
                Double.parseDouble(m.get("max_amount")),
                Double.parseDouble(m.get("max_installments")),
                Double.parseDouble(m.get("amount_vs_avg_ratio")),
                Double.parseDouble(m.get("max_minutes")),
                Double.parseDouble(m.get("max_km")),
                Double.parseDouble(m.get("max_tx_count_24h")),
                Double.parseDouble(m.get("max_merchant_avg_amount"))
        );
    }

    public static Normalization fromClasspath(String resourcePath) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try (InputStream in = cl.getResourceAsStream(resourcePath)) {
            if (in == null) return defaults();
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return fromJson(json);
        } catch (IOException ex) {
            return defaults();
        }
    }
}
