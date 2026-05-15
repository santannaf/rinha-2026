package com.rinha.vector;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parser JSON minimal para um único formato fixo: objeto flat com pares
 * {@code "chave": valor} onde valor é número ou string. Usado só para
 * carregar {@code normalization.json} e {@code mcc_risk.json}.
 * Sem dependências, sem reflection.
 */
final class TinyJson {

    private TinyJson() {}

    static Map<String, String> parseFlatObject(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        int i = skipWs(json, 0);
        if (i >= json.length() || json.charAt(i) != '{') {
            throw new IllegalArgumentException("expected '{' at start");
        }
        i++;
        i = skipWs(json, i);
        if (i < json.length() && json.charAt(i) == '}') return result;
        while (true) {
            i = skipWs(json, i);
            if (json.charAt(i) != '"') throw new IllegalArgumentException("expected string key at " + i);
            int kStart = ++i;
            while (i < json.length() && json.charAt(i) != '"') i++;
            String key = json.substring(kStart, i);
            i++;
            i = skipWs(json, i);
            if (i >= json.length() || json.charAt(i) != ':') throw new IllegalArgumentException("expected ':'");
            i++;
            i = skipWs(json, i);
            String value;
            if (json.charAt(i) == '"') {
                int vStart = ++i;
                while (i < json.length() && json.charAt(i) != '"') i++;
                value = json.substring(vStart, i);
                i++;
            } else {
                int vStart = i;
                while (i < json.length()) {
                    char c = json.charAt(i);
                    if (c == ',' || c == '}' || c == ' ' || c == '\t' || c == '\n' || c == '\r') break;
                    i++;
                }
                value = json.substring(vStart, i);
            }
            result.put(key, value);
            i = skipWs(json, i);
            if (i < json.length() && json.charAt(i) == ',') { i++; continue; }
            if (i < json.length() && json.charAt(i) == '}') return result;
            throw new IllegalArgumentException("expected ',' or '}' at " + i);
        }
    }

    private static int skipWs(String s, int i) {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return i;
            i++;
        }
        return i;
    }
}
