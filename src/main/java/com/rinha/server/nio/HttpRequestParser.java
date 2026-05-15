package com.rinha.server.nio;

import java.nio.ByteBuffer;

/**
 * Parser HTTP/1.1 incremental zero-alloc. Só interpreta o que importa
 * pro hot path desta API:
 *   - Method (GET / POST / OTHER)
 *   - Path (em byte[] copiado pra sobreviver a compact do readBuf)
 *   - Content-Length
 *   - Connection: close
 *
 * Tudo o mais é ignorado.
 *
 * Uso:
 *   ByteBuffer readBuf;  // já em modo leitura (flip()ed)
 *   Status s = parser.feed(readBuf);
 *   switch (s) {
 *     case NEED_MORE: readBuf.compact(); break;
 *     case REQUEST_READY: ...processa... parser.reset(); break;
 *     case ERROR_*: ...resposta de erro + close;
 *   }
 */
public final class HttpRequestParser {

    public static final int MAX_HEADERS = 2048;
    public static final int MAX_BODY = 8192;

    public enum Status {
        NEED_MORE,
        REQUEST_READY,
        ERROR_BAD,
        ERROR_TOO_LARGE
    }

    public enum Method { GET, POST, OTHER }

    private enum State { REQUEST_LINE, HEADERS, BODY, DONE }

    private State state;
    private Method method;
    private int contentLength;
    private boolean keepAlive;
    private int headerBytes;
    private final byte[] pathBuf = new byte[256];
    private int pathLen;
    private final byte[] bodyBuf = new byte[MAX_BODY];
    private int bodyLen;
    // scratch pra parsing de linha de header (header line cabe em 256 B)
    private final byte[] lineBuf = new byte[512];
    private int lineLen;

    public HttpRequestParser() {
        reset();
    }

    public void reset() {
        state = State.REQUEST_LINE;
        method = Method.OTHER;
        contentLength = 0;
        keepAlive = true;
        headerBytes = 0;
        pathLen = 0;
        bodyLen = 0;
        lineLen = 0;
    }

    public Method method() { return method; }
    public byte[] pathBytes() { return pathBuf; }
    public int pathLen() { return pathLen; }
    public byte[] bodyBytes() { return bodyBuf; }
    public int bodyLen() { return bodyLen; }
    public boolean keepAlive() { return keepAlive; }

    public Status feed(ByteBuffer buf) {
        while (buf.hasRemaining()) {
            switch (state) {
                case REQUEST_LINE: {
                    Status s = readLine(buf);
                    if (s != null) return s;
                    if (lineLen == 0) continue;
                    if (!parseRequestLine()) return Status.ERROR_BAD;
                    lineLen = 0;
                    state = State.HEADERS;
                    break;
                }
                case HEADERS: {
                    Status s = readLine(buf);
                    if (s != null) return s;
                    if (lineLen == 0) {
                        // linha vazia → fim dos headers
                        if (method == Method.POST) {
                            if (contentLength < 0 || contentLength > MAX_BODY) {
                                return Status.ERROR_TOO_LARGE;
                            }
                            if (contentLength == 0) {
                                state = State.DONE;
                                return Status.REQUEST_READY;
                            }
                            state = State.BODY;
                        } else {
                            state = State.DONE;
                            return Status.REQUEST_READY;
                        }
                        break;
                    }
                    if (!parseHeaderLine()) return Status.ERROR_BAD;
                    lineLen = 0;
                    break;
                }
                case BODY: {
                    int need = contentLength - bodyLen;
                    int avail = buf.remaining();
                    int n = Math.min(need, avail);
                    buf.get(bodyBuf, bodyLen, n);
                    bodyLen += n;
                    if (bodyLen == contentLength) {
                        state = State.DONE;
                        return Status.REQUEST_READY;
                    }
                    // ainda precisa de mais bytes
                    return Status.NEED_MORE;
                }
                case DONE:
                    // já entregou request; caller deve chamar reset()
                    return Status.REQUEST_READY;
            }
        }
        return Status.NEED_MORE;
    }

    /**
     * Lê uma linha terminada em \r\n pro {@code lineBuf}. Retorna:
     *   - {@code null} se completou ({@code lineLen} = tamanho da linha sem CRLF, pode ser 0)
     *   - {@code Status.NEED_MORE} se o buffer acabou antes do CRLF
     *   - {@code Status.ERROR_BAD} se linha estourou MAX_HEADERS ou capacidade do scratch.
     */
    private Status readLine(ByteBuffer buf) {
        while (buf.hasRemaining()) {
            byte b = buf.get();
            headerBytes++;
            if (headerBytes > MAX_HEADERS) return Status.ERROR_BAD;
            if (b == '\r') {
                // espera \n
                if (!buf.hasRemaining()) {
                    // back up pra reprocessar no próximo feed
                    buf.position(buf.position() - 1);
                    headerBytes--;
                    return Status.NEED_MORE;
                }
                byte n = buf.get();
                headerBytes++;
                if (n != '\n') return Status.ERROR_BAD;
                return null;
            }
            if (lineLen >= lineBuf.length) return Status.ERROR_BAD;
            lineBuf[lineLen++] = b;
        }
        return Status.NEED_MORE;
    }

    private boolean parseRequestLine() {
        // METHOD SP PATH SP HTTP/1.1
        int i = 0;
        int sp1 = indexOf(lineBuf, i, lineLen, (byte) ' ');
        if (sp1 < 0) return false;
        method = parseMethod(lineBuf, i, sp1);
        int pStart = sp1 + 1;
        int sp2 = indexOf(lineBuf, pStart, lineLen, (byte) ' ');
        if (sp2 < 0) return false;
        int pl = sp2 - pStart;
        if (pl <= 0 || pl > pathBuf.length) return false;
        // ignora query string: corta no '?'
        int qm = indexOf(lineBuf, pStart, pStart + pl, (byte) '?');
        if (qm >= 0) pl = qm - pStart;
        System.arraycopy(lineBuf, pStart, pathBuf, 0, pl);
        pathLen = pl;
        // versão HTTP — não validamos rigidamente
        return true;
    }

    private static Method parseMethod(byte[] b, int from, int to) {
        int len = to - from;
        if (len == 3 && b[from] == 'G' && b[from + 1] == 'E' && b[from + 2] == 'T') {
            return Method.GET;
        }
        if (len == 4 && b[from] == 'P' && b[from + 1] == 'O' && b[from + 2] == 'S'
                && b[from + 3] == 'T') {
            return Method.POST;
        }
        return Method.OTHER;
    }

    private boolean parseHeaderLine() {
        // procura ':'
        int colon = indexOf(lineBuf, 0, lineLen, (byte) ':');
        if (colon <= 0) return false;
        // skip OWS após ':'
        int vStart = colon + 1;
        while (vStart < lineLen && (lineBuf[vStart] == ' ' || lineBuf[vStart] == '\t')) vStart++;
        int vEnd = lineLen;
        while (vEnd > vStart && (lineBuf[vEnd - 1] == ' ' || lineBuf[vEnd - 1] == '\t')) vEnd--;

        if (equalsIgnoreCaseAscii(lineBuf, 0, colon, "content-length")) {
            int n = parseIntAscii(lineBuf, vStart, vEnd);
            if (n < 0) return false;
            contentLength = n;
        } else if (equalsIgnoreCaseAscii(lineBuf, 0, colon, "connection")) {
            if (equalsIgnoreCaseAscii(lineBuf, vStart, vEnd, "close")) {
                keepAlive = false;
            }
        }
        return true;
    }

    private static int indexOf(byte[] b, int from, int to, byte target) {
        for (int i = from; i < to; i++) if (b[i] == target) return i;
        return -1;
    }

    private static boolean equalsIgnoreCaseAscii(byte[] b, int from, int to, String ascii) {
        int len = to - from;
        if (len != ascii.length()) return false;
        for (int i = 0; i < len; i++) {
            int c1 = b[from + i] & 0xFF;
            int c2 = ascii.charAt(i);
            if (c1 >= 'A' && c1 <= 'Z') c1 += 32;
            if (c2 >= 'A' && c2 <= 'Z') c2 += 32;
            if (c1 != c2) return false;
        }
        return true;
    }

    private static int parseIntAscii(byte[] b, int from, int to) {
        if (from >= to) return -1;
        int n = 0;
        for (int i = from; i < to; i++) {
            int d = b[i] - '0';
            if (d < 0 || d > 9) return -1;
            n = n * 10 + d;
            if (n < 0) return -1; // overflow
        }
        return n;
    }
}
