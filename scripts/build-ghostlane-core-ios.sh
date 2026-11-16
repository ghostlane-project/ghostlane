#!/usr/bin/env bash
# build-ghostlane-core-ios.sh <output-dir>
#
# Builds GhostlaneCore.xcframework, the packet tunnel extension's only engine
# library, at the pin in scripts/ghostlane-core-pin.sh:
#   - ghostlane-core (`--locked --release -p ghostlane-core`) for
#     aarch64-apple-ios (the device) and aarch64-apple-ios-sim (the simulator,
#     arm64 only: the extension target excludes x86_64 there), with the
#     workspace's release profile as it ships (thin LTO, one codegen unit).
#     GHOSTLANE_CORE_FAST=1 turns LTO off and codegen units up, for a lab
#     build only;
#   - the headers of both halves in each slice, each under a directory of its
#     own as hev's are (SWIFT_INCLUDE_PATHS points at both): Headers/XrayRust/
#     (xray-ffi's C ABI) and Headers/OlcFfiFFI/ (UniFFI's C side);
#   - the UniFFI Swift bindings, generated in library mode from the device
#     archive by olc-ffi's own uniffi-bindgen, with olc-ffi's error shim. They
#     are committed in iosApp/PacketTunnel/GhostlaneCore/ (Xcode's synchronized
#     folder compiles what is there when the project is read), so this script
#     checks them: missing, they are written there; different, the build stops,
#     since the Swift would not match the library it calls (set
#     GHOSTLANE_CORE_WRITE_BINDINGS=1 to overwrite after a pin move).
# <output-dir>/GhostlaneCore.xcframework.commit records the commit and the
# profile; an existing build of the same pin and profile is kept.
# Needs Xcode, rustup, git. bash 3.2 safe (macOS).
set -euo pipefail

OUT="${1:?usage: build-ghostlane-core-ios.sh <output-dir>}"
mkdir -p "$OUT"; OUT="$(cd "$OUT" && pwd)"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
# shellcheck source=scripts/ghostlane-core-pin.sh
. "$HERE/ghostlane-core-pin.sh"
export PATH="$HOME/.cargo/bin:$PATH"

profile="shipped"
if [ "${GHOSTLANE_CORE_FAST:-0}" = "1" ]; then
    profile="fast"
    export CARGO_PROFILE_RELEASE_LTO=false CARGO_PROFILE_RELEASE_CODEGEN_UNITS=16
fi
stamp="$GHOSTLANE_CORE_COMMIT $profile"
bindings="$ROOT/iosApp/PacketTunnel/GhostlaneCore"
if [ -f "$OUT/GhostlaneCore.xcframework.commit" ] && [ "$(cat "$OUT/GhostlaneCore.xcframework.commit")" = "$stamp" ] \
   && [ -f "$bindings/OlcFfi.swift" ]; then
    echo "ghostlane-core $stamp: already built in $OUT"
    exit 0
fi

export IPHONEOS_DEPLOYMENT_TARGET=15.0 CARGO_TERM_COLOR=never CARGO_INCREMENTAL=0 CARGO_NET_RETRY=5
src="${GHOSTLANE_CORE_SRC:-$OUT/src}"
if [ "$(git -C "$src" rev-parse HEAD 2>/dev/null || true)" != "$GHOSTLANE_CORE_COMMIT" ]; then
    rm -rf "$src"
    git init -q "$src"
    git -C "$src" fetch -q --depth 1 "https://github.com/${GHOSTLANE_CORE_REPO}.git" "$GHOSTLANE_CORE_COMMIT"
    git -C "$src" checkout -q FETCH_HEAD
fi
test "$(git -C "$src" rev-parse HEAD)" = "$GHOSTLANE_CORE_COMMIT"

rustup toolchain install "$GHOSTLANE_CORE_TOOLCHAIN" --profile minimal \
    --target aarch64-apple-ios --target aarch64-apple-ios-sim >/dev/null
cargo_="cargo +$GHOSTLANE_CORE_TOOLCHAIN"
for target in aarch64-apple-ios aarch64-apple-ios-sim; do
    start=$(date +%s)
    (cd "$src" && $cargo_ build --locked --release -p ghostlane-core --target "$target")
    lib="$src/target/$target/release/libghostlane_core.a"
    echo "BUILD ghostlane-core $target ($profile): $(wc -c < "$lib" | tr -d ' ') bytes in $(( $(date +%s) - start )) s"
done
device="$src/target/aarch64-apple-ios/release/libghostlane_core.a"
simulator="$src/target/aarch64-apple-ios-sim/release/libghostlane_core.a"

(cd "$src" && $cargo_ build --locked -p olc-ffi --features bindgen --bin uniffi-bindgen)
gen="$OUT/gen"
rm -rf "$gen"; mkdir -p "$gen"
(cd "$src" && target/debug/uniffi-bindgen generate --library "$device" --language swift --out-dir "$gen")
cp "$src/olc/olc-ffi/swift/OlcrtcError+Localized.swift" "$gen/"

inc="$OUT/include"
rm -rf "$inc"; mkdir -p "$inc/OlcFfiFFI" "$inc/XrayRust"
cp "$gen/OlcFfiFFI.h" "$inc/OlcFfiFFI/OlcFfiFFI.h"
cp "$gen/OlcFfiFFI.modulemap" "$inc/OlcFfiFFI/module.modulemap"
cp "$src/crates/xray-ffi/include/xray_ffi.h" "$src/crates/xray-ffi/include/module.modulemap" "$inc/XrayRust/"
rm -rf "$OUT/GhostlaneCore.xcframework"
xcodebuild -create-xcframework -library "$device" -headers "$inc" -library "$simulator" -headers "$inc" \
    -output "$OUT/GhostlaneCore.xcframework" >/dev/null
ls "$OUT/GhostlaneCore.xcframework"
echo "SYMBOLS uniffi exports: $(nm -gU "$device" 2>/dev/null | grep -c ' _uniffi_olc_ffi_fn_' || true); xray exports: $(nm -gU "$device" 2>/dev/null | grep -c ' _xray_' || true)"

mkdir -p "$bindings"
status=0
for f in OlcFfi.swift OlcrtcError+Localized.swift; do
    if [ ! -f "$bindings/$f" ] || [ "${GHOSTLANE_CORE_WRITE_BINDINGS:-0}" = "1" ]; then
        cp "$gen/$f" "$bindings/$f"
        echo "BINDINGS wrote iosApp/PacketTunnel/GhostlaneCore/$f (commit it)"
    elif ! cmp -s "$gen/$f" "$bindings/$f"; then
        echo "error: iosApp/PacketTunnel/GhostlaneCore/$f is not what ghostlane-core $GHOSTLANE_CORE_COMMIT generates; run with GHOSTLANE_CORE_WRITE_BINDINGS=1 and commit it" >&2
        diff -u "$bindings/$f" "$gen/$f" | head -40 >&2 || true
        status=1
    fi
done
[ "$status" -eq 0 ] || exit 1
echo "$stamp" > "$OUT/GhostlaneCore.xcframework.commit"
