#!/usr/bin/env bash
# The report the gate compares against: the newest earlier release's
# gate-report.json that is comparable, or nothing.
#
#   GH_REPO=owner/name [GH_TOKEN=...] gate-previous-report.sh <out.json> [<current tag>]
#
# Writes the report to <out.json> (an empty file when there is none) and prints
# previous_tag=<tag or empty> for $GITHUB_OUTPUT. Never fails the job: a
# listing or a download that does not work is a note on stderr and "nothing",
# and the verdict then says the relative checks were skipped.
#
# Candidates are non-draft releases tagged vX.Y.Z (the rolling `nightly` and the
# ios-cores-* tags never are) that carry a gate-report.json asset, newest
# version first, the current tag left out (a re-run after a partial publish
# must not compare a release with itself), the first five tried. A report is
# taken when it is schema 1, for the local target (cell ids carry no target,
# so a link report's cells would pair with local ones by name), not a
# break-glass stub (skipped), and planned something.
#
# GH_REPO is required: nothing here guesses the repository from a git remote,
# and the verdict job runs this from inside the engine's checkout.
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/gate-api.sh
. "${here}/gate-api.sh"

out="${1:?usage: gate-previous-report.sh <out.json> [<current tag>]}"
current="${2:-}"
: >"${out}"

note() {
  echo "gate-previous-report: $*" >&2
}

none() {
  echo "previous_tag="
  exit 0
}

repo="${GH_REPO:-}"
if [ -z "${repo}" ]; then
  note "GH_REPO is not set; no previous report"
  none
fi

tmp="$(mktemp -d)"
trap 'rm -rf "${tmp}"' EXIT

candidates="${tmp}/candidates.tsv"
: >"${candidates}"
page=1
while [ "${page}" -le 10 ]; do
  if ! body="$(gate_api_get "repos/${repo}/releases?per_page=100&page=${page}")"; then
    note "could not list the releases of ${repo}"
    none
  fi
  if ! jq -r '.[]
      | select(.draft | not)
      | select(.tag_name | test("^v[0-9]+\\.[0-9]+\\.[0-9]+$"))
      | .tag_name as $tag
      | (.assets // [])[] | select(.name == "gate-report.json")
      | [$tag, .url] | @tsv' <<<"${body}" >>"${candidates}"; then
    note "the release listing of ${repo} was not what GitHub returns"
    none
  fi
  [ "$(jq 'length' <<<"${body}")" -ge 100 ] || break
  page=$((page + 1))
done

tried=0
while IFS=$'\t' read -r tag url; do
  [ -n "${tag}" ] && [ "${tag}" != "${current}" ] || continue
  tried=$((tried + 1))
  [ "${tried}" -le 5 ] || break
  file="${tmp}/${tried}.json"
  if ! gate_api_download "${url}" "${file}" 2>/dev/null; then
    note "${tag}: could not download its gate-report.json"
    continue
  fi
  if jq -e '.schema == 1 and .target == "local" and (.skipped != true) and ((.planned // 0) > 0)' \
    "${file}" >/dev/null 2>&1; then
    cp "${file}" "${out}"
    note "comparing with ${tag}"
    echo "previous_tag=${tag}"
    exit 0
  fi
  note "${tag}: its report is skipped, empty, not local or not schema 1; not a baseline"
done < <(sort -t $'\t' -k1,1 -rV "${candidates}")

note "no earlier release carries a comparable gate report"
none
