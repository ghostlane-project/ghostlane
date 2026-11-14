#!/usr/bin/env bash
# Builds HevSocks5Tunnel.xcframework from a checkout of hev-socks5-tunnel.
#
# Derived from the project's own build-apple.sh, minus the tvOS slices this app
# has no target for, plus a minimum iOS of 15 (the extension's deployment
# target) and macOS 11 (the first release with arm64). Each slice is the
# project's static library merged with its three static dependencies (lwIP,
# yaml, hev-task-system) into one archive, so the extension links one library.
#
# lwIP is built with the extension's own sizes: scripts/patches/
# hev-lwipopts-ios.patch, applied to the checkout here (once; a second build
# from the same checkout finds it in place). hev's lwipopts.h defines every
# option without #ifndef, so a -D on the command line would be redefined by
# the header; the patch appends #undef/#define pairs after hev's values.
#
# Usage: build-hev-ios.sh <hev checkout, with submodules> <output dir>
# Needs Xcode: xcrun, libtool, lipo, xcodebuild.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="${1:?usage: build-hev-ios.sh <hev-socks5-tunnel checkout> <output-dir>}"
OUT="${2:?usage: build-hev-ios.sh <hev-socks5-tunnel checkout> <output-dir>}"
OUT="$(mkdir -p "$OUT" && cd "$OUT" && pwd)"
cd "$SRC"
test -f src/hev-main.h || { echo "$SRC is not a hev-socks5-tunnel checkout"; exit 1; }
test -f third-part/lwip/Makefile || { echo "submodules missing: clone with --recursive"; exit 1; }

# HevTunnel.swift's misc settings are worked out from hev's stack rule
# (src/hev-config.c, hev_config_parse_doc) and its two constants, and
# HevTunnelConfigurationTests holds the configuration to them. A pin that
# moves any of it has to move those too, so no framework is built past them.
if ! grep -qx 'static const int TASK_STACK_SIZE = 20480;' src/hev-config-const.h \
    || ! grep -qx 'static const int UDP_BUF_SIZE = 1500;' src/hev-config-const.h \
    || ! grep -q 'udp_buffer_size = UDP_BUF_SIZE \* udp_copy_buffer_nums;' src/hev-config.c \
    || ! grep -q 'min_task_stack_size = TASK_STACK_SIZE + udp_buffer_size;' src/hev-config.c \
    || ! grep -q '"udp-copy-buffer-nums"' src/hev-config.c; then
    echo "hev's task stack rule or its constants differ at this commit:"
    echo "revisit the misc settings in iosApp/PacketTunnel/HevTunnel.swift and"
    echo "the constants in iosApp/Tests/HevTunnelConfigurationTests.swift"
    exit 1
fi

opts=third-part/lwip/src/ports/include/lwipopts.h
if ! grep -q "Ghostlane: the iOS packet tunnel's sizes" "$opts"; then
    patch -p1 --forward --silent < "$here/patches/hev-lwipopts-ios.patch"
fi
# The values the extension's settings and the pools' sizes were worked out
# for; a half-applied header fails here rather than in a phone.
for want in 'MEMP_NUM_TCP_PCB                1024' 'MEMP_NUM_UDP_PCB                512' \
            'MEMP_NUM_TCP_SEG                2048' 'MEMP_NUM_PBUF                   2048' \
            'PBUF_POOL_SIZE                  5' 'TCP_WND                         (4 * TCP_MSS)' \
            'TCP_SND_BUF                     (4 * TCP_MSS)'; do
    grep -qxF "#define $want" "$opts" || { echo "$opts: no '#define $want' after the patch"; exit 1; }
done

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# reportPools <archive>: what lwIP's static pools hold, read from the built
# library's own memp_pools table (count x element size) on this Mac's native
# slice. memp_init writes every element at start, so all of it is resident
# before the first packet. A report only: a failure here fails nothing.
reportPools() {
    cat > "$work/memp-report.c" <<'C'
#include <stdio.h>
#include "lwip/opt.h"
#include "lwip/memp.h"

static const char *const names[] = {
#define LWIP_MEMPOOL(name, num, size, desc) #name,
#include "lwip/priv/memp_std.h"
};

int
main (void)
{
    unsigned long total = 0;
    int i;

    for (i = 0; i < MEMP_MAX; i++) {
        unsigned long bytes = (unsigned long)memp_pools[i]->num * memp_pools[i]->size;
        printf ("%-16s %6u x %5u B = %9lu B\n", names[i], (unsigned)memp_pools[i]->num,
                (unsigned)memp_pools[i]->size, bytes);
        total += bytes;
    }
    printf ("%-16s %28lu B\n", "all pools", total);
    printf ("TCP_MSS %d, TCP_WND %d, TCP_SND_BUF %d, TCP_SND_QUEUELEN %d, PBUF_POOL_BUFSIZE %d\n",
            (int)TCP_MSS, (int)TCP_WND, (int)TCP_SND_BUF, (int)TCP_SND_QUEUELEN, (int)PBUF_POOL_BUFSIZE);
    return 0;
}
C
    xcrun --sdk macosx clang -arch "$(uname -m)" -mmacosx-version-min=11.0 \
        -Ithird-part/lwip/src/include -Ithird-part/lwip/src/ports/include \
        -o "$work/memp-report" "$work/memp-report.c" "$1" \
        && "$work/memp-report"
}

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

echo "== lwIP's static pools, $(uname -m) (scripts/patches/hev-lwipopts-ios.patch) =="
reportPools "$work/macosx-arm64-x86_64/libhev-socks5-tunnel.a" \
    || echo "(the pool report did not build or run; the framework does not depend on it)"

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
