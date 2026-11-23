#!/usr/bin/env bash
# The pin move's events step (scripts/pin-move.d/events-hash) against a git
# directory of its own, in place of the core's repository: it writes the
# fixture of the commit it is given and its hash, says whether they changed,
# and --check fails on a copy or a hash that is not the commit's. Then the
# app's own copy is checked against its own hash, as HostEventsTest reads it.
#
#   scripts/p6-tests/events-hash.sh
# Needs bash, git and sha256sum or shasum.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
step="$root/scripts/pin-move.d/events-hash"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failed=0
fail() { echo "FAIL: $*"; failed=1; }
pass() { echo "ok: $*"; }

[ -x "$step" ] || fail "the step is not executable: pin-move.sh runs only executable steps"

repo="$work/core"
git init -q "$repo"
git -C "$repo" config user.email test@example.invalid
git -C "$repo" config user.name test
mkdir -p "$repo/crates/ghostlane-engine/tests/fixtures"
printf '%s\n' '{"event":"ended","reason":"signal","code":null,"message":null}' > "$repo/crates/ghostlane-engine/tests/fixtures/events.jsonl"
git -C "$repo" add -A && git -C "$repo" commit -qm one
one="$(git -C "$repo" rev-parse HEAD)"
printf '%s\n' '{"event":"bound","interface":"eth0"}' >> "$repo/crates/ghostlane-engine/tests/fixtures/events.jsonl"
git -C "$repo" commit -qam two
two="$(git -C "$repo" rev-parse HEAD)"
git -C "$repo" rm -q crates/ghostlane-engine/tests/fixtures/events.jsonl
git -C "$repo" commit -qm none
none="$(git -C "$repo" rev-parse HEAD)"

copy="$work/app/events.jsonl"
run() { EVENTS_SOURCE_REPO="$repo" EVENTS_COPY="$copy" "$step" "$@"; }
sum() { if command -v sha256sum > /dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }

out="$(run "$one")" || fail "the first move failed"
grep -q '^events: changed' <<< "$out" && pass "a copy that was not there is written" || fail "first move said: $out"
git -C "$repo" show "$one:crates/ghostlane-engine/tests/fixtures/events.jsonl" | cmp -s - "$copy" &&
  pass "the copy is the fixture at the commit" || fail "the copy is not the fixture at the first commit"
[ "$(cat "$copy.sha256")" = "$(sum "$copy")  events.jsonl" ] &&
  pass "the hash is written as sha256sum writes it" || fail "the hash line is $(cat "$copy.sha256")"

out="$(run "$one")" || fail "the same move again failed"
grep -q '^events: unchanged' <<< "$out" && pass "the same commit again changes nothing" || fail "second move said: $out"

run --check "$one" > /dev/null && pass "--check passes the copy of its commit" || fail "--check refused the copy of its commit"
if run --check "$two" > /dev/null 2>&1; then fail "--check passed a copy of another commit"; else pass "--check refuses a copy of another commit"; fi

out="$(run "$two")" || fail "the move to the second commit failed"
grep -q '^events: changed' <<< "$out" && pass "a fixture that changed rewrites the copy" || fail "third move said: $out"
[ "$(wc -l < "$copy" | tr -d ' ')" = 2 ] && pass "the copy is the second commit's" || fail "the copy has $(wc -l < "$copy") lines"

printf '%s  events.jsonl\n' "$(printf '0%.0s' $(seq 64))" > "$copy.sha256"
if run --check "$two" > /dev/null 2>&1; then fail "--check passed a hash that is not the copy's"; else pass "--check refuses a hash that is not the copy's"; fi

if run "$none" > /dev/null 2>&1; then fail "a commit with no fixture passed"; else pass "a commit with no fixture fails the step"; fi
if run "not-a-commit" > /dev/null 2>&1; then fail "a word that is not a commit passed"; else pass "a word that is not a commit is refused"; fi

# The app's own copy, as HostEventsTest reads it.
app="$root/sharedUI/src/jvmTest/resources/ghostlane-engine"
if [ -f "$app/events.jsonl" ] && [ "$(cat "$app/events.jsonl.sha256")" = "$(sum "$app/events.jsonl")  events.jsonl" ]; then
  pass "the app's copy hashes to the hash beside it"
else
  fail "the app's copy and the hash beside it differ: run scripts/pin-move.d/events-hash at the pin"
fi

exit "$failed"
