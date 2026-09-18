#!/usr/bin/env bash
# What one run of the release gate tests, and whether a leg holds what it needs.
#
#   gate-resolve.sh               key=value lines for $GITHUB_OUTPUT (gate.yml, resolve)
#   gate-resolve.sh check <leg>   exit 1, naming the secret, when a leg's secrets are
#                                 missing or malformed (gate.yml, every suite leg)
#
# Resolve reads the run from the environment the workflow step sets:
#   GATE_EVENT                  github.event_name
#   GATE_CALL_MODE              inputs.mode; set only by a caller (release.yml
#                               passes "release"). Inside a called workflow the
#                               github context is the caller's, so the event says
#                               workflow_dispatch for a release: the mode input is
#                               what tells the two apart.
#   GATE_CALL_ENGINE_SHA, GATE_CALL_ENGINE_VERSION, GATE_CALL_APP_VERSION,
#   GATE_CALL_SKIP, GATE_CALL_SKIP_REASON, GATE_CALL_SEVERITY   the caller's inputs
#   GATE_ENGINE_REF, GATE_PROVIDERS, GATE_TRANSPORTS            a manual run's inputs
#   GITHUB_REF_NAME, GITHUB_SHA, GH_TOKEN
#
# and writes:
#   mode                        release | scheduled (the daily run) | pin (a push
#                               that touched the pin or the gate) | manual
#   engine_repository, engine_sha, engine_short, engine_version, engine_pinned,
#   engine_on_proofkit, engine_proofkit_ahead_by, go_version
#   app_version, current_tag    current_tag is v<app_version> for a release, else empty
#   severity                    compare severity: warn unless a caller asks for fail
#   skip                        true only for a release dispatched with gate: skip
#   limited                     true when a manual run narrowed providers or transports
#   providers, transports
#   legs                        {"include":[{"provider":"jitsi"},...]}, one per provider
#
# Outputs carry no secret: this job holds none. The legs check their own.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/gate-api.sh
. "${here}/gate-api.sh"

readonly PROVIDERS=(jitsi telemost wbstream)
# Masks shorter than this shred every log line they match (gate-mask.sh).
readonly MIN=6
readonly MIN_TOKEN=16

die() {
  echo "::error title=Release gate::$*" >&2
  exit 1
}

err() {
  echo "::error title=Release gate secret::$*" >&2
}

trim() {
  local s="$1"
  s="${s#"${s%%[![:space:]]*}"}"
  printf '%s' "${s%"${s##*[![:space:]]}"}"
}

# entries <list>: trimmed, non-empty entries, one per line; commas and
# newlines both separate.
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

# field <key> <key=value lines>
field() {
  awk -v k="$1" 'index($0, k "=") == 1 { print substr($0, length(k) + 2); exit }' <<<"$2"
}

one_line() {
  case "$2" in
    *$'\n'* | *$'\r'*) die "$1 must be a single line" ;;
  esac
}

resolve() {
  local mode="${GATE_CALL_MODE:-}"
  if [ -n "${mode}" ]; then
    [ "${mode}" = release ] || die "mode '${mode}' is not one a caller can ask for (release)"
  else
    case "${GATE_EVENT:-}" in
      schedule) mode=scheduled ;;
      push) mode=pin ;;
      workflow_dispatch) mode=manual ;;
      *) die "no gate mode for the event '${GATE_EVENT:-}'" ;;
    esac
  fi

  local skip=false reason
  if [ "${mode}" = release ] && [ "${GATE_CALL_SKIP:-false}" = true ]; then
    skip=true
    reason="$(trim "${GATE_CALL_SKIP_REASON:-}")"
    [ -n "${reason}" ] || die "gate: skip needs a gate_reason; the release notes print it"
  fi

  local pin app_version current_tag=""
  if [ "${mode}" = release ]; then
    [[ "${GATE_CALL_ENGINE_SHA:-}" =~ ^[0-9a-f]{40}$ ]] || die "engine_sha must be a 40-hex commit"
    app_version="${GATE_CALL_APP_VERSION:-}"
    [ -n "${app_version}" ] || die "app_version is empty"
    one_line app_version "${app_version}"
    current_tag="v${app_version}"
    # The caller resolved the pin on the same commit; a disagreement means the
    # caller passed something else, and the gate would test the wrong engine.
    pin="$("${here}/olcrtc-pin.sh")"
    [ "$(field olcrtc_sha "${pin}")" = "${GATE_CALL_ENGINE_SHA}" ] ||
      die "the caller passed engine ${GATE_CALL_ENGINE_SHA:0:12}, but scripts/cores-pins.sh on this commit pins $(field olcrtc_rev "${pin}")"
    if [ -n "${GATE_CALL_ENGINE_VERSION:-}" ] && [ "${GATE_CALL_ENGINE_VERSION}" != "$(field olcrtc_version "${pin}")" ]; then
      die "the caller passed engine version ${GATE_CALL_ENGINE_VERSION}, but scripts/cores-pins.sh says $(field olcrtc_version "${pin}")"
    fi
  else
    app_version="${GITHUB_REF_NAME:-local}@${GITHUB_SHA:0:7}"
    one_line app_version "${app_version}"
    if [ "${mode}" = manual ] && [ -n "$(trim "${GATE_ENGINE_REF:-}")" ]; then
      pin="$("${here}/olcrtc-pin.sh" --ref "$(trim "${GATE_ENGINE_REF}")")"
    else
      pin="$("${here}/olcrtc-pin.sh")"
    fi
  fi

  local repo sha version
  repo="$(field olcrtc_repository "${pin}")"
  sha="$(field olcrtc_sha "${pin}")"
  version="$(field olcrtc_version "${pin}")"

  local severity=warn
  if [ "${mode}" = release ] && [ -n "${GATE_CALL_SEVERITY:-}" ]; then
    severity="${GATE_CALL_SEVERITY}"
  fi
  case "${severity}" in warn | fail) ;; *) die "compare_severity must be warn or fail" ;; esac

  local -a providers=() picked
  local limited=false transports="" p t
  if [ "${mode}" = manual ] && [ -n "$(trim "${GATE_PROVIDERS:-}")" ]; then
    mapfile -t picked < <(entries "${GATE_PROVIDERS}")
    for p in "${picked[@]}"; do
      case " ${PROVIDERS[*]} " in
        *" ${p} "*) ;;
        *) die "unknown provider '${p}' (jitsi, telemost, wbstream)" ;;
      esac
    done
    for p in "${PROVIDERS[@]}"; do
      case " ${picked[*]} " in *" ${p} "*) providers+=("${p}") ;; esac
    done
  else
    providers=("${PROVIDERS[@]}")
  fi
  [ "${#providers[@]}" -gt 0 ] || die "no provider chosen"
  [ "${#providers[@]}" -eq "${#PROVIDERS[@]}" ] || limited=true
  if [ "${mode}" = manual ] && [ -n "$(trim "${GATE_TRANSPORTS:-}")" ]; then
    mapfile -t picked < <(entries "${GATE_TRANSPORTS}")
    for t in "${picked[@]}"; do
      [[ "${t}" =~ ^[a-z0-9]+$ ]] || die "'${t}' is not a transport name"
    done
    transports="$(printf '%s\n' "${picked[@]}" | awk '!seen[$0]++' | paste -sd, -)"
    limited=true
  fi

  # The pin can predate the gate (aaffe1e0 does): no internal/gate, no suite.
  # A skipped release does not run it, so it does not need it either.
  if [ "${skip}" = false ]; then
    local path code
    local -a paths
    mapfile -t paths < <("${here}/gate-run.sh" paths)
    [ "${#paths[@]}" -gt 0 ] || die "gate-run.sh names no engine paths to check"
    for path in "${paths[@]}"; do
      code="$(gate_api_status "repos/${repo}/contents/${path}?ref=${sha}")"
      case "${code}" in
        200) ;;
        404)
          die "engine ${sha:0:12} (${version}) has no ${path}: it predates the release gate. Re-pin to an engine commit that carries internal/gate (OLCRTC_VERSION in scripts/cores-pins.sh; docs/release-gate.md, Re-pinning). Until then a release passes only with gate: skip and a reason."
          ;;
        *) die "could not tell whether engine ${sha:0:12} carries ${path} (HTTP ${code})" ;;
      esac
    done
  fi

  local legs
  legs="$(jq -cn '{include: [$ARGS.positional[] | {provider: .}]}' --args "${providers[@]}")"

  echo "gate: ${mode}, engine ${sha:0:12} (${version}), app ${app_version}, legs ${providers[*]}${transports:+, transports ${transports}}, skip ${skip}" >&2
  printf '%s\n' \
    "mode=${mode}" \
    "engine_repository=${repo}" \
    "engine_sha=${sha}" \
    "engine_short=${sha:0:12}" \
    "engine_version=${version}" \
    "engine_pinned=$(field olcrtc_pinned "${pin}")" \
    "engine_on_proofkit=$(field olcrtc_on_proofkit "${pin}")" \
    "engine_proofkit_ahead_by=$(field olcrtc_proofkit_ahead_by "${pin}")" \
    "go_version=$(field go_version "${pin}")" \
    "app_version=${app_version}" \
    "current_tag=${current_tag}" \
    "severity=${severity}" \
    "skip=${skip}" \
    "limited=${limited}" \
    "providers=$(printf '%s\n' "${providers[@]}" | paste -sd, -)" \
    "transports=${transports}" \
    "legs=${legs}"
}

# describe <secret>: what belongs in it, for the error that says it is missing.
describe() {
  case "$1" in
    GATE_TELEMOST_ROOMS) echo "Telemost room ids or links, comma-separated; the gate joins the first" ;;
    GATE_WBSTREAM_ROOMS) echo "WB Stream room ids, comma-separated; the gate joins the first" ;;
    GATE_WBSTREAM_TOKEN) echo "a WB Stream account access token; WB refuses a guest as the first participant of an idle room" ;;
    GATE_JITSI_HOSTS) echo "Jitsi host names, comma-separated" ;;
  esac
}

# check_list <provider> <secret> <required|optional> <room|host>. Messages name
# the secret and the entry's position, never a value.
check_list() {
  local provider="$1" name="$2" need="$3" kind="$4"
  local -a items
  local e base seg i=0 bad=0
  mapfile -t items < <(entries "${!name:-}")
  if [ "${#items[@]}" -eq 0 ]; then
    if [ "${need}" = required ]; then
      err "the ${provider} leg needs the repository secret ${name} ($(describe "${name}")). A missing secret fails the leg; it is never skipped."
      return 1
    fi
    return 0
  fi
  for e in "${items[@]}"; do
    i=$((i + 1))
    case "${kind}" in
      room)
        base="${e%%[?#]*}"
        base="${base%/}"
        seg="${base##*/}"
        if [[ "${e}" =~ [[:space:]] ]]; then
          err "${name}, entry ${i}: contains whitespace"
          bad=1
        elif [ "${#seg}" -lt "${MIN}" ]; then
          err "${name}, entry ${i}: the room id is shorter than ${MIN} characters, too short to mask in a log"
          bad=1
        fi
        ;;
      host)
        # A bare host and nothing else: gate-mask.sh and gate-scrub.py take the
        # entry as it is, and a Go error prints the host without a port
        # ("lookup <host>", "certificate is valid for ..., not <host>"). A
        # host:port entry would leave that bare host unmasked and unscrubbed.
        if [[ ! "${e}" =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ ]]; then
          err "${name}, entry ${i}: not a bare host name (no scheme, no port, no path)"
          bad=1
        elif [ "${#e}" -lt "${MIN}" ]; then
          err "${name}, entry ${i}: shorter than ${MIN} characters, too short to mask in a log"
          bad=1
        fi
        ;;
    esac
  done
  return "${bad}"
}

check_token() {
  local provider="$1" name="$2" value
  value="$(trim "${!name:-}")"
  if [ -z "${value}" ]; then
    err "the ${provider} leg needs the repository secret ${name} ($(describe "${name}")). A missing secret fails the leg; it is never skipped."
    return 1
  fi
  if [[ "${value}" =~ [[:space:]] ]]; then
    err "${name}: contains whitespace"
    return 1
  fi
  if [ "${#value}" -lt "${MIN_TOKEN}" ]; then
    err "${name}: shorter than ${MIN_TOKEN} characters, which no access token is"
    return 1
  fi
}

check() {
  local provider="${1:-}" bad=0
  case "${provider}" in
    jitsi)
      check_list jitsi GATE_JITSI_HOSTS optional host || bad=1
      ;;
    telemost)
      check_list telemost GATE_TELEMOST_ROOMS required room || bad=1
      ;;
    wbstream)
      check_list wbstream GATE_WBSTREAM_ROOMS required room || bad=1
      check_token wbstream GATE_WBSTREAM_TOKEN || bad=1
      ;;
    *) die "usage: gate-resolve.sh check <jitsi|telemost|wbstream>" ;;
  esac
  [ "${bad}" -eq 0 ] || exit 1
  echo "the ${provider} leg's secrets are in place" >&2
}

case "${1:-}" in
  "") resolve ;;
  check) check "${2:-}" ;;
  *) die "usage: gate-resolve.sh [check <provider>]" ;;
esac
