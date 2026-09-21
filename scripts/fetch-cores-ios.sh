#!/usr/bin/env bash
# Puts Cores.xcframework where the Xcode extension target expects it.
#
# One framework holding sing-box, Xray and olcRTC — they cannot be linked apart,
# see build-cores-ios.sh. Building it needs Go, gomobile and a macOS machine, so
# this looks for one already built, in the order that costs least:
#
#   1. the destination itself   — nothing to do
#   2. the local cache          — a copy; survives `clean`
#   3. the release tag          — a download
#
# The cache exists because the destination is inside `sharedUI/build`, which is
# Gradle's to delete and which Gradle does delete: it is the declared output of
# fetchCoresIosXcframework, and a stale-output sweep once removed a framework
# that had taken twenty minutes to build and could not be re-downloaded, because
# the release for that version pair did not exist yet. A locally built framework
# now lands in the cache too, so losing the build directory costs a copy.
#
# The tag names the versions it was built from, so a version bump is a new tag
# rather than a silent swap — and a new cache entry rather than a stale hit.
# That only holds while the tag is *derived*: it was a literal here once, olcRTC
# was re-pinned without it, and this script went on restoring a framework built
# from the previous revision. Nothing looked wrong, because step 1 below finds
# the freshly built framework in the destination and returns before the stale
# cache entry is ever consulted — until Gradle sweeps the destination, which is
# exactly what it is documented above to do.
set -euo pipefail

DEST="${1:?usage: fetch-cores-ios.sh <destination-dir>}"
# shellcheck source=scripts/cores-pins.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/cores-pins.sh"
TAG="${CORES_RELEASE_TAG:-${CORES_TAG}}"
ASSET="Cores-ios.zip"
URL="https://github.com/ghostlane-project/ghostlane/releases/download/${TAG}/${ASSET}"
# Same default as build-cores-ios.sh writes to. Keyed by tag: two version pairs
# never share an entry.
CACHE="${CORES_CACHE_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/olcbox/cores}/${TAG}"
# The destination says nothing about itself — a framework is a directory of
# stripped binaries — so it is made to. Without this the check below is "is
# there *a* framework here", which is how an app was built and shipped to a
# device against an olcRTC revision two pins old while every version in the
# tree said otherwise, and how a whole day went into explaining behaviour that
# belonged to code the build did not contain.
STAMP="${DEST}/Cores.xcframework.tag"

if [ -d "${DEST}/Cores.xcframework/ios-arm64" ]; then
    present="$(cat "${STAMP}" 2>/dev/null || echo "an unstamped framework")"
    if [ "${present}" = "${TAG}" ]; then
        echo "Cores.xcframework already present in ${DEST} (${TAG})"
        exit 0
    fi
    echo "== ${DEST} holds ${present}, this build wants ${TAG} — replacing =="
    rm -rf "${DEST}/Cores.xcframework" "${STAMP}"
fi

mkdir -p "${DEST}"

if [ -d "${CACHE}/Cores.xcframework/ios-arm64" ]; then
    echo "== restoring Cores.xcframework from ${CACHE} =="
    rm -rf "${DEST}/Cores.xcframework"
    cp -R "${CACHE}/Cores.xcframework" "${DEST}/Cores.xcframework"
    printf '%s\n' "${TAG}" > "${STAMP}"
    echo "== ready: ${DEST}/Cores.xcframework (${TAG}) =="
    exit 0
fi

work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

echo "== fetching ${ASSET} from ${TAG} =="
# A missing release is the expected failure right after a version bump, and
# `curl -f` alone reports it as a bare exit 22 from somewhere inside an Xcode
# build phase. Say which tag is missing and what produces it.
curl -fsSL -o "${work}/${ASSET}" "${URL}" || {
    echo "no ${ASSET} published at tag ${TAG}, and nothing cached in ${CACHE}."
    echo "Build it yourself (needs Go and Xcode, ~20 min):"
    echo "  bash scripts/build-cores-ios.sh \"${DEST}\""
    echo "or run the 'iOS Frameworks' workflow to build and publish it once."
    exit 1
}
unzip -q -o "${work}/${ASSET}" -d "${DEST}"

test -d "${DEST}/Cores.xcframework/ios-arm64" \
    || { echo "downloaded archive has no ios-arm64 slice"; exit 1; }
# Checked here as well as at build time: a release published before the macOS
# slice existed can still be fetched under a tag that promises it, and the next
# thing to notice would be a linker looking for a Mac binary that is not there.
test -n "$(find "${DEST}/Cores.xcframework" -maxdepth 1 -type d -name 'macos*' -print -quit)" \
    || { echo "downloaded archive has no macOS slice — it predates ${TAG}"; exit 1; }
# And the simulator slice, for the same reason and with the same failure: an
# archive built before it existed still downloads under a tag that promises it,
# and the next thing anyone sees is Xcode reporting that the module `Cores`
# cannot be resolved — which names neither the platform nor the tag.
test -n "$(find "${DEST}/Cores.xcframework" -maxdepth 1 -type d -name 'ios-*simulator*' -print -quit)" \
    || { echo "downloaded archive has no simulator slice — it predates ${TAG}"; exit 1; }

# Cache what was just downloaded, so the next sweep of the build directory is a
# copy rather than a download.
mkdir -p "${CACHE}"
rm -rf "${CACHE}/Cores.xcframework"
cp -R "${DEST}/Cores.xcframework" "${CACHE}/Cores.xcframework"
printf '%s\n' "${TAG}" > "${STAMP}"

echo "== ready: ${DEST}/Cores.xcframework =="
