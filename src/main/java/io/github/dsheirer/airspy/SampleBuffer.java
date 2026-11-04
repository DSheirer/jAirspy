package io.github.dsheirer.airspy;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Samples delivered to a {@link SampleListener}.
 *
 * <p>{@link #samples()} points at memory owned by the native library. It may be
 * used only on the callback's thread and only until the callback returns; after
 * that, access throws {@link IllegalStateException}. Use one of the copying
 * methods, or {@link MemorySegment#copy}, to keep the data.
 *
 * @param samples sample memory, in native byte order
 * @param sampleCount number of samples, or of I/Q pairs for the IQ types
 * @param sampleType format of the samples
 * @param droppedSamples samples dropped between the previous block and this
 *     one because the listener did not keep up
 */
public record SampleBuffer(MemorySegment samples, int sampleCount, SampleType sampleType, long droppedSamples) {

    /** Copies 32-bit float samples to a new array; I/Q pairs are interleaved. */
    public float[] copyFloats() {
        return samples.toArray(ValueLayout.JAVA_FLOAT);
    }

    /** Copies 16-bit samples to a new array; I/Q pairs are interleaved. */
    public short[] copyShorts() {
        return samples.toArray(ValueLayout.JAVA_SHORT);
    }

    /** Copies the sample memory to a new array. */
    public byte[] copyBytes() {
        return samples.toArray(ValueLayout.JAVA_BYTE);
    }
}
