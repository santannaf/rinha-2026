package com.rinha.server.service;

import com.rinha.server.ReadyState;

/**
 * Lógica pura do GET /ready, sem dependência de servidor HTTP.
 * Retorno: {@code 0} pronto (200), {@code 1} carregando (503).
 */
public final class ReadyService {

    public static final int READY = 0;
    public static final int LOADING = 1;

    private final ReadyState state;

    public ReadyService(ReadyState state) {
        this.state = state;
    }

    public int handle() {
        return state.isReady() ? READY : LOADING;
    }
}
