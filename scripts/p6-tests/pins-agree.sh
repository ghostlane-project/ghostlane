#!/usr/bin/env bash
# K7's engine half, scripts/pins-agree.sh. First its own rules against planted
# trees and a planted lib.sh (no network); then the real tree: the app's engine
# pins against OLC_PIN of the core at the core pin (read from the fork).
#
#   scripts/p6-tests/pins-agree.sh
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
script="$root/scripts/pins-agree.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failed=0
fail() { echo "FAIL: $*"; failed=1; }
pass() { echo "ok: $*"; }

a=2a5153529b0496fe8b42929c56f8cb2e7d36c917
b=42dd44d28883
tree="$work/tree"
mkdir -p "$tree/scripts" "$tree/cli"
cp "$root/scripts/ghostlane-core-pin.sh" "$tree/scripts/"
set_app() { # <12-hex> for cores-pins, go.mod, go.sum
  printf 'OLCRTC_VERSION="${OLCRTC_VERSION:-v0.0.0-20261119213407-%s}"\n' "$1" > "$tree/scripts/cores-pins.sh"
  printf 'replace x => github.com/ghostlane-project/olcrtc v0.0.0-20261119213407-%s\nrequire github.com/openlibrecommunity/olcrtc v0.0.0-20261119213407-%s\n' "$1" "$1" > "$tree/cli/go.mod"
  printf 'github.com/ghostlane-project/olcrtc v0.0.0-20261119213407-%s h1:x=\ngithub.com/ghostlane-project/olcrtc v0.0.0-20261119213407-%s/go.mod h1:y=\n' "$1" "$1" > "$tree/cli/go.sum"
}
lib() { printf 'OLC_PIN=%s\n' "$1" > "$work/lib.sh"; }
run() { PINS_AGREE_LIB="$work/lib.sh" bash "$script" "$tree" >"$work/out" 2>&1; }

lib "$a"; set_app "${a:0:12}"
run && pass "all copies at one commit pass" || { fail "agreeing pins were refused"; cat "$work/out"; }
set_app "$b"
if run; then fail "an app pin behind OLC_PIN passed"; else grep -q "cores-pins.sh.*is at $b" "$work/out" && pass "an app pin behind OLC_PIN is named" || fail "no message naming cores-pins.sh"; fi
set_app "${a:0:12}"; sed -i "s/${a:0:12}/$b/g" "$tree/cli/go.mod"
if run; then fail "a go.mod that differs passed"; else grep -q 'cli/go.mod is at' "$work/out" && pass "a go.mod that differs is named" || fail "no message naming cli/go.mod"; fi
set_app "${a:0:12}"; sed -i "s/${a:0:12}/$b/" "$tree/cli/go.sum"
if run; then fail "a go.sum that differs passed"; else pass "a go.sum that differs fails"; fi
set_app "${a:0:12}"; lib "abc123"
if run; then fail "a short OLC_PIN passed"; else pass "a short OLC_PIN fails"; fi
set_app "${a:0:12}"; lib "$a"; sed -i 's/^GHOSTLANE_CORE_COMMIT=.*/GHOSTLANE_CORE_COMMIT=zz/' "$tree/scripts/ghostlane-core-pin.sh"
if run; then fail "a broken core pin passed"; else pass "a broken core pin fails"; fi

echo "--- the real tree"
if bash "$script" "$root"; then pass "the app's engine pins agree with the exits' OLC_PIN"; else fail "the app's engine pins differ from the exits' OLC_PIN at the core pin"; fi
exit "$failed"
