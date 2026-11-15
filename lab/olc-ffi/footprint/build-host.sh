#!/usr/bin/env bash
# build-host.sh <xray-rust checkout> <out dir>
#
# The Rust flavour of the footprint harness (footprint.py): fp-host
# (host/main.swift), a macOS command-line process with ghostlane-core linked
# in, as the packet-tunnel extension will link it.
#   - ghostlane-core for aarch64-apple-darwin with the workspace's release
#     profile as it is (codegen-units 1, lto "thin"; any override in the
#     environment is refused), as the static library alone;
#   - one lab change to the checkout, said in the BUILD line: the Tokio
#     runtime takes the phone's profile on this Mac too (one worker, four
#     blocking threads, Profile::phone(), which is what for_target() gives on
#     iOS), so the threads are the extension's;
#   - the UniFFI Swift bindings generated from that archive by olc-ffi's own
#     uniffi-bindgen, plus the error shim, compiled with main.swift (-O, whole
#     module) and the archive into <out>/fp-host, dead code stripped.
# bash 3.2 safe (macOS).
set -euo pipefail

src="$(cd "${1:?usage: build-host.sh <xray-rust checkout> <out dir>}" && pwd)"
mkdir -p "${2:?usage: build-host.sh <xray-rust checkout> <out dir>}"
out="$(cd "$2" && pwd)"
here="$(cd "$(dirname "$0")" && pwd)"
target=aarch64-apple-darwin
export MACOSX_DEPLOYMENT_TARGET=12.0
export CARGO_TERM_COLOR=never CARGO_INCREMENTAL=0 CARGO_NET_RETRY=5

for v in CARGO_PROFILE_RELEASE_LTO CARGO_PROFILE_RELEASE_CODEGEN_UNITS CARGO_PROFILE_RELEASE_OPT_LEVEL \
    CARGO_PROFILE_RELEASE_DEBUG CARGO_PROFILE_RELEASE_PANIC RUSTFLAGS CARGO_ENCODED_RUSTFLAGS \
    CARGO_BUILD_RUSTFLAGS; do
    if [ -n "$(eval "echo \${$v:-}")" ]; then
        echo "$v is set: the footprint build takes the workspace's release profile as it is" >&2
        exit 1
    fi
done
profile=$(awk '/^\[profile\.release\]/{p=1; next} /^\[/{p=0} p && NF {printf "%s; ", $0}' "$src/Cargo.toml")
case "$profile" in
    *'codegen-units = 1;'*'lto = "thin";'*) ;;
    *) echo "the workspace's release profile is not codegen-units 1, lto thin: $profile" >&2; exit 1 ;;
esac

rt="$src/crates/ghostlane-runtime/src/runtime.rs"
python3 - "$rt" <<'PY'
import sys
path = sys.argv[1]
was = 'if cfg!(target_os = "ios") {'
now = 'if cfg!(any(target_os = "ios", target_os = "macos")) {'
text = open(path).read()
if now not in text:
    if text.count(was) != 1:
        sys.exit("runtime.rs: the profile switch is not where the lab expects it")
    open(path, "w").write(text.replace(was, now))
PY

start=$(date +%s)
(cd "$src" && cargo rustc --locked --release -p ghostlane-core --target "$target" --crate-type staticlib)
lib="$src/target/$target/release/libghostlane_core.a"
test -f "$lib" || { echo "cargo left no $lib" >&2; exit 1; }
echo "BUILD ghostlane-core $target, release profile [$profile] staticlib, runtime Profile::phone(): $(wc -c < "$lib" | tr -d ' ') bytes in $(( $(date +%s) - start )) s"

(cd "$src" && cargo build --locked -p olc-ffi --features bindgen --bin uniffi-bindgen)
gen="$out/gen"
rm -rf "$gen"
mkdir -p "$gen"
(cd "$src" && target/debug/uniffi-bindgen generate --library "$lib" --language swift --out-dir "$gen")
inc="$out/include/OlcFfiFFI"
rm -rf "$out/include"
mkdir -p "$inc"
cp "$gen/OlcFfiFFI.h" "$inc/OlcFfiFFI.h"
cp "$gen/OlcFfiFFI.modulemap" "$inc/module.modulemap"

start=$(date +%s)
xcrun swiftc -O -wmo -module-name FpHost -target "arm64-apple-macos$MACOSX_DEPLOYMENT_TARGET" \
    -I "$inc" \
    "$gen/OlcFfi.swift" "$src/olc/olc-ffi/swift/OlcrtcError+Localized.swift" "$here/host/main.swift" \
    -Xlinker "$lib" -lc++ -lresolv -framework Security -framework CoreFoundation -framework SystemConfiguration \
    -Xlinker -dead_strip -o "$out/fp-host"
echo "BUILD fp-host: $(wc -c < "$out/fp-host" | tr -d ' ') bytes in $(( $(date +%s) - start )) s"
