#!/usr/bin/env bash
# K7, the engine half: the olcRTC engine the exits run and the one the app ships
# are the same commit.
#
#   - the exits' engine and the Rust core's harness: OLC_PIN (40 hex) in
#     olc/interop/lib.sh of the core, read at the commit scripts/ghostlane-core-pin.sh
#     names (the fork's interop workflow calls it "the exits' engine");
#   - the app's engine pin: OLCRTC_VERSION in scripts/cores-pins.sh, a
#     pseudo-version whose tail is the first 12 hex of the commit;
#   - the CLI's copy: the olcrtc version in cli/go.mod (replace and require) and
#     cli/go.sum.
#
# Fails when any of them names another commit than OLC_PIN, and says which.
#
#   scripts/pins-agree.sh [root]
# root defaults to the repository. PINS_AGREE_LIB=<file> reads the core's lib.sh
# from a file instead of raw.githubusercontent.com (the script's own tests).
# Needs bash, curl (unless PINS_AGREE_LIB is set), sed, grep.
set -euo pipefail
root="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
fail=0
bad() { echo "FAIL: $*"; fail=1; }

core="$(sed -n 's/^GHOSTLANE_CORE_COMMIT=//p' "$root/scripts/ghostlane-core-pin.sh")"
repo="$(sed -n 's/^GHOSTLANE_CORE_REPO="\${GHOSTLANE_CORE_REPO:-\(.*\)}"$/\1/p' "$root/scripts/ghostlane-core-pin.sh")"
[[ "$core" =~ ^[0-9a-f]{40}$ ]] || { echo "FAIL: no 40-hex GHOSTLANE_CORE_COMMIT in scripts/ghostlane-core-pin.sh"; exit 1; }

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
if [ -n "${PINS_AGREE_LIB:-}" ]; then
  cp "$PINS_AGREE_LIB" "$tmp/lib.sh"
else
  curl -fsSL --retry 3 -o "$tmp/lib.sh" "https://raw.githubusercontent.com/${repo:-ghostlane-project/xray-rust}/${core}/olc/interop/lib.sh" ||
    { echo "FAIL: cannot read olc/interop/lib.sh of the core at ${core:0:12}"; exit 1; }
fi
olc="$(sed -n 's/^OLC_PIN=//p' "$tmp/lib.sh")"
[[ "$olc" =~ ^[0-9a-f]{40}$ ]] || { echo "FAIL: OLC_PIN at core ${core:0:12} is not one 40-hex commit (got '$olc')"; exit 1; }
echo "exits and core harness: OLC_PIN ${olc:0:12} (core ${core:0:12})"

tail_of() { grep -oE 'v0\.0\.0-[0-9]{14}-[0-9a-f]{12}' | sed 's/.*-//' | sort -u; }
check() { # <label> <tails>
  local label="$1" tails="$2" n
  n="$(printf '%s\n' "$tails" | grep -c . || true)"
  if [ "$n" -ne 1 ]; then bad "$label names $n engine commits ('$(echo $tails)'), one expected"; return; fi
  if [ "$tails" = "${olc:0:12}" ]; then echo "ok: $label $tails"
  else bad "$label is at $tails, the exits' OLC_PIN is ${olc:0:12}"; fi
}
check "scripts/cores-pins.sh OLCRTC_VERSION" "$(grep '^OLCRTC_VERSION=' "$root/scripts/cores-pins.sh" | tail_of)"
check "cli/go.mod" "$(grep 'olcrtc' "$root/cli/go.mod" | tail_of)"
check "cli/go.sum" "$(grep 'ghostlane-project/olcrtc' "$root/cli/go.sum" | tail_of)"
exit "$fail"
