package io.github.dsheirer.airspy.internal;

import io.github.dsheirer.libusb.NativeLoader;
import java.lang.foreign.SymbolLookup;
import java.util.List;

/**
 * Loads libairspy through jlibusb, which supplies the libusb it depends on.
 *
 * <p>By default libairspy is extracted from the jar, next to jlibusb's copy of
 * libusb. Set the {@value #PATH_PROPERTY} system property to a directory to
 * load libairspy from there instead; set {@code jlibusb.library.path} to
 * replace libusb.
 */
public final class NativeLibraryLoader {
    /** System property naming a directory that overrides the bundled libairspy. */
    public static final String PATH_PROPERTY = "jairspy.library.path";

    private static final String RESOURCE_ROOT = "/jairspy/native/";

    private NativeLibraryLoader() {
    }

    /** File name of libairspy on a platform, such as {@code linux-x86_64}. */
    public static String libraryName(String platform) {
        if (platform.startsWith("linux")) {
            return "libairspy.so";
        }
        if (platform.startsWith("macos")) {
            return "libairspy.dylib";
        }
        return "airspy.dll";
    }

    /** Loads libusb and libairspy and returns a lookup for the symbols of libairspy. */
    static SymbolLookup load() {
        String name = libraryName(NativeLoader.platform());
        return NativeLoader.load(NativeLibraryLoader.class, RESOURCE_ROOT, List.of(name), PATH_PROPERTY);
    }
}
