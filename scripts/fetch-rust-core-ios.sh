#!/usr/bin/env bash
# Puts XrayRust.xcframework, the Rust core for the tunnel extension, where the
# Xcode build expects it, and only the one build rust-core-pins.sh pins.
#
# The shape of fetch-hev-ios.sh, plus a checksum: the tag, the asset and the
# URL are derived in rust-core-pins.sh, and the downloaded zip must have the
# sha256 pinned there (RUST_CORE_IOS_SHA256), or nothing is unpacked.
#
#   1. the destination itself  - nothing to do when its stamp names the pin
#   2. the local cache         - the verified zip, checked again and unpacked;
#                                survives `clean` (the destination sits inside
#                                sharedUI/build, which Gradle sweeps)
#   3. the release asset       - a download, checked, then cached
#
# The stamp beside the framework holds the tag and the sha256, so a pin bump
# replaces the framework instead of trusting whatever is there.
#
# A framework built on this Mac (build-rust-core-ios.sh <destination-dir>)
# is stamped "<tag> local <commit>". It is used only when asked for with
# RUST_CORE_IOS_ALLOW_LOCAL=1, and only while its tag is the pin's: that is
# the way to build the extension before anything is published, or to try a
# packaging change. Without the variable it is replaced by the published one
# (or, with nothing published yet, left alone and refused).
#
# Usage: fetch-rust-core-ios.sh <destination-dir>
set -euo pipefail

DEST="${1:?usage: fetch-rust-core-ios.sh <destination-dir>}"
# shellcheck source=scripts/rust-core-pins.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/rust-core-pins.sh"
TAG="${RUST_CORE_IOS_TAG}"
ASSET="${RUST_CORE_IOS_ASSET}"
WANT="${RUST_CORE_IOS_SHA256}"
FRAMEWORK="XrayRust.xcframework"
CACHE="${RUST_CORE_CACHE_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/olcbox/rust-core}/${TAG}"
STAMP="${DEST}/${FRAMEWORK}.tag"
LOCAL_STAMP="${TAG} local ${XRAY_RUST_COMMIT}"
PUBLISH_HOW="gh workflow run rust-core.yml --repo ghostlane-project/ghostlane --ref <this branch> -f publish=true"
BUILD_HOW="bash scripts/build-rust-core-ios.sh \"${DEST}\" (Xcode and rustup, about 15 minutes), then build with RUST_CORE_IOS_ALLOW_LOCAL=1"

present=""
if [ -d "${DEST}/${FRAMEWORK}/ios-arm64" ]; then
    present="$(cat "${STAMP}" 2>/dev/null || echo "an unstamped framework")"
fi

if [ -n "${present}" ] && [ "${present}" = "${LOCAL_STAMP}" ]; then
    if [ "${RUST_CORE_IOS_ALLOW_LOCAL:-}" = "1" ]; then
        echo "${FRAMEWORK} built here for ${TAG} (RUST_CORE_IOS_ALLOW_LOCAL=1): using it, not the published one"
        exit 0
    fi
    echo "== ${DEST} holds a ${FRAMEWORK} built here for ${TAG}; it is used only with RUST_CORE_IOS_ALLOW_LOCAL=1 =="
fi

if [ -z "${WANT}" ]; then
    echo "rust-core-pins.sh pins no sha256 for ${TAG}: nothing has been published for xray-rust ${XRAY_RUST_COMMIT:0:12} build ${RUST_CORE_IOS_BUILD} yet." >&2
    echo "Publish it (${PUBLISH_HOW}) and paste the sha256 its summary prints;" >&2
    echo "or build it here: ${BUILD_HOW}." >&2
    exit 1
fi
case "${WANT}" in
    *[!0-9a-f]*) echo "RUST_CORE_IOS_SHA256 '${WANT}' is not a lowercase hex sha256" >&2; exit 1 ;;
esac
[ "${#WANT}" -eq 64 ] || { echo "RUST_CORE_IOS_SHA256 '${WANT}' is not 64 hex digits" >&2; exit 1; }
want_stamp="${TAG} ${WANT}"

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1
}

# check_zip <zip>: the pinned bytes, or a refusal.
check_zip() {
    local got
    got="$(sha256_of "$1")"
    if [ "${got}" != "${WANT}" ]; then
        echo "$1: sha256 ${got}, rust-core-pins.sh pins ${WANT}; refusing it" >&2
        return 1
    fi
}

if [ -n "${present}" ]; then
    if [ "${present}" = "${want_stamp}" ]; then
        echo "${FRAMEWORK} already present in ${DEST} (${TAG})"
        exit 0
    fi
    echo "== ${DEST} holds ${present}, this build wants ${want_stamp}: replacing =="
fi
# The build record goes with the framework it describes (a local build leaves
# it as rust-core-ios.build.json).
rm -rf "${DEST:?}/${FRAMEWORK}" "${STAMP}" "${DEST}/${FRAMEWORK}.build.json" "${DEST}/rust-core-ios.build.json"
mkdir -p "${DEST}"

work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT
zip="${work}/${ASSET}"

if [ -f "${CACHE}/${ASSET}" ] && check_zip "${CACHE}/${ASSET}"; then
    echo "== ${ASSET} from ${CACHE} =="
    command cp -f "${CACHE}/${ASSET}" "${zip}"
else
    echo "== fetching ${ASSET} from ${TAG} =="
    curl -fsSL --retry 3 -o "${zip}" "${RUST_CORE_IOS_URL}" || {
        echo "no ${ASSET} at ${RUST_CORE_IOS_URL}, and nothing usable cached in ${CACHE}." >&2
        echo "Publish it (${PUBLISH_HOW}), or build it here: ${BUILD_HOW}." >&2
        exit 1
    }
    check_zip "${zip}"
    mkdir -p "${CACHE}"
    command cp -f "${zip}" "${CACHE}/${ASSET}"
fi

unzip -q -o "${zip}" -d "${work}/unpacked"
for slice in ios-arm64 ios-arm64_x86_64-simulator; do
    test -f "${work}/unpacked/${FRAMEWORK}/${slice}/libxray_ffi.a" \
        || { echo "${ASSET} has no ${slice} slice" >&2; exit 1; }
    for f in xray_ffi.h module.modulemap; do
        test -f "${work}/unpacked/${FRAMEWORK}/${slice}/Headers/XrayRust/${f}" \
            || { echo "${ASSET}'s ${slice} carries no Headers/XrayRust/${f}; Swift could not import it" >&2; exit 1; }
    done
done
mv "${work}/unpacked/${FRAMEWORK}" "${DEST}/${FRAMEWORK}"
if [ -f "${work}/unpacked/rust-core-ios.build.json" ]; then
    mv "${work}/unpacked/rust-core-ios.build.json" "${DEST}/${FRAMEWORK}.build.json"
fi
printf '%s\n' "${want_stamp}" > "${STAMP}"

echo "== ready: ${DEST}/${FRAMEWORK} (${TAG}) =="
