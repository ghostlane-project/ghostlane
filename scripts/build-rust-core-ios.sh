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
# <output-dir> receives XrayRust.xcframework, rust-core-ios.build.json (the
# build record: commit, toolchain, Xcode, each target's and each slice's size
# and time, the system libraries a link needs) and XrayRust.xcframework.tag,
# "<tag> local <commit>", which fetch-rust-core-ios.sh accepts only with
# RUST_CORE_IOS_ALLOW_LOCAL=1.
#
# Each slice is checked by a link, not a symbol listing: a C caller of the
# five entry points the Swift glue calls first is compiled against the slice's
# header and linked against its library, for each architecture.
#
# The checkout and Cargo's target directory live in a temporary directory
# that is removed at exit (several GB for three targets).
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
rustup toolchain install "${XRAY_RUST_TOOLCHAIN}" --profile minimal --no-self-update
# shellcheck disable=SC2086 # the target lists are space-separated words
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

# One target at a time, as upstream builds them: three thin-LTO links of the
# whole core at once on a 7 GB runner would risk the memory for little time.
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

rm -rf "${OUT:?}/${FRAMEWORK}" "${OUT}/${FRAMEWORK}.tag"
xcodebuild -create-xcframework \
    -library "${stage}/device/${LIB}" -headers "${stage}/include" \
    -library "${stage}/simulator/${LIB}" -headers "${stage}/include" \
    -output "${OUT}/${FRAMEWORK}"

echo "== checking ${FRAMEWORK} =="
fw="${OUT}/${FRAMEWORK}"
# check_slice <slice directory> <the architectures it must hold, sorted>
check_slice() {
    local dir="$1" want_archs="$2" archs
    test -f "${fw}/${dir}/${LIB}" || { echo "no ${dir}/${LIB}" >&2; exit 1; }
    for f in xray_ffi.h module.modulemap; do
        cmp -s "${fw}/${dir}/Headers/${MODULE}/${f}" "${src}/crates/xray-ffi/include/${f}" \
            || { echo "${dir}/Headers/${MODULE}/${f} differs from the pin's crates/xray-ffi/include/${f}" >&2; exit 1; }
    done
    archs="$(lipo -archs "${fw}/${dir}/${LIB}" | tr ' ' '\n' | sort | tr '\n' ' ' | sed 's/ *$//')"
    [ "${archs}" = "${want_archs}" ] || { echo "${dir}: architectures '${archs}', want '${want_archs}'" >&2; exit 1; }
    # What nm sees, for the log only: rustc's objects carry LLVM bitcode that
    # Xcode's nm may try (and fail) to read instead of the symbol table, so
    # --no-llvm-bc, and its errors stay in the log. The link below decides.
    if nm -g -U -j --no-llvm-bc "${fw}/${dir}/${LIB}" > "${work}/symbols" 2> "${work}/nm.err"; then
        echo "${dir}: nm lists $(grep -c '^_xray_core_' "${work}/symbols" || true) _xray_core_* symbols"
    else
        echo "${dir}: nm failed; its first errors:"
        head -n 5 "${work}/nm.err"
    fi
}

# link_slice <slice directory> <sdk> <clang target>...: a C program calling
# the entry points the Swift glue calls first, compiled against the slice's
# header and linked against its library, as the extension's link will be. A
# library without one of them fails here, not on the phone's build.
# First with nothing but the library; when that fails on system symbols, again
# with the system libraries a Rust static library may need, and the build
# record says so (link_needs).
link_needs=""
cat > "${work}/link-check.c" <<'C'
#include <stdint.h>
#include <XrayRust/xray_ffi.h>
int main(void) {
    volatile uintptr_t entry[] = {
        (uintptr_t)&xray_core_new,
        (uintptr_t)&xray_core_load_config_json,
        (uintptr_t)&xray_core_start,
        (uintptr_t)&xray_core_stop,
        (uintptr_t)&xray_core_free,
    };
    return entry[0] == 0;
}
C
SYSTEM_LIBS="-framework CoreFoundation -framework Security -framework SystemConfiguration -liconv -lresolv"
link_slice() {
    local dir="$1" sdk="$2" target out
    shift 2
    for target in "$@"; do
        out="${work}/link-check-${target}"
        # shellcheck disable=SC2086 # SYSTEM_LIBS is a list of linker words
        if xcrun -sdk "${sdk}" clang -target "${target}" \
            -I "${fw}/${dir}/Headers" "${work}/link-check.c" \
            -L "${fw}/${dir}" -lxray_ffi -o "${out}" 2> "${work}/link.err"; then
            echo "${dir}: links for ${target} with the library alone"
        elif xcrun -sdk "${sdk}" clang -target "${target}" \
            -I "${fw}/${dir}/Headers" "${work}/link-check.c" \
            -L "${fw}/${dir}" -lxray_ffi ${SYSTEM_LIBS} -o "${out}" 2> "${work}/link2.err"; then
            echo "${dir}: links for ${target} only with ${SYSTEM_LIBS}; the library alone left:"
            head -n 20 "${work}/link.err"
            link_needs="${SYSTEM_LIBS}"
        else
            echo "${dir}: a C caller of the five entry points does not link for ${target}:" >&2
            head -n 40 "${work}/link2.err" >&2
            exit 1
        fi
        lipo -archs "${out}"
    done
}
check_slice ios-arm64 "arm64"
check_slice ios-arm64_x86_64-simulator "arm64 x86_64"
link_slice ios-arm64 iphoneos "arm64-apple-ios${IPHONEOS_DEPLOYMENT_TARGET}"
link_slice ios-arm64_x86_64-simulator iphonesimulator \
    "arm64-apple-ios${IPHONEOS_DEPLOYMENT_TARGET}-simulator" "x86_64-apple-ios${IPHONEOS_DEPLOYMENT_TARGET}-simulator"
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
B_TAG="${RUST_CORE_IOS_TAG}" B_TLS="${SHAPED_RUSTLS_REPO}" B_LINK="${link_needs}" \
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
    "tag": e["B_TAG"],
    "repo": e["B_REPO"], "commit": e["B_COMMIT"], "toolchain": e["B_CHAN"],
    "rustls_repo": e["B_TLS"],
    "rustc": e["B_RUSTC"], "xcode": e["B_XCODE"], "iphoneos_sdk": e["B_SDK"],
    "minimum_ios": e["B_MIN"],
    "cargo": "cargo build --locked --release -p xray-ffi --target <target>",
    "profile_release": e["B_PROFILE"],
    "targets": targets, "slices": slices,
    "link_needs": e["B_LINK"].split(),
    "header_sha256": sha(os.path.join(e["B_INCLUDE"], "xray_ffi.h")),
    "modulemap_sha256": sha(os.path.join(e["B_INCLUDE"], "module.modulemap")),
    "built_by_run": e["B_RUN"],
}, open(sys.argv[1], "w"), indent=1)
' "${OUT}/rust-core-ios.build.json"
cat "${OUT}/rust-core-ios.build.json"
# The stamp fetch-rust-core-ios.sh reads: a framework built here, for this
# tag, from this commit. fetch uses it only with RUST_CORE_IOS_ALLOW_LOCAL=1.
printf '%s local %s\n' "${RUST_CORE_IOS_TAG}" "${XRAY_RUST_COMMIT}" > "${OUT}/${FRAMEWORK}.tag"
echo "== ready: ${fw} (${RUST_CORE_IOS_TAG}, built here) =="
