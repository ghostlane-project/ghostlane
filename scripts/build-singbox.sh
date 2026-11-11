#!/usr/bin/env bash
# build-singbox.sh <out>
#
# Builds sing-box for the machine it runs on into <out>, from the tree
# scripts/patched-singbox.sh writes (the pinned module with the Reality hello
# patch). The desktop jobs of release.yml call it on their own runners
# (Windows, macOS arm64 and amd64, Linux) in place of SagerNet's release
# archives, which carry the hello current Xray servers refuse.
#
# The recipe is upstream's own (.github/workflows/build.yml at the tag): the
# tags in release/DEFAULT_BUILD_TAGS_OTHERS and the flags in release/LDFLAGS,
# read from the tree, without cgo. What it leaves out of upstream's archives is
# the naive outbound (cronet, the one reason upstream links with cgo there),
# which no configuration the app writes asks for.
set -euo pipefail

out="${1:?usage: build-singbox.sh <out>}"
here="$(cd "$(dirname "$0")" && pwd)"
unset SINGBOX_VERSION
# shellcheck source=scripts/cores-pins.sh
. "${here}/cores-pins.sh"

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
tags="$(cat release/DEFAULT_BUILD_TAGS_OTHERS)"
ldflags="-X 'github.com/sagernet/sing-box/constant.Version=${SINGBOX_VERSION}' $(cat release/LDFLAGS) -s -w -buildid="
echo "build tags: ${tags}"
CGO_ENABLED=0 go build -trimpath -tags "${tags}" -ldflags "${ldflags}" -o "${out}" ./cmd/sing-box
