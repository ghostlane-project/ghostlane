#!/usr/bin/env bash
# fetch-sing-box-server.sh <dir>
#
# Downloads the official sing-box linux-amd64 release pinned below (the one
# the fork's VLESS-over-HTTP/2 test runs against), checks its sha256, and
# unpacks it to <dir>/sing-box. Prints "sing-box-<version>=<path>" as its last
# line: the value `rust-core-interop.py run --engine sing-box --servers` reads.
set -euo pipefail

SING_BOX_VERSION=1.13.20
SING_BOX_SHA256=646bc01bf128c32a12eb50d8690e387bba7504da7b1d65c704bd53916e38595a

dir="${1:?usage: fetch-sing-box-server.sh <dir>}"
mkdir -p "${dir}"
dir="$(cd "${dir}" && pwd)"
if [ ! -x "${dir}/sing-box" ]; then
  curl -fsSL --retry 3 -o "${dir}/sing-box.tgz" \
    "https://github.com/SagerNet/sing-box/releases/download/v${SING_BOX_VERSION}/sing-box-${SING_BOX_VERSION}-linux-amd64.tar.gz"
  got="$(sha256sum "${dir}/sing-box.tgz" | cut -d' ' -f1)"
  if [ "${got}" != "${SING_BOX_SHA256}" ]; then
    echo "sing-box ${SING_BOX_VERSION}: the archive hashes to ${got}, pinned ${SING_BOX_SHA256}" >&2
    exit 1
  fi
  tar -xzf "${dir}/sing-box.tgz" -C "${dir}" --strip-components=1 \
    "sing-box-${SING_BOX_VERSION}-linux-amd64/sing-box"
  rm -f "${dir}/sing-box.tgz"
  chmod +x "${dir}/sing-box"
fi
"${dir}/sing-box" version | sed -n 1p >&2
echo "sing-box-${SING_BOX_VERSION}=${dir}/sing-box"
