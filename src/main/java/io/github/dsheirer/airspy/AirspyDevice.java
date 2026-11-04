package io.github.dsheirer.airspy;

import static io.github.dsheirer.airspy.Airspy.check;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import io.github.dsheirer.airspy.internal.Layouts;
import io.github.dsheirer.airspy.internal.Native;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Objects;

/**
 * An open Airspy device. Close it when finished, which also stops streaming.
 *
 * <p>Select the sample type before reading or selecting sample rates: the
 * library reports real sample rates as twice the I/Q rates.
 *
 * <p>Control methods may be called from any thread, including from a
 * {@link SampleListener}, except {@link #stopRx()} and {@link #close()}. Do not
 * close a device while other threads are still using it.
 */
public final class AirspyDevice implements AutoCloseable {
    /** Highest value of {@link #setLnaGain}, {@link #setMixerGain} and {@link #setVgaGain}. */
    public static final int MAX_GAIN = 15;
    /** Highest value of {@link #setLinearityGain} and {@link #setSensitivityGain}. */
    public static final int MAX_COMBINED_GAIN = 21;

    private static final int MAX_TRANSFER = 0xFFFF;
    private static final int VERSION_LENGTH = 128;
    private static final long MIN_SAMPLE_RATE_BY_VALUE = 1_000_000;
    private static final MethodHandle ON_TRANSFER;

    static {
        try {
            ON_TRANSFER = MethodHandles.lookup().findVirtual(AirspyDevice.class, "onTransfer",
                MethodType.methodType(int.class, MemorySegment.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final MemorySegment handle;
    private final Object lifecycleLock = new Object();
    private boolean closed;
    // Mirrors the library's packing flag, which sets the size of RAW transfers.
    private volatile boolean packing;

    // Streaming state: written under lifecycleLock before the stream starts,
    // read by the callback thread.
    private Arena streamArena;
    private volatile SampleListener listener;
    private volatile Thread callbackThread;
    private volatile Throwable streamingFailure;
    private volatile boolean packedStream;

    AirspyDevice(MemorySegment handle) {
        this.handle = handle;
    }

    // ---- Information ----

    /** Returns the board id; see {@link Airspy#boardName(int)}. */
    public int boardId() throws AirspyException {
        return readByte("airspy_board_id_read", Native.BOARD_ID_READ);
    }

    /** Returns the name of the board, such as {@code AIRSPY}. */
    public String boardName() throws AirspyException {
        return Airspy.boardName(boardId());
    }

    /** Returns the firmware version string. */
    public String firmwareVersion() throws AirspyException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment version = arena.allocate(VERSION_LENGTH);
            check("airspy_version_string_read",
                Native.invoke(Native.VERSION_STRING_READ, handle(), version, (byte) VERSION_LENGTH));
            return Layouts.string(version);
        }
    }

    /** Returns the microcontroller part id and the serial number. */
    public PartIdSerialNumber partIdSerialNumber() throws AirspyException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment p = arena.allocate(Layouts.PART_SERIAL);
            check("airspy_board_partid_serialno_read", Native.invoke(Native.BOARD_PARTID_SERIALNO_READ, handle(), p));
            long serial = Layouts.u32(p, Layouts.PART_SERIAL, "serial_no", 2) << 32
                | Layouts.u32(p, Layouts.PART_SERIAL, "serial_no", 3);
            return new PartIdSerialNumber(Layouts.u32(p, Layouts.PART_SERIAL, "part_id", 0),
                Layouts.u32(p, Layouts.PART_SERIAL, "part_id", 1), serial);
        }
    }

    // ---- Tuning and sampling ----

    /** Tunes to a frequency in Hz; Airspy R2 and Mini cover 24 MHz to 1.8 GHz. */
    public void setFrequency(long hertz) throws AirspyException {
        check("airspy_set_freq", Native.invoke(Native.SET_FREQ, handle(), u32(hertz)));
    }

    /**
     * Returns the sample rates in Hz for the current sample type. Real types
     * report twice the rate of the I/Q types.
     */
    public long[] sampleRates() throws AirspyException {
        try (Arena arena = Arena.ofConfined()) {
            // A length of 0 makes the function store the count in the first element.
            MemorySegment countSegment = arena.allocate(JAVA_INT);
            check("airspy_get_samplerates", Native.invoke(Native.GET_SAMPLERATES, handle(), countSegment, 0));
            int count = countSegment.get(JAVA_INT, 0);
            if (count == 0) {
                return new long[0];
            }
            MemorySegment values = arena.allocate(JAVA_INT, count);
            check("airspy_get_samplerates", Native.invoke(Native.GET_SAMPLERATES, handle(), values, count));
            long[] result = new long[count];
            for (int i = 0; i < count; i++) {
                result[i] = Integer.toUnsignedLong(values.getAtIndex(JAVA_INT, i));
            }
            return result;
        }
    }

    /**
     * Selects a sample rate in Hz. A rate from {@link #sampleRates()} is
     * selected by its index, which works for every sample type; other rates of
     * at least 1 MHz are passed to the firmware as they are.
     */
    public void setSampleRate(long hertz) throws AirspyException {
        long[] rates = sampleRates();
        long value = -1;
        for (int i = 0; i < rates.length && value < 0; i++) {
            if (rates[i] == hertz) {
                value = i;
            }
        }
        if (value < 0) {
            // The library reads values below 1 MHz as an index.
            if (hertz < MIN_SAMPLE_RATE_BY_VALUE) {
                throw new IllegalArgumentException("Unsupported sample rate: " + hertz);
            }
            value = hertz;
        }
        check("airspy_set_samplerate", Native.invoke(Native.SET_SAMPLERATE, handle(), u32(value)));
    }

    /** Selects the format of delivered samples. */
    public void setSampleType(SampleType type) throws AirspyException {
        check("airspy_set_sample_type", Native.invoke(Native.SET_SAMPLE_TYPE, handle(), type.code()));
    }

    /**
     * Enables 12-bit USB packing, which lowers the USB bandwidth by a quarter.
     * Fails with {@link AirspyError#BUSY} while streaming.
     */
    public void setPacking(boolean enabled) throws AirspyException {
        check("airspy_set_packing", Native.invoke(Native.SET_PACKING, handle(), flag(enabled)));
        packing = enabled;
    }

    /** Installs a custom half-band filter kernel for the float I/Q conversion. */
    public void setConversionFilter(float[] kernel) throws AirspyException {
        try (Arena arena = Arena.ofConfined()) {
            check("airspy_set_conversion_filter_float32", Native.invoke(Native.SET_CONVERSION_FILTER_FLOAT32,
                handle(), arena.allocateFrom(JAVA_FLOAT, kernel), kernel.length));
        }
    }

    /** Installs a custom half-band filter kernel for the 16-bit I/Q conversion. */
    public void setConversionFilter(short[] kernel) throws AirspyException {
        try (Arena arena = Arena.ofConfined()) {
            check("airspy_set_conversion_filter_int16", Native.invoke(Native.SET_CONVERSION_FILTER_INT16,
                handle(), arena.allocateFrom(JAVA_SHORT, kernel), kernel.length));
        }
    }

    // ---- Gain and RF ----

    /** Sets the LNA gain, 0 to {@value #MAX_GAIN}. */
    public void setLnaGain(int value) throws AirspyException {
        check("airspy_set_lna_gain", Native.invoke(Native.SET_LNA_GAIN, handle(), gain(value, MAX_GAIN)));
    }

    /** Sets the mixer gain, 0 to {@value #MAX_GAIN}. */
    public void setMixerGain(int value) throws AirspyException {
        check("airspy_set_mixer_gain", Native.invoke(Native.SET_MIXER_GAIN, handle(), gain(value, MAX_GAIN)));
    }

    /** Sets the VGA (IF) gain, 0 to {@value #MAX_GAIN}. */
    public void setVgaGain(int value) throws AirspyException {
        check("airspy_set_vga_gain", Native.invoke(Native.SET_VGA_GAIN, handle(), gain(value, MAX_GAIN)));
    }

    /** Switches the LNA automatic gain control. */
    public void setLnaAgc(boolean enabled) throws AirspyException {
        check("airspy_set_lna_agc", Native.invoke(Native.SET_LNA_AGC, handle(), flag(enabled)));
    }

    /** Switches the mixer automatic gain control. */
    public void setMixerAgc(boolean enabled) throws AirspyException {
        check("airspy_set_mixer_agc", Native.invoke(Native.SET_MIXER_AGC, handle(), flag(enabled)));
    }

    /**
     * Sets the LNA, mixer and VGA gains from the library's linearity-optimized
     * table, 0 to {@value #MAX_COMBINED_GAIN}. This disables both AGCs.
     */
    public void setLinearityGain(int value) throws AirspyException {
        check("airspy_set_linearity_gain",
            Native.invoke(Native.SET_LINEARITY_GAIN, handle(), gain(value, MAX_COMBINED_GAIN)));
    }

    /**
     * Sets the LNA, mixer and VGA gains from the library's sensitivity-optimized
     * table, 0 to {@value #MAX_COMBINED_GAIN}. This disables both AGCs.
     */
    public void setSensitivityGain(int value) throws AirspyException {
        check("airspy_set_sensitivity_gain",
            Native.invoke(Native.SET_SENSITIVITY_GAIN, handle(), gain(value, MAX_COMBINED_GAIN)));
    }

    /**
     * Switches the bias tee. This puts DC power on the antenna connector, which
     * can damage equipment not built for it.
     */
    public void setBiasTee(boolean enabled) throws AirspyException {
        check("airspy_set_rf_bias", Native.invoke(Native.SET_RF_BIAS, handle(), flag(enabled)));
    }

    // ---- Streaming ----

    /**
     * Starts streaming samples to a listener.
     *
     * @throws IllegalStateException if the device is already streaming
     */
    public void startRx(SampleListener sampleListener) throws AirspyException {
        Objects.requireNonNull(sampleListener, "sampleListener");
        synchronized (lifecycleLock) {
            MemorySegment device = handle();
            if (isStreaming()) {
                throw new IllegalStateException("Device is already streaming");
            }
            // A listener may have ended the previous stream without stopRx; the
            // library refuses to start again until its threads are joined.
            stopRx();

            packedStream = packing;
            streamingFailure = null;
            listener = sampleListener;

            Arena arena = Arena.ofShared();
            try {
                MemorySegment callback = Native.sampleCallback(ON_TRANSFER.bindTo(this), arena);
                check("airspy_start_rx", Native.invoke(Native.START_RX, device, callback, MemorySegment.NULL));
                streamArena = arena;
            } catch (AirspyException | RuntimeException e) {
                arena.close();
                throw e;
            }
        }
    }

    /**
     * Stops streaming. Does nothing if the device is not streaming.
     *
     * @throws IllegalStateException if called from a {@link SampleListener}
     */
    public void stopRx() throws AirspyException {
        rejectCallbackThread("stopRx");
        synchronized (lifecycleLock) {
            if (streamArena != null) {
                try {
                    check("airspy_stop_rx", Native.invoke(Native.STOP_RX, handle()));
                } finally {
                    releaseStream();
                }
            }
        }
    }

    /** Returns true while samples are being delivered. */
    public boolean isStreaming() throws AirspyException {
        return check("airspy_is_streaming", Native.invoke(Native.IS_STREAMING, handle())) != 0;
    }

    /** Returns the exception that stopped streaming, or null if there was none. */
    public Throwable streamingFailure() {
        return streamingFailure;
    }

    /**
     * Called by the streaming thread through the upcall stub. An exception that
     * escaped from here would terminate the JVM, so all of them are caught.
     */
    @SuppressWarnings("unused")
    private int onTransfer(MemorySegment transferPointer) {
        callbackThread = Thread.currentThread();
        // Closing this arena ends the samples segment's lifetime, so that a
        // listener that keeps it gets an exception and not freed native memory.
        try (Arena callbackArena = Arena.ofConfined()) {
            MemorySegment t = transferPointer.reinterpret(Layouts.TRANSFER.byteSize());
            int count = t.get(JAVA_INT, Layouts.offset(Layouts.TRANSFER, "sample_count"));
            SampleType type = SampleType.values()[t.get(JAVA_INT, Layouts.offset(Layouts.TRANSFER, "sample_type"))];
            long bytes = type.byteLength(count, packedStream);
            MemorySegment samples = t.get(ADDRESS,
                Layouts.offset(Layouts.TRANSFER, "samples")).reinterpret(bytes, callbackArena, null);
            long dropped = Layouts.i64(t, Layouts.TRANSFER, "dropped_samples");
            return listener.onSamples(new SampleBuffer(samples, count, type, dropped)) ? 0 : 1;
        } catch (Throwable failure) {
            streamingFailure = failure;
            return 1;
        }
    }

    private void releaseStream() {
        if (streamArena != null) {
            streamArena.close();
            streamArena = null;
        }
        listener = null;
        callbackThread = null;
    }

    private void rejectCallbackThread(String method) {
        if (Thread.currentThread() == callbackThread) {
            throw new IllegalStateException(method + " cannot be called from a SampleListener");
        }
    }

    // ---- Low-level hardware access ----

    /** Writes a GPIO pin; port is 0 to 7 and pin 0 to 31. */
    public void gpioWrite(int port, int pin, boolean high) throws AirspyException {
        check("airspy_gpio_write", Native.invoke(Native.GPIO_WRITE, handle(), gpioPort(port), gpioPin(pin), flag(high)));
    }

    /** Reads a GPIO pin. */
    public boolean gpioRead(int port, int pin) throws AirspyException {
        return readByte("airspy_gpio_read", Native.GPIO_READ, gpioPort(port), gpioPin(pin)) != 0;
    }

    /** Sets a GPIO pin's direction. */
    public void gpioSetOutput(int port, int pin, boolean output) throws AirspyException {
        check("airspy_gpiodir_write",
            Native.invoke(Native.GPIODIR_WRITE, handle(), gpioPort(port), gpioPin(pin), flag(output)));
    }

    /** Returns true if a GPIO pin is an output. */
    public boolean gpioIsOutput(int port, int pin) throws AirspyException {
        return readByte("airspy_gpiodir_read", Native.GPIODIR_READ, gpioPort(port), gpioPin(pin)) != 0;
    }

    /** Writes a register of the Si5351C clock generator. */
    public void si5351cWrite(int register, int value) throws AirspyException {
        check("airspy_si5351c_write", Native.invoke(Native.SI5351C_WRITE, handle(), u8(register), u8(value)));
    }

    /** Reads a register of the Si5351C clock generator. */
    public int si5351cRead(int register) throws AirspyException {
        return readByte("airspy_si5351c_read", Native.SI5351C_READ, u8(register));
    }

    /** Writes a register of the R820T tuner. */
    public void r820tWrite(int register, int value) throws AirspyException {
        check("airspy_r820t_write", Native.invoke(Native.R820T_WRITE, handle(), u8(register), u8(value)));
    }

    /** Reads a register of the R820T tuner. */
    public int r820tRead(int register) throws AirspyException {
        return readByte("airspy_r820t_read", Native.R820T_READ, u8(register));
    }

    /** Erases the whole SPI flash, including the firmware. */
    public void spiFlashErase() throws AirspyException {
        check("airspy_spiflash_erase", Native.invoke(Native.SPIFLASH_ERASE, handle()));
    }

    /** Erases one SPI flash sector, 2 to 13; sectors 0 and 1 are reserved. */
    public void spiFlashEraseSector(int sector) throws AirspyException {
        check("airspy_spiflash_erase_sector", Native.invoke(Native.SPIFLASH_ERASE_SECTOR, handle(), u16(sector)));
    }

    /** Writes to the SPI flash, in one transfer of at most 65535 bytes. */
    public void spiFlashWrite(long address, byte[] data) throws AirspyException {
        checkTransferLength(data.length);
        try (Arena arena = Arena.ofConfined()) {
            check("airspy_spiflash_write", Native.invoke(Native.SPIFLASH_WRITE, handle(), u32(address),
                (short) data.length, arena.allocateFrom(JAVA_BYTE, data)));
        }
    }

    /** Reads from the SPI flash, in one transfer of at most 65535 bytes. */
    public byte[] spiFlashRead(long address, int length) throws AirspyException {
        checkTransferLength(length);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocate(Math.max(length, 1));
            check("airspy_spiflash_read",
                Native.invoke(Native.SPIFLASH_READ, handle(), u32(address), (short) length, data));
            return data.asSlice(0, length).toArray(JAVA_BYTE);
        }
    }

    private static void checkTransferLength(int length) {
        if (length < 0 || length > MAX_TRANSFER) {
            throw new IllegalArgumentException("Transfer length out of range: " + length);
        }
    }

    // ---- Lifecycle ----

    /**
     * Stops streaming and releases the device. Closing again does nothing.
     *
     * @throws IllegalStateException if called from a {@link SampleListener}
     */
    @Override
    public void close() throws AirspyException {
        rejectCallbackThread("close");
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            try {
                stopRx();
            } finally {
                closed = true;
                check("airspy_close", Native.invoke(Native.CLOSE, handle));
            }
        }
    }

    private MemorySegment handle() {
        if (closed) {
            throw new IllegalStateException("Device is closed");
        }
        return handle;
    }

    private int readByte(String function, MethodHandle getter, Object... arguments) throws AirspyException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment value = arena.allocate(JAVA_BYTE);
            Object[] all = new Object[arguments.length + 2];
            all[0] = handle();
            System.arraycopy(arguments, 0, all, 1, arguments.length);
            all[all.length - 1] = value;
            check(function, Native.invoke(getter, all));
            return Byte.toUnsignedInt(value.get(JAVA_BYTE, 0));
        }
    }

    private static byte flag(boolean value) {
        return (byte) (value ? 1 : 0);
    }

    private static byte gain(int value, int max) {
        if (value < 0 || value > max) {
            throw new IllegalArgumentException("Gain out of range 0-" + max + ": " + value);
        }
        return (byte) value;
    }

    private static int gpioPort(int port) {
        if (port < 0 || port > 7) {
            throw new IllegalArgumentException("GPIO port out of range 0-7: " + port);
        }
        return port;
    }

    private static int gpioPin(int pin) {
        if (pin < 0 || pin > 31) {
            throw new IllegalArgumentException("GPIO pin out of range 0-31: " + pin);
        }
        return pin;
    }

    private static byte u8(int value) {
        if (value < 0 || value > 0xFF) {
            throw new IllegalArgumentException("Value out of 8-bit range: " + value);
        }
        return (byte) value;
    }

    private static short u16(int value) {
        if (value < 0 || value > 0xFFFF) {
            throw new IllegalArgumentException("Value out of 16-bit range: " + value);
        }
        return (short) value;
    }

    private static int u32(long value) {
        if (value < 0 || value > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException("Value out of 32-bit range: " + value);
        }
        return (int) value;
    }
}
