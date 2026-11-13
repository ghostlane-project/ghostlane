#!/usr/bin/env bash
# engine-build.sh <engine-src> <bin-dir>
#
# From an engine checkout (engine-src.sh), builds into <bin-dir>:
#
#   olcrtc            the desktop app's client, as desktopApp/build.gradle.kts
#                     registerOlcRtcBuildTask builds it: CGO_ENABLED=0,
#                     go build -trimpath -ldflags "-s -w" ./cmd/olcrtc, no tags;
#   olcrtc-e2e.test   the engine's own end-to-end package compiled as a test
#                     binary with -tags olcrtc_lean, the phones' tag
#                     (sharedUI/olcrtc-bin/build.gradle.kts gomobile bind), so
#                     TestLocalThroughputSoak runs a client and a server over
#                     the in-memory provider without any meeting service.
#
# bash 3.2 safe (macOS runners).
set -euo pipefail

src="${1:?usage: engine-build.sh <engine-src> <bin-dir>}"
out="${2:?usage: engine-build.sh <engine-src> <bin-dir>}"
mkdir -p "${out}"
out="$(cd "${out}" && pwd)"
cd "${src}"

echo "== olcrtc (desktop recipe) =="
CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" -o "${out}/olcrtc" ./cmd/olcrtc

echo "== olcrtc-e2e.test (-tags olcrtc_lean) =="
go test -c -tags olcrtc_lean -o "${out}/olcrtc-e2e.test" ./internal/e2e

ls -l "${out}/olcrtc" "${out}/olcrtc-e2e.test"
