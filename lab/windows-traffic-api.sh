#!/usr/bin/env bash
# What the Windows tun's sing-box says of the flows it carries, asked of the
# pinned release on Windows: the names of the totals, how a flow names its
# program, and whether the rule on a line's binary cares how the path is
# spelled. scripts/check-singbox-traffic-api.sh asks the same on Linux and on
# a Mac; nothing had asked a Windows build.
#
#   lab/windows-traffic-api.sh /path/to/sing-box.exe
set -uo pipefail
bin="$1"
"$bin" version | head -1

work="$RUNNER_TEMP/traffic-api"
rm -rf "$work"; mkdir -p "$work"
# Windows' own curl: one file, no libraries beside it, so a copy of it runs.
cp /c/Windows/System32/curl.exe "$work/line-binary.exe"
cp /c/Windows/System32/curl.exe "$work/users-program.exe"
line="$(cygpath -w "$work/line-binary.exe")"
user="$(cygpath -w "$work/users-program.exe")"
echo "the line's binary, as the rule names it: $line"
secret="0f1e2d3c4b5a69788796a5b4c3d2e1f0"

pids=()
cleanup() { for pid in "${pids[@]:-}"; do kill "$pid" 2>/dev/null || true; done; }
trap cleanup EXIT

head -c 60000000 /dev/urandom > "$work/payload.bin"
(cd "$work" && exec python -m http.server 18823 --bind 127.0.0.1 >/dev/null 2>&1) &
pids+=("$!")

# ask <how the rule spells the line's path>: starts the pair, sends one flow of each program, prints what the API lists.
ask() {
  local spelled="$1" label="$2"
  SPELLED="$spelled" SECRET="$secret" WORK="$(cygpath -w "$work")" python - <<'PY'
import json, os
work, spelled, secret = os.environ["WORK"], os.environ["SPELLED"], os.environ["SECRET"]
json.dump({"log": {"level": "warn"},
           "inbounds": [{"type": "socks", "tag": "in", "listen": "127.0.0.1", "listen_port": 18821}],
           "outbounds": [{"type": "direct", "tag": "direct"}]}, open(os.path.join(work, "upstream.json"), "w"))
json.dump({"log": {"level": "warn"},
           "inbounds": [{"type": "socks", "tag": "tun-in", "listen": "127.0.0.1", "listen_port": 18820}],
           "outbounds": [{"type": "socks", "tag": "out", "server": "127.0.0.1", "server_port": 18821, "version": "5"},
                         {"type": "direct", "tag": "direct"}],
           "route": {"rules": [{"inbound": ["tun-in"], "process_path": [spelled], "outbound": "direct"}]},
           "experimental": {"clash_api": {"external_controller": "127.0.0.1:18822", "secret": secret}}},
          open(os.path.join(work, "tun.json"), "w"))
PY
  "$bin" run -c "$work/upstream.json" >"$work/upstream.log" 2>&1 &
  local upstream=$!
  "$bin" run -c "$work/tun.json" >"$work/tun.log" 2>&1 &
  local tun=$!
  local api="http://127.0.0.1:18822/connections"
  for _ in $(seq 1 100); do
    [ "$(curl -s -o /dev/null -w '%{http_code}' "$api" || true)" = "401" ] &&
      [ "$("$work/users-program.exe" -s -o /dev/null -w '%{http_code}' --socks5-hostname 127.0.0.1:18820 http://127.0.0.1:18823/upstream.json || true)" = "200" ] && break
    sleep 0.1
  done
  "$work/line-binary.exe" -s --socks5-hostname 127.0.0.1:18820 --limit-rate 2000k -o NUL http://127.0.0.1:18823/payload.bin &
  local a=$!
  "$work/users-program.exe" -s --socks5-hostname 127.0.0.1:18820 --limit-rate 2000k -o NUL http://127.0.0.1:18823/payload.bin &
  local b=$!
  sleep 2
  echo "=== the rule spells the line's path $label: $spelled"
  curl -s -H "Authorization: Bearer $secret" "$api" | LINE="$line" USER_PROGRAM="$user" python -c '
import json, os, sys
try: answer = json.load(sys.stdin)
except Exception as e: print("   the API did not answer:", e); sys.exit(0)
print("   totals:", {k: answer.get(k) for k in ("downloadTotal", "uploadTotal")}, "keys:", sorted(answer.keys()))
for flow in answer.get("connections") or []:
    m = flow["metadata"]
    mine = "the line" if m["processPath"].lower().startswith(os.environ["LINE"].lower()) else "the user" if m["processPath"].lower().startswith(os.environ["USER_PROGRAM"].lower()) else "?"
    exact = m["processPath"] in (os.environ["LINE"], os.environ["USER_PROGRAM"]) or any(m["processPath"].startswith(p + " (") for p in (os.environ["LINE"], os.environ["USER_PROGRAM"]))
    print("   flow of %-8s by %-10s named %r, spelled as the app spells it: %s, down %s" % (mine, "/".join(flow["chains"]), m["processPath"], exact, flow["download"]))'
  kill "$a" "$b" "$tun" "$upstream" 2>/dev/null
  sleep 1
  tail -2 "$work/tun.log" | cut -c1-200 | sed 's/^/   tun: /'
}

ask "$line" "as Windows spells it"
ask "$(printf '%s' "$line" | python -c 'import sys; p=sys.stdin.read(); print(p[0].lower() + p[1:], end="")')" "with a small drive letter"
ask "$(printf '%s' "$line" | python -c 'import sys; print(sys.stdin.read().upper(), end="")')" "in capitals"
ask "$(printf '%s' "$line" | tr '\\' '/')" "with forward slashes"
