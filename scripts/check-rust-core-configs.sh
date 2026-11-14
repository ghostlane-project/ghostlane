#!/usr/bin/env bash
# Asks the Rust core's own parser about every core.json reference config:
#
#   scripts/check-rust-core-configs.sh <xray-rust-binary>
#
# runs `<xray-rust-binary> config check -config <file>` on each
# sharedUI/src/jvmTest/resources/reference-configs/ios-core-*.json and
# bypass-ios-core-*.json, the configs RustCoreConfigDumpTest holds
# XrayConfig.buildIosCore to. The binary is ghostlane-project/xray-rust's
# `xray-rust` (crates/xray-cli), built from the fork the app links.
#
# The parser refuses a whole config over one field it does not know, so this
# is what says the app's core.json starts on the phone; XrayRustSurface in
# the Kotlin tests only knows the field lists. `config check` exits 0 for an
# accepted config (warnings allowed, and printed), 1 for a refused one and 2
# for a usage or I/O error; any non-zero exit fails this script. It parses
# only: it does not compile the outbound graph or bind the listener.
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "usage: $0 <xray-rust-binary>" >&2
  exit 2
fi
bin="$1"
if [ ! -x "$bin" ] && ! command -v "$bin" >/dev/null 2>&1; then
  echo "not an executable: $bin" >&2
  exit 2
fi

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
references="$root/sharedUI/src/jvmTest/resources/reference-configs"

shopt -s nullglob
configs=("$references"/ios-core-*.json "$references"/bypass-ios-core-*.json)
if [ "${#configs[@]}" -eq 0 ]; then
  echo "no ios-core reference configs under $references" >&2
  exit 1
fi

failed=0
for config in "${configs[@]}"; do
  if "$bin" config check -config "$config"; then
    echo "ok   $(basename "$config")"
  else
    echo "FAIL $(basename "$config")"
    failed=1
  fi
done
exit "$failed"
