package com.rinha.server;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Flag global de prontidão. /ready só responde 2xx quando markReady()
 * for chamado. O Main faz isso apenas depois de load + build + warmup.
 */
public final class ReadyState {

    private final AtomicBoolean ready = new AtomicBoolean(false);

    public void markReady() {
        ready.set(true);
    }

    public boolean isReady() {
        return ready.get();
    }
}
