#!/usr/bin/env bash
# Cores.xcframework — sing-box, Xray and olcRTC, bound together in one framework.
#
# They have to share a framework, not merely a binary. Each `gomobile bind`
# emits its own copy of the cgo bootstrap and the gomobile seq layer, so linking
# two independently-bound frameworks into one extension fails at link time:
#
#   duplicate symbol '_crosscall2'          duplicate symbol '_IncGoRef'
#   duplicate symbol '__cgo_topofstack'     duplicate symbol '_DestroyRef'
#   duplicate symbol '_x_cgo_bindm'         duplicate symbol '_proxy_error_Error'
#   ... 49 duplicate symbols
#
# There is no linker flag for this, and hiding one copy would be worse than the
# error: two Go runtimes in one process means two schedulers and two sets of
# signal handlers. One bind over all three packages gives one runtime and one
# copy of the glue, which is the only correct shape.
#
# The class names do not change — prefixes come from the Go package names, so
# `LibboxBoxService`, `LibXrayInvoke` and `MobileStartWithTransport` are still
# exactly that. Only the module to import changes, to `Cores`.
#
# The build tags are load-bearing. Without with_gvisor there is no userspace
# stack for the tun, without with_quic no Hysteria2, without with_utls no
# Reality fingerprinting. olcrtc_lean leaves two things out of the olcRTC
# engine that no phone uses, the videochannel transport (a QR codec) and the
# livekit provider (protobuf and CEL initialisers): measured on the phone
# profile, 40.3 → 29.8 MB idle and 47.0 → 36.2 MB at a speed test's upload
# peak, in an extension the system kills at about 45. The app never offers
# either, and refuses a videochannel link on import.
set -euo pipefail

# The pinned trio this was verified against, so a laptop run and a CI run
# produce the same framework. It lives in one file because the cache key below,
# the release tag the workflow publishes and the lookup fetch-cores-ios.sh does
# all have to name the same thing — they did not, once, and the framework that
# got linked was not the one the pins described.
# shellcheck source=scripts/cores-pins.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/cores-pins.sh"
# Our own engine, pinned to a published revision rather than the working copy the
# app's own OlcRtcMobile is built from (OLCRTC_REPO): the extension is built on
# machines that do not have that checkout. A protocol change in olcrtc therefore
# has to be pushed before it reaches the tunnel.
#
# Our fork never rewrote its own module path, so it still declares itself as
# OLCRTC_MODULE below while living at OLCRTC_FORK. Go refuses to require it under
# the name it is fetched from — that is what `module declares its path as` means
# — and the fix is the ordinary fork shape: require the declared path, replace it
# with the fork. It works precisely *because* the fork kept the upstream path.
OLCRTC_MODULE="github.com/openlibrecommunity/olcrtc"
OLCRTC_FORK="${OLCRTC_FORK:-github.com/romanpodpriatov/olcrtc}"
# Pinned rather than @latest: gobind generates code against the seq package of
# its own version, so the tool and the module dependency below must be the same
# version or the generated bindings compile against the wrong API.
GOMOBILE_VERSION="${GOMOBILE_VERSION:-v0.1.13}"
OUT="${1:?usage: build-cores-ios.sh <output-dir>}"

mkdir -p "$OUT"
# Absolute from here on. The build runs from the temporary directory created
# below, and a destination given as a relative path — which is how anyone would
# type it — resolves against *that* from the `cd` onwards. The framework then
# lands in a tree the exit trap deletes, while the cache copy succeeds because
# its path is absolute, so the run ends with "cached in ..." and looks entirely
# successful with nothing at the destination.
OUT="$(cd "$OUT" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

cd "$work"
mkdir cores && cd cores

# The toolchain first, checked the way the olcrtc revision is checked below and
# for the same reason: a framework built by the wrong Go is indistinguishable
# from the right one until Xcode fails to link it. Major.minor rather than the
# exact patch on a laptop; the workflow pins the exact one.
go_have="$(go env GOVERSION)"
case "${go_have}" in
  "go${GO_VERSION%.*}."*) ;;
  *)
    echo "Go ${go_have} on PATH, but scripts/cores-pins.sh pins ${GO_VERSION}."
    echo "Refusing to build: 1.27 emits an archive with an undefined http2 symbol that Xcode cannot link."
    exit 1
    ;;
esac

echo "== wrapper module: Go ${go_have}, sing-box v${SINGBOX_VERSION} + libXray ${LIBXRAY_VERSION} + olcrtc ${OLCRTC_VERSION} =="
# 1.26.3 is libXray's own floor, stated here rather than left to `go get` to
# raise, so the toolchain requirement is visible before anything downloads.
cat > go.mod <<EOF
module github.com/romanpodpriatov/olcbox-cores

go 1.26.3
EOF

# Imported blank: nothing here calls them, but they must be in the build list
# for gomobile to resolve the package paths passed to bind.
cat > cores.go <<'EOF'
// Package cores exists only to pull every engine into one module so they can
// be bound into a single framework. See build-cores-ios.sh for why.
package cores

import (
	_ "github.com/sagernet/sing-box/experimental/libbox"
	_ "github.com/xtls/libxray"
	_ "github.com/openlibrecommunity/olcrtc/mobile"
)
EOF

export GOFLAGS=-mod=mod
# olcrtc goes in by edit rather than by `go get`: the version lives on the
# replacement, and asking go to fetch the declared path would send it to the
# upstream repository instead of ours. The required version is a placeholder for
# exactly that reason — the replacement decides what is downloaded.
go mod edit \
  -require="${OLCRTC_MODULE}@v0.0.0-00010101000000-000000000000" \
  -replace="${OLCRTC_MODULE}=${OLCRTC_FORK}@${OLCRTC_VERSION}"

go get "github.com/sagernet/sing-box@v${SINGBOX_VERSION}"
go get "github.com/xtls/libxray@${LIBXRAY_VERSION}"
# gobind looks for the bind package through the module being built, so it has
# to be a dependency rather than merely installed.
go get "github.com/sagernet/gomobile/bind@${GOMOBILE_VERSION}"
# Never `go mod tidy` here: nothing imports bind, so tidy would drop it.

# Merging three dependency graphs is the one thing that can go wrong for reasons
# no one project would ever see alone — the cores share sagernet/sing, pion and
# the whole of golang.org/x, and minimal version selection hands all of them
# whichever copy is newest. Print what was chosen: when a build breaks after a
# version bump, this is the line that says why.
echo "== resolved =="
go list -m github.com/sagernet/sing-box github.com/xtls/libxray github.com/xtls/xray-core \
  "${OLCRTC_MODULE}" github.com/sagernet/sing github.com/pion/transport/v4 \
  golang.org/x/net golang.org/x/crypto

# Printed is not the same as checked, and olcrtc is the one that moves. A
# replacement that resolves to anything other than the pin yields a framework
# that looks entirely correct — three headers present, every API bound — and
# then caches under the pinned tag, where nothing afterwards can tell it apart.
# That is not hypothetical: a cache entry named for the revision carrying the
# UDP relay turned out to hold a build without it, and cost a day of explaining
# behaviour that belonged to code the binary did not contain.
resolved="$(go list -m -f '{{if .Replace}}{{.Replace.Version}}{{else}}{{.Version}}{{end}}' "${OLCRTC_MODULE}")"
if [ "${resolved}" != "${OLCRTC_VERSION}" ]; then
  echo "olcrtc resolved to ${resolved}, but the pin says ${OLCRTC_VERSION}."
  echo "Refusing to build: the result would be indistinguishable from the right one."
  exit 1
fi

# Compile every core against that merged graph before binding them. The bind is
# a 20+ minute step on a runner billed at 10x, and it starts by doing exactly
# this — so a conflict caught here is caught twenty minutes earlier, and with a
# plain Go error instead of gomobile's output.
echo "== pre-flight: do all three cores still compile together? =="
go build -tags "with_gvisor,with_quic,with_utls,with_clash_api,olcrtc_lean" ./...

echo "== gomobile ${GOMOBILE_VERSION} (sagernet fork) =="
# Into this build's own bin, never the shared ~/go/bin.
#
# sing-box needs the sagernet fork, but our olcrtc framework builds with
# UPSTREAM gomobile and resolves it from PATH (:sharedUI:buildOlcrtcIosXcframework
# runs plain `gomobile bind`). Installing the fork to GOPATH/bin overwrites that
# shared binary and breaks the olcrtc build for every later Xcode run — which is
# exactly what happened the first time this script ran on a real machine. A
# private GOBIN keeps the fork to the twenty minutes it is needed for.
export GOBIN="$work/bin"
# If this step fails to build, it is the fork's x/tools pin against a newer Go
# toolchain — the pairing hazard the separate libbox script warned about. The
# fix is a Go version this fork builds on, not a change to anything below.
go install "github.com/sagernet/gomobile/cmd/gomobile@${GOMOBILE_VERSION}"
go install "github.com/sagernet/gomobile/cmd/gobind@${GOMOBILE_VERSION}"
# First on PATH so `gomobile bind` finds the matching gobind, and so nothing
# outside this script sees either of them.
export PATH="$GOBIN:$PATH"
gomobile init

echo "== bind, all three packages in one framework =="
# Two platforms, one bind. macOS is here because the Mac app's tunnel will be a
# NetworkExtension exactly like the iOS one — outside the App Store it has to be
# a *system* extension, but it links the same Go cores through the same generated
# API. Binding it separately would produce a second copy of the cgo bootstrap and
# the seq layer, which is the duplicate-symbol wall described at the top of this
# file; the platform axis of an xcframework is precisely what avoids that.
#
# gomobile builds the macOS slice for both architectures, which the desktop app
# needs: release.yml ships an arm64 DMG and an Intel one.
#
# `iossimulator` is a target of its own — gomobile's `ios` means the device and
# nothing else. Without it the framework has no simulator slice, and the Xcode
# project's simulator search path points at a directory that does not exist:
# every simulator build fails with `Unable to resolve module dependency: 'Cores'`,
# which names the module and not the missing platform. Nobody could run the app
# on a simulator at all, screenshots included. The extension still cannot carry
# traffic there — NetworkExtension does not work in the simulator — but the app
# builds, runs and can be photographed.
gomobile bind -v \
  -target=ios,iossimulator,macos \
  -tags "with_gvisor,with_quic,with_utls,with_clash_api,olcrtc_lean" \
  -ldflags "-s -w" \
  -o "$OUT/Cores.xcframework" \
  github.com/sagernet/sing-box/experimental/libbox \
  github.com/xtls/libxray \
  "${OLCRTC_MODULE}/mobile"

echo "== slices produced =="
ls -1 "$OUT/Cores.xcframework"
test -d "$OUT/Cores.xcframework/ios-arm64" \
  || { echo "no device slice — the bind did not target iOS properly"; exit 1; }

# Matched by glob: gomobile names the macOS slice after the architectures it
# actually built — `macos-arm64_x86_64` as of the first run, note the Mach-O
# spelling rather than Go's `amd64` — and pinning that string here would turn a
# change in what we get into a failure that reads like a missing slice.
#
# `-print -quit` rather than a pipe into `head`: this script runs under
# `set -o pipefail`, where the reader closing the pipe first sends find a SIGPIPE
# and fails the whole command — reporting a missing slice that is sitting there.
macos_slice="$(find "$OUT/Cores.xcframework" -maxdepth 1 -type d -name 'macos*' -print -quit)"
test -n "$macos_slice" \
  || { echo "no macOS slice — the bind did not target macOS properly"; exit 1; }
echo "== macOS slice: $(basename "$macos_slice") =="

# Named by architecture for the same reason, and found the same way.
simulator_slice="$(find "$OUT/Cores.xcframework" -maxdepth 1 -type d -name 'ios-*simulator*' -print -quit)"
test -n "$simulator_slice" \
  || { echo "no simulator slice — the bind did not target iossimulator properly"; exit 1; }
echo "== simulator slice: $(basename "$simulator_slice") =="

# The whole point of the exercise: every API, one framework. A bind that silently
# dropped one package would otherwise only fail much later, in Xcode — and now it
# could drop it from one platform and not the other, which is worse, because the
# build that catches it is the one nobody runs until the Mac app is being wired
# up. Check both slices.
headers="$OUT/Cores.xcframework/ios-arm64/Cores.framework/Headers"
for slice in "$OUT/Cores.xcframework/ios-arm64" "$simulator_slice" "$macos_slice"; do
  slice_headers="$slice/Cores.framework/Headers"
  test -f "$slice_headers/Libbox.objc.h" \
    || { echo "no Libbox header in $(basename "$slice") — sing-box was not bound"; exit 1; }
  test -f "$slice_headers/LibXray.objc.h" \
    || { echo "no LibXray header in $(basename "$slice") — Xray was not bound"; exit 1; }
  test -f "$slice_headers/Mobile.objc.h" \
    || { echo "no Mobile header in $(basename "$slice") — olcrtc was not bound"; exit 1; }
done
echo "== all three APIs present on every slice =="

# Say which pins this framework came from, next to the framework itself.
#
# A built framework is a directory of stripped binaries and looks exactly like
# any other; fetch-cores-ios.sh used to see one sitting in the destination and
# conclude there was nothing to do. That is how an app reached a device built
# against an olcRTC revision two pins old, and how the day after was spent
# explaining behaviour produced by code the binary did not contain. The stamp
# is what makes "already present" mean "already present *and* the right one".
printf '%s\n' "${CORES_TAG}" > "$OUT/Cores.xcframework.tag"

# Keep a copy outside the build directory.
#
# The usual destination is inside `sharedUI/build`, which belongs to Gradle and
# which Gradle sweeps: it is the declared output of fetchCoresIosXcframework,
# and a sweep once deleted twenty minutes of work that could not be
# re-downloaded, because the release for this version pair did not exist yet.
# fetch-cores-ios.sh looks here before it looks at the network.
CACHE="${CORES_CACHE_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/olcbox/cores}/${CORES_TAG}"
if [ "$(cd "$OUT" && pwd)" != "$CACHE" ]; then
  mkdir -p "$CACHE"
  rm -rf "$CACHE/Cores.xcframework"
  cp -R "$OUT/Cores.xcframework" "$CACHE/Cores.xcframework"
  echo "== cached in $CACHE =="
fi

# The generated header is the authority on what the Swift bridge must implement,
# and guessing at it has cost several rounds before: Swift renames some of these
# on import, so the Go source is not the last word. Print the two protocols the
# extension conforms to, so a conformance error can be fixed by reading rather
# than by another build.
echo
echo "== protocols the extension must match =="
sed -n '/@protocol LibboxPlatformInterface /,/@end/p;/@protocol LibboxCommandServerHandler /,/@end/p' \
  "$headers/Libbox.objc.h"
sed -n '/@protocol MobileSocketProtector /,/@end/p' "$headers/Mobile.objc.h"
