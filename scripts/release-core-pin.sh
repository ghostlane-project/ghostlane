#!/usr/bin/env bash
# The release names the core it was built with.
#   release-core-pin.sh                  prints core_pin=<12 hex>, the head of
#                                        GHOSTLANE_CORE_COMMIT (for $GITHUB_OUTPUT)
#   release-core-pin.sh check FILE PIN   fails unless FILE holds the line
#                                        "- Rust core pin: `PIN`" (PIN is 12 hex)
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
case "${1:-}" in
  '')
    commit="$(sed -n 's/^GHOSTLANE_CORE_COMMIT=\([0-9a-f]\{40\}\)$/\1/p' "$here/ghostlane-core-pin.sh")"
    [ -n "$commit" ] || { echo "::error::scripts/ghostlane-core-pin.sh holds no 40-hex GHOSTLANE_CORE_COMMIT" >&2; exit 1; }
    echo "core_pin=${commit:0:12}"
    ;;
  check)
    file="${2:-}"; pin="${3:-}"
    [[ "$pin" =~ ^[0-9a-f]{12}$ ]] || { echo "::error::the expected core pin is not 12 hex: '${pin}'" >&2; exit 1; }
    [ -s "$file" ] || { echo "::error::release notes ${file} are missing or empty" >&2; exit 1; }
    if ! grep -qxF -- "- Rust core pin: \`${pin}\`" "$file"; then
      echo "::error::${file} does not name the Rust core pin \`${pin}\`; nothing is published without it" >&2
      exit 1
    fi
    ;;
  *) echo "usage: release-core-pin.sh [check FILE PIN]" >&2; exit 2 ;;
esac
