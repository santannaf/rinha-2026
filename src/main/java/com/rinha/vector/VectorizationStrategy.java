package com.rinha.vector;

/**
 * Abstração unificada parse-and-vectorize. Recebe o corpo da request
 * em bytes e escreve diretamente no double[14].
 *
 * Permite trocar a estratégia (tree-based, schema-aware, fused) sem
 * alterar o handler — útil para benchmarking e para promover o
 * vencedor como default.
 */
public interface VectorizationStrategy {

    /**
     * Lê o JSON em {@code body[offset..offset+length)} e popula
     * {@code out[outOffset..outOffset+13]}.
     */
    void vectorize(byte[] body, int offset, int length, double[] out, int outOffset);

    String name();
}
