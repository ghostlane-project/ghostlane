#!/usr/bin/env bash
# build-hev-macos.sh <hev-socks5-tunnel source> <output-dir>
#
# Builds hev-socks5-tunnel (MIT, C, lwIP), the tun2socks that stands in front
# of the core, as one program for macOS, arm64 and x86_64 in one file, from a
# source tree with its three libraries under third-part/ (the app keeps one,
# with its own fixes, in androidApp/src/main/jni/hev-socks5-tunnel). The tree
# is copied and built in the copy, once per architecture, with the project's
# own Makefile and the flags scripts/build-hev-ios.sh gives the framework's
# macOS slices (a minimum macOS of 11.0, the first release with arm64):
#   <output-dir>/hev-socks5-tunnel          stripped, signed ad hoc
#   <output-dir>/symbols/hev-socks5-tunnel  as it was linked
#   <output-dir>/report.txt                 one HEV line
# The program says which tree it is (hev-socks5-tunnel --version): the id of
# the source directory's git tree, or HEV_REV_ID.
#
# Needs Xcode: xcrun, lipo, strip, codesign, otool. bash 3.2 safe.
set -euo pipefail

[ $# -eq 2 ] || { echo "usage: build-hev-macos.sh <hev-socks5-tunnel source> <output-dir>" >&2; exit 2; }
SRC="$(cd "$1" && pwd)"
mkdir -p "$2"
OUT="$(cd "$2" && pwd)"
MIN="${HEV_MACOS_MIN:-11.0}"
test -f "$SRC/src/hev-main.h" || { echo "$SRC is not a hev-socks5-tunnel source tree" >&2; exit 1; }
test -f "$SRC/third-part/lwip/Makefile" || { echo "$SRC has no third-part/lwip: its libraries are missing" >&2; exit 1; }

rev="${HEV_REV_ID:-}"
if [ -z "$rev" ]; then
  prefix="$(git -C "$SRC" rev-parse --show-prefix 2> /dev/null || true)"
  rev="$(git -C "$SRC" rev-parse --short=12 "HEAD:${prefix%/}" 2> /dev/null || true)"
fi
[ -n "$rev" ] || rev=unknown

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cc="xcrun --sdk macosx --toolchain macosx clang"
for arch in arm64 x86_64; do
  echo "== hev-socks5-tunnel for macOS $arch (min $MIN, tree $rev)"
  mkdir "$work/$arch"
  cp -R "$SRC/." "$work/$arch/"
  rm -rf "$work/$arch/.git"
  make -C "$work/$arch" clean > /dev/null
  # -Wno-error: hev's Makefile has -Wall -Werror before CFLAGS, and a newer Xcode's
  # clang (macos-latest) warns -Wuninitialized-const-pointer in hev-socks5-tunnel.c;
  # the warnings still print.
  # STRIP=true: the Makefile strips what it links; here the program is kept
  # as linked and stripped as a copy.
  make -C "$work/$arch" -j"$(sysctl -n hw.ncpu)" exec PP="$cc" CC="$cc" STRIP=true REV_ID="$rev" \
    CFLAGS="-arch $arch -mmacosx-version-min=$MIN -Wno-error" \
    LFLAGS="-arch $arch -mmacosx-version-min=$MIN"
  test "$(lipo -archs "$work/$arch/bin/hev-socks5-tunnel")" = "$arch"
done

mkdir -p "$OUT/symbols"
lipo -create "$work/arm64/bin/hev-socks5-tunnel" "$work/x86_64/bin/hev-socks5-tunnel" \
  -output "$OUT/symbols/hev-socks5-tunnel"
cp "$OUT/symbols/hev-socks5-tunnel" "$OUT/hev-socks5-tunnel"
strip "$OUT/hev-socks5-tunnel"
# A strip leaves the linker's ad hoc signature stale, and an arm64 Mac does
# not start a program whose signature does not hold.
codesign --force --sign - "$OUT/hev-socks5-tunnel"
codesign --verify "$OUT/hev-socks5-tunnel"

archs="$(lipo -archs "$OUT/hev-socks5-tunnel")"
case "$archs" in
  *arm64*x86_64* | *x86_64*arm64*) ;;
  *) echo "the program holds '$archs', not arm64 and x86_64" >&2; exit 1 ;;
esac
# It prints its version and leaves with a status that is not 0.
said="$("$OUT/hev-socks5-tunnel" --version 2>&1 | grep -E '^Version: ' || true)"
[ -n "$said" ] || { echo "the program does not say its version" >&2; exit 1; }
minos="$(otool -l -arch arm64 "$OUT/hev-socks5-tunnel" | awk '$1 == "minos" { print $2; exit }')"
echo "HEV macos: $(wc -c < "$OUT/hev-socks5-tunnel" | tr -d ' ') bytes stripped, $(wc -c < "$OUT/symbols/hev-socks5-tunnel" | tr -d ' ') as linked, $archs, minimum macOS ${minos:-unknown}, tree $rev, says \"$said\"" | tee "$OUT/report.txt"
