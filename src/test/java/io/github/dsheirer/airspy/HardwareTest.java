package io.github.dsheirer.airspy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Receive-only tests against a real device. Enable with {@code -PhardwareTest=true}.
 *
 * <p>Each test runs on its own thread with a time limit, so a native call that
 * never returns fails the test instead of stopping the run.
 */
@EnabledIfSystemProperty(named = "airspy.hardware.test", matches = "true")
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class HardwareTest {
    private static final long FREQUENCY = 100_000_000L;

    private static void configure(AirspyDevice device, SampleType type) throws AirspyException {
        device.setSampleType(type);
        device.setSampleRate(Arrays.stream(device.sampleRates()).min().orElseThrow());
        device.setFrequency(FREQUENCY);
        device.setLinearityGain(12);
    }

    @Test
    void readsDeviceInformation() throws AirspyException {
        long[] serials = Airspy.listDevices();
        assertTrue(serials.length > 0, "No device found");
        try (AirspyDevice device = Airspy.open(serials[0])) {
            assertEquals(serials[0], device.partIdSerialNumber().serialNumber());
            assertEquals("AIRSPY", device.boardName());
            assertFalse(device.firmwareVersion().isEmpty());

            device.setSampleType(SampleType.FLOAT32_IQ);
            long[] iqRates = device.sampleRates();
            assertTrue(iqRates.length > 0);
            device.setSampleType(SampleType.INT16_REAL);
            assertArrayEquals(Arrays.stream(iqRates).map(rate -> rate * 2).toArray(), device.sampleRates());
            device.setSampleRate(device.sampleRates()[0]);
            assertThrows(IllegalArgumentException.class, () -> device.setSampleRate(1234));
        }
    }

    @Test
    void streamsSamplesAndStops() throws Exception {
        try (AirspyDevice device = Airspy.open()) {
            configure(device, SampleType.FLOAT32_IQ);
            CountDownLatch received = new CountDownLatch(50);
            AtomicReference<float[]> last = new AtomicReference<>();
            device.startRx(buffer -> {
                assertEquals(SampleType.FLOAT32_IQ, buffer.sampleType());
                last.set(buffer.copyFloats());
                received.countDown();
                return true;
            });
            assertTrue(received.await(10, TimeUnit.SECONDS), "Samples did not arrive");
            assertTrue(device.isStreaming());
            device.stopRx();
            assertFalse(device.isStreaming());
            assertNull(device.streamingFailure());
            float[] samples = last.get();
            assertTrue(samples.length > 0);
            boolean nonZero = false;
            for (float sample : samples) {
                assertTrue(Float.isFinite(sample));
                nonZero |= sample != 0;
            }
            assertTrue(nonZero, "Samples were all zero");
        }
    }

    @Test
    void streamsEverySampleType() throws Exception {
        try (AirspyDevice device = Airspy.open()) {
            for (SampleType type : SampleType.values()) {
                configure(device, type);
                CountDownLatch received = new CountDownLatch(5);
                device.startRx(buffer -> {
                    // Reading the last byte proves the computed size is usable.
                    buffer.copyBytes();
                    received.countDown();
                    return true;
                });
                assertTrue(received.await(10, TimeUnit.SECONDS), "No samples for " + type);
                device.stopRx();
                assertNull(device.streamingFailure(), type.toString());
            }
        }
    }

    @Test
    void packedRawTransfersMatchUsbBufferSize() throws Exception {
        try (AirspyDevice device = Airspy.open()) {
            device.setPacking(true);
            try {
                configure(device, SampleType.RAW);
                CountDownLatch received = new CountDownLatch(5);
                AtomicLong bytes = new AtomicLong();
                AtomicLong samples = new AtomicLong();
                device.startRx(buffer -> {
                    bytes.set(buffer.samples().byteSize());
                    samples.set(buffer.sampleCount());
                    received.countDown();
                    return true;
                });
                assertTrue(received.await(10, TimeUnit.SECONDS));
                device.stopRx();
                assertNull(device.streamingFailure());
                assertEquals(6144 * 24, bytes.get());
                assertEquals(samples.get() * 3 / 2, bytes.get());
            } finally {
                device.setPacking(false);
            }
        }
    }

    @Test
    void canRestartAfterListenerEndedStream() throws Exception {
        try (AirspyDevice device = Airspy.open()) {
            configure(device, SampleType.INT16_IQ);
            for (int run = 0; run < 3; run++) {
                CountDownLatch received = new CountDownLatch(1);
                device.startRx(buffer -> {
                    received.countDown();
                    return false;
                });
                assertTrue(received.await(10, TimeUnit.SECONDS), "No samples in run " + run);
                for (int i = 0; i < 200 && device.isStreaming(); i++) {
                    Thread.sleep(5);
                }
            }
            assertNull(device.streamingFailure());
        }
    }

    @Test
    void listenerExceptionStopsStreamInsteadOfCrashing() throws Exception {
        try (AirspyDevice device = Airspy.open()) {
            configure(device, SampleType.INT16_IQ);
            CountDownLatch called = new CountDownLatch(1);
            device.startRx(buffer -> {
                called.countDown();
                throw new IllegalArgumentException("expected");
            });
            assertTrue(called.await(10, TimeUnit.SECONDS));
            for (int i = 0; i < 100 && device.streamingFailure() == null; i++) {
                Thread.sleep(10);
            }
            assertInstanceOf(IllegalArgumentException.class, device.streamingFailure());
            device.stopRx();
        }
    }

    @Test
    void samplesCannotBeUsedAfterCallbackReturns() throws Exception {
        try (AirspyDevice device = Airspy.open()) {
            configure(device, SampleType.FLOAT32_IQ);
            AtomicReference<MemorySegment> kept = new AtomicReference<>();
            CountDownLatch called = new CountDownLatch(1);
            device.startRx(buffer -> {
                kept.set(buffer.samples());
                called.countDown();
                return false;
            });
            assertTrue(called.await(10, TimeUnit.SECONDS));
            device.stopRx();
            assertThrows(RuntimeException.class, () -> kept.get().get(ValueLayout.JAVA_BYTE, 0));
        }
    }

    @Test
    @SuppressWarnings("try")
    void stoppingFromListenerIsRejected() throws Exception {
        try (AirspyDevice device = Airspy.open()) {
            configure(device, SampleType.FLOAT32_IQ);
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            CountDownLatch called = new CountDownLatch(1);
            device.startRx(buffer -> {
                try {
                    device.close();
                } catch (Throwable t) {
                    thrown.set(t);
                }
                called.countDown();
                return false;
            });
            assertTrue(called.await(10, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, thrown.get());
        }
    }
}
