package io.github.dsheirer.airspy;

/** Sample formats of {@code enum airspy_sample_type}, in native order. */
public enum SampleType {
    /** 32-bit float I/Q pairs. */
    FLOAT32_IQ,
    /** 32-bit float real samples. */
    FLOAT32_REAL,
    /** Signed 16-bit I/Q pairs. */
    INT16_IQ,
    /** Signed 16-bit real samples. */
    INT16_REAL,
    /** Unsigned 16-bit real samples holding the 12-bit ADC values. */
    UINT16_REAL,
    /** ADC samples exactly as the hardware delivered them, packed if packing is enabled. */
    RAW;

    /** Returns the native enum value. */
    public int code() {
        return ordinal();
    }

    /** Returns true for the types that deliver I/Q pairs. */
    public boolean isComplex() {
        return this == FLOAT32_IQ || this == INT16_IQ;
    }

    /**
     * Returns the size in bytes of a transfer of this type.
     *
     * @param sampleCount number of samples, or of I/Q pairs for the IQ types
     * @param packed whether 12-bit packing is enabled; only {@link #RAW} stays packed
     */
    long byteLength(long sampleCount, boolean packed) {
        return switch (this) {
            case FLOAT32_IQ -> sampleCount * 8;
            case FLOAT32_REAL, INT16_IQ -> sampleCount * 4;
            case INT16_REAL, UINT16_REAL -> sampleCount * 2;
            case RAW -> packed ? sampleCount * 3 / 2 : sampleCount * 2;
        };
    }
}
