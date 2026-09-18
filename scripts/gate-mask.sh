#!/usr/bin/env bash
# Registers the gate's secret values with the runner's log masker. Run as the
# first step that holds them, in every job that holds them; masks are per job.
#
# GitHub masks a secret's whole value, and a pool is a whole comma list, so
# none of its entries on its own is masked until this says so. For every entry
# of GATE_TELEMOST_ROOMS, GATE_WBSTREAM_ROOMS and GATE_JITSI_HOSTS (commas or
# newlines separate, whitespace trimmed) it masks the entry, the entry without
# its query or fragment, and its last path segment - a room URL's id, which is
# what a log line that builds its own URL prints - and it masks
# GATE_WBSTREAM_TOKEN whole.
#
# Nothing shorter than six characters is registered: a mask that short shreds
# every log line it matches. gate-resolve.sh check refuses such a value before
# anything runs with it, so it never reaches the suite unmasked.
#
# Prints nothing but ::add-mask:: lines. Workflow-command data is unescaped by
# the runner (%25, %0D, %0A), so those three are escaped first; an unescaped %
# would have masked a different string.
set -euo pipefail

readonly MIN=6

mask() {
  local v="$1"
  [ "${#v}" -ge "${MIN}" ] || return 0
  v="${v//%/%25}"
  v="${v//$'\r'/%0D}"
  v="${v//$'\n'/%0A}"
  printf '::add-mask::%s\n' "${v}"
}

mask_list() {
  local list="${1//$'\r'/,}"
  list="${list//$'\n'/,}"
  local -a parts
  local e base seg
  IFS=',' read -r -a parts <<<"${list}"
  for e in "${parts[@]}"; do
    e="${e#"${e%%[![:space:]]*}"}"
    e="${e%"${e##*[![:space:]]}"}"
    [ -n "${e}" ] || continue
    mask "${e}"
    base="${e%%[?#]*}"
    [ "${base}" = "${e}" ] || mask "${base}"
    base="${base%/}"
    seg="${base##*/}"
    [ "${seg}" = "${e}" ] || mask "${seg}"
  done
}

mask_list "${GATE_TELEMOST_ROOMS:-}"
mask_list "${GATE_WBSTREAM_ROOMS:-}"
mask_list "${GATE_JITSI_HOSTS:-}"
token="${GATE_WBSTREAM_TOKEN:-}"
token="${token#"${token%%[![:space:]]*}"}"
token="${token%"${token##*[![:space:]]}"}"
if [ -n "${token}" ]; then mask "${token}"; fi
