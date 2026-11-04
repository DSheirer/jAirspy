#!/usr/bin/env bash
# Builds libusb and libairspy as shared libraries for the host platform and
# copies them to dist/native/<platform>. Works on Linux and macOS.
#
# Usage: native/build-unix.sh [airspy-git-ref]
#
# libusb stays a separate shared library because it is LGPL-2.1: keeping it out
# of libairspy lets users replace it. It is built without udev so the Linux
# binaries do not need libudev at runtime.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=versions.env
source "$ROOT/native/versions.env"
REF="${1:-$AIRSPY_REF}"
WORK="${JAIRSPY_WORK_DIR:-$ROOT/build/native-work}"
JOBS="$(getconf _NPROCESSORS_ONLN)"

case "$(uname -s)" in
    Linux)  OS=linux ;;
    Darwin) OS=macos ;;
    *) echo "Unsupported OS: $(uname -s)" >&2; exit 1 ;;
esac
case "$(uname -m)" in
    x86_64|amd64)  ARCH=x86_64 ;;
    aarch64|arm64) ARCH=aarch64 ;;
    *) echo "Unsupported architecture: $(uname -m)" >&2; exit 1 ;;
esac
PLATFORM="$OS-$ARCH"
OUT="$ROOT/dist/native/$PLATFORM"

rm -rf "$WORK" "$OUT"
mkdir -p "$WORK" "$OUT" "$ROOT/dist/native/licenses"
cd "$WORK"

echo "=== libusb $LIBUSB_VERSION ==="
TARBALL="libusb-$LIBUSB_VERSION.tar.bz2"
curl -fsSL -o "$TARBALL" \
    "https://github.com/libusb/libusb/releases/download/v$LIBUSB_VERSION/$TARBALL"
if command -v sha256sum >/dev/null; then
    echo "$LIBUSB_TARBALL_SHA256  $TARBALL" | sha256sum -c -
else
    echo "$LIBUSB_TARBALL_SHA256  $TARBALL" | shasum -a 256 -c -
fi
tar xjf "$TARBALL"

MIN_MACOS=11.0
USB_PREFIX="$WORK/usb"
USB_ARGS=(--prefix="$USB_PREFIX" --disable-static --enable-shared)
if [ "$OS" = linux ]; then
    USB_ARGS+=(--disable-udev)
else
    export CFLAGS="-O2 -mmacosx-version-min=$MIN_MACOS"
    export LDFLAGS="-mmacosx-version-min=$MIN_MACOS"
fi
(cd "libusb-$LIBUSB_VERSION" && ./configure "${USB_ARGS[@]}" >/dev/null && make -j"$JOBS" install >/dev/null)
unset CFLAGS LDFLAGS

echo "=== libairspy $REF ==="
# Fetching by ref works for a commit as well as a branch or tag.
git init --quiet airspy
git -C airspy fetch --quiet --depth 1 "$AIRSPY_REPOSITORY" "$REF"
git -C airspy checkout --quiet FETCH_HEAD
UPSTREAM_COMMIT="$(git -C airspy rev-parse HEAD)"
HEADER_VERSION="$(sed -nE 's/^#define AIRSPY_VERSION "(.*)"/\1/p' airspy/libairspy/src/airspy.h)"
if [ "$REF" = "$AIRSPY_REF" ] && [ "$HEADER_VERSION" != "$AIRSPY_VERSION" ]; then
    echo "airspy.h at $REF is version $HEADER_VERSION, but AIRSPY_VERSION is $AIRSPY_VERSION" >&2
    exit 1
fi
# Fixes to upstream that it has not adopted yet; see native/patches/README.md.
PATCHES=""
for patch in "$ROOT"/native/patches/*.patch; do
    echo "Applying $(basename "$patch")"
    git -C airspy apply "$patch"
    PATCHES="$PATCHES $(basename "$patch")"
done

# Only the library is built, not airspy-tools. CMAKE_POLICY_VERSION_MINIMUM
# lets CMake 4 configure older upstream versions that declare
# cmake_minimum_required(2.8); newer ones ignore it.
CMAKE_ARGS=(
    -S airspy/libairspy -B build-airspy
    -DCMAKE_BUILD_TYPE=Release
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5
    -DLIBUSB_INCLUDE_DIR="$USB_PREFIX/include/libusb-1.0"
)
if [ "$OS" = linux ]; then
    USB_LIB="$USB_PREFIX/lib/libusb-1.0.so"
    # $ORIGIN only: the build tree's paths must not leak into the shipped library.
    CMAKE_ARGS+=(-DCMAKE_BUILD_WITH_INSTALL_RPATH=ON -DCMAKE_INSTALL_RPATH='$ORIGIN')
else
    USB_LIB="$USB_PREFIX/lib/libusb-1.0.0.dylib"
    # Upstream defaults these to the install prefix, which would leak into the binary.
    CMAKE_ARGS+=(-DCMAKE_OSX_DEPLOYMENT_TARGET="$MIN_MACOS"
        -DCMAKE_INSTALL_NAME_DIR=@rpath -DCMAKE_INSTALL_RPATH=@loader_path)
fi
cmake "${CMAKE_ARGS[@]}" -DLIBUSB_LIBRARIES="$USB_LIB" >/dev/null
cmake --build build-airspy --parallel "$JOBS" --target airspy >/dev/null

echo "=== packaging $PLATFORM ==="
if [ "$OS" = linux ]; then
    cp -L "$USB_PREFIX/lib/libusb-1.0.so.0" "$OUT/libusb-1.0.so.0"
    cp -L build-airspy/src/libairspy.so "$OUT/libairspy.so"
    chmod 755 "$OUT"/*
    # Only glibc and libusb may remain as dependencies.
    readelf -d "$OUT/libairspy.so" | grep -E 'NEEDED|RUNPATH|RPATH'
else
    cp -L "$USB_PREFIX/lib/libusb-1.0.0.dylib" "$OUT/libusb-1.0.0.dylib"
    cp -L build-airspy/src/libairspy.dylib "$OUT/libairspy.dylib"
    chmod 755 "$OUT"/*
    # Point libairspy at the bundled libusb, not the absolute build path, and
    # re-sign because install_name_tool invalidates the ad-hoc signatures.
    LINKED_USB="$(otool -L "$OUT/libairspy.dylib" | awk '/libusb-1\.0/ {print $1; exit}')"
    install_name_tool -id @rpath/libusb-1.0.0.dylib "$OUT/libusb-1.0.0.dylib"
    install_name_tool -id @rpath/libairspy.dylib "$OUT/libairspy.dylib"
    install_name_tool -change "$LINKED_USB" @loader_path/libusb-1.0.0.dylib "$OUT/libairspy.dylib"
    codesign -f -s - "$OUT"/*.dylib
    otool -L "$OUT/libairspy.dylib"
    if otool -l "$OUT/libairspy.dylib" | grep -A2 LC_RPATH | grep -q "$WORK"; then
        echo "libairspy.dylib contains a build tree rpath" >&2
        exit 1
    fi
fi

# Pinned sources must match the bindings. Tracking upstream master only warns,
# so that one new upstream function does not stop the nightly builds.
if [ "$OS" = linux ]; then BUILT="$OUT/libairspy.so"; else BUILT="$OUT/libairspy.dylib"; fi
if ! "$ROOT/native/check-bindings.sh" airspy/libairspy/src/airspy.h "$BUILT"; then
    [ "$REF" != "$AIRSPY_REF" ] || exit 1
    echo "::warning::Bindings differ from airspy.h at $REF"
fi

cp airspy/libairspy/LICENSE.md "$ROOT/dist/native/licenses/libairspy-LICENSE.md"
cp "libusb-$LIBUSB_VERSION/COPYING" "$ROOT/dist/native/licenses/libusb-COPYING"
cat > "$OUT/build-info.properties" <<INFO
airspy.ref=$REF
airspy.commit=$UPSTREAM_COMMIT
airspy.version=$HEADER_VERSION
airspy.patches=${PATCHES# }
libusb.version=$LIBUSB_VERSION
INFO
ls -l "$OUT"
