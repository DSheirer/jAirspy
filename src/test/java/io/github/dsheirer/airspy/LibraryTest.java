package io.github.dsheirer.airspy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.airspy.internal.Layouts;
import io.github.dsheirer.airspy.internal.Native;
import io.github.dsheirer.airspy.internal.NativeLibraryLoader;
import io.github.dsheirer.libusb.NativeLoader;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
    void namesLibraries() {
        assertEquals("libairspy.so", NativeLibraryLoader.libraryName("linux-aarch64"));
        assertEquals("libairspy.dylib", NativeLibraryLoader.libraryName("macos-x86_64"));
        assertEquals("airspy.dll", NativeLibraryLoader.libraryName("windows-aarch64"));
    }

    @Test
    void sharesLibusbWithJlibusb() throws Throwable {
        // Loading libairspy loads jlibusb's libusb, so both sit in its directory.
        assertFalse(Airspy.libraryVersion().toString().isBlank());
        Path directory = NativeLoader.directory();
        String platform = NativeLoader.platform();
        assertTrue(Files.isRegularFile(directory.resolve(NativeLoader.libusbName(platform))));
        assertTrue(Files.isRegularFile(directory.resolve(NativeLibraryLoader.libraryName(platform))));
        if (platform.startsWith("linux")) {
            // One copy of libusb in the process, the one jlibusb extracted.
            List<String> mapped = Files.readAllLines(Path.of("/proc/self/maps")).stream()
                .filter(line -> line.contains("libusb-1.0"))
                .map(line -> line.substring(line.indexOf('/')))
                .distinct()
                .toList();
            assertEquals(List.of(directory.resolve("libusb-1.0.so.0").toString()), mapped);
        } else if (platform.startsWith("macos")) {
            // dyld must reuse the libusb jlibusb loaded when libairspy asks for
            // @loader_path/libusb-1.0.0.dylib, even through the /var symlink.
            List<String> images = dyldImages().stream().filter(name -> name.contains("libusb-1.0")).toList();
            assertEquals(1, images.size(), images.toString());
        }
    }

    /** Paths of the images dyld has loaded into this process. */
    private static List<String> dyldImages() throws Throwable {
        Linker linker = Linker.nativeLinker();
        SymbolLookup system = linker.defaultLookup();
        MethodHandle count = linker.downcallHandle(system.find("_dyld_image_count").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT));
        MethodHandle name = linker.downcallHandle(system.find("_dyld_get_image_name").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        int images = (int) count.invokeExact();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < images; i++) {
            MemorySegment pointer = (MemorySegment) name.invokeExact(i);
            if (!pointer.equals(MemorySegment.NULL)) {
                names.add(pointer.reinterpret(Long.MAX_VALUE).getString(0));
            }
        }
        return names;
    }
}
