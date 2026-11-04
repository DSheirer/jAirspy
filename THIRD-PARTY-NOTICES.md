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

Source: <https://github.com/libusb/libusb>

Licensed under the GNU Lesser General Public License version 2.1 or later. See
`META-INF/licenses/libusb-COPYING`. The exact version in each jar is recorded in
`jairspy/native/<platform>/build-info.properties`, and
`native/versions.env` in this repository records the release it was built from.

libusb is shipped as a separate shared library so it can be replaced: point the
`jairspy.library.path` system property at a directory containing your own
build along with libairspy.

## pthreads4w (Windows only)

Source: <https://github.com/jwinarske/pthreads4w>

Licensed under the Apache License 2.0 and statically linked into `airspy.dll`.
See `META-INF/licenses/pthreads4w-LICENSE`.
