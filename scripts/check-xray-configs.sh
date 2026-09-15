#!/usr/bin/env bash
# Validate every output of XrayConfigDumpTest against the pinned Xray binary.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
bin="${XRAY_BIN:-xray}"
"$bin" version
shopt -s nullglob
configs=("$root"/sharedUI/build/xray-configs/*.json)
if [ "${#configs[@]}" -eq 0 ]; then
  echo 'Run :sharedUI:jvmTest first; no Xray configurations were emitted.' >&2
  exit 1
fi
for config in "${configs[@]}"; do
  "$bin" run -test -c "$config"
done
