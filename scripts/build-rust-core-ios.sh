#!/usr/bin/env bash
# Builds XrayRust.xcframework, the Rust core (xray-rust's C ABI, crate
# xray-ffi) as static libraries for the iPhone and its simulator, from the pin
# in scripts/rust-core-pins.sh.
#
# The approach is upstream's scripts/build-apple-xcframework.sh at the pin,
# narrowed to what the tunnel extension needs:
#   - the same build per target: `cargo build --locked --release -p xray-ffi
#     --target <t>`, with the workspace's [profile.release] (thin LTO, one
#     codegen unit, panic unwind), one target after another;
#   - the same targets for iOS: aarch64-apple-ios for the device,
#     aarch64-apple-ios-sim and x86_64-apple-ios merged with lipo for the
#     simulator; no tvOS (upstream's need a nightly with build-std) and no
#     macOS, which the app has no Rust line for yet;
#   - the same C header and module map, crates/xray-ffi/include, but under
#     Headers/XrayRust/ in each slice instead of Headers/ itself: the shape of
#     HevSocks5Tunnel.xcframework (build-hev-ios.sh), so SWIFT_INCLUDE_PATHS
#     can point at that directory and two libraries' module.modulemap files
#     can never land on one path;
#   - a minimum iOS of 15.0, the extension's deployment target (upstream's
#     default is the same).
# No Swift from upstream (platform/apple, MPL-2.0) is used: the app's glue is
# its own.
#
# <output-dir> receives XrayRust.xcframework and rust-core-ios.build.json,
# the build record: commit, toolchain, Xcode, each target's and each slice's
# size and time. The checkout and Cargo's target directory live in a
# temporary directory that is removed at exit (several GB for three targets).
#
# Usage: build-rust-core-ios.sh <output-dir>
# Needs Xcode (xcrun, xcodebuild, lipo, nm), rustup, git, python3.
# bash 3.2 safe (macOS).
set -euo pipefail

OUT="${1:?usage: build-rust-core-ios.sh <output-dir>}"
OUT="$(mkdir -p "${OUT}" && cd "${OUT}" && pwd)"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/rust-core-pins.sh
. "${HERE}/rust-core-pins.sh"

FRAMEWORK="XrayRust.xcframework"
MODULE="XrayRust"
LIB="libxray_ffi.a"
DEVICE_TARGETS="aarch64-apple-ios"
SIMULATOR_TARGETS="aarch64-apple-ios-sim x86_64-apple-ios"
export IPHONEOS_DEPLOYMENT_TARGET="15.0"

for tool in git rustup xcrun xcodebuild lipo nm python3 /usr/libexec/PlistBuddy; do
    command -v "${tool}" >/dev/null 2>&1 || { echo "missing required command: ${tool}" >&2; exit 1; }
done

work="$(mktemp -d "${TMPDIR:-/tmp}/rust-core-ios.XXXXXX")"
trap 'rm -rf "${work}"' EXIT

src="$(bash "${HERE}/rust-core-checkout.sh" "${work}/src" | tail -n 1)"
for f in xray_ffi.h module.modulemap; do
    test -f "${src}/crates/xray-ffi/include/${f}" || { echo "the pin has no crates/xray-ffi/include/${f}" >&2; exit 1; }
done

echo "== toolchain ${XRAY_RUST_TOOLCHAIN} =="
# shellcheck disable=SC2086 # the target lists are space-separated words
rustup toolchain install "${XRAY_RUST_TOOLCHAIN}" --profile minimal --no-self-update
# shellcheck disable=SC2086
rustup target add --toolchain "${XRAY_RUST_TOOLCHAIN}" ${DEVICE_TARGETS} ${SIMULATOR_TARGETS}
rustc_v="$(rustc "+${XRAY_RUST_TOOLCHAIN}" -V)"
xcode_v="$(xcodebuild -version | tr '\n' ' ' | sed 's/ *$//')"
sdk_v="$(xcrun --sdk iphoneos --show-sdk-version)"
echo "${rustc_v}; ${xcode_v}; iphoneos SDK ${sdk_v}; minimum iOS ${IPHONEOS_DEPLOYMENT_TARGET}"

echo "== [profile.release] at ${XRAY_RUST_COMMIT:0:12} =="
profile="$(awk '/^\[profile\.release\]/{on=1; next} /^\[/{on=0} on && NF' "${src}/Cargo.toml" | tr '\n' ';' | sed 's/;$//')"
echo "${profile}"

export CARGO_TARGET_DIR="${work}/target"
export CARGO_TERM_COLOR=never CARGO_INCREMENTAL=0 CARGO_NET_RETRY=5

# One target at a time, as upstream builds them: three thin-LTO links at once
# would not fit a 7 GB runner.
targets_record=""
for target in ${DEVICE_TARGETS} ${SIMULATOR_TARGETS}; do
    echo "== cargo build --locked --release -p xray-ffi --target ${target} =="
    start="$(date +%s)"
    (cd "${src}" && cargo "+${XRAY_RUST_TOOLCHAIN}" build --locked --release -p xray-ffi --target "${target}")
    seconds=$(( $(date +%s) - start ))
    built="${CARGO_TARGET_DIR}/${target}/release/${LIB}"
    test -f "${built}" || { echo "cargo left no ${built}" >&2; exit 1; }
    bytes="$(wc -c < "${built}" | tr -d ' ')"
    echo "${target}: ${LIB} ${bytes} bytes in ${seconds} s"
    targets_record="${targets_record}${target} ${bytes} ${seconds}
"
done

stage="${work}/stage"
mkdir -p "${stage}/device" "${stage}/simulator" "${stage}/include/${MODULE}"
# shellcheck disable=SC2086
cp "${CARGO_TARGET_DIR}/${DEVICE_TARGETS}/release/${LIB}" "${stage}/device/${LIB}"
sim_libs=""
for target in ${SIMULATOR_TARGETS}; do
    sim_libs="${sim_libs} ${CARGO_TARGET_DIR}/${target}/release/${LIB}"
done
# shellcheck disable=SC2086
lipo -create ${sim_libs} -output "${stage}/simulator/${LIB}"
cp "${src}"/crates/xray-ffi/include/* "${stage}/include/${MODULE}/"

rm -rf "${OUT:?}/${FRAMEWORK}"
xcodebuild -create-xcframework \
    -library "${stage}/device/${LIB}" -headers "${stage}/include" \
    -library "${stage}/simulator/${LIB}" -headers "${stage}/include" \
    -output "${OUT}/${FRAMEWORK}"

echo "== checking ${FRAMEWORK} =="
fw="${OUT}/${FRAMEWORK}"
# slice directory, the architectures it must hold, the platform variant
# Info.plist must give it ("" for the device)
check_slice() {
    local dir="$1" want_archs="$2" archs sym
    test -f "${fw}/${dir}/${LIB}" || { echo "no ${dir}/${LIB}" >&2; exit 1; }
    for f in xray_ffi.h module.modulemap; do
        cmp -s "${fw}/${dir}/Headers/${MODULE}/${f}" "${src}/crates/xray-ffi/include/${f}" \
            || { echo "${dir}/Headers/${MODULE}/${f} differs from the pin's crates/xray-ffi/include/${f}" >&2; exit 1; }
    done
    archs="$(lipo -archs "${fw}/${dir}/${LIB}" | tr ' ' '\n' | sort | tr '\n' ' ' | sed 's/ *$//')"
    [ "${archs}" = "${want_archs}" ] || { echo "${dir}: architectures '${archs}', want '${want_archs}'" >&2; exit 1; }
    # The entry points the Swift glue calls first; a library without them
    # would only fail at the extension's link.
    nm -g -U -j "${fw}/${dir}/${LIB}" > "${work}/symbols" 2>/dev/null || true
    for sym in _xray_core_new _xray_core_load_config_json _xray_core_start _xray_core_stop _xray_core_free; do
        grep -qx "${sym}" "${work}/symbols" || { echo "${dir}/${LIB} defines no ${sym}" >&2; exit 1; }
    done
}
check_slice ios-arm64 "arm64"
check_slice ios-arm64_x86_64-simulator "arm64 x86_64"
plist="${fw}/Info.plist"
if /usr/libexec/PlistBuddy -c "Print :AvailableLibraries:2" "${plist}" >/dev/null 2>&1; then
    echo "${FRAMEWORK} has more than the two iOS slices" >&2
    exit 1
fi

echo "== sizes =="
slices_record=""
for dir in ios-arm64 ios-arm64_x86_64-simulator; do
    bytes="$(wc -c < "${fw}/${dir}/${LIB}" | tr -d ' ')"
    archs="$(lipo -archs "${fw}/${dir}/${LIB}")"
    printf '%-28s %12s bytes  %6.1f MiB  (%s)\n' "${dir}" "${bytes}" "$(echo "${bytes}" | awk '{print $1/1048576}')" "${archs}"
    slices_record="${slices_record}${dir} ${bytes} ${archs}
"
done
printf '%-28s %12s KiB on disk\n' "${FRAMEWORK}" "$(du -sk "${fw}" | cut -f1)"

# Values go through the environment: the profile text carries quotes.
B_REPO="${XRAY_RUST_REPO}" B_COMMIT="${XRAY_RUST_COMMIT}" B_CHAN="${XRAY_RUST_TOOLCHAIN}" \
B_RUSTC="${rustc_v}" B_XCODE="${xcode_v}" B_SDK="${sdk_v}" B_MIN="${IPHONEOS_DEPLOYMENT_TARGET}" \
B_PROFILE="${profile}" B_TARGETS="${targets_record}" B_SLICES="${slices_record}" \
B_RUN="${GITHUB_RUN_ID:-local}" B_INCLUDE="${src}/crates/xray-ffi/include" \
python3 -I -c '
import hashlib, json, os, sys
e = os.environ
def sha(p):
    return hashlib.sha256(open(p, "rb").read()).hexdigest()
targets = {}
for line in e["B_TARGETS"].splitlines():
    t, b, s = line.split()
    targets[t] = {"bytes": int(b), "seconds": int(s)}
slices = {}
for line in e["B_SLICES"].splitlines():
    d, b, *archs = line.split()
    slices[d] = {"bytes": int(b), "archs": archs}
json.dump({
    "repo": e["B_REPO"], "commit": e["B_COMMIT"], "toolchain": e["B_CHAN"],
    "rustc": e["B_RUSTC"], "xcode": e["B_XCODE"], "iphoneos_sdk": e["B_SDK"],
    "minimum_ios": e["B_MIN"],
    "cargo": "cargo build --locked --release -p xray-ffi --target <target>",
    "profile_release": e["B_PROFILE"],
    "targets": targets, "slices": slices,
    "header_sha256": sha(os.path.join(e["B_INCLUDE"], "xray_ffi.h")),
    "modulemap_sha256": sha(os.path.join(e["B_INCLUDE"], "module.modulemap")),
    "built_by_run": e["B_RUN"],
}, open(sys.argv[1], "w"), indent=1)
' "${OUT}/rust-core-ios.build.json"
cat "${OUT}/rust-core-ios.build.json"
echo "== ready: ${fw} =="
