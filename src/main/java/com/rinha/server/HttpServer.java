package com.rinha.server;

/**
 * Servidor HTTP do backend. Hoje só temos {@code NioHttpServer}.
 * Construção via {@link ServerFactory}.
 */
public interface HttpServer {

    void start();

    void stop();
}
