#!/usr/bin/env bash
# Asks the Rust core itself about every core.json reference config:
#
#   scripts/check-rust-core-configs.sh <xray-rust-binary>
#
# for each sharedUI/src/jvmTest/resources/reference-configs/*core-*.json (the
# iPhone's ios-core-*.json and bypass-ios-core-*.json, and core-*.json and
# bypass-core-*.json of every other kind of line), the configs
# RustCoreConfigDumpTest holds the app's writer (net/core) to, byte for byte,
# and each blocked-only/*core-*.json there, the blocked-only mode's, which
# InteropDumpTest holds to the writer:
#
#   1. `<xray-rust-binary> config check -config <file> --geodata-dir <dir>`:
#      the core's parser. It refuses a whole config over one field it does not
#      know; exit 0 is accepted (warnings allowed, and printed), 1 refused, 2
#      a usage or I/O error. It parses only.
#   2. `<xray-rust-binary> run -config <copy>`: the core built from it and
#      started, on a free loopback port instead of 10810, until it says it
#      bound the SOCKS inbound; then it is stopped. That is the step a value
#      the parser takes but the core cannot build fails at (the outbound
#      graph, the DNS servers, the listener). No connection is made: the
#      lines' servers are test addresses.
#
# The geodata directory is the app's: a copy of the lists it bundles
# (sharedUI/src/commonMain/composeResources/files/xray/*.dat), as the host
# hands the core the directory it unpacked them to. The parser is given it as
# its only one, and the run's copy is written into it, where the core looks
# first; a list a config names that the app does not bundle fails both steps.
# Copies, not links: the core opens a list without following a link. Each
# config that names a list is also handed to the parser with an empty
# directory, which it must refuse (exit 1): a reference the parser takes with
# no lists proves nothing of them.
#
# The binary is ghostlane-project/xray-rust's `xray-rust` (crates/xray-cli),
# built from the fork the app links; PR Checks builds it at its pinned commit
# (job "Rust core: the app's core.json"). Any refusal fails this script.
# Needs bash and python3.
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "usage: $0 <xray-rust-binary>" >&2
  exit 2
fi
bin="$1"
if [ ! -x "$bin" ] && ! command -v "$bin" >/dev/null 2>&1; then
  echo "not an executable: $bin" >&2
  exit 2
fi

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
references="$root/sharedUI/src/jvmTest/resources/reference-configs"

shopt -s nullglob
configs=("$references"/*core-*.json)
if [ "${#configs[@]}" -eq 0 ]; then
  echo "no core.json reference configs (*core-*.json) under $references" >&2
  exit 1
fi
blocked=("$references"/blocked-only/*core-*.json)

work="$(mktemp -d)"
server=""
geodata="$work/geodata"
nolists="$work/no-lists"
mkdir "$geodata" "$nolists"
# No lists is no failure of its own: a config that names one fails the parser.
lists=("$root"/sharedUI/src/commonMain/composeResources/files/xray/*.dat)
if [ "${#lists[@]}" -gt 0 ]; then
  cp "${lists[@]}" "$geodata/"
fi
# Invoked by the trap below, which shellcheck does not count as a call.
# shellcheck disable=SC2329
cleanup() {
  if [ -n "$server" ]; then kill -9 "$server" 2>/dev/null || true; fi
  rm -rf "$work"
}
trap cleanup EXIT

# starts <config>: 0 when the core started from it and bound its inbound.
starts() {
  local config="$1" copy="$geodata/run.json" log="$work/run.log" port
  port="$(python3 -I - "$config" "$copy" <<'PY'
import json, socket, sys
config = json.load(open(sys.argv[1]))
with socket.socket() as s:
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
inbounds = config.get("inbounds") or []
if len(inbounds) != 1:
    sys.exit("want one inbound, found %d" % len(inbounds))
inbounds[0]["port"] = port
json.dump(config, open(sys.argv[2], "w"))
print(port)
PY
)"
  "$bin" run -config "$copy" > "$log" 2>&1 &
  server=$!
  for _ in $(seq 1 100); do
    if grep -q "bound inbound in at 127.0.0.1:$port" "$log"; then
      break
    fi
    if ! kill -0 "$server" 2>/dev/null; then
      break
    fi
    sleep 0.1
  done
  local ok=1
  grep -q "bound inbound in at 127.0.0.1:$port" "$log" && ok=0
  if kill -0 "$server" 2>/dev/null; then
    kill -TERM "$server" 2>/dev/null || true
    for _ in $(seq 1 50); do
      kill -0 "$server" 2>/dev/null || break
      sleep 0.1
    done
    kill -9 "$server" 2>/dev/null || true
  else
    ok=1
  fi
  wait "$server" 2>/dev/null || true
  server=""
  if [ "$ok" -ne 0 ]; then
    echo "  the core did not start from it (port $port); its output:"
    sed 's/^/    /' "$log" | tail -n 20
  fi
  return "$ok"
}

# refused_without_lists <config>: 0 when the parser refuses it (exit 1) with
# an empty geodata directory as its only one.
refused_without_lists() {
  local code=0
  "$bin" config check -config "$1" --geodata-dir "$nolists" > "$work/check.log" 2>&1 || code=$?
  if [ "$code" -eq 1 ]; then
    return 0
  fi
  echo "  with no lists the parser gave exit $code; its output:"
  sed 's/^/    /' "$work/check.log" | tail -n 20
  return 1
}

failed=0
for config in "${configs[@]}" "${blocked[@]}"; do
  name="${config#"$references"/}"
  if ! "$bin" config check -config "$config" --geodata-dir "$geodata"; then
    echo "FAIL $name: the parser refuses it"
    failed=1
  elif grep -q '"ext:' "$config" && ! refused_without_lists "$config"; then
    echo "FAIL $name: it names a list, and the parser does not refuse it with no lists"
    failed=1
  elif ! starts "$config"; then
    echo "FAIL $name: parsed, but the core does not start from it"
    failed=1
  else
    echo "ok   $name: parsed, started, bound its SOCKS port"
  fi
done
exit "$failed"
