#!/usr/bin/env bash
# The engine's side of the release gate, in one file.
#
# Every engine flag, environment name, build tag, path, command and file name
# the app's gate depends on is spelled here and nowhere else, so aligning the
# app with the engine is an edit of this file alone. The workflow
# (.github/workflows/gate.yml) and the other gate scripts call it.
#
# The contract, from the engine plan (the olcbox repository,
# docs/superpowers/plans/2026-09-15-release-gate-engine.md: Task 13, Task 14,
# Task 15 and "Amendments 2026-09-18" A1-A9):
#
#   paths a revision must carry   internal/gate, cmd/gate-report
#   the suite                     go test ./internal/gate -run '^TestGate$' -v
#   flags                         -olcrtc.gate                  run it at all
#                                 -olcrtc.gate-target=local     a server the suite starts
#                                 -olcrtc.gate-dir=<ABSOLUTE>   report and scrubbed logs
#                                 -olcrtc.gate-providers=<one>  this leg's provider
#                                 -olcrtc.gate-clients=<one>    cli or mobile (A8)
#                                 -olcrtc.gate-transports=<l>   only when a manual run asks
#                                 -olcrtc.gate-dry              print the plan, run nothing
#   build per flavour (A8)        cli: no tags; mobile: -tags olcrtc_lean
#   environment (A2, A5, A7)      OLCRTC_GATE_TELEMOST_ROOMS, OLCRTC_GATE_WBSTREAM_ROOMS,
#                                 OLCRTC_GATE_WBSTREAM_TOKEN, OLCRTC_GATE_JITSI_HOSTS
#                                 (never argv), OLCRTC_GATE_ENGINE_COMMIT,
#                                 OLCRTC_GATE_ENGINE_REF, OLCRTC_GATE_APP_VERSION
#   the report                    <gate-dir>/gate-report.json, schema 1
#   the plan                      one cell id per line on stdout of the dry run,
#                                 platform/provider/transport/client/scenario
#   the report tool               go run ./cmd/gate-report render <report>
#                                 go run ./cmd/gate-report compare -severity <warn|fail> <prev> <cur>
#                                 (flags before the files; exit 2 = regression at fail)
#
# Pools: the engine takes a room from its pool by run number. The app hands it
# the FIRST entry only, so every run of this repository uses the same room per
# provider and the per-provider concurrency group in gate.yml is what keeps two
# runs out of it. Later entries are spares: moving one to the front is how a
# room that rotted is replaced (docs/release-gate.md).
#
# Usage (the engine checkout is GATE_ENGINE_DIR, default $GITHUB_WORKSPACE/olcrtc):
#   gate-run.sh paths                          what the resolve job checks for
#   gate-run.sh unit <default|lean>            the engine's unit tests, one build
#   gate-run.sh plan <cli|mobile> <dir>        dry run -> <dir>/plan.txt
#   gate-run.sh run <cli|mobile> <dir>         the cells -> <dir>/gate-report.json
#   gate-run.sh summary <leg dir>              one markdown line per flavour
#   gate-run.sh render <report.json>           the engine's table
#   gate-run.sh compare <warn|fail> <prev> <cur>
#   gate-run.sh merge <gate-merge-reports.py merge arguments>
#
# plan and run read GATE_PROVIDER (jitsi, telemost or wbstream), GATE_TRANSPORTS
# (optional), GATE_ENGINE_SHA, GATE_ENGINE_VERSION and GATE_APP_VERSION, and the
# leg's secrets under the repository's names: GATE_TELEMOST_ROOMS,
# GATE_WBSTREAM_ROOMS, GATE_WBSTREAM_TOKEN, GATE_JITSI_HOSTS. Nothing here
# prints a secret.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

readonly ENGINE_PATHS=(internal/gate cmd/gate-report)
readonly SUITE_PKG=./internal/gate
readonly SUITE_RUN='^TestGate$'
readonly REPORT_NAME=gate-report.json
readonly LEAN_TAG=olcrtc_lean
# go test -timeout per flavour. An expiry panics the test binary before
# TestMain writes the report, so each sits below the step's timeout in gate.yml
# (cli 25, mobile 35 minutes), and the merge turns the lost cells into "did not
# run". Calibrate after the first runs; the job timeout moves with them.
readonly GO_TIMEOUT_CLI=20m
readonly GO_TIMEOUT_MOBILE=30m
readonly GO_TIMEOUT_UNIT=15m

engine_dir="${GATE_ENGINE_DIR:-${GITHUB_WORKSPACE:-${PWD}}/olcrtc}"

die() {
  echo "::error title=Release gate::$*" >&2
  exit 1
}

# entries <list>: one trimmed, non-empty entry per line. Commas and newlines
# both separate, so a secret pasted one entry per line still splits.
entries() {
  local list="${1//$'\r'/,}"
  list="${list//$'\n'/,}"
  local -a parts
  local e
  IFS=',' read -r -a parts <<<"${list}"
  for e in "${parts[@]}"; do
    e="$(trim "${e}")"
    if [ -n "${e}" ]; then printf '%s\n' "${e}"; fi
  done
}

trim() {
  local s="$1"
  s="${s#"${s%%[![:space:]]*}"}"
  printf '%s' "${s%"${s##*[![:space:]]}"}"
}

# first_entry <list>: no pipe into head, which under pipefail could fail the
# assignment it feeds when the writer is cut off.
first_entry() {
  local e
  while IFS= read -r e; do
    printf '%s' "${e}"
    return 0
  done < <(entries "$1")
}

joined() {
  entries "$1" | paste -sd, -
}

# go_build_flags <cli|mobile>: the build the flavour ships (A8).
go_build_flags() {
  case "$1" in
    cli) build_flags=() ;;
    mobile) build_flags=(-tags "${LEAN_TAG}") ;;
    *) die "unknown client flavour '$1' (cli or mobile)" ;;
  esac
}

go_timeout() {
  case "$1" in
    cli) printf '%s' "${GO_TIMEOUT_CLI}" ;;
    mobile) printf '%s' "${GO_TIMEOUT_MOBILE}" ;;
  esac
}

# suite_flags <client> <dir>: the flags every run of one leg's flavour shares.
suite_flags() {
  case "${GATE_PROVIDER:-}" in
    jitsi | telemost | wbstream) ;;
    *) die "GATE_PROVIDER must be jitsi, telemost or wbstream" ;;
  esac
  suite=(-olcrtc.gate -olcrtc.gate-target=local
    "-olcrtc.gate-providers=${GATE_PROVIDER}"
    "-olcrtc.gate-clients=$1"
    "-olcrtc.gate-dir=$2")
  if [ -n "${GATE_TRANSPORTS:-}" ]; then
    suite+=("-olcrtc.gate-transports=${GATE_TRANSPORTS}")
  fi
}

absolute_dir() {
  case "$1" in
    /*) ;;
    *) die "the gate directory must be absolute: go test runs in the package directory, so a relative one lands under internal/gate" ;;
  esac
  mkdir -p "$1"
}

cmd_paths() {
  printf '%s\n' "${ENGINE_PATHS[@]}"
}

cmd_unit() {
  local -a tags
  case "${1:-}" in
    default) tags=() ;;
    lean) tags=(-tags "${LEAN_TAG}") ;;
    *) die "usage: gate-run.sh unit <default|lean>" ;;
  esac
  cd "${engine_dir}"
  go test -count=1 -race -timeout "${GO_TIMEOUT_UNIT}" "${tags[@]}" ./...
}

cmd_plan() {
  local client="${1:-}" dir="${2:-}"
  [ -n "${dir}" ] || die "usage: gate-run.sh plan <cli|mobile> <dir>"
  local -a build_flags suite
  go_build_flags "${client}"
  absolute_dir "${dir}"
  suite_flags "${client}" "${dir}"
  # The dry run needs no room: it gets none, whatever the step's env holds.
  unset GATE_TELEMOST_ROOMS GATE_WBSTREAM_ROOMS GATE_WBSTREAM_TOKEN GATE_JITSI_HOSTS
  unset OLCRTC_GATE_TELEMOST_ROOMS OLCRTC_GATE_WBSTREAM_ROOMS OLCRTC_GATE_WBSTREAM_TOKEN OLCRTC_GATE_JITSI_HOSTS
  # -v: in package-list mode go test prints a passing test's stdout only with it.
  (cd "${engine_dir}" && go test -count=1 "${build_flags[@]}" -run "${SUITE_RUN}" -v "${SUITE_PKG}" \
    "${suite[@]}" -olcrtc.gate-dry) 2>&1 | tee "${dir}/plan.log" ||
    die "the ${GATE_PROVIDER}/${client} dry run failed"
  grep -E "^engine-[a-z0-9]+/${GATE_PROVIDER}/[a-z0-9]+/${client}/S[0-9]+$" "${dir}/plan.log" |
    awk '!seen[$0]++' >"${dir}/plan.txt" || true
  [ -s "${dir}/plan.txt" ] || die "the ${GATE_PROVIDER}/${client} dry run planned no cells"
  echo "${GATE_PROVIDER}/${client}: $(wc -l <"${dir}/plan.txt") cells planned" >&2
}

cmd_run() {
  local client="${1:-}" dir="${2:-}"
  [ -n "${dir}" ] || die "usage: gate-run.sh run <cli|mobile> <dir>"
  local -a build_flags suite
  go_build_flags "${client}"
  absolute_dir "${dir}"
  suite_flags "${client}" "${dir}"
  [ -n "${GATE_ENGINE_SHA:-}" ] || die "GATE_ENGINE_SHA is not set"

  # Report metadata, then the leg's secrets under the engine's names. Exported
  # by the shell, so they reach go test through its environment and never
  # through anybody's argv; the repository names are dropped after.
  export OLCRTC_GATE_ENGINE_COMMIT="${GATE_ENGINE_SHA}"
  export OLCRTC_GATE_ENGINE_REF="${GATE_ENGINE_VERSION:-}"
  export OLCRTC_GATE_APP_VERSION="${GATE_APP_VERSION:-}"
  unset OLCRTC_GATE_TELEMOST_ROOMS OLCRTC_GATE_WBSTREAM_ROOMS OLCRTC_GATE_WBSTREAM_TOKEN OLCRTC_GATE_JITSI_HOSTS
  case "${GATE_PROVIDER}" in
    telemost)
      OLCRTC_GATE_TELEMOST_ROOMS="$(first_entry "${GATE_TELEMOST_ROOMS:-}")"
      [ -n "${OLCRTC_GATE_TELEMOST_ROOMS}" ] || die "the telemost leg needs GATE_TELEMOST_ROOMS"
      export OLCRTC_GATE_TELEMOST_ROOMS
      ;;
    wbstream)
      OLCRTC_GATE_WBSTREAM_ROOMS="$(first_entry "${GATE_WBSTREAM_ROOMS:-}")"
      [ -n "${OLCRTC_GATE_WBSTREAM_ROOMS}" ] || die "the wbstream leg needs GATE_WBSTREAM_ROOMS"
      OLCRTC_GATE_WBSTREAM_TOKEN="$(trim "${GATE_WBSTREAM_TOKEN:-}")"
      [ -n "${OLCRTC_GATE_WBSTREAM_TOKEN}" ] || die "the wbstream leg needs GATE_WBSTREAM_TOKEN"
      export OLCRTC_GATE_WBSTREAM_ROOMS OLCRTC_GATE_WBSTREAM_TOKEN
      ;;
    jitsi)
      if [ -n "$(joined "${GATE_JITSI_HOSTS:-}")" ]; then
        OLCRTC_GATE_JITSI_HOSTS="$(joined "${GATE_JITSI_HOSTS}")"
        export OLCRTC_GATE_JITSI_HOSTS
      fi
      ;;
  esac
  unset GATE_TELEMOST_ROOMS GATE_WBSTREAM_ROOMS GATE_WBSTREAM_TOKEN GATE_JITSI_HOSTS

  (cd "${engine_dir}" && go test -count=1 "${build_flags[@]}" -timeout "$(go_timeout "${client}")" \
    -run "${SUITE_RUN}" -v "${SUITE_PKG}" "${suite[@]}") 2>&1 | tee "${dir}/go-test.log"
}

cmd_summary() {
  local leg="${1:-}" client plan planned report
  [ -n "${leg}" ] || die "usage: gate-run.sh summary <leg dir>"
  local provider
  provider="$(basename "${leg}")"
  for client in cli mobile; do
    plan="${leg}/${client}/plan.txt"
    report="${leg}/${client}/${REPORT_NAME}"
    planned="no plan"
    if [ -s "${plan}" ]; then planned="$(grep -c . "${plan}") planned"; fi
    if [ -s "${report}" ] && jq -e . "${report}" >/dev/null 2>&1; then
      jq -r --arg leg "${provider}/${client}" --arg planned "${planned}" \
        '"- `\($leg)`: \($planned); report: \(.passed // 0) passed, \(.failed // 0) failed of \(.planned // 0), \(.executed // 0) ran"' \
        "${report}"
    else
      echo "- \`${provider}/${client}\`: ${planned}; no report (a timeout, a crash or a missing secret: see this job's log)"
    fi
  done
}

cmd_render() {
  local report="${1:-}"
  [ -f "${report}" ] || die "usage: gate-run.sh render <report.json>"
  report="$(cd "$(dirname "${report}")" && pwd)/$(basename "${report}")"
  cd "${engine_dir}"
  go run ./cmd/gate-report render "${report}"
}

cmd_compare() {
  local severity="${1:-}" prev="${2:-}" cur="${3:-}"
  case "${severity}" in warn | fail) ;; *) die "usage: gate-run.sh compare <warn|fail> <prev> <cur>" ;; esac
  [ -f "${prev}" ] && [ -f "${cur}" ] || die "compare needs two report files"
  prev="$(cd "$(dirname "${prev}")" && pwd)/$(basename "${prev}")"
  cur="$(cd "$(dirname "${cur}")" && pwd)/$(basename "${cur}")"
  cd "${engine_dir}"
  # Flags before the files: Go's FlagSet stops at the first positional argument.
  go run ./cmd/gate-report compare -severity "${severity}" "${prev}" "${cur}"
}

cmd_merge() {
  exec python3 "${here}/gate-merge-reports.py" merge --report-name "${REPORT_NAME}" "$@"
}

sub="${1:-}"
shift || true
case "${sub}" in
  paths) cmd_paths ;;
  unit) cmd_unit "$@" ;;
  plan) cmd_plan "$@" ;;
  run) cmd_run "$@" ;;
  summary) cmd_summary "$@" ;;
  render) cmd_render "$@" ;;
  compare) cmd_compare "$@" ;;
  merge) cmd_merge "$@" ;;
  *) die "usage: gate-run.sh paths|unit|plan|run|summary|render|compare|merge ..." ;;
esac
