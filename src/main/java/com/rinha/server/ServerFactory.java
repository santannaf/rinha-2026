package com.rinha.server;

import com.rinha.config.AppConfig;
import com.rinha.score.FraudScorer;
import com.rinha.server.nio.NioHttpServer;
import com.rinha.server.service.FraudScoreService;
import com.rinha.server.service.ReadyService;
import com.rinha.vector.VectorizationStrategy;

/**
 * Constrói o {@link HttpServer}. Hoje só existe a implementação NIO; o env
 * {@code HTTP_SERVER} permanece como ponto de extensão pra futuras variantes.
 */
public final class ServerFactory {

    private ServerFactory() {}

    public static HttpServer create(AppConfig cfg,
                                    ReadyState ready,
                                    IndexHolder holder,
                                    VectorizationStrategy strategy,
                                    FraudScorer scorer) {
        ReadyService readyService = new ReadyService(ready);
        FraudScoreService scoreService = new FraudScoreService(ready, holder, strategy, scorer);

        String impl = cfg.httpServer();
        if ("NIO".equals(impl)) {
            return new NioHttpServer(cfg, readyService, scoreService);
        }
        throw new IllegalArgumentException("HTTP_SERVER inválido: " + impl
                + " (suportado: NIO)");
    }
}
