package com.rinha.server;

import com.rinha.config.AppConfig;
import com.rinha.score.FraudScorer;
import com.rinha.server.service.FraudScoreService;
import com.rinha.server.service.ReadyService;
import com.rinha.server.uds.UdsHttpServer;
import com.rinha.vector.VectorizationStrategy;

/**
 * Constrói o {@link HttpServer}. A implementação é o servidor bloqueante
 * UDS (uma virtual thread por conexão). O env {@code HTTP_SERVER} permanece
 * como ponto de extensão pra futuras variantes.
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
        return new UdsHttpServer(cfg, readyService, scoreService);
    }
}
