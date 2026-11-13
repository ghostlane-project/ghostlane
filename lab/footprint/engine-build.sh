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
# The phones' engine is mobile.New(), whose first act is
# client.UseConstrainedBuffers() (mobile/runtime.go at the pin): smux 4 MiB /
# 512 KiB instead of 32 MiB / 4 MiB, vp8channel queues 1024 / 1024, the small
# NACK responder. The e2e package never turns that profile on, so this adds
# one test file to the checkout (never to the engine's repository) that turns
# it on when OLCRTC_LAB_CONSTRAINED=1 and says so in the log. The profile is
# process-wide: in the soak it holds for both peers.
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

echo "== olcrtc-e2e.test (-tags olcrtc_lean), with the lab's profile switch =="
grep -q 'client.UseConstrainedBuffers()' mobile/runtime.go \
  || { echo "mobile/runtime.go no longer calls client.UseConstrainedBuffers(); re-read the phone's profile" >&2; exit 1; }
cat > internal/e2e/zz_lab_profile_test.go <<'EOF'
package e2e

// Added by the Ghostlane footprint lab (lab/footprint/engine-build.sh); not
// part of the engine. OLCRTC_LAB_CONSTRAINED=1 turns on the profile
// mobile.New() turns on for every phone, before any session starts.

import (
	"fmt"
	"os"

	"github.com/openlibrecommunity/olcrtc/pkg/olcrtc/client"
)

func init() {
	if os.Getenv("OLCRTC_LAB_CONSTRAINED") == "1" {
		client.UseConstrainedBuffers()
		fmt.Fprintln(os.Stderr, "[lab] constrained buffers on (client.UseConstrainedBuffers, as mobile.New)")
	}
}
EOF
go test -c -tags olcrtc_lean -o "${out}/olcrtc-e2e.test" ./internal/e2e
rm -f internal/e2e/zz_lab_profile_test.go

ls -l "${out}/olcrtc" "${out}/olcrtc-e2e.test"
