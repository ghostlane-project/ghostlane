#!/usr/bin/env bash
# Puts HevSocks5Tunnel.xcframework where the Xcode extension target expects it.
#
# The same shape as fetch-cores-ios.sh, for the same reasons: the destination
# is inside sharedUI/build, which Gradle sweeps, so a copy is kept in the
# user's cache; the destination is stamped with the tag it holds, so a pin
# bump replaces the framework instead of trusting whatever is there; and the
# tag is derived from hev-pins.sh, never spelled here.
#
#   1. the destination itself   — nothing to do
#   2. the local cache          — a copy; survives `clean`
#   3. the release tag          — a download
set -euo pipefail

DEST="${1:?usage: fetch-hev-ios.sh <destination-dir>}"
# shellcheck source=scripts/hev-pins.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/hev-pins.sh"
TAG="${HEV_RELEASE_TAG:-${HEV_TAG}}"
ASSET="HevSocks5Tunnel-ios.zip"
URL="https://github.com/romanpodpriatov/olcbox/releases/download/${TAG}/${ASSET}"
CACHE="${HEV_CACHE_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/olcbox/hev}/${TAG}"
STAMP="${DEST}/HevSocks5Tunnel.xcframework.tag"

if [ -d "${DEST}/HevSocks5Tunnel.xcframework/ios-arm64" ]; then
    present="$(cat "${STAMP}" 2>/dev/null || echo "an unstamped framework")"
    if [ "${present}" = "${TAG}" ]; then
        echo "HevSocks5Tunnel.xcframework already present in ${DEST} (${TAG})"
        exit 0
    fi
    echo "== ${DEST} holds ${present}, this build wants ${TAG} — replacing =="
    rm -rf "${DEST}/HevSocks5Tunnel.xcframework" "${STAMP}"
fi

mkdir -p "${DEST}"

if [ -d "${CACHE}/HevSocks5Tunnel.xcframework/ios-arm64" ]; then
    echo "== restoring HevSocks5Tunnel.xcframework from ${CACHE} =="
    rm -rf "${DEST}/HevSocks5Tunnel.xcframework"
    cp -R "${CACHE}/HevSocks5Tunnel.xcframework" "${DEST}/HevSocks5Tunnel.xcframework"
    printf '%s\n' "${TAG}" > "${STAMP}"
    echo "== ready: ${DEST}/HevSocks5Tunnel.xcframework (${TAG}) =="
    exit 0
fi

work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

echo "== fetching ${ASSET} from ${TAG} =="
curl -fsSL -o "${work}/${ASSET}" "${URL}" || {
    echo "no ${ASSET} published at tag ${TAG}, and nothing cached in ${CACHE}."
    echo "Build it yourself (needs Xcode, a few minutes):"
    echo "  git clone --recursive https://github.com/heiher/hev-socks5-tunnel.git hev && (cd hev && git checkout --recurse-submodules ${HEV_COMMIT})"
    echo "  bash scripts/build-hev-ios.sh hev \"${DEST}\""
    echo "or run the 'hev-socks5-tunnel' workflow to build and publish it once."
    exit 1
}
unzip -q -o "${work}/${ASSET}" -d "${DEST}"

for slice in ios-arm64 ios-arm64_x86_64-simulator macos-arm64_x86_64; do
    test -f "${DEST}/HevSocks5Tunnel.xcframework/${slice}/libhev-socks5-tunnel.a" \
        || { echo "downloaded archive has no ${slice} slice"; exit 1; }
done
test -f "${DEST}/HevSocks5Tunnel.xcframework/ios-arm64/Headers/HevSocks5Tunnel/module.modulemap" \
    || { echo "downloaded archive carries no module map; Swift could not import it"; exit 1; }

mkdir -p "${CACHE}"
rm -rf "${CACHE}/HevSocks5Tunnel.xcframework"
cp -R "${DEST}/HevSocks5Tunnel.xcframework" "${CACHE}/HevSocks5Tunnel.xcframework"
printf '%s\n' "${TAG}" > "${STAMP}"

echo "== ready: ${DEST}/HevSocks5Tunnel.xcframework =="
