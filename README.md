# jAirspy

Java bindings for [libairspy](https://github.com/airspy/airspyone_host), the
driver for the Airspy R2 and Airspy Mini software-defined radios, built on the
Java Foreign Function & Memory API. The jar contains libairspy for every
supported platform and needs one other jar,
[jlibusb](https://github.com/DSheirer/jlibusb), which supplies libusb. There is
nothing to install. (The Airspy HF+ uses a different library and
is not supported.)

| OS | x86-64 | aarch64 |
|---------|----|----|
| Linux (glibc 2.34+, such as Ubuntu 22.04, Debian 12, RHEL 9) | ✓ | ✓ |
| macOS 11+ | ✓ | ✓ |
| Windows | ✓ | ✓ |

Requires Java 23 or later.

## Getting it

Download `jairspy-<version>.jar` from
[Releases](https://github.com/DSheirer/jAirspy/releases), and the
`jlibusb-<version>.jar` it was built with from
[jlibusb releases](https://github.com/DSheirer/jlibusb/releases). The rolling
[`nightly`](https://github.com/DSheirer/jAirspy/releases/tag/nightly)
pre-release tracks `main` of this project and `master` of upstream.

Check that your machine can see a device:

```
java -jar jairspy-<version>.jar
```

## Using it

```java
try (AirspyDevice device = Airspy.open()) {
    device.setSampleType(SampleType.FLOAT32_IQ);
    device.setSampleRate(device.sampleRates()[0]);
    device.setFrequency(100_000_000L);
    device.setLinearityGain(12);

    device.startRx(buffer -> {
        float[] iq = buffer.copyFloats();   // interleaved I, Q
        queue.offer(iq);
        return true;                        // false stops the stream
    });
    Thread.sleep(10_000);
}
```

Set the sample type before reading or choosing sample rates: the library
reports real sample rates as twice the I/Q rates. Gain can be set as a single
linearity or sensitivity value (0 to 21), or as separate LNA, mixer and VGA
gains (0 to 15) with optional LNA and mixer AGC.

`SampleListener` runs on the driver's streaming thread, so hand slow work to
another thread. `buffer.samples()` gives zero-copy access, valid only until the
listener returns; later use throws an exception rather than reading freed
memory. An exception thrown by a listener stops the stream and is available from
`streamingFailure()`.

### Native access

Java prints a warning, which future versions will turn into an error, unless
you allow native access:

```
java --enable-native-access=ALL-UNNAMED -cp app.jar:jairspy.jar:jlibusb.jar my.App
```

On the module path, use `--enable-native-access=io.github.dsheirer.airspy,io.github.dsheirer.libusb`. In
your own executable jar, add `Enable-Native-Access: ALL-UNNAMED` to the manifest.

### USB access

- **Linux:** install upstream's
  [udev rules](https://github.com/airspy/airspyone_host/blob/master/airspy-tools/52-airspy.rules)
  to use a device without root.
- **Windows:** the device needs the WinUSB driver. Current Airspy firmware asks
  Windows to install it automatically; otherwise install it with
  [Zadig](https://zadig.akeo.ie/).

### Native library location

libairspy is extracted at startup into the private temporary directory that
jlibusb extracts libusb to, so the process holds one copy of libusb shared with
any other library built on jlibusb. Set `-Djairspy.library.path=<dir>` to load
libairspy from a directory instead, and `-Djlibusb.library.path=<dir>` to
supply your own build of libusb. If the temporary directory is mounted
`noexec`, point `-Djava.io.tmpdir` elsewhere.

## Building

You need JDK 23+, CMake, a C compiler, make and git. On Windows use Visual Studio
and 7-Zip.

```
./native/build-unix.sh                                # Linux and macOS
pwsh native/build-windows.ps1 -Arch x64               # Windows; or -Arch ARM64
./gradlew build                                       # jar in build/libs
./gradlew test -PhardwareTest=true                    # also test a connected device
```

A local jar contains only your platform. The hardware tests are receive-only.
Pass an upstream git ref to build something other than the pin, such as
`./native/build-unix.sh master`.

## Releases

jAirspy versions follow upstream: jAirspy 1.0.12 wraps libairspy 1.0.12. The
upstream source and version are pinned in
[`native/versions.env`](native/versions.env), which is the only place they are
set. Upstream has not tagged 1.0.12, so `AIRSPY_REF` pins a commit on `master`
and `AIRSPY_VERSION` gives its version; the build fails if that does not match
the version in the pinned `airspy.h`.

| Trigger | What happens |
|---------|--------------|
| Pull request | Builds and tests all six platforms; publishes nothing |
| Merge to `main` | Replaces the `nightly` pre-release, built against upstream `master` |
| Daily schedule | The same, if this project or upstream has changed |
| Tag `vX.Y.Z` | Publishes a release built from the pinned upstream version |

To release, tag the pinned version: `git tag v1.0.12 && git push origin v1.0.12`.
The workflow rejects a tag that does not match `AIRSPY_VERSION`. For a Java-only
fix on the same upstream version add a fourth number, such as `v1.0.12.1`; for a
pre-release add a suffix, such as `v1.0.12-rc1`.

To adopt a new upstream version, change `AIRSPY_REF` (a commit or tag) and
`AIRSPY_VERSION` in `native/versions.env`; the latter also moves the jAirspy
version. If upstream added functions, the build lists them; add them to
`Native.java`.

## License

Apache License 2.0. The bundled native libraries have their own licenses: see
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
