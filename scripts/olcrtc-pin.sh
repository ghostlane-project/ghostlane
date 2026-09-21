#!/usr/bin/env bash
# The engine revision every platform builds, as a commit a checkout can take.
#
#   scripts/olcrtc-pin.sh            the pin: OLCRTC_VERSION in scripts/cores-pins.sh
#   scripts/olcrtc-pin.sh --ref REF  a candidate (branch, tag or commit) instead
#
# Prints key=value lines for $GITHUB_OUTPUT; everything else goes to stderr:
#
#   olcrtc_repository         owner/name of the engine repository
#   olcrtc_version            the pseudo-version (or REF as given, with --ref)
#   olcrtc_rev                its 12-hex revision
#   olcrtc_sha                the full 40-hex commit
#   olcrtc_pinned             true for the pin, false for --ref
#   olcrtc_on_proofkit        true when the engine's proofkit branch contains it
#   olcrtc_proofkit_ahead_by  commits proofkit has on top of it (unknown when
#                             GitHub would not say)
#   go_version                GO_VERSION from cores-pins.sh
#
# Why the full SHA: actions/checkout takes `ref` as a commit only when it is 40
# (or 64) hex; a 12-hex tail is looked up as a branch or a tag and fails with
# "A branch or tag with the name ... could not be found". Why the timestamp is
# checked: Go builds the pseudo-version from the commit time, so a tail whose
# commit has another time is a pseudo-version typed by hand, and the Cores
# built from it came from somewhere else.
#
# Linux and macOS runners only. A Windows checkout has CRLF line endings (the
# repository has no .gitattributes), and a sourced value would end in \r; the
# Windows job takes release_version's output instead.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/gate-api.sh
. "${here}/gate-api.sh"

repo="${OLCRTC_REPOSITORY:-ghostlane-project/olcrtc}"
branch="${OLCRTC_BRANCH:-proofkit}"

die() {
  echo "::error title=Engine pin::$*" >&2
  exit 1
}

ref=""
case "${1:-}" in
  "") ;;
  --ref)
    ref="${2:-}"
    [ -n "${ref}" ] || die "--ref needs a branch, a tag or a commit"
    # A ref goes into a URL; refuse what no branch, tag or commit is spelled with.
    if [[ ! "${ref}" =~ ^[A-Za-z0-9][A-Za-z0-9._/-]*$ ]] || [[ "${ref}" == *..* ]]; then
      die "'${ref}' is not a branch, tag or commit name"
    fi
    ;;
  *) die "usage: olcrtc-pin.sh [--ref REF]" ;;
esac

# Sourced with the two variables unset: cores-pins.sh reads ${OLCRTC_VERSION:-...}
# and ${GO_VERSION:-...}, so an inherited value (release.yml sets GO_VERSION in
# its env) would win over the file and the check would compare a copy with itself.
# shellcheck disable=SC2016 # the inner shell expands them, after sourcing
pins="$(env -u OLCRTC_VERSION -u GO_VERSION \
  bash -c '. "$1"; printf "%s\n%s\n" "${OLCRTC_VERSION}" "${GO_VERSION}"' _ "${here}/cores-pins.sh")"
version="$(sed -n 1p <<<"${pins}")"
go_version="$(sed -n 2p <<<"${pins}")"
case "${version}${go_version}" in
  *$'\r'*) die "scripts/cores-pins.sh has CRLF line endings; this runs on Linux and macOS only" ;;
esac
[ -n "${go_version}" ] || die "scripts/cores-pins.sh sets no GO_VERSION"

ts=""
if [ -n "${ref}" ]; then
  lookup="${ref}"
  version="${ref}"
elif [[ "${version}" =~ ^v[0-9]+\.[0-9]+\.[0-9]+-(.+[.-])?([0-9]{14})-([0-9a-f]{12})$ ]]; then
  # vX.0.0-TIME-REV, vX.Y.Z-pre.0.TIME-REV or vX.Y.(Z+1)-0.TIME-REV
  ts="${BASH_REMATCH[2]}"
  lookup="${BASH_REMATCH[3]}"
elif [[ "${version}" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([-+][0-9A-Za-z.-]+)?$ ]]; then
  # A released tag carries no revision; the tag is the lookup, no time to check.
  lookup="${version}"
else
  die "OLCRTC_VERSION '${version}' in scripts/cores-pins.sh is neither a pseudo-version nor a tag"
fi

body="$(gate_api_get "repos/${repo}/commits/$(gate_uri "${lookup}")")" ||
  die "GitHub does not know '${lookup}' in ${repo} (a deleted or force-pushed branch takes unmerged commits with it)"
sha="$(jq -r '.sha // empty' <<<"${body}")"
[[ "${sha}" =~ ^[0-9a-f]{40}$ ]] || die "'${lookup}' did not resolve to a commit in ${repo}"

if [ -n "${ts}" ]; then
  [ "${sha:0:12}" = "${lookup}" ] || die "revision ${lookup} resolved to ${sha}, which is not it"
  when="$(jq -r '.commit.committer.date // empty' <<<"${body}")"
  got="$(jq -r '.commit.committer.date | fromdateiso8601 | strftime("%Y%m%d%H%M%S")' <<<"${body}" 2>/dev/null)" || got=""
  [ "${got}" = "${ts}" ] ||
    die "OLCRTC_VERSION ${version} carries the time ${ts}, but commit ${sha:0:12} was committed at ${when:-an unknown time}: the pseudo-version does not describe this commit"
fi

# Informational: whether the branch the fixes land on still contains the pin.
# GitHub keeps serving a commit a branch no longer reaches only until it is
# collected; a pin off proofkit is one force-push away from failing every
# checkout (the iOS Cores survive through the module proxy, nothing else does).
on_proofkit="unknown"
ahead="unknown"
if cmp="$(gate_api_get "repos/${repo}/compare/${sha}...$(gate_uri "${branch}")" 2>/dev/null)"; then
  status="$(jq -r '.status // empty' <<<"${cmp}")"
  case "${status}" in
    identical | ahead)
      on_proofkit=true
      ahead="$(jq -r '.ahead_by // 0' <<<"${cmp}")"
      ;;
    behind | diverged)
      on_proofkit=false
      ahead="$(jq -r '.ahead_by // 0' <<<"${cmp}")"
      echo "::warning title=Engine pin::${sha:0:12} is not on ${branch} (${status}); a force-push or a deleted branch can make it unreachable" >&2
      ;;
  esac
else
  echo "::warning title=Engine pin::could not compare ${sha:0:12} with ${branch}" >&2
fi

pinned=true
[ -z "${ref}" ] || pinned=false

echo "olcrtc ${version} -> ${sha} (on ${branch}: ${on_proofkit}, ${branch} ahead by ${ahead})" >&2
printf '%s\n' \
  "olcrtc_repository=${repo}" \
  "olcrtc_version=${version}" \
  "olcrtc_rev=${sha:0:12}" \
  "olcrtc_sha=${sha}" \
  "olcrtc_pinned=${pinned}" \
  "olcrtc_on_proofkit=${on_proofkit}" \
  "olcrtc_proofkit_ahead_by=${ahead}" \
  "go_version=${go_version}"
