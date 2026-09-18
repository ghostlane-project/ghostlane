# shellcheck shell=bash
# Read-only GitHub REST calls for olcrtc-pin.sh, gate-resolve.sh and
# gate-previous-report.sh. Sourced, never run.
#
# curl rather than gh: the answer's HTTP status is what these scripts decide
# on (a 404 from the contents API is "this engine has no gate", anything else
# is "could not tell"), curl says it exactly, and the offline tests point
# GITHUB_API_URL at a local server instead of faking a CLI. The runner sets
# GITHUB_API_URL; outside Actions it is api.github.com.
#
# GH_TOKEN, when set, reaches curl on stdin as a config line, so it never sits
# in a process's argv. curl drops that header on a redirect to another host
# (release assets redirect to storage), so the token stays with GitHub.

# gate_api_url <path|url>: a path is taken relative to the API root.
gate_api_url() {
  case "$1" in
    http://* | https://*) printf '%s' "$1" ;;
    *) printf '%s/%s' "${GITHUB_API_URL:-https://api.github.com}" "${1#/}" ;;
  esac
}

# _gate_curl <accept> <curl args...>
_gate_curl() {
  local accept="$1"
  shift
  {
    if [ -n "${GH_TOKEN:-}" ]; then
      printf 'header = "Authorization: Bearer %s"\n' "${GH_TOKEN}"
    fi
  } | curl --config - --silent --show-error --location \
    --retry 3 --retry-delay 2 --connect-timeout 10 --max-time 120 \
    -H "Accept: ${accept}" -H 'X-GitHub-Api-Version: 2022-11-28' "$@"
}

# gate_api_get <path|url>: the body on stdout; non-zero on HTTP >= 400 or no answer.
gate_api_get() {
  local url
  url="$(gate_api_url "$1")"
  _gate_curl 'application/vnd.github+json' --fail "${url}"
}

# gate_api_download <path|url> <file>: a release asset's bytes into <file>.
gate_api_download() {
  local url
  url="$(gate_api_url "$1")"
  _gate_curl 'application/octet-stream' --fail --output "$2" "${url}"
}

# gate_api_status <path|url>: the HTTP status alone, 000 when nothing answered.
gate_api_status() {
  local url code
  url="$(gate_api_url "$1")"
  code="$(_gate_curl 'application/vnd.github+json' --output /dev/null --write-out '%{http_code}' "${url}" 2>/dev/null)" || true
  printf '%s' "${code:-000}"
}

# gate_uri <text>: percent-encoded for one path segment or query value.
gate_uri() {
  jq -rn --arg v "$1" '$v|@uri'
}
