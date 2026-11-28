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
# Agree means the same 12-hex, or an app pin that is an ancestor of OLC_PIN with
# only documentation between them (the olcRTC repo's compare API: every file is
# *.md, under docs/, LICENSE* or README*). An app pin ahead of OLC_PIN or
# diverged, or any other file, fails. cli/go.mod and cli/go.sum are read only
# while they exist; scripts/cores-pins.sh always.
# PINS_AGREE_COMPARE=<json file> stands in for the compare answer (tests);
# GITHUB_TOKEN, when set, is sent as a header and never printed.
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

olc_repo="$(sed -n 's|^OLC_REPO=https://github.com/||p' "$tmp/lib.sh")"
olc_repo="${olc_repo:-ghostlane-project/olcrtc}"

tail_of() { grep -oE 'v0\.0\.0-[0-9]{14}-[0-9a-f]{12}' | sed 's/.*-//' | sort -u; }

# docs_only <app12>: 0 when the compare app12...OLC_PIN is ahead and touches docs only.
docs_only() {
  local cmp="$tmp/compare.json"
  if [ -n "${PINS_AGREE_COMPARE:-}" ]; then cp "$PINS_AGREE_COMPARE" "$cmp"
  else
    local hdr=()
    [ -n "${GITHUB_TOKEN:-}" ] && hdr=(-H "Authorization: Bearer $GITHUB_TOKEN")
    curl -fsSL --retry 3 "${hdr[@]}" -H "Accept: application/vnd.github+json" -o "$cmp" \
      "https://api.github.com/repos/${olc_repo}/compare/$1...${olc}" 2>/dev/null || return 1
  fi
  python3 -I - "$cmp" <<'PY'
import json, re, sys
d = json.load(open(sys.argv[1]))
files = d.get("files") or []
if d.get("status") != "ahead" or d.get("behind_by", 0) != 0 or len(files) >= 300:
    sys.exit(1)
doc = re.compile(r"(^docs/|\.md$|^LICENSE|^README)")
if not all(doc.search(f["filename"]) and (not f.get("previous_filename") or doc.search(f["previous_filename"])) for f in files):
    sys.exit(1)
print(d.get("ahead_by", len(d.get("commits", []))))
PY
}

check() { # <label> <tails>
  local label="$1" tails="$2" n n_commits
  n="$(printf '%s\n' "$tails" | grep -c . || true)"
  if [ "$n" -ne 1 ]; then bad "$label names $n engine commits ('$(echo $tails)'), one expected"; return; fi
  if [ "$tails" = "${olc:0:12}" ]; then echo "ok: $label $tails"
  elif n_commits="$(docs_only "$tails")"; then echo "ok: $label $tails, agree (docs-only difference: $n_commits commits)"
  else bad "$label is at $tails, the exits' OLC_PIN is ${olc:0:12}, and more than documentation lies between"; fi
}
check "scripts/cores-pins.sh OLCRTC_VERSION" "$(grep '^OLCRTC_VERSION=' "$root/scripts/cores-pins.sh" | tail_of)"
[ ! -f "$root/cli/go.mod" ] || check "cli/go.mod" "$(grep 'olcrtc' "$root/cli/go.mod" | tail_of)"
[ ! -f "$root/cli/go.sum" ] || check "cli/go.sum" "$(grep 'ghostlane-project/olcrtc' "$root/cli/go.sum" | tail_of)"
exit "$fail"
