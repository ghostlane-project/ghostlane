#!/usr/bin/env bash
# Builds HevSocks5Tunnel.xcframework from a checkout of hev-socks5-tunnel.
#
# Derived from the project's own build-apple.sh, minus the tvOS slices this app
# has no target for, plus a minimum iOS of 15 (the extension's deployment
# target) and macOS 11 (the first release with arm64). Each slice is the
# project's static library merged with its three static dependencies (lwIP,
# yaml, hev-task-system) into one archive, so the extension links one library.
#
# Usage: build-hev-ios.sh <hev checkout, with submodules> <output dir>
# Needs Xcode: xcrun, libtool, lipo, xcodebuild.
set -euo pipefail

SRC="${1:?usage: build-hev-ios.sh <hev-socks5-tunnel checkout> <output-dir>}"
OUT="${2:?usage: build-hev-ios.sh <hev-socks5-tunnel checkout> <output-dir>}"
OUT="$(mkdir -p "$OUT" && cd "$OUT" && pwd)"
cd "$SRC"
test -f src/hev-main.h || { echo "$SRC is not a hev-socks5-tunnel checkout"; exit 1; }
test -f third-part/lwip/Makefile || { echo "submodules missing: clone with --recursive"; exit 1; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# buildStatic <sdk> <arch> <min os version>
buildStatic() {
    local sdk="$1" arch="$2" min="$3"
    echo "== $sdk $arch (min $min) =="
    make clean >/dev/null
    make PP="xcrun --sdk $sdk --toolchain $sdk clang" \
         CC="xcrun --sdk $sdk --toolchain $sdk clang" \
         CFLAGS="-arch $arch -m${sdk}-version-min=$min" \
         LFLAGS="-arch $arch -m${sdk}-version-min=$min -Wl,-Bsymbolic-functions" \
         static
    local dir="$work/$sdk-$arch"
    mkdir -p "$dir"
    libtool -static -o "$dir/libhev-socks5-tunnel.a" \
        bin/libhev-socks5-tunnel.a \
        third-part/lwip/bin/liblwip.a \
        third-part/yaml/bin/libyaml.a \
        third-part/hev-task-system/bin/libhev-task-system.a
    make clean >/dev/null
}

# mergeStatic <sdk> <arch1> <arch2>: one fat archive for a two-arch slice.
mergeStatic() {
    local sdk="$1" a="$2" b="$3"
    local dir="$work/$sdk-$a-$b"
    mkdir -p "$dir"
    lipo -create \
        -arch "$a" "$work/$sdk-$a/libhev-socks5-tunnel.a" \
        -arch "$b" "$work/$sdk-$b/libhev-socks5-tunnel.a" \
        -output "$dir/libhev-socks5-tunnel.a"
}

buildStatic iphoneos arm64 15.0
buildStatic iphonesimulator arm64 15.0
buildStatic iphonesimulator x86_64 15.0
mergeStatic iphonesimulator arm64 x86_64
buildStatic macosx arm64 11.0
buildStatic macosx x86_64 11.0
mergeStatic macosx arm64 x86_64

# The headers directory becomes each slice's Headers/. The project's own
# module map sits next to hev-main.h, so Swift can `import HevSocks5Tunnel`
# with nothing more than that directory on its include path.
include="$work/include"
mkdir -p "$include/HevSocks5Tunnel"
cp src/hev-main.h module.modulemap "$include/HevSocks5Tunnel/"

rm -rf "$OUT/HevSocks5Tunnel.xcframework"
xcodebuild -create-xcframework \
    -library "$work/iphoneos-arm64/libhev-socks5-tunnel.a" -headers "$include" \
    -library "$work/iphonesimulator-arm64-x86_64/libhev-socks5-tunnel.a" -headers "$include" \
    -library "$work/macosx-arm64-x86_64/libhev-socks5-tunnel.a" -headers "$include" \
    -output "$OUT/HevSocks5Tunnel.xcframework"

echo "== slices produced =="
ls -1 "$OUT/HevSocks5Tunnel.xcframework"
# The names the Xcode project's search paths are written against.
test -f "$OUT/HevSocks5Tunnel.xcframework/ios-arm64/libhev-socks5-tunnel.a"
test -f "$OUT/HevSocks5Tunnel.xcframework/ios-arm64/Headers/HevSocks5Tunnel/module.modulemap"
test -f "$OUT/HevSocks5Tunnel.xcframework/ios-arm64_x86_64-simulator/libhev-socks5-tunnel.a"
test -f "$OUT/HevSocks5Tunnel.xcframework/macos-arm64_x86_64/libhev-socks5-tunnel.a"
echo "== ready: $OUT/HevSocks5Tunnel.xcframework =="
