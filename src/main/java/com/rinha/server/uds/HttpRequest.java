package com.rinha.server.uds;

/**
 * Parser HTTP/1.1 byte-a-byte sobre o buffer de header já lido (terminado
 * em {@code \r\n\r\n}). Só interpreta o que o hot path desta API precisa:
 *   - método (GET / POST / OTHER)
 *   - path (sem query string)
 *   - Content-Length
 *   - Connection: close
 *
 * Espelha o roteamento por bytes do servidor de referência do Diego. Cada
 * conexão reaproveita uma instância — {@link #parse} é re-entrante por chamada
 * e devolve a própria instância preenchida (ou {@code null} em erro).
 */
final class HttpRequest {

    static final int GET = 1;
    static final int POST = 2;
    static final int OTHER = 0;

    /** Body típico ~400 B; teto generoso de 8 KiB. */
    static final int MAX_BODY = 8192;
    private static final int MAX_PATH = 256;

    int method;
    final byte[] path = new byte[MAX_PATH];
    int pathLen;
    int contentLength;
    boolean keepAlive;

    private HttpRequest() {}

    /**
     * Parseia um header completo ({@code buf[0..len)} termina em CRLFCRLF).
     * Devolve uma {@link HttpRequest} preenchida ou {@code null} se a
     * request-line for inválida.
     */
    static HttpRequest parse(byte[] buf, int len) {
        HttpRequest r = new HttpRequest();
        if (!r.parseRequestLine(buf, len)) return null;
        r.contentLength = parseContentLength(buf, len);
        r.keepAlive = parseKeepAlive(buf, len);
        return r;
    }

    /** METHOD SP PATH SP HTTP/1.1 CRLF. */
    private boolean parseRequestLine(byte[] buf, int len) {
        int sp1 = indexOf(buf, 0, len, (byte) ' ');
        if (sp1 <= 0) return false;
        method = parseMethod(buf, 0, sp1);

        int pStart = sp1 + 1;
        int sp2 = indexOf(buf, pStart, len, (byte) ' ');
        if (sp2 < 0) return false;
        int pl = sp2 - pStart;
        if (pl <= 0) return false;
        // corta query string
        int qm = indexOf(buf, pStart, sp2, (byte) '?');
        if (qm >= 0) pl = qm - pStart;
        if (pl <= 0 || pl > path.length) return false;
        System.arraycopy(buf, pStart, path, 0, pl);
        pathLen = pl;
        return true;
    }

    private static int parseMethod(byte[] b, int from, int to) {
        int len = to - from;
        if (len == 3 && b[from] == 'G' && b[from + 1] == 'E' && b[from + 2] == 'T') {
            return GET;
        }
        if (len == 4 && b[from] == 'P' && b[from + 1] == 'O' && b[from + 2] == 'S'
                && b[from + 3] == 'T') {
            return POST;
        }
        return OTHER;
    }

    /** Procura o header {@code content-length} (case-insensitive) e lê o valor. */
    private static int parseContentLength(byte[] buf, int len) {
        int i = findHeader(buf, len, CONTENT_LENGTH);
        if (i < 0) return 0;
        int p = i + CONTENT_LENGTH.length;
        while (p < len && (buf[p] == ' ' || buf[p] == '\t')) p++;
        int v = 0;
        boolean any = false;
        while (p < len && buf[p] >= '0' && buf[p] <= '9') {
            v = v * 10 + (buf[p++] - '0');
            any = true;
            if (v < 0) return Integer.MAX_VALUE; // overflow → 413 depois
        }
        return any ? v : 0;
    }

    /** HTTP/1.1: keep-alive default; só {@code Connection: close} desliga. */
    private static boolean parseKeepAlive(byte[] buf, int len) {
        int i = findHeader(buf, len, CONNECTION);
        if (i < 0) return true;
        int p = i + CONNECTION.length;
        while (p < len && (buf[p] == ' ' || buf[p] == '\t')) p++;
        return !regionMatchesCi(buf, p, len, CLOSE);
    }

    private static final byte[] CONTENT_LENGTH = "content-length:".getBytes();
    private static final byte[] CONNECTION = "connection:".getBytes();
    private static final byte[] CLOSE = "close".getBytes();

    /** Acha o início do valor de um header {@code key} (key inclui o ':'). */
    private static int findHeader(byte[] buf, int len, byte[] key) {
        for (int i = 0; i <= len - key.length; i++) {
            int j = 0;
            while (j < key.length) {
                byte hb = buf[i + j];
                if (hb >= 'A' && hb <= 'Z') hb = (byte) (hb + 32);
                if (hb != key[j]) break;
                j++;
            }
            if (j == key.length) return i;
        }
        return -1;
    }

    private static boolean regionMatchesCi(byte[] buf, int from, int len, byte[] s) {
        if (from + s.length > len) return false;
        for (int i = 0; i < s.length; i++) {
            byte hb = buf[from + i];
            if (hb >= 'A' && hb <= 'Z') hb = (byte) (hb + 32);
            if (hb != s[i]) return false;
        }
        return true;
    }

    private static int indexOf(byte[] b, int from, int to, byte target) {
        for (int i = from; i < to; i++) {
            if (b[i] == target) return i;
        }
        return -1;
    }
}
