package com.rinha.perf;

/**
 * Logger de performance condicional. Quando desligado, métodos viram
 * branches previsíveis que o JIT elimina. Quando ligado, escreve em
 * System.out apenas — sem framework de logging.
 */
public final class PerfLog {

    private static volatile boolean enabled = false;

    private PerfLog() {}

    public static void configure(boolean on) {
        enabled = on;
    }

    public static boolean enabled() {
        return enabled;
    }

    public static long now() {
        return enabled ? System.nanoTime() : 0L;
    }

    public static void log(String stage, long startNanos) {
        if (!enabled) return;
        long elapsed = System.nanoTime() - startNanos;
        System.out.printf("[perf] %-12s %6d us%n", stage, elapsed / 1000);
    }
}
