package io.github.dsheirer.airspy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.airspy.internal.Layouts;
import io.github.dsheirer.airspy.internal.Native;
import io.github.dsheirer.airspy.internal.NativeLibraryLoader;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Tests that need the native library for the current platform but no hardware. */
class LibraryTest {
    @Test
    void structureSizesMatchHeader() {
        assertEquals(48, Layouts.TRANSFER.byteSize());
        assertEquals(24, Layouts.PART_SERIAL.byteSize());
        assertEquals(12, Layouts.LIB_VERSION.byteSize());
        assertEquals(24, Layouts.offset(Layouts.TRANSFER, "sample_count"));
        assertEquals(32, Layouts.offset(Layouts.TRANSFER, "dropped_samples"));
        assertEquals(40, Layouts.offset(Layouts.TRANSFER, "sample_type"));
    }

    @Test
    void loadsLibraryAndResolvesEveryFunction() {
        LibraryVersion version = Airspy.libraryVersion();
        assertTrue(version.major() >= 1, version.toString());
        assertNotNull(Native.OPEN_FD);
    }

    @Test
    void translatesNativeNames() {
        assertEquals("AIRSPY_ERROR_BUSY", Native.errorName(AirspyError.BUSY.code()));
        assertEquals(AirspyError.BUSY, AirspyError.fromCode(-6));
        assertEquals(AirspyError.OTHER, AirspyError.fromCode(-12345));
        assertEquals("AIRSPY", Airspy.boardName(0));
    }

    @Test
    void enumeratesWithoutFailing() {
        // CI machines may have no USB bus, which the library reports as one of these.
        try {
            assertNotNull(Airspy.listDevices());
        } catch (AirspyException e) {
            assertTrue(Set.of(AirspyError.LIBUSB, AirspyError.NOT_FOUND).contains(e.error()), e.getMessage());
        }
    }

    @Test
    void transferSizesFollowStreamFormat() {
        assertEquals(800, SampleType.FLOAT32_IQ.byteLength(100, false));
        assertEquals(400, SampleType.FLOAT32_REAL.byteLength(100, true));
        assertEquals(400, SampleType.INT16_IQ.byteLength(100, false));
        assertEquals(200, SampleType.INT16_REAL.byteLength(100, false));
        assertEquals(200, SampleType.UINT16_REAL.byteLength(100, true));
        assertEquals(200, SampleType.RAW.byteLength(100, false));
        assertEquals(150, SampleType.RAW.byteLength(100, true));
        // A packed USB buffer of 6144 * 24 bytes holds 98304 12-bit samples.
        assertEquals(6144 * 24, SampleType.RAW.byteLength(6144 * 24 / 2 * 4 / 3, true));
    }

    @Test
    void mapsPlatforms() {
        assertEquals("linux-x86_64", NativeLibraryLoader.platform("Linux", "amd64"));
        assertEquals("linux-aarch64", NativeLibraryLoader.platform("Linux", "aarch64"));
        assertEquals("macos-x86_64", NativeLibraryLoader.platform("Mac OS X", "x86_64"));
        assertEquals("macos-aarch64", NativeLibraryLoader.platform("Mac OS X", "aarch64"));
        assertEquals("windows-x86_64", NativeLibraryLoader.platform("Windows 11", "amd64"));
        assertEquals("windows-aarch64", NativeLibraryLoader.platform("Windows 11", "aarch64"));
        assertThrows(UnsatisfiedLinkError.class, () -> NativeLibraryLoader.platform("Solaris", "amd64"));
        assertThrows(UnsatisfiedLinkError.class, () -> NativeLibraryLoader.platform("Linux", "riscv64"));
    }
}
