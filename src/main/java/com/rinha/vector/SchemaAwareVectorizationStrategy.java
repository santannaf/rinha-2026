package com.rinha.vector;

import java.nio.charset.StandardCharsets;

/**
 * Estratégia schema-aware inspirada no PayloadParser.cs do projeto
 * de referência em .NET.
 *
 *  - Percorre o byte[] do request diretamente.
 *  - Não constrói árvore JSON nem aloca Map.
 *  - Parser de double/int manual (sem Double.parseDouble + new String).
 *  - Datas ISO 8601 parseadas por offset fixo (Sakamoto + civil days).
 *  - Set-membership por comparação byte-a-byte com a chave entre aspas.
 *
 * Alocações residuais:
 *  - new String para lookup do MCC no McCRiskMap (HashMap<String,Double>).
 *    Mitigação possível: mapa int->double indexado pelo MCC numérico.
 *
 * Premissas validadas:
 *  - O servidor recebe payload UTF-8 que é puro ASCII (timestamps ISO,
 *    números, e identificadores como "MERC-001"). Caracteres não-ASCII
 *    em identificadores quebrariam — não esperado na Rinha.
 *  - Timestamps no formato "YYYY-MM-DDThh:mm:ss[.sss]Z" ou
 *    "...±HH:MM". Sem fuso usamos UTC (que é o caso do enunciado).
 */
public final class SchemaAwareVectorizationStrategy implements VectorizationStrategy {

    private static final byte[] KEY_TRANSACTION = "\"transaction\"".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] KEY_CUSTOMER = "\"customer\"".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] KEY_MERCHANT = "\"merchant\"".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] KEY_TERMINAL = "\"terminal\"".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] KEY_LAST_TX = "\"last_transaction\"".getBytes(StandardCharsets.US_ASCII);

    private static final byte[] KEY_AMOUNT = "\"amount\"".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] KEY_INSTALLMENTS = "\"installments\"".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] KEY_REQUESTED_AT = "\"requested_at\"".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] KEY_AVG_AMOUNT = "\"avg_amount\"".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] KEY_TX_COUNT_24H = "\"tx_count_24h\"".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] KEY_KNOWN_MERCHANTS = "\"known_merchants\"".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] KEY_ID = "\"id\"".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] KEY_MCC = "\"mcc\"".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] KEY_IS_ONLINE = "\"is_online\"".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] KEY_CARD_PRESENT = "\"card_present\"".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] KEY_KM_FROM_HOME = "\"km_from_home\"".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] KEY_TIMESTAMP = "\"timestamp\"".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] KEY_KM_FROM_CURRENT = "\"km_from_current\"".getBytes(StandardCharsets.US_ASCII);

    private final Normalization norm;
    private final McCRiskMap mccRisk;

    public SchemaAwareVectorizationStrategy(Normalization norm, McCRiskMap mccRisk) {
        this.norm = norm;
        this.mccRisk = mccRisk;
    }

    @Override
    public void vectorize(byte[] body, int from, int length, double[] out, int outOffset) {
        int end = from + length;

        int[] tx = findObject(body, from, end, KEY_TRANSACTION);
        int[] cu = findObject(body, from, end, KEY_CUSTOMER);
        int[] me = findObject(body, from, end, KEY_MERCHANT);
        int[] te = findObject(body, from, end, KEY_TERMINAL);
        int[] lt = findObjectOrNull(body, from, end, KEY_LAST_TX);

        double amount         = readDouble(body, findValueAfter(body, tx[0], tx[1], KEY_AMOUNT));
        double installments   = readDouble(body, findValueAfter(body, tx[0], tx[1], KEY_INSTALLMENTS));
        int requestedAtPos    = findStringStart(body, tx[0], tx[1], KEY_REQUESTED_AT);

        double custAvgAmount  = readDouble(body, findValueAfter(body, cu[0], cu[1], KEY_AVG_AMOUNT));
        double txCount24h     = readDouble(body, findValueAfter(body, cu[0], cu[1], KEY_TX_COUNT_24H));
        int[] kmRange         = findArray(body, cu[0], cu[1], KEY_KNOWN_MERCHANTS);

        int[] merchantIdSlice = findStringSlice(body, me[0], me[1], KEY_ID);
        int[] mccSlice        = findStringSlice(body, me[0], me[1], KEY_MCC);
        double merchAvgAmount = readDouble(body, findValueAfter(body, me[0], me[1], KEY_AVG_AMOUNT));

        boolean isOnline      = readBoolean(body, findValueAfter(body, te[0], te[1], KEY_IS_ONLINE));
        boolean cardPresent   = readBoolean(body, findValueAfter(body, te[0], te[1], KEY_CARD_PRESENT));
        double kmFromHome     = readDouble(body, findValueAfter(body, te[0], te[1], KEY_KM_FROM_HOME));

        // ----- hora e dia da semana -----
        int year  = readInt2or4(body, requestedAtPos + 1, 4);
        int month = readInt2or4(body, requestedAtPos + 1 + 5, 2);
        int day   = readInt2or4(body, requestedAtPos + 1 + 8, 2);
        int hour  = readInt2or4(body, requestedAtPos + 1 + 11, 2);
        int minute = readInt2or4(body, requestedAtPos + 1 + 14, 2);

        // ----- saída -----
        // clamp01 também quantiza para 4 casas decimais (igual à referência .NET);
        // dim 3, 4 estão em [0,1] naturalmente mas precisam de quantização.
        out[outOffset]      = clamp01(amount / norm.maxAmount());
        out[outOffset + 1]  = clamp01(installments / norm.maxInstallments());
        out[outOffset + 2]  = clamp01((amount / custAvgAmount) / norm.amountVsAvgRatio());
        out[outOffset + 3]  = clamp01(hour / 23.0);
        out[outOffset + 4]  = clamp01(isoDayOfWeekZeroBased(year, month, day) / 6.0);

        if (lt == null) {
            out[outOffset + 5] = -1.0;
            out[outOffset + 6] = -1.0;
        } else {
            int ltTsPos       = findStringStart(body, lt[0], lt[1], KEY_TIMESTAMP);
            int lyear   = readInt2or4(body, ltTsPos + 1, 4);
            int lmonth  = readInt2or4(body, ltTsPos + 1 + 5, 2);
            int lday    = readInt2or4(body, ltTsPos + 1 + 8, 2);
            int lhour   = readInt2or4(body, ltTsPos + 1 + 11, 2);
            int lminute = readInt2or4(body, ltTsPos + 1 + 14, 2);

            long minutesNow  = epochMinutes(year,  month,  day,  hour,  minute);
            long minutesLast = epochMinutes(lyear, lmonth, lday, lhour, lminute);
            long deltaMin    = minutesNow - minutesLast;
            double kmFromCurrent = readDouble(body, findValueAfter(body, lt[0], lt[1], KEY_KM_FROM_CURRENT));

            out[outOffset + 5] = clamp01(deltaMin / norm.maxMinutes());
            out[outOffset + 6] = clamp01(kmFromCurrent / norm.maxKm());
        }

        out[outOffset + 7]  = clamp01(kmFromHome / norm.maxKm());
        out[outOffset + 8]  = clamp01(txCount24h / norm.maxTxCount24h());
        out[outOffset + 9]  = isOnline    ? 1.0 : 0.0;
        out[outOffset + 10] = cardPresent ? 1.0 : 0.0;
        out[outOffset + 11] = isKnownMerchant(body, kmRange[0], kmRange[1], merchantIdSlice[0], merchantIdSlice[1])
                ? 0.0 : 1.0;
        out[outOffset + 12] = mccRiskLookup(body, mccSlice[0], mccSlice[1]);
        out[outOffset + 13] = clamp01(merchAvgAmount / norm.maxMerchantAvgAmount());
    }

    @Override
    public String name() {
        return "SCHEMA";
    }

    // ====================================================================
    // Helpers de localização e leitura
    // ====================================================================

    /**
     * Localiza um sub-objeto JSON associado à chave informada.
     * Retorna [start, end) do {...}.
     * Lança IllegalArgumentException quando a chave não está presente.
     */
    private static int[] findObject(byte[] data, int from, int to, byte[] key) {
        int p = indexAfterKey(data, from, to, key);
        if (p < 0) throw new IllegalArgumentException("Missing key " + new String(key));
        p = skipWs(data, p, to);
        if (data[p] != '{') throw new IllegalArgumentException("Expected object after key");
        return new int[]{p, scanBalanced(data, p, to, (byte) '{', (byte) '}')};
    }

    /**
     * Variante que aceita null. Retorna null quando o valor associado
     * é literal {@code null}.
     */
    private static int[] findObjectOrNull(byte[] data, int from, int to, byte[] key) {
        int p = indexAfterKey(data, from, to, key);
        if (p < 0) return null;
        p = skipWs(data, p, to);
        if (data[p] == 'n') return null;
        if (data[p] != '{') throw new IllegalArgumentException("Expected object or null");
        return new int[]{p, scanBalanced(data, p, to, (byte) '{', (byte) '}')};
    }

    private static int[] findArray(byte[] data, int from, int to, byte[] key) {
        int p = indexAfterKey(data, from, to, key);
        if (p < 0) throw new IllegalArgumentException("Missing array key");
        p = skipWs(data, p, to);
        if (data[p] != '[') throw new IllegalArgumentException("Expected array");
        return new int[]{p, scanBalanced(data, p, to, (byte) '[', (byte) ']')};
    }

    /**
     * Retorna a posição do primeiro byte do valor (já fora dos espaços).
     */
    private static int findValueAfter(byte[] data, int from, int to, byte[] key) {
        int p = indexAfterKey(data, from, to, key);
        if (p < 0) throw new IllegalArgumentException("Missing key " + new String(key));
        return skipWs(data, p, to);
    }

    /**
     * Retorna a posição da aspa de abertura da string.
     */
    private static int findStringStart(byte[] data, int from, int to, byte[] key) {
        int p = findValueAfter(data, from, to, key);
        if (data[p] != '"') throw new IllegalArgumentException("Expected string for key");
        return p;
    }

    /**
     * Retorna [contentStart, contentEnd) — o slice entre aspas, sem
     * incluir as próprias aspas.
     */
    private static int[] findStringSlice(byte[] data, int from, int to, byte[] key) {
        int q = findStringStart(data, from, to, key);
        int s = q + 1;
        int e = s;
        while (e < to) {
            byte c = data[e];
            if (c == '"') return new int[]{s, e};
            if (c == '\\') e += 2; else e++;
        }
        throw new IllegalArgumentException("Unterminated string");
    }

    /**
     * Encontra a chave {@code key} dentro de {@code [from..to)} e
     * devolve a posição imediatamente após os dois pontos ':'.
     * -1 se não achar.
     *
     * Usa Boyer-Moore-Horspool simplificado (skip baseado no último char).
     */
    private static int indexAfterKey(byte[] data, int from, int to, byte[] needle) {
        int p = indexOf(data, from, to, needle);
        if (p < 0) return -1;
        p += needle.length;
        // Pula whitespace + dois pontos.
        while (p < to && (data[p] == ' ' || data[p] == '\t' || data[p] == '\n' || data[p] == '\r')) p++;
        if (p >= to || data[p] != ':') return -1;
        return p + 1;
    }

    private static int indexOf(byte[] data, int from, int to, byte[] needle) {
        int nLen = needle.length;
        int limit = to - nLen;
        outer:
        for (int i = from; i <= limit; i++) {
            for (int j = 0; j < nLen; j++) {
                if (data[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static int skipWs(byte[] data, int from, int to) {
        int p = from;
        while (p < to) {
            byte c = data[p];
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') p++;
            else return p;
        }
        return p;
    }

    /**
     * Retorna o índice do byte seguinte ao fechamento (>= open+1).
     * Ignora strings (não conta { ou } dentro de aspas).
     */
    private static int scanBalanced(byte[] data, int from, int to, byte open, byte close) {
        int depth = 0;
        boolean inStr = false;
        for (int i = from; i < to; i++) {
            byte c = data[i];
            if (inStr) {
                if (c == '\\') { i++; continue; }
                if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') { inStr = true; continue; }
            if (c == open) depth++;
            else if (c == close) { depth--; if (depth == 0) return i + 1; }
        }
        throw new IllegalArgumentException("Unbalanced JSON delimiters");
    }

    // ====================================================================
    // Leitura de tipos primitivos (sem alocação)
    // ====================================================================

    /** Lê double simples sem usar String. */
    private static double readDouble(byte[] data, int from) {
        int p = from;
        boolean neg = false;
        if (data[p] == '-') { neg = true; p++; }
        long intPart = 0;
        while (p < data.length) {
            byte c = data[p];
            if (c < '0' || c > '9') break;
            intPart = intPart * 10 + (c - '0');
            p++;
        }
        double v = intPart;
        if (p < data.length && data[p] == '.') {
            p++;
            long frac = 0;
            long div = 1;
            while (p < data.length) {
                byte c = data[p];
                if (c < '0' || c > '9') break;
                frac = frac * 10 + (c - '0');
                div *= 10;
                p++;
            }
            v += ((double) frac) / div;
        }
        if (p < data.length && (data[p] == 'e' || data[p] == 'E')) {
            p++;
            boolean negExp = false;
            if (data[p] == '-') { negExp = true; p++; }
            else if (data[p] == '+') p++;
            int exp = 0;
            while (p < data.length) {
                byte c = data[p];
                if (c < '0' || c > '9') break;
                exp = exp * 10 + (c - '0');
                p++;
            }
            double scale = pow10(exp);
            v = negExp ? v / scale : v * scale;
        }
        return neg ? -v : v;
    }

    private static double pow10(int exp) {
        double r = 1.0;
        double b = 10.0;
        int e = exp;
        while (e > 0) {
            if ((e & 1) == 1) r *= b;
            b *= b;
            e >>= 1;
        }
        return r;
    }

    /** Lê inteiro de N dígitos a partir de {@code from}. */
    private static int readInt2or4(byte[] data, int from, int len) {
        int v = 0;
        for (int i = 0; i < len; i++) {
            byte c = data[from + i];
            v = v * 10 + (c - '0');
        }
        return v;
    }

    private static boolean readBoolean(byte[] data, int from) {
        return data[from] == 't';
    }

    // ====================================================================
    // Datas
    // ====================================================================

    /**
     * ISO 8601 day-of-week: Mon=0, Sun=6.
     * Sakamoto retorna Sun=0, então convertemos para ISO.
     */
    static int isoDayOfWeekZeroBased(int year, int month, int day) {
        int[] t = {0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4};
        int y = year - (month < 3 ? 1 : 0);
        int sakamoto = (y + y / 4 - y / 100 + y / 400 + t[month - 1] + day) % 7;
        return (sakamoto + 6) % 7;
    }

    /**
     * Howard Hinnant civil days from epoch (1970-01-01).
     * Reduz a aritmética de calendário para inteiros.
     */
    static long epochMinutes(int year, int month, int day, int hour, int minute) {
        long y = year - (month <= 2 ? 1 : 0);
        long era = (y >= 0 ? y : y - 399) / 400;
        long yoe = y - era * 400;
        long doy = (153L * (month + (month > 2 ? -3 : 9)) + 2) / 5 + day - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        long days = era * 146097 + doe - 719468;
        return days * 1440L + hour * 60L + minute;
    }

    // ====================================================================
    // Membership tolerante a duplicatas
    // ====================================================================

    /**
     * Procura "{merchantId}" (com aspas) dentro do slice do array.
     */
    static boolean isKnownMerchant(byte[] data, int arrStart, int arrEnd, int idStart, int idEnd) {
        int idLen = idEnd - idStart;
        int limit = arrEnd - idLen - 2;
        for (int i = arrStart; i <= limit; i++) {
            if (data[i] != '"') continue;
            if (data[i + 1 + idLen] != '"') continue;
            boolean match = true;
            for (int j = 0; j < idLen; j++) {
                if (data[i + 1 + j] != data[idStart + j]) { match = false; break; }
            }
            if (match) return true;
        }
        return false;
    }

    private double mccRiskLookup(byte[] data, int start, int end) {
        // Uma única alocação por request — pode ser eliminada trocando
        // McCRiskMap por uma estrutura indexada por bytes.
        String mcc = new String(data, start, end - start, StandardCharsets.US_ASCII);
        return mccRisk.risk(mcc);
    }

    /**
     * Clamp em [0,1] + quantização para 4 casas decimais.
     * Por que quantizar: as referências em references.json.gz estão armazenadas
     * com precisão de 4 decimais; quantizar a query alinha empates de distância
     * com o gabarito que rotulou test-data.json. NaN é tratado como 0
     * (igual ao Clamp01 da referência .NET).
     */
    private static double clamp01(double x) {
        if (Double.isNaN(x)) return 0.0;
        if (x < 0.0) return 0.0;
        if (x > 1.0) return 1.0;
        return Math.round(x * 10000.0) / 10000.0;
    }
}
