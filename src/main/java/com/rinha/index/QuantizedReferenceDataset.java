package com.rinha.index;

import com.rinha.dataset.QuantParams;

import java.nio.ShortBuffer;

/**
 * Dataset rotulado em layout flat int16 — análogo ao {@link ReferenceDataset}
 * mas com vetores quantizados.
 *
 *   flatShorts.get(i * DIM + d) = i-ésimo vetor, dimensão d (quantizado)
 *   labels[i]                   = 0 (legit) | 1 (fraud)
 *
 * Usa metade da memória do float32 (~84 MB pra 3M × 14, vs 168 MB).
 * Métrica L2² em int é proporcional à L2² em float pelo {@link QuantParams},
 * então ranking é lossless mesmo sem dequantize.
 */
public final class QuantizedReferenceDataset {

    public static final int DIM = ReferenceDataset.DIM;
    public static final byte LABEL_LEGIT = ReferenceDataset.LABEL_LEGIT;
    public static final byte LABEL_FRAUD = ReferenceDataset.LABEL_FRAUD;

    private final ShortBuffer flatShorts;
    private final byte[] labels;
    private final int size;
    private final QuantParams params;

    public QuantizedReferenceDataset(ShortBuffer flatShorts, byte[] labels,
                                     int size, QuantParams params) {
        if (flatShorts.remaining() < (long) size * DIM
                && flatShorts.capacity() < (long) size * DIM) {
            throw new IllegalArgumentException("flatShorts too small: capacity="
                    + flatShorts.capacity() + ", need " + (long) size * DIM);
        }
        if (labels.length < size) {
            throw new IllegalArgumentException("labels too small");
        }
        this.flatShorts = flatShorts;
        this.labels = labels;
        this.size = size;
        this.params = params;
    }

    public QuantizedReferenceDataset(short[] flat, byte[] labels, int size,
                                     QuantParams params) {
        this(ShortBuffer.wrap(flat), labels, size, params);
    }

    public int size() { return size; }
    public ShortBuffer flatShorts() { return flatShorts; }
    public byte[] labels() { return labels; }
    public QuantParams params() { return params; }

    public int offsetOf(int index) { return index * DIM; }
    public byte labelAt(int index) { return labels[index]; }
    public boolean isFraud(int index) { return labels[index] == LABEL_FRAUD; }
}
