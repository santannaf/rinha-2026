package com.rinha.server.nio;

import java.nio.charset.StandardCharsets;

/**
 * Tabela estática de respostas HTTP/1.1 já com status line + headers + body
 * em UTF-8. O hot path NIO seleciona um {@code byte[]} e escreve direto
 * no SocketChannel, sem serializar nada.
 *
 * Respostas de SUCESSO (200, 503 loading) não incluem {@code Connection:
 * close} — ficam keep-alive (default 1.1). Respostas de ERRO (400, 404, 405)
 * incluem {@code Connection: close} pra encerrar conexões mal-comportadas.
 *
 * {@code Date} e {@code Server} headers são omitidos: nginx upstream não
 * exige Date e {@code Server} não tem uso real aqui.
 *
 * As 6 variantes de SCORE_200 dependem de K=5 (constante do enunciado).
 */
public final class PreRendered {

    public static final byte[][] SCORE_200 = new byte[6][];
    public static final byte[] READY_200;
    public static final byte[] LOADING_503;
    public static final byte[] BAD_REQUEST_400;
    public static final byte[] NOT_FOUND_404;
    public static final byte[] METHOD_NOT_ALLOWED_405;
    public static final byte[] PAYLOAD_TOO_LARGE_413;

    static {
        double[] scores = {0.0, 0.2, 0.4, 0.6, 0.8, 1.0};
        boolean[] approved = {true, true, true, false, false, false};
        for (int i = 0; i < 6; i++) {
            String body = "{\"approved\":" + approved[i] + ",\"fraud_score\":" + scores[i] + "}";
            SCORE_200[i] = http200Json(body);
        }
        READY_200 = http200Json("{\"status\":\"ready\"}");
        LOADING_503 = http503Json("{\"status\":\"loading\"}");
        BAD_REQUEST_400 = httpErrorJson(400, "Bad Request", "{\"error\":\"bad_request\"}");
        NOT_FOUND_404 = httpErrorJson(404, "Not Found", "{\"error\":\"not_found\"}");
        METHOD_NOT_ALLOWED_405 = httpErrorJson(405, "Method Not Allowed",
                "{\"error\":\"method_not_allowed\"}");
        PAYLOAD_TOO_LARGE_413 = httpErrorJson(413, "Payload Too Large",
                "{\"error\":\"payload_too_large\"}");
    }

    private PreRendered() {}

    private static byte[] http200Json(String body) {
        return assemble(200, "OK", body, false);
    }

    private static byte[] http503Json(String body) {
        return assemble(503, "Service Unavailable", body, false);
    }

    private static byte[] httpErrorJson(int status, String reason, String body) {
        return assemble(status, reason, body, true);
    }

    private static byte[] assemble(int status, String reason, String body, boolean close) {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(128 + bodyBytes.length);
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
        sb.append("Content-Type: application/json\r\n");
        sb.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        if (close) sb.append("Connection: close\r\n");
        sb.append("\r\n");
        byte[] head = sb.toString().getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[head.length + bodyBytes.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(bodyBytes, 0, out, head.length, bodyBytes.length);
        return out;
    }
}
