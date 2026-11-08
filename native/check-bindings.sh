#!/usr/bin/env bash
# Compares the functions in airspy.h with the bindings in Native.java, so a new
# upstream function fails the build rather than being silently unavailable.
#
# Usage: native/check-bindings.sh <path-to-airspy.h> [path-to-built-libairspy]
#
# Functions marked "/* name() deprecated */" in the header need no binding.
# Given the built library, header functions it does not export (upstream
# declares airspy_config_read and airspy_config_write without implementing
# them) need no binding either.
set -euo pipefail
# sort and comm must agree on collation order whatever the caller's locale.
export LC_ALL=C

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HEADER="$1"
LIBRARY="${2:-}"
BINDINGS="$ROOT/src/main/java/io/github/dsheirer/airspy/internal/Native.java"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Declarations look like: extern ADDAPI int ADDCALL name(
grep -oE '/\* *airspy_[a-z0-9_]+\(\) deprecated' "$HEADER" \
    | sed -E 's/.*(airspy_[a-z0-9_]+).*/\1/' | sort -u > "$WORK/deprecated"
grep -E '^extern ADDAPI' "$HEADER" \
    | sed -E 's/.*ADDCALL ([a-z0-9_]+)\(.*/\1/' | sort -u \
    | comm -23 - "$WORK/deprecated" > "$WORK/declared"

if [ -n "$LIBRARY" ]; then
    if [ "$(uname -s)" = Darwin ]; then
        nm -gU "$LIBRARY" | awk '{print $3}' | sed 's/^_//'
    else
        nm -D --defined-only "$LIBRARY" | awk '$2 == "T" {print $3}'
    fi | grep '^airspy_' | sort -u > "$WORK/exported"
    comm -12 "$WORK/declared" "$WORK/exported" > "$WORK/header"
    UNIMPLEMENTED="$(comm -23 "$WORK/declared" "$WORK/exported" | tr '\n' ' ')"
    [ -z "$UNIMPLEMENTED" ] || echo "Declared in airspy.h but not exported: $UNIMPLEMENTED"
else
    cp "$WORK/declared" "$WORK/header"
fi

grep -oE '"airspy_[a-z0-9_]+"' "$BINDINGS" | tr -d '"' | sort -u > "$WORK/bindings"

MISSING="$(comm -23 "$WORK/header" "$WORK/bindings")"
STALE="$(comm -13 "$WORK/header" "$WORK/bindings")"
if [ -n "$MISSING" ] || [ -n "$STALE" ]; then
    [ -z "$MISSING" ] || printf 'Functions in airspy.h without a binding:\n%s\n' "$MISSING" >&2
    [ -z "$STALE" ] || printf 'Bindings not in airspy.h:\n%s\n' "$STALE" >&2
    exit 1
fi
echo "Bindings match airspy.h ($(wc -l < "$WORK/header" | tr -d ' ') functions)"
