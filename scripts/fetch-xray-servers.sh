#!/usr/bin/env bash
# fetch-xray-servers.sh <dir>
#
# Downloads the official Xray-linux-64 build of every version in
# scripts/xray-servers.txt, checks it against the sha256 pinned there, and
# unpacks each to <dir>/<version>/xray. Prints the XRAY_SERVERS value the
# CLI's end-to-ends read ("<version>=<path> ...") as its last line.
set -euo pipefail

dir="${1:?usage: fetch-xray-servers.sh <dir>}"
here="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "${dir}"
dir="$(cd "${dir}" && pwd)"
servers=""
while read -r version sum; do
  case "${version}" in ''|'#'*) continue ;; esac
  out="${dir}/${version}"
  mkdir -p "${out}"
  if [ ! -x "${out}/xray" ]; then
    curl -fsSL --retry 3 -o "${out}/Xray-linux-64.zip" \
      "https://github.com/XTLS/Xray-core/releases/download/v${version}/Xray-linux-64.zip"
    got="$(sha256sum "${out}/Xray-linux-64.zip" | cut -d' ' -f1)"
    if [ "${got}" != "${sum}" ]; then
      echo "Xray v${version}: the archive hashes to ${got}, xray-servers.txt pins ${sum}" >&2
      exit 1
    fi
    unzip -oq "${out}/Xray-linux-64.zip" xray -d "${out}"
    rm -f "${out}/Xray-linux-64.zip"
    chmod +x "${out}/xray"
  fi
  "${out}/xray" version | head -n 1 >&2
  servers="${servers:+${servers} }${version}=${out}/xray"
done < "${here}/xray-servers.txt"
echo "${servers}"
