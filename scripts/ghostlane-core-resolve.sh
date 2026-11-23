#!/usr/bin/env bash
# Which commit of the Rust core a job builds or tests:
#
#   scripts/ghostlane-core-resolve.sh [commit]
#
# Prints GHOSTLANE_CORE_REPO=... and GHOSTLANE_CORE_COMMIT=... (and the
# toolchain), one per line, ready for `>> "$GITHUB_ENV"`. With no argument it is
# the pin in scripts/ghostlane-core-pin.sh; with one (a workflow_dispatch's
# `pin` input) it is that commit, which must be a full 40-hex hash, and an empty
# argument means the pin. The pin file stays the only place the app names a
# commit; an override is a run's own question and writes nothing.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/ghostlane-core-pin.sh
. "$here/ghostlane-core-pin.sh"
commit="${1:-$GHOSTLANE_CORE_COMMIT}"
if ! [[ "$commit" =~ ^[0-9a-f]{40}$ ]]; then
  echo "the core commit must be 40 lowercase hex digits: '$commit'" >&2
  exit 2
fi
printf 'GHOSTLANE_CORE_REPO=%s\n' "$GHOSTLANE_CORE_REPO"
printf 'GHOSTLANE_CORE_COMMIT=%s\n' "$commit"
printf 'GHOSTLANE_CORE_TOOLCHAIN=%s\n' "$GHOSTLANE_CORE_TOOLCHAIN"
