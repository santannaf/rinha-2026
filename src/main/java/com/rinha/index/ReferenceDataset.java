package com.rinha.index;

import java.nio.FloatBuffer;

/**
 * Dataset rotulado em layout flat. Para N vetores de DIM dimensões:
 *   flatFloats.get(i * DIM + d) = i-ésimo vetor, dimensão d (precisão float32)
 *   labels[i] = 0 (legit) | 1 (fraud)
 *
 * Storage abstrai duas origens:
 *  1. Heap-backed ({@link FloatBuffer#wrap(float[])}) — usado pelo loader
 *     streaming JSON e pelo gerador sintético; conta como heap anônimo.
 *  2. Direct/mapped — {@code FileChannel.map} de um arquivo {@code .bin} v2.
 *     Páginas físicas vivem no page cache do kernel e podem ser compartilhadas
 *     entre containers (mesma inode → mesma página física), permitindo dois
 *     backends caberem em 350 MiB sem duplicar os 168 MiB do dataset.
 *
 * Os 3 MB de {@code labels} ficam sempre em heap byte[] (não vale o esforço de
 * mmap-ar pra ganhar 3 MB compartilhados, e bytes não têm endianness).
 *
 * O query do request continua sendo {@code double[14]} (pequeno, por request).
 * As métricas de distância widenam float→double por dim no hot path.
 */
public final class ReferenceDataset {

    public static final int DIM = 14;
    public static final byte LABEL_LEGIT = 0;
    public static final byte LABEL_FRAUD = 1;

    private final FloatBuffer flatFloats;
    private final byte[] labels;
    private final int size;

    /**
     * Construtor genérico. O buffer precisa cobrir pelo menos {@code size * DIM}
     * floats e estar em little-endian quando vier de bytes externos.
     */
    public ReferenceDataset(FloatBuffer flatFloats, byte[] labels, int size) {
        if (flatFloats.remaining() < (long) size * DIM
                && flatFloats.capacity() < (long) size * DIM) {
            throw new IllegalArgumentException("flatFloats too small: capacity="
                    + flatFloats.capacity() + ", need " + (long) size * DIM);
        }
        if (labels.length < size) {
            throw new IllegalArgumentException("labels too small");
        }
        this.flatFloats = flatFloats;
        this.labels = labels;
        this.size = size;
    }

    /** Atalho para origens heap-backed (sintético, loader JSON). */
    public ReferenceDataset(float[] flat, byte[] labels, int size) {
        this(FloatBuffer.wrap(flat), labels, size);
    }

    public int size() {
        return size;
    }

    public FloatBuffer flatFloats() {
        return flatFloats;
    }

    public byte[] labels() {
        return labels;
    }

    public int offsetOf(int index) {
        return index * DIM;
    }

    public byte labelAt(int index) {
        return labels[index];
    }

    public boolean isFraud(int index) {
        return labels[index] == LABEL_FRAUD;
    }

    /**
     * Copia o i-ésimo vetor para um buffer auxiliar, widening float→double.
     * Útil em warmup e testes — não usar no hot path da busca.
     */
    public void copyVector(int index, double[] out) {
        int off = index * DIM;
        for (int d = 0; d < DIM; d++) {
            out[d] = flatFloats.get(off + d);
        }
    }
}
