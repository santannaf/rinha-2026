package com.rinha.vector;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Mapa MCC -> risco. Default 0.5 quando o MCC não existe.
 */
public final class McCRiskMap {

    public static final double DEFAULT_RISK = 0.5;

    private final Map<String, Double> map;

    private McCRiskMap(Map<String, Double> map) {
        this.map = map;
    }

    public static McCRiskMap empty() {
        return new McCRiskMap(new HashMap<>());
    }

    public static McCRiskMap fromJson(String json) {
        Map<String, String> raw = TinyJson.parseFlatObject(json);
        Map<String, Double> m = new HashMap<>(raw.size() * 2);
        for (Map.Entry<String, String> e : raw.entrySet()) {
            m.put(e.getKey(), Double.parseDouble(e.getValue()));
        }
        return new McCRiskMap(m);
    }

    public static McCRiskMap fromClasspath(String resourcePath) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try (InputStream in = cl.getResourceAsStream(resourcePath)) {
            if (in == null) return empty();
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return fromJson(json);
        } catch (IOException ex) {
            return empty();
        }
    }

    public double risk(String mcc) {
        Double v = map.get(mcc);
        return v == null ? DEFAULT_RISK : v.doubleValue();
    }

    public int size() {
        return map.size();
    }
}
