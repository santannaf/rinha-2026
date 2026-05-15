package com.rinha.server.nio;

import com.rinha.config.AppConfig;
import com.rinha.server.HttpServer;
import com.rinha.server.service.FraudScoreService;
import com.rinha.server.service.ReadyService;

import java.io.IOException;

public final class NioHttpServer implements HttpServer {

    private final NioEventLoop loop;

    public NioHttpServer(AppConfig cfg,
                         ReadyService readyService,
                         FraudScoreService scoreService) {
        this.loop = new NioEventLoop(cfg.serverPort(), cfg.udsPath(),
                readyService, scoreService);
    }

    @Override
    public void start() {
        try {
            loop.start();
        } catch (IOException ex) {
            throw new RuntimeException("nio server start failed at "
                    + loop.boundDescription(), ex);
        }
        System.out.println("[server] nio listening on " + loop.boundDescription());
    }

    @Override
    public void stop() {
        try {
            loop.stop();
        } catch (IOException | InterruptedException ex) {
            System.err.println("[server] nio stop error: " + ex.getMessage());
        }
    }
}
