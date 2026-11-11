#!/usr/bin/env bash
# Validate every output of XrayConfigDumpTest against the pinned Xray binary.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
bin="${XRAY_BIN:-xray}"
"$bin" version
shopt -s nullglob
# xray-configs-26: router exports that need Xray >= 26.3 (native Hysteria2),
# which the pinned release (26.7.11) reads too.
configs=("$root"/sharedUI/build/xray-configs/*.json "$root"/sharedUI/build/xray-configs-26/*.json)
if [ "${#configs[@]}" -eq 0 ]; then
  echo 'Run :sharedUI:jvmTest first; no Xray configurations were emitted.' >&2
  exit 1
fi
for config in "${configs[@]}"; do
  "$bin" run -test -c "$config"
done
