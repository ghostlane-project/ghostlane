#!/bin/bash
# A real room behind the Mac's kill switch: the engine the app ships, a call of
# the gate's pool, and an exit that is another machine (the workflow's other
# job). Asked three ways: with no switch; behind a tunnel under the block, the
# engine the app's own child, as the build before this fix had it; and run by
# the helper, the tunnel started after it, as the app now does.
#
#   sudo --preserve-env=… bash lab/macos-room.sh <helper-check> <sing-box> <user> <engine>
# Environment: ROOM, ROOM_KEY, ROOM_CHANNEL, PROVIDER, TRANSPORT; GITHUB_* for the marks.
set -u
HELPER="$1"; SB="$2"; WHO="$3"; ENGINE_BUILT="$4"
WHO_UID="$(id -u "$WHO")"; WHO_GID="$(id -g "$WHO")"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REFERENCE="$root/sharedUI/src/jvmTest/resources/reference-configs/desktop-tun-macos-names.json"
ANCHOR='com.apple/100.org.olcbox.app.hold'
KEY='State:/Network/Service/org.olcbox.app.tunnel/DNS'
LINES='/Library/Application Support/org.olcbox.app.lines'
GROUP=7302243
PORT=10810
work="$(cd /tmp && pwd -P)/ghostlane-room.$$"
mkdir -p "$work/line"; chmod 755 "$work"
python3 -c 'import os,sys,time,subprocess
os.setsid(); time.sleep(420); subprocess.run(["/sbin/pfctl","-a",sys.argv[1],"-F","all"])' "$ANCHOR" >/dev/null 2>&1 &

say() { printf '\n== %s\n' "$*"; }
note() { printf '   %-52s %s\n' "$1" "$2"; }
now() { perl -MTime::HiRes=time -e 'printf "%d\n", time*1000'; }
as_user() { python3 -c 'import os,sys; os.setgid(int(sys.argv[2])); os.setuid(int(sys.argv[1])); os.execvp(sys.argv[3], sys.argv[3:])' "$WHO_UID" "$WHO_GID" "$@"; }
mark() {
  local text; text=$(printf '%s' "$2" | tr '\n"\\' '  /' | cut -c1-138)
  [ -n "${GITHUB_TOKEN:-}" ] || return 0
  curl -4 -s -o /dev/null --max-time 8 -X POST \
    -H "Authorization: Bearer $GITHUB_TOKEN" -H "Accept: application/vnd.github+json" \
    "https://api.github.com/repos/$GITHUB_REPOSITORY/statuses/$GITHUB_SHA" \
    -d "{\"state\":\"success\",\"context\":\"room/$PROVIDER/$1\",\"description\":\"$text\"}" || true
}
# Who the internet sees: this Mac, or the exit a room leads to. Empty for no answer.
seen_as() { as_user curl -4 -s --max-time "${2:-8}" ${1:+--socks5-hostname "$1"} https://api.ipify.org 2>/dev/null | grep -E '^[0-9.]+$' | head -1; }
# What an engine's log says, with nothing in it that names a room.
told() {
  local ice udp ready
  ice=$(grep -c "ICE connection state changed: connected" "$1" 2>/dev/null)
  udp=$(grep -cE "failed to get server reflexive|failed to allocate on TURN" "$1" 2>/dev/null)
  ready=$(grep -c "SOCKS5 server listening" "$1" 2>/dev/null)
  echo "ice connected $ice, udp ways that failed $udp, socks $ready"
}
last_words() {
  grep -E "ICE connection state|failed to get server reflexive|failed to allocate|SOCKS5 server|authenticated peer|client: " "$1" 2>/dev/null |
    sed -E 's#https?://[^ ]+#<url>#g; s/[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+/<ip>/g; s/[0-9A-Za-z+\/=_-]{16,}/<id>/g' | cut -c1-150 | tail -"${2:-6}" | sed 's/^/        /'
}
yaml() { # <dns>
  cat <<Y
mode: cnc
auth:
  provider: '$PROVIDER'
room:
  id: '$ROOM'
  channel: '$ROOM_CHANNEL'
crypto:
  key: '$ROOM_KEY'
net:
  transport: '$TRANSPORT'
  dns: '$1'
socks:
  host: '127.0.0.1'
  port: $PORT
udp:
  enabled: true
vp8:
  fps: 60
  batch_size: 64
Y
}
# Waits until the engine says its SOCKS port listens, or it is gone, or the seconds are up.
ready_within() { # <log> <seconds> <pid>
  local t0; t0=$(now)
  for _ in $(seq 1 $(( $2 * 5 ))); do
    grep -q "SOCKS5 server listening" "$1" 2>/dev/null && { echo "ready in $(( $(now) - t0 ))ms"; return 0; }
    kill -0 "$3" 2>/dev/null || { echo "gone after $(( $(now) - t0 ))ms"; return 1; }
    sleep 0.2
  done
  echo "not ready in $2 s"; return 1
}

say "the machine"
sw_vers | tr '\n' ' '; echo
IF=$(route -n get default | awk '/interface:/{print $2}')
RESOLVER=$(scutil --dns | awk '/nameserver\[0\]/{print $3; exit}')
ENGINE="$work/line/olcrtc-darwin-lab"
cp "$ENGINE_BUILT" "$ENGINE"
chown -R "$WHO" "$work/line"
MAC=$(seen_as "")
echo "way out: $IF; the system asks $RESOLVER; the internet sees this Mac as ${MAC:+an address of its own}"
[ -n "$MAC" ] || { mark 0 "this Mac has no network to begin with"; exit 1; }

python3 - "$work" "$IF" "$REFERENCE" "$ENGINE" "$LINES/olcrtc-darwin-lab" "$PORT" <<'PY'
import json, sys
work, way_out, reference, engine, copy, port = sys.argv[1:7]
config = json.load(open(reference))
tun = config["inbounds"][0]
tun.pop("route_exclude_address", None)
out, direct = config["outbounds"]
out["server_port"] = int(port)
direct["bind_interface"] = way_out
lines = [engine, copy]
config["dns"]["rules"][2]["process_path"] = lines
config["route"]["rules"][2]["process_path"] = lines
for n in range(225, 232):
    tun["interface_name"] = "utun%d" % n
    json.dump(config, open(work + "/utun%d.json" % n, "w"))
PY
request() {
  python3 - "$work" "$@" <<'PY'
import json, sys
work, verb, rest = sys.argv[1], sys.argv[2], sys.argv[3:]
r = {"verb": verb}
if verb == "start":
    r["config"] = open(work + "/" + rest[0] + ".json").read()
    r["names"] = "172.19.0.2"
    r["hold"] = {"interface": rest[0], "pass": []}
elif verb == "line":
    r["program"], r["output"], r["arguments"] = rest[0], rest[1], rest[2:]
elif rest:
    r["hold"] = rest[0]
print(json.dumps(r))
PY
}
mkfifo "$work/ask"
"$HELPER" "$SB" "$WHO_UID" <"$work/ask" >"$work/said" 2>"$work/helper.err" &
DAEMON=$!
exec 3>"$work/ask"
ask() {
  local before; before=$(wc -l <"$work/said")
  printf '%s\n' "$1" >&3
  for _ in $(seq 1 150); do [ "$(wc -l <"$work/said")" -gt "$before" ] && break; sleep 0.1; done
  tail -1 "$work/said" | python3 -c '
import json,sys
try: r=json.loads(sys.stdin.readline())
except Exception as e: print("no reply"); sys.exit(0)
print("ok=%s state=%s hold=%s line=%s error=%s" % (r.get("ok"), r.get("state"), r.get("hold"), "yes" if r.get("line") else "no", r.get("error")))'
}
field() { tail -1 "$work/said" | python3 -c 'import json,sys; print(json.loads(sys.stdin.readline()).get(sys.argv[1],0))' "$1"; }

say "1. no switch: the engine the app's own child, no tunnel"
EXIT=""
for attempt in 1 2 3 4 5 6; do
  yaml "$RESOLVER:53" > "$work/line/room.yaml"; chown "$WHO" "$work/line/room.yaml"; chmod 600 "$work/line/room.yaml"
  : > "$work/line/p1.log"
  as_user "$ENGINE" "$work/line/room.yaml" >"$work/line/p1.log" 2>&1 &
  E=$!
  r=$(ready_within "$work/line/p1.log" 45 "$E")
  [[ "$r" == ready* ]] && EXIT=$(seen_as "127.0.0.1:$PORT" 15)
  note "try $attempt" "$r; $(told "$work/line/p1.log"); through the room the internet sees ${EXIT:+another machine}${EXIT:-nobody}"
  kill "$E" 2>/dev/null; sleep 2; kill -9 "$E" 2>/dev/null
  [ -n "$EXIT" ] && [ "$EXIT" != "$MAC" ] && break
  last_words "$work/line/p1.log" 4
  EXIT=""; sleep 15
done
mark 1-no-switch "$r; $(told "$work/line/p1.log"); exit $([ -n "$EXIT" ] && echo is another machine || echo not reached)"
[ -n "$EXIT" ] || { echo "the room cannot be joined from here at all: nothing to compare"; kill -TERM "$DAEMON"; exit 1; }

say "2. as the build before: a tunnel under the block first, the engine the app's own child behind it"
note "the tunnel" "$(ask "$(request start utun225)")"
sleep 2
yaml "172.19.0.2" > "$work/line/room.yaml"
: > "$work/line/p2.log"
as_user "$ENGINE" "$work/line/room.yaml" >"$work/line/p2.log" 2>&1 &
E=$!
r=$(ready_within "$work/line/p2.log" 40 "$E")
note "the engine" "$r; $(told "$work/line/p2.log")"
last_words "$work/line/p2.log" 5
mark 2-as-before "$r; $(told "$work/line/p2.log")"
kill "$E" 2>/dev/null; sleep 1; kill -9 "$E" 2>/dev/null
note "stopped, the block kept" "$(ask "$(request stop keep)")"

say "3. as now: behind the block that stands, the helper runs the engine, and the tunnel comes after it"
yaml "$RESOLVER:53" > "$work/line/room.yaml"
SAID="$work/line/p3.log"; : > "$SAID"; chown "$WHO" "$SAID"; chmod 600 "$SAID"
note "the helper runs it" "$(ask "$(request line "$ENGINE" "$SAID" "$work/line/room.yaml")")"
LPID=$(field line)
note "as" "$(ps -o uid=,gid= -p "$LPID" 2>/dev/null | tr -s ' ') (the asker is $WHO_UID, the block lets out $GROUP)"
r=$(ready_within "$SAID" 45 "$LPID")
note "the engine" "$r; $(told "$SAID")"
last_words "$SAID" 5
THROUGH_SOCKS=$(seen_as "127.0.0.1:$PORT" 15)
note "through the room, asked of its port" "${THROUGH_SOCKS:+$([ "$THROUGH_SOCKS" = "$EXIT" ] && echo the exit || echo "somebody else")}${THROUGH_SOCKS:-nobody}"
mark 3a-by-the-helper "$r; $(told "$SAID"); gid $(ps -o gid= -p "$LPID" 2>/dev/null | tr -d ' '); by its port: $([ "$THROUGH_SOCKS" = "$EXIT" ] && echo the exit || echo "${THROUGH_SOCKS:+other}${THROUGH_SOCKS:-nobody}")"
note "the tunnel, started after it" "$(ask "$(request start utun226)")"
PID=$(field pid)
sleep 3
T=""; for _ in 1 2 3 4; do T=$(seen_as "" 12); [ -n "$T" ] && break; sleep 2; done
note "any program's request, through the tunnel" "${T:+$([ "$T" = "$EXIT" ] && echo "leaves by the exit" || { [ "$T" = "$MAC" ] && echo "LEAVES AS THIS MAC" || echo "leaves as somebody else"; })}${T:-no answer}"
mark 3b-through-the-tunnel "any program: $([ "$T" = "$EXIT" ] && echo leaves by the exit || { [ "$T" = "$MAC" ] && echo LEAVES AS THIS MAC || echo "${T:+other}${T:-no answer}"; }); $(told "$SAID")"
if [ "${PID:-0}" -gt 1 ]; then
  kill -9 "$PID"; sleep 1.5
  D=$(seen_as "" 6); S=$(seen_as "127.0.0.1:$PORT" 15)
  note "the tunnel killed: any program's request" "${D:+ANSWERED}${D:-refused}"
  note "the line itself, asked of its port" "${S:+$([ "$S" = "$EXIT" ] && echo "still the exit" || echo other)}${S:-nobody}"
  mark 3c-tunnel-killed "any program: ${D:+ANSWERED}${D:-refused}; the line by its port: $([ "$S" = "$EXIT" ] && echo still the exit || echo "${S:+other}${S:-nobody}"); line alive: $(kill -0 "$LPID" 2>/dev/null && echo yes || echo no)"
  note "a tunnel again, under the block" "$(ask "$(request start utun227)")"
  sleep 3
  T=""; for _ in 1 2 3 4; do T=$(seen_as "" 12); [ -n "$T" ] && break; sleep 2; done
  note "any program's request, through the new tunnel" "${T:+$([ "$T" = "$EXIT" ] && echo "leaves by the exit" || echo other)}${T:-no answer}"
  mark 3d-tunnel-again "any program: $([ "$T" = "$EXIT" ] && echo leaves by the exit || echo "${T:+other}${T:-no answer}")"
fi
ask "$(request line-stop)" >/dev/null
ask "$(request stop)" >/dev/null
note "released" "$(ask "$(request release)")"

exec 3>&-
kill -TERM "$DAEMON" 2>/dev/null
sleep 1
pfctl -a "$ANCHOR" -F all >/dev/null 2>&1
printf 'remove %s\n' "$KEY" | scutil >/dev/null 2>&1
if [ -s "$work/helper.err" ]; then echo "--- the helper said on its error stream"; tail -5 "$work/helper.err" | cut -c1-200; fi
rm -rf "$work" "$LINES/olcrtc-darwin-lab"
echo
echo "asked"
