package io.github.dsheirer.airspy.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Finds and loads the native libraries for the current platform.
 *
 * <p>By default the libraries are extracted from the jar to a private temporary
 * directory. Set the {@value #PATH_PROPERTY} system property to a directory to
 * load them from there instead, for example to use a replacement libusb.
 */
public final class NativeLibraryLoader {
    /** System property naming a directory that overrides the bundled libraries. */
    public static final String PATH_PROPERTY = "jairspy.library.path";

    private static final String RESOURCE_ROOT = "/jairspy/native/";
    private static final String TEMP_PREFIX = "jairspy-";

    private NativeLibraryLoader() {
    }

    /** Identifies the operating system and CPU, such as {@code linux-x86_64}. */
    public static String platform(String osName, String osArch) {
        String name = osName.toLowerCase(Locale.ROOT);
        String os;
        if (name.contains("linux")) {
            os = "linux";
        } else if (name.contains("mac") || name.contains("darwin")) {
            os = "macos";
        } else if (name.contains("windows")) {
            os = "windows";
        } else {
            throw new UnsatisfiedLinkError("jAirspy does not support operating system: " + osName);
        }
        String arch = switch (osArch.toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64", "x64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> throw new UnsatisfiedLinkError("jAirspy does not support CPU architecture: " + osArch);
        };
        return os + "-" + arch;
    }

    /**
     * Library file names for a platform, dependencies first. libusb has to load
     * first so the operating system resolves libairspy's dependency on it to
     * the copy that is already in the process.
     */
    public static List<String> libraryNames(String platform) {
        if (platform.startsWith("linux")) {
            return List.of("libusb-1.0.so.0", "libairspy.so");
        }
        if (platform.startsWith("macos")) {
            return List.of("libusb-1.0.0.dylib", "libairspy.dylib");
        }
        return List.of("libusb-1.0.dll", "airspy.dll");
    }

    /** Loads the libraries and returns a lookup for the symbols of libairspy. */
    static SymbolLookup load() {
        String platform = platform(System.getProperty("os.name"), System.getProperty("os.arch"));
        List<String> names = libraryNames(platform);
        String override = System.getProperty(PATH_PROPERTY);
        Path directory;
        if (override != null) {
            directory = Path.of(override);
        } else {
            try {
                directory = extract(platform, names);
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to extract native libraries", e);
            }
        }

        SymbolLookup lookup = null;
        for (String name : names) {
            // The global arena keeps the libraries loaded for the life of the JVM.
            lookup = SymbolLookup.libraryLookup(directory.resolve(name), Arena.global());
        }
        return lookup;
    }

    private static Path extract(String platform, List<String> names) throws IOException {
        if (NativeLibraryLoader.class.getResource(RESOURCE_ROOT + platform + "/" + names.getLast()) == null) {
            throw new UnsatisfiedLinkError("This jAirspy jar has no native libraries for " + platform);
        }
        if (platform.startsWith("windows")) {
            deleteStaleDirectories();
        }
        // createTempDirectory is private to this user, so other local users
        // cannot swap the libraries between extraction and loading.
        Path directory = Files.createTempDirectory(TEMP_PREFIX);
        directory.toFile().deleteOnExit();
        for (String name : names) {
            Path target = directory.resolve(name);
            try (InputStream in = NativeLibraryLoader.class.getResourceAsStream(RESOURCE_ROOT + platform + "/" + name)) {
                if (in == null) {
                    throw new UnsatisfiedLinkError("Missing native library " + name + " for " + platform);
                }
                Files.copy(in, target);
            }
            target.toFile().deleteOnExit();
        }
        return directory;
    }

    /**
     * Windows locks loaded DLLs, so {@code deleteOnExit} cannot remove them.
     * Clears directories left by earlier runs; the age limit keeps this away
     * from a JVM that is starting up at the same time.
     */
    private static void deleteStaleDirectories() {
        Path temp = Path.of(System.getProperty("java.io.tmpdir"));
        Instant cutoff = Instant.now().minus(Duration.ofDays(1));
        try (Stream<Path> entries = Files.list(temp)) {
            entries.filter(path -> path.getFileName().toString().startsWith(TEMP_PREFIX))
                .filter(path -> isOlderThan(path, cutoff))
                .forEach(NativeLibraryLoader::deleteQuietly);
        } catch (IOException | RuntimeException e) {
            // Housekeeping only: failing here must not stop the library loading.
        }
    }

    private static boolean isOlderThan(Path path, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(path).toInstant().isBefore(cutoff);
        } catch (IOException e) {
            return false;
        }
    }

    private static void deleteQuietly(Path directory) {
        try (Stream<Path> files = Files.walk(directory)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException e) {
            // A running JVM still has this directory's DLLs open.
        }
    }
}
