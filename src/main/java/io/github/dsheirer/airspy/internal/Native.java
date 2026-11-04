package io.github.dsheirer.airspy.internal;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

/**
 * Downcall handles for the public functions of libairspy.
 *
 * <p>The deprecated {@code airspy_init} and {@code airspy_exit} are
 * intentionally absent, as are {@code airspy_config_read} and
 * {@code airspy_config_write}, which the header declares but the library does
 * not implement. {@code native/check-bindings.sh} compares this list against
 * {@code airspy.h} and the built library.
 */
public final class Native {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LOOKUP = NativeLibraryLoader.load();

    public static final MethodHandle LIB_VERSION = voidCall("airspy_lib_version", ADDRESS);
    public static final MethodHandle LIST_DEVICES = call("airspy_list_devices", ADDRESS, JAVA_INT);
    public static final MethodHandle OPEN = call("airspy_open", ADDRESS);
    public static final MethodHandle OPEN_SN = call("airspy_open_sn", ADDRESS, JAVA_LONG);
    public static final MethodHandle OPEN_FD = call("airspy_open_fd", ADDRESS, JAVA_INT);
    public static final MethodHandle CLOSE = call("airspy_close", ADDRESS);
    public static final MethodHandle ERROR_NAME = LINKER.downcallHandle(symbol("airspy_error_name"),
        FunctionDescriptor.of(ADDRESS, JAVA_INT));
    public static final MethodHandle BOARD_ID_NAME = LINKER.downcallHandle(symbol("airspy_board_id_name"),
        FunctionDescriptor.of(ADDRESS, JAVA_INT));

    public static final MethodHandle BOARD_ID_READ = call("airspy_board_id_read", ADDRESS, ADDRESS);
    public static final MethodHandle VERSION_STRING_READ =
        call("airspy_version_string_read", ADDRESS, ADDRESS, JAVA_BYTE);
    public static final MethodHandle BOARD_PARTID_SERIALNO_READ =
        call("airspy_board_partid_serialno_read", ADDRESS, ADDRESS);

    public static final MethodHandle GET_SAMPLERATES = call("airspy_get_samplerates", ADDRESS, ADDRESS, JAVA_INT);
    public static final MethodHandle SET_SAMPLERATE = call("airspy_set_samplerate", ADDRESS, JAVA_INT);
    public static final MethodHandle SET_CONVERSION_FILTER_FLOAT32 =
        call("airspy_set_conversion_filter_float32", ADDRESS, ADDRESS, JAVA_INT);
    public static final MethodHandle SET_CONVERSION_FILTER_INT16 =
        call("airspy_set_conversion_filter_int16", ADDRESS, ADDRESS, JAVA_INT);
    public static final MethodHandle SET_SAMPLE_TYPE = call("airspy_set_sample_type", ADDRESS, JAVA_INT);
    public static final MethodHandle SET_FREQ = call("airspy_set_freq", ADDRESS, JAVA_INT);
    public static final MethodHandle SET_PACKING = call("airspy_set_packing", ADDRESS, JAVA_BYTE);

    public static final MethodHandle START_RX = call("airspy_start_rx", ADDRESS, ADDRESS, ADDRESS);
    public static final MethodHandle STOP_RX = call("airspy_stop_rx", ADDRESS);
    public static final MethodHandle IS_STREAMING = call("airspy_is_streaming", ADDRESS);

    public static final MethodHandle SET_LNA_GAIN = call("airspy_set_lna_gain", ADDRESS, JAVA_BYTE);
    public static final MethodHandle SET_MIXER_GAIN = call("airspy_set_mixer_gain", ADDRESS, JAVA_BYTE);
    public static final MethodHandle SET_VGA_GAIN = call("airspy_set_vga_gain", ADDRESS, JAVA_BYTE);
    public static final MethodHandle SET_LNA_AGC = call("airspy_set_lna_agc", ADDRESS, JAVA_BYTE);
    public static final MethodHandle SET_MIXER_AGC = call("airspy_set_mixer_agc", ADDRESS, JAVA_BYTE);
    public static final MethodHandle SET_LINEARITY_GAIN = call("airspy_set_linearity_gain", ADDRESS, JAVA_BYTE);
    public static final MethodHandle SET_SENSITIVITY_GAIN = call("airspy_set_sensitivity_gain", ADDRESS, JAVA_BYTE);
    public static final MethodHandle SET_RF_BIAS = call("airspy_set_rf_bias", ADDRESS, JAVA_BYTE);

    public static final MethodHandle GPIO_WRITE = call("airspy_gpio_write", ADDRESS, JAVA_INT, JAVA_INT, JAVA_BYTE);
    public static final MethodHandle GPIO_READ = call("airspy_gpio_read", ADDRESS, JAVA_INT, JAVA_INT, ADDRESS);
    public static final MethodHandle GPIODIR_WRITE =
        call("airspy_gpiodir_write", ADDRESS, JAVA_INT, JAVA_INT, JAVA_BYTE);
    public static final MethodHandle GPIODIR_READ = call("airspy_gpiodir_read", ADDRESS, JAVA_INT, JAVA_INT, ADDRESS);

    public static final MethodHandle SI5351C_WRITE = call("airspy_si5351c_write", ADDRESS, JAVA_BYTE, JAVA_BYTE);
    public static final MethodHandle SI5351C_READ = call("airspy_si5351c_read", ADDRESS, JAVA_BYTE, ADDRESS);
    public static final MethodHandle R820T_WRITE = call("airspy_r820t_write", ADDRESS, JAVA_BYTE, JAVA_BYTE);
    public static final MethodHandle R820T_READ = call("airspy_r820t_read", ADDRESS, JAVA_BYTE, ADDRESS);

    public static final MethodHandle SPIFLASH_ERASE = call("airspy_spiflash_erase", ADDRESS);
    public static final MethodHandle SPIFLASH_ERASE_SECTOR = call("airspy_spiflash_erase_sector", ADDRESS, JAVA_SHORT);
    public static final MethodHandle SPIFLASH_WRITE =
        call("airspy_spiflash_write", ADDRESS, JAVA_INT, JAVA_SHORT, ADDRESS);
    public static final MethodHandle SPIFLASH_READ =
        call("airspy_spiflash_read", ADDRESS, JAVA_INT, JAVA_SHORT, ADDRESS);

    private Native() {
    }

    private static MemorySegment symbol(String name) {
        return LOOKUP.find(name).orElseThrow(() -> new UnsatisfiedLinkError("libairspy has no symbol " + name));
    }

    private static MethodHandle call(String name, MemoryLayout... arguments) {
        return LINKER.downcallHandle(symbol(name), FunctionDescriptor.of(JAVA_INT, arguments));
    }

    private static MethodHandle voidCall(String name, MemoryLayout... arguments) {
        return LINKER.downcallHandle(symbol(name), FunctionDescriptor.ofVoid(arguments));
    }

    /**
     * Calls a native function returning {@code int}. This boxes its arguments,
     * which is fine for the control paths using it; samples arrive by upcall.
     */
    public static int invoke(MethodHandle function, Object... arguments) {
        try {
            return (int) function.invokeWithArguments(arguments);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("Native call failed", t);
        }
    }

    /** Calls a native function that returns nothing. */
    public static void invokeVoid(MethodHandle function, Object... arguments) {
        try {
            function.invokeWithArguments(arguments);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("Native call failed", t);
        }
    }

    /** Returns the C name of an error code, such as {@code AIRSPY_ERROR_BUSY}. */
    public static String errorName(int code) {
        return name(ERROR_NAME, code);
    }

    /** Returns the name of a board id, such as {@code AIRSPY}. */
    public static String boardIdName(int boardId) {
        return name(BOARD_ID_NAME, boardId);
    }

    private static String name(MethodHandle function, int value) {
        try {
            MemorySegment name = (MemorySegment) function.invokeExact(value);
            return cString(name);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("Native call failed", t);
        }
    }

    /** Reads a NUL-terminated string that the library owns. */
    public static String cString(MemorySegment pointer) {
        return pointer.equals(MemorySegment.NULL) ? null : pointer.reinterpret(Long.MAX_VALUE).getString(0);
    }

    /** Creates an upcall stub for {@code airspy_sample_block_cb_fn}. */
    public static MemorySegment sampleCallback(MethodHandle target, Arena arena) {
        return LINKER.upcallStub(target, FunctionDescriptor.of(JAVA_INT, ADDRESS), arena);
    }
}
