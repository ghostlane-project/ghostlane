#!/usr/bin/env bash
# hev-build.sh <bin-dir>
#
# hev-socks5-tunnel (https://github.com/heiher/hev-socks5-tunnel, MIT, C,
# lwIP) at the commit scripts/hev-pins.sh pins for the iPhone (HEV_COMMIT:
# 941c7581, tag 2.17.1 plus three commits; src/hev-config-const.h says
# 2.17.1), checked out the way .github/workflows/hev-tunnel.yml checks it out
# (a recursive clone, then the commit with its submodules), and built with the
# recipe the desktop app uses on Linux (desktopApp/build.gradle.kts
# buildHevSocks5TunnelLinux: `make clean exec` in the source tree).
#
# The iPhone links the same tree as a static library (scripts/build-hev-ios.sh:
# `make static` with Apple's clang, -O3 from the Makefile either way) and runs
# it on a thread of the extension; the lab runs the executable, which is that
# library plus main(), as root, as its own process with a tun of its own.
#
# Writes <bin-dir>/hev-socks5-tunnel and <bin-dir>/hev.versions.txt.
set -euo pipefail

out="${1:?usage: hev-build.sh <bin-dir>}"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
unset HEV_COMMIT HEV_BUILD HEV_TAG
# shellcheck source=../../scripts/hev-pins.sh
. "${here}/../../scripts/hev-pins.sh"

src="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/hev-socks5-tunnel"
rm -rf "${src}"
git clone --quiet --recursive https://github.com/heiher/hev-socks5-tunnel.git "${src}"
git -C "${src}" -c advice.detachedHead=false checkout --quiet --recurse-submodules "${HEV_COMMIT}"
git -C "${src}" submodule update --init --recursive --quiet
got="$(git -C "${src}" rev-parse HEAD)"
if [ "${got}" != "${HEV_COMMIT}" ]; then
  echo "checked out ${got}, but scripts/hev-pins.sh pins ${HEV_COMMIT}" >&2
  exit 1
fi

( cd "${src}" && make clean exec )

mkdir -p "${out}"
install -m 0755 "${src}/bin/hev-socks5-tunnel" "${out}/hev-socks5-tunnel"
{
  echo "hev-socks5-tunnel ${got} (scripts/hev-pins.sh HEV_COMMIT)"
  grep -E '^#define (MAJOR|MINOR|MICRO)_VERSION' "${src}/src/hev-config-const.h" | tr -s ' ' | tr '\n' ' '
  echo
  git -C "${src}" submodule status --recursive
  "${CC:-cc}" --version | head -n 1
} > "${out}/hev.versions.txt"
cat "${out}/hev.versions.txt"
ls -l "${out}/hev-socks5-tunnel"
