package io.github.dsheirer.airspy;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import io.github.dsheirer.airspy.internal.Layouts;
import io.github.dsheirer.airspy.internal.Native;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

/**
 * Entry point of the library: finds and opens Airspy devices (Airspy R2 and
 * Airspy Mini; the Airspy HF+ uses a different library).
 *
 * <pre>{@code
 * try (AirspyDevice device = Airspy.open()) {
 *     device.setSampleType(SampleType.FLOAT32_IQ);
 *     device.setSampleRate(device.sampleRates()[0]);
 *     device.setFrequency(100_000_000L);
 *     device.setLinearityGain(12);
 *     device.startRx(buffer -> {
 *         process(buffer.copyFloats());
 *         return true;
 *     });
 *     Thread.sleep(1000);
 * }
 * }</pre>
 *
 * <p>The native enumeration and open functions are not thread-safe, so the
 * methods of this class run one at a time.
 */
public final class Airspy {
    private static final Object LOCK = new Object();

    private Airspy() {
    }

    /** Returns the version of the loaded native library. */
    public static LibraryVersion libraryVersion() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment version = arena.allocate(Layouts.LIB_VERSION);
            Native.invokeVoid(Native.LIB_VERSION, version);
            return new LibraryVersion(Layouts.u32(version, Layouts.LIB_VERSION, "major_version"),
                Layouts.u32(version, Layouts.LIB_VERSION, "minor_version"),
                Layouts.u32(version, Layouts.LIB_VERSION, "revision"));
        }
    }

    /** Returns the serial numbers of the connected devices. */
    public static long[] listDevices() throws AirspyException {
        synchronized (LOCK) {
            int count = check("airspy_list_devices", Native.invoke(Native.LIST_DEVICES, MemorySegment.NULL, 0));
            if (count == 0) {
                return new long[0];
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment serials = arena.allocate(JAVA_LONG, count);
                int found = check("airspy_list_devices", Native.invoke(Native.LIST_DEVICES, serials, count));
                return serials.asSlice(0, Math.min(found, count) * JAVA_LONG.byteSize()).toArray(JAVA_LONG);
            }
        }
    }

    /** Opens the first available device. */
    public static AirspyDevice open() throws AirspyException {
        return open("airspy_open", Native.OPEN);
    }

    /** Opens the device with a serial number from {@link #listDevices()}. */
    public static AirspyDevice open(long serialNumber) throws AirspyException {
        return open("airspy_open_sn", Native.OPEN_SN, serialNumber);
    }

    /**
     * Opens a device from a USB file descriptor, as on Android. The descriptor
     * must stay open until the device is closed.
     */
    public static AirspyDevice openFileDescriptor(int fd) throws AirspyException {
        return open("airspy_open_fd", Native.OPEN_FD, fd);
    }

    /** Returns the name of a board id from {@link AirspyDevice#boardId()}. */
    public static String boardName(int boardId) {
        return Native.boardIdName(boardId);
    }

    private static AirspyDevice open(String function, MethodHandle handle, Object... arguments)
            throws AirspyException {
        synchronized (LOCK) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment pointer = arena.allocate(ADDRESS);
                Object[] all = new Object[arguments.length + 1];
                all[0] = pointer;
                System.arraycopy(arguments, 0, all, 1, arguments.length);
                check(function, Native.invoke(handle, all));
                return new AirspyDevice(pointer.get(ADDRESS, 0));
            }
        }
    }

    static int check(String function, int result) throws AirspyException {
        if (result < 0) {
            throw new AirspyException(function, result);
        }
        return result;
    }
}
