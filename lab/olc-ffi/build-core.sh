#!/usr/bin/env bash
# build-core.sh <xray-rust checkout> <lab dir>
#
# The Rust side of the lab's Swift package (Package.swift):
#   - ghostlane-core, the one static library that carries both clients
#     (olc-ffi, the olcRTC client's UniFFI API, and xray-ffi) on one Tokio
#     runtime and one allocator, for the iOS simulator
#     (aarch64-apple-ios-sim);
#   - the UniFFI Swift bindings, generated from that archive in library mode
#     by olc-ffi's own uniffi-bindgen (so they match the library they bind),
#     plus the error shim olc/olc-ffi/swift/OlcrtcError+Localized.swift,
#     into Sources/OlcFfi/;
#   - GhostlaneCoreFFI.xcframework with the C header and its module map under
#     Headers/OlcFfiFFI/, the shape of HevSocks5Tunnel.xcframework, so two
#     module maps never land on one path.
# The release profile is the workspace's but for LTO and codegen units (a
# lab build, not the shipped one: the footprint rows are an upper bound).
# bash 3.2 safe (macOS).
set -euo pipefail

src="$(cd "${1:?usage: build-core.sh <xray-rust checkout> <lab dir>}" && pwd)"
lab="$(cd "${2:?usage: build-core.sh <xray-rust checkout> <lab dir>}" && pwd)"
target=aarch64-apple-ios-sim
export IPHONEOS_DEPLOYMENT_TARGET=15.0
export CARGO_PROFILE_RELEASE_LTO=false CARGO_PROFILE_RELEASE_CODEGEN_UNITS=16
export CARGO_TERM_COLOR=never CARGO_INCREMENTAL=0 CARGO_NET_RETRY=5

start=$(date +%s)
(cd "$src" && cargo build --locked --release -p ghostlane-core --target "$target")
lib="$src/target/$target/release/libghostlane_core.a"
test -f "$lib" || { echo "cargo left no $lib" >&2; exit 1; }
echo "BUILD ghostlane-core $target: $(wc -c < "$lib" | tr -d ' ') bytes in $(( $(date +%s) - start )) s"

(cd "$src" && cargo build --locked -p olc-ffi --features bindgen --bin uniffi-bindgen)
gen="$lab/build/gen"
rm -rf "$gen"
mkdir -p "$gen"
(cd "$src" && target/debug/uniffi-bindgen generate --library "$lib" --language swift --out-dir "$gen")
mkdir -p "$lab/Sources/OlcFfi"
cp "$gen/OlcFfi.swift" "$lab/Sources/OlcFfi/OlcFfi.swift"
cp "$src/olc/olc-ffi/swift/OlcrtcError+Localized.swift" "$lab/Sources/OlcFfi/"

inc="$lab/build/include"
rm -rf "$inc"
mkdir -p "$inc/OlcFfiFFI"
cp "$gen/OlcFfiFFI.h" "$inc/OlcFfiFFI/OlcFfiFFI.h"
cp "$gen/OlcFfiFFI.modulemap" "$inc/OlcFfiFFI/module.modulemap"
rm -rf "$lab/build/GhostlaneCoreFFI.xcframework"
xcodebuild -create-xcframework -library "$lib" -headers "$inc" \
    -output "$lab/build/GhostlaneCoreFFI.xcframework"
ls -R "$lab/build/GhostlaneCoreFFI.xcframework" | head -20
echo "SYMBOLS uniffi exports: $(nm -gU "$lib" 2>/dev/null | grep -c ' _uniffi_olc_ffi_fn_' || true); xray exports: $(nm -gU "$lib" 2>/dev/null | grep -c ' _xray_' || true)"
