#!/usr/bin/env bash
# Asks the Rust core itself about every core.json reference config:
#
#   scripts/check-rust-core-configs.sh <xray-rust-binary>
#
# for each sharedUI/src/jvmTest/resources/reference-configs/ios-core-*.json
# and bypass-ios-core-*.json, the configs RustCoreConfigDumpTest holds
# XrayConfig.buildIosCore to:
#
#   1. `<xray-rust-binary> config check -config <file>`: the core's parser.
#      It refuses a whole config over one field it does not know; exit 0 is
#      accepted (warnings allowed, and printed), 1 refused, 2 a usage or I/O
#      error. It parses only.
#   2. `<xray-rust-binary> run -config <copy>`: the core built from it and
#      started, on a free loopback port instead of 10810, until it says it
#      bound the SOCKS inbound; then it is stopped. That is the step a value
#      the parser takes but the core cannot build fails at (the outbound
#      graph, the DNS servers, the listener). No connection is made: the
#      lines' servers are test addresses.
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
configs=("$references"/ios-core-*.json "$references"/bypass-ios-core-*.json)
if [ "${#configs[@]}" -eq 0 ]; then
  echo "no ios-core reference configs under $references" >&2
  exit 1
fi

work="$(mktemp -d)"
server=""
# Invoked by the trap below, which shellcheck does not count as a call.
# shellcheck disable=SC2329
cleanup() {
  if [ -n "$server" ]; then kill -9 "$server" 2>/dev/null || true; fi
  rm -rf "$work"
}
trap cleanup EXIT

# starts <config>: 0 when the core started from it and bound its inbound.
starts() {
  local config="$1" copy="$work/run.json" log="$work/run.log" port
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

failed=0
for config in "${configs[@]}"; do
  name="$(basename "$config")"
  if ! "$bin" config check -config "$config"; then
    echo "FAIL $name: the parser refuses it"
    failed=1
  elif ! starts "$config"; then
    echo "FAIL $name: parsed, but the core does not start from it"
    failed=1
  else
    echo "ok   $name: parsed, started, bound its SOCKS port"
  fi
done
exit "$failed"
