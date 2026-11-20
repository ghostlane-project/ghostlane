#!/usr/bin/env bash
# build-singbox.sh <out> [desktop|android|upstream]
#
# Builds sing-box into <out>, from the tree scripts/patched-singbox.sh writes
# (the pinned module with the Reality hello patch), for the machine it runs on
# unless GOOS and GOARCH say otherwise. The desktop jobs of release.yml call it
# on their own runners (Windows, macOS arm64 and amd64, Linux) in place of
# SagerNet's release archives, which carry the hello current Xray servers
# refuse; PR Checks calls it for the binaries the app's configs are checked with.
#
# The tags are the second argument's list:
#   desktop   (the default) SINGBOX_TAGS_DESKTOP, what the desktops ship;
#   android   SINGBOX_TAGS_ANDROID. release.yml builds Android's own binary
#             through the NDK and reads the same list; this one is for PR
#             Checks, to check Android's configs and to size its binary;
#   upstream  SagerNet's release/DEFAULT_BUILD_TAGS_OTHERS from the tree, which
#             every build used before scripts/singbox-tags.sh. Only for
#             measuring what our lists leave out; nothing ships it.
# The rest of the recipe is upstream's own (.github/workflows/build.yml at the
# tag): the flags in release/LDFLAGS, read from the tree, without cgo.
set -euo pipefail

out="${1:?usage: build-singbox.sh <out> [desktop|android|upstream]}"
target="${2:-desktop}"
here="$(cd "$(dirname "$0")" && pwd)"
unset SINGBOX_VERSION
# shellcheck source=scripts/cores-pins.sh
. "${here}/cores-pins.sh"
# shellcheck source=scripts/singbox-tags.sh
. "${here}/singbox-tags.sh"
case "${target}" in
  desktop) tags="${SINGBOX_TAGS_DESKTOP}" ;;
  android) tags="${SINGBOX_TAGS_ANDROID}" ;;
  upstream) tags="" ;;
  *) echo "build-singbox.sh: '${target}' is not desktop, android or upstream" >&2; exit 2 ;;
esac

work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT
bash "${here}/patched-singbox.sh" "${work}/sing-box"

mkdir -p "$(dirname "${out}")"
out="$(cd "$(dirname "${out}")" && pwd)/$(basename "${out}")"
if command -v cygpath >/dev/null 2>&1; then
  # Git Bash on Windows: go.exe takes a Windows path, and nothing below may be
  # rewritten as one on its way there.
  out="$(cygpath -w "${out}")"
  export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
fi
cd "${work}/sing-box"
[ -n "${tags}" ] || tags="$(cat release/DEFAULT_BUILD_TAGS_OTHERS)"
ldflags="-X 'github.com/sagernet/sing-box/constant.Version=${SINGBOX_VERSION}' $(cat release/LDFLAGS) -s -w -buildid="
echo "build tags (${target}): ${tags}"
CGO_ENABLED=0 go build -trimpath -tags "${tags}" -ldflags "${ldflags}" -o "${out}" ./cmd/sing-box
