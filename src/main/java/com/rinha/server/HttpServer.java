package com.rinha.server;

/**
 * Servidor HTTP do backend. A implementação é {@code UdsHttpServer}
 * (bloqueante, uma virtual thread por conexão). Construção via
 * {@link ServerFactory}.
 */
public interface HttpServer {

    void start();

    void stop();
}
