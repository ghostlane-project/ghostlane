#!/usr/bin/env bash
# Whether a release run may sign and publish, decided in one place.
#
#   release-guard.sh decide   key=value lines for $GITHUB_OUTPUT (release.yml, plan)
#   release-guard.sh pin      the core pin against the store line (release.yml, plan)
#
# Decide reads the run from the environment the workflow step sets:
#   GUARD_REF          github.ref
#   GUARD_SHA          github.sha, the commit being built
#   GUARD_DRY_RUN      inputs.dry_run
#   GUARD_SIGNED_HEAD  inputs.signed_head
#   GUARD_PLAY_TRACK   inputs.play_track
#
# and writes:
#   signed      true when the run may read the signing, store and publish
#               secrets, upload to a store track and publish a release; false
#               is a dry run. release.yml asks for exactly `== 'true'`
#               wherever one of those is read, so an output that was never
#               written is a dry run too.
#   play_track  the Google Play track the bundle goes to: the input in a run
#               that signs, none in a dry run
#   why         one sentence for the log and the run's summary
#
# The rule. On main a run signs unless dry_run is ticked. Off main a run is
# dry, whatever dry_run says, unless signed_head is the full commit being
# built: a branch signs only by naming the commit it means. A signed_head that
# names another commit, or is not 40 hex, fails the run before anything is
# built, and so does anything this cannot read. A dry run builds every chosen
# platform the way a checkout without the repository's secrets does (unsigned,
# or with a throw-away key where the platform needs one to install), uploads
# to no store, publishes nothing, and has the gate write its skipped report
# instead of joining a room.
#
# Pin reads GUARD_SIGNED (decide's `signed`), GH_TOKEN, and the pin from
# scripts/ghostlane-core-pin.sh. A run that signs builds only a core commit
# the fork's `ghostlane` branch holds: that branch is where the store line is,
# and the integration branches of the core are ahead of it by work that has
# not been through a store review. Such a run fails here when the pin is not
# an ancestor of `ghostlane`, or when GitHub will not say. A dry run prints
# the same finding and goes on.
#
# scripts/test-release-dry.sh holds both, and what release.yml does with them.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# The branch of the core's repository the stores' builds are cut from.
readonly STORE_BRANCH=ghostlane

die() {
  echo "::error title=Release guard::$*" >&2
  exit 1
}

decide() {
  local ref="${GUARD_REF:-}" sha="${GUARD_SHA:-}" dry_run="${GUARD_DRY_RUN:-}"
  local head="${GUARD_SIGNED_HEAD:-}" track="${GUARD_PLAY_TRACK:-}"

  [ -n "${ref}" ] || die "no ref: the guard cannot tell main from a branch"
  [[ "${sha}" =~ ^[0-9a-f]{40}$ ]] || die "the commit being built is not a 40-hex commit"
  case "${dry_run}" in
    true | false) ;;
    *) die "dry_run must be true or false" ;;
  esac
  track="${track:-internal}"
  case "${track}" in
    internal | production | none) ;;
    *) die "play_track must be internal, production or none" ;;
  esac
  if [ -n "${head}" ]; then
    [[ "${head}" =~ ^[0-9a-f]{40}$ ]] ||
      die "signed_head must be the full 40-hex commit this run may sign, in lower case"
    [ "${head}" = "${sha}" ] ||
      die "signed_head names ${head:0:12}, but this run builds ${sha:0:12}: nothing is built. Dispatch from the commit you mean, or leave signed_head empty for a dry run"
  fi

  local signed=false why
  if [ "${dry_run}" = true ]; then
    why="Dry run, as asked: nothing is signed, uploaded to a store or published, and the gate joins no live room."
  elif [ "${ref}" = refs/heads/main ]; then
    signed=true
    why="A run on main: it signs, and uploads and publishes what was asked for."
  elif [ -n "${head}" ]; then
    signed=true
    why="A run off main whose signed_head names its own commit ${sha:0:12}: it signs, and uploads and publishes what was asked for."
  else
    why="Dry run: off main a run signs only when signed_head names its commit. Nothing is signed, uploaded to a store or published, and the gate joins no live room."
  fi
  if [ "${signed}" != true ]; then
    track=none
  fi

  echo "release guard: ${ref} at ${sha:0:12}, signed ${signed}, play track ${track}" >&2
  printf '%s\n' \
    "signed=${signed}" \
    "play_track=${track}" \
    "why=${why}"
}

pin() {
  # shellcheck source=scripts/gate-api.sh
  . "${here}/gate-api.sh"

  # Sourced with the repository unset: the pin file reads
  # ${GHOSTLANE_CORE_REPO:-...}, so an inherited value would win over the file.
  local pins repo commit
  # shellcheck disable=SC2016 # the inner shell expands them, after sourcing
  pins="$(env -u GHOSTLANE_CORE_REPO -u GHOSTLANE_CORE_COMMIT \
    bash -c '. "$1"; printf "%s\n%s\n" "${GHOSTLANE_CORE_REPO}" "${GHOSTLANE_CORE_COMMIT}"' _ "${here}/ghostlane-core-pin.sh")"
  repo="$(sed -n 1p <<<"${pins}")"
  commit="$(sed -n 2p <<<"${pins}")"
  [[ "${repo}" =~ ^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$ ]] || die "the pin file names no repository for the core"
  [[ "${commit}" =~ ^[0-9a-f]{40}$ ]] || die "the pin file names no 40-hex commit for the core"

  # base...head: what the store line is, seen from the pin. identical or ahead
  # means the line holds the pin; behind or diverged means the pin has commits
  # the line does not.
  local state=unknown cmp status=""
  if cmp="$(gate_api_get "repos/${repo}/compare/${commit}...${STORE_BRANCH}" 2>/dev/null)"; then
    status="$(jq -r '.status // empty' <<<"${cmp}" 2>/dev/null)" || status=""
    case "${status}" in
      identical | ahead) state=held ;;
      behind | diverged) state=off ;;
    esac
  fi

  local finding
  case "${state}" in
    held)
      echo "release guard: the core pin ${commit:0:12} is on ${repo}'s ${STORE_BRANCH} branch" >&2
      return 0
      ;;
    off) finding="the core pin ${commit:0:12} is not on ${repo}'s ${STORE_BRANCH} branch (${status})" ;;
    *) finding="GitHub would not say whether the core pin ${commit:0:12} is on ${repo}'s ${STORE_BRANCH} branch" ;;
  esac
  if [ "${GUARD_SIGNED:-}" = true ]; then
    die "${finding}. A run that signs or publishes builds only what the store line holds: move ${STORE_BRANCH} to the pin first, or run dry"
  fi
  echo "::notice title=Release guard::${finding}. This run is dry and goes on; a run that signs would stop here" >&2
}

case "${1:-}" in
  decide) decide ;;
  pin) pin ;;
  *) die "usage: release-guard.sh decide | pin" ;;
esac
