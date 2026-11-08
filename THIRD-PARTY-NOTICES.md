# Third-party notices

jAirspy is licensed under the Apache License 2.0. The jar also contains native
libraries from the projects below, which keep their own licenses. Full license
texts are in `META-INF/licenses/` inside the jar.

## libairspy

Source: <https://github.com/airspy/airspyone_host>

Licensed under the BSD-3-Clause license, with the I/Q conversion code under the
MIT license. See `META-INF/licenses/libairspy-LICENSE.md`. jAirspy is an
independent project and is not endorsed by Airspy or its contributors.

The bundled libairspy is modified by the patches in `native/patches/` of this
repository; each jar records them in
`jairspy/native/<platform>/build-info.properties`.

## libusb

jAirspy does not contain libusb. It is supplied at runtime by
[jlibusb](https://github.com/DSheirer/jlibusb), which ships it under the GNU
Lesser General Public License version 2.1 or later and documents how to
replace it. jAirspy is built against the libusb headers of the release that
`native/versions.env` records.

## pthreads4w (Windows only)

Source: <https://github.com/jwinarske/pthreads4w>

Licensed under the Apache License 2.0 and statically linked into `airspy.dll`.
See `META-INF/licenses/pthreads4w-LICENSE`.
