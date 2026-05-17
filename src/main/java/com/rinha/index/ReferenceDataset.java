package com.rinha.index;

import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * Dataset rotulado em layout flat. Para N vetores de DIM dimensões:
 *   flatFloats.get(i*DIM+d)  — vetor i, dim d (float32), OU
 *   flatShorts.get(i*DIM+d)  — vetor i, dim d (int16, ×QUANT_SCALE)
 *   labels[i] = 0 (legit) | 1 (fraud)
 *
 * Duas representações dos vetores:
 *  - float32: usada na construção do índice (k-means precisa de precisão) e
 *    pelo dataset sintético/dev.
 *  - int16 (×10000): usada em runtime — o dataset clustered é gravado
 *    quantizado. Metade dos bytes → metade do tráfego de memória no scan do
 *    repair (que é memory-bound). Lossless: as referências já estão na grade
 *    de 1/10000, então valor × 10000 dá inteiro exato.
 *
 * Só uma das duas é não-nula; {@link #quantized()} diz qual.
 *
 * Os labels ficam sempre em heap byte[] (~3 MB; bytes não têm endianness).
 */
public final class ReferenceDataset {

    public static final int DIM = 14;
    public static final byte LABEL_LEGIT = 0;
    public static final byte LABEL_FRAUD = 1;
    /** Escala de quantização int16: valor_real × QUANT_SCALE = inteiro exato. */
    public static final int QUANT_SCALE = 10000;

    private final FloatBuffer flatFloats;   // não-nulo no modo float32
    private final ShortBuffer flatShorts;   // não-nulo no modo int16
    private final byte[] labels;
    private final int size;

    /**
     * Construtor float32. O buffer precisa cobrir pelo menos {@code size*DIM}
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
        this.flatShorts = null;
        this.labels = labels;
        this.size = size;
    }

    /** Atalho para origens heap-backed (sintético, loader JSON). */
    public ReferenceDataset(float[] flat, byte[] labels, int size) {
        this(FloatBuffer.wrap(flat), labels, size);
    }

    private ReferenceDataset(ShortBuffer flatShorts, byte[] labels, int size) {
        if (flatShorts.capacity() < (long) size * DIM) {
            throw new IllegalArgumentException("flatShorts too small: capacity="
                    + flatShorts.capacity() + ", need " + (long) size * DIM);
        }
        if (labels.length < size) {
            throw new IllegalArgumentException("labels too small");
        }
        this.flatFloats = null;
        this.flatShorts = flatShorts;
        this.labels = labels;
        this.size = size;
    }

    /** Cria um dataset no modo int16 (vetores já quantizados ×QUANT_SCALE). */
    public static ReferenceDataset quantized(ShortBuffer flatShorts, byte[] labels, int size) {
        return new ReferenceDataset(flatShorts, labels, size);
    }

    public int size() {
        return size;
    }

    /** true se os vetores estão em int16 (×QUANT_SCALE); false se float32. */
    public boolean quantized() {
        return flatShorts != null;
    }

    /** Buffer float32 — {@code null} no modo int16. */
    public FloatBuffer flatFloats() {
        return flatFloats;
    }

    /** Buffer int16 (×QUANT_SCALE) — {@code null} no modo float32. */
    public ShortBuffer flatShorts() {
        return flatShorts;
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
     * Copia o vetor {@code index} para {@code out} em escala real [0,1]
     * (dequantiza se int16). Útil em warmup e testes — não no hot path.
     */
    public void copyVector(int index, double[] out) {
        int off = index * DIM;
        if (flatShorts != null) {
            for (int d = 0; d < DIM; d++) {
                out[d] = flatShorts.get(off + d) / (double) QUANT_SCALE;
            }
        } else {
            for (int d = 0; d < DIM; d++) {
                out[d] = flatFloats.get(off + d);
            }
        }
    }
}
