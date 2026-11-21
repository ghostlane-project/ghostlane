#!/usr/bin/env bash
# Moves the core pin and regenerates what follows it:
#
#   scripts/pin-move.sh <40-hex commit of the core>
#
# It rewrites GHOSTLANE_CORE_COMMIT in scripts/ghostlane-core-pin.sh, then runs
# every executable file of scripts/pin-move.d/ in name order with
# GHOSTLANE_CORE_COMMIT set to the new commit (the events fixture's hash, the
# notices, the size record, the surface lists: each task that has such a thing
# drops one script there and never edits this one). A step that fails stops the
# move. The comment above the pin is the person's to update.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
commit="${1:-}"
if ! [[ "$commit" =~ ^[0-9a-f]{40}$ ]]; then
  echo "usage: $0 <40-hex commit>" >&2
  exit 2
fi
pin="$here/ghostlane-core-pin.sh"
sed -i "s/^GHOSTLANE_CORE_COMMIT=[0-9a-f]\{40\}\$/GHOSTLANE_CORE_COMMIT=$commit/" "$pin"
grep -qx "GHOSTLANE_CORE_COMMIT=$commit" "$pin" || { echo "the pin line was not rewritten" >&2; exit 1; }
export GHOSTLANE_CORE_COMMIT="$commit"
shopt -s nullglob
for step in "$here"/pin-move.d/*; do
  [ -f "$step" ] && [ -x "$step" ] || continue
  echo "pin-move: $(basename "$step")"
  "$step" "$commit"
done
echo "pin-move: the pin is $commit"
