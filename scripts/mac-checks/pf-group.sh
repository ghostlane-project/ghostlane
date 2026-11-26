#!/usr/bin/env bash
# K8's check of the engine in the pf group, on a Mac: with the block armed a
# program started by the helper in the tunnel's group fetches a page from a
# local server through the utun, and the same program started by its user
# outside the group is refused. A stand-in follows
# the engine's run contract (stdin held open, a ready line, then a fetch),
# which proves the helper's half of it. A real engine, handed over by K8
# from the Core build's Apple artifact, is the release's proof.
#
# The helper is built by pr-checks.yml from every file of
# desktopApp/tunneldaemon (its own main replaced by scripts/macos-helper-check/
# main.swift), and this script is run as root with four arguments:
#
#   sudo bash scripts/mac-checks/pf-group.sh <helper-check> <hev> <user> <stand-in-line>
#
# A fifth, the engine at the Core build's pin, makes it the engine's check.
# GHOSTLANE_ENGINE names it for a hand run. K8 should pass it from the Core
# build's Apple artifact; until then the stand-in proves the helper's side.
#
# Run as root on a Mac with a moment of network downtime: the helper loads a
# pf block and points the system's resolver at a tun. A trap takes all of it
# away, whatever happens.
set -uo pipefail
HELPER="${1:?the helper built as the app builds it}"
HEV="${2:?hev-socks5-tunnel built from the app's tree}"
WHO="${3:?the user who asks}"
STAND_IN_BUILT="${4:?a stand-in for a line's program}"
ENGINE="${5:-${GHOSTLANE_ENGINE:-}}"
WHO_UID="$(id -u "$WHO")"
WHO_GID="$(id -g "$WHO")"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
ANCHOR='com.apple/100.org.olcbox.app.hold'
KEY='State:/Network/Service/org.olcbox.app.tunnel/DNS'
LINES='/Library/Application Support/org.olcbox.app.lines'
GROUP=7302243
ECHO_PORT=18947
UPSTREAM_PORT=18948
TARGET='203.0.113.40'
FIXTURE="$root/sharedUI/src/jvmTest/resources/macos-helper/tun-request.json"
work="$(cd /tmp && pwd -P)/ghostlane-pf-group.$$"
mkdir -p "$work/line"
chmod 755 "$work"

failed=0
say() { printf '\n== %s\n' "$*"; }
expect() {
  if [[ "$2" =~ $3 ]]; then
    printf '   ok    %s: %s\n' "$1" "$2"
  else
    printf '   FAIL  %s: found "%s", expected to match %s\n' "$1" "$2" "$3"
    failed=1
  fi
}
as_user() { python3 -c 'import os,sys; os.setgid(int(sys.argv[2])); os.setuid(int(sys.argv[1])); os.execvp(sys.argv[3], sys.argv[3:])' "$WHO_UID" "$WHO_GID" "$@"; }
alive() { kill -0 "$1" 2>/dev/null && echo there || echo gone; }
leave() {
  exec 3>&- 2>/dev/null
  [ -n "${DAEMON:-}" ] && kill -9 "$DAEMON" 2>/dev/null
  [ -n "${ENGINE_PID:-}" ] && kill -9 "$ENGINE_PID" 2>/dev/null
  [ -n "${FORWARDER:-}" ] && kill "$FORWARDER" 2>/dev/null
  [ -n "${ECHO:-}" ] && kill "$ECHO" 2>/dev/null
  printf 'remove %s\n' "$KEY" | scutil >/dev/null 2>&1
  pfctl -a "$ANCHOR" -F all >/dev/null 2>&1
  dscacheutil -flushcache 2>/dev/null
  killall -HUP mDNSResponder 2>/dev/null
  rm -rf "$work" "$LINES/ghostlane-engine" "$LINES/engine-stand-in"
  rmdir "$LINES" 2>/dev/null
}
trap leave EXIT

say "the machine"
sw_vers | tr '\n' ' '; echo
IF=$(route -n get default | awk '/interface:/{print $2}')
MINE=$(ipconfig getifaddr "$IF")
expect "there is a way out, $IF at $MINE" "$MINE" '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$'
expect "hev is a program for this Mac" "$("$HEV" --version 2>&1 | grep -c '^Version: ')" '^1$'
if [ -n "$ENGINE" ]; then
  expect "the engine is a program" "$([ -x "$ENGINE" ] && echo yes || echo no)" '^yes$'
  "$ENGINE" version > "$work/version.out" 2> "$work/version.log" || true
  expect "it speaks the app's protocol" "$(python3 -I - "$work/version.out" <<'PY'
import json, sys
lines = [l for l in open(sys.argv[1], encoding="utf-8").read().splitlines() if l.strip()]
try: v = json.loads(lines[0]) if lines else {}
except Exception: v = {}
print("protocol=%s" % v.get("protocol"))
PY
)" '^protocol=1$'
else
  echo "no engine was handed over: the helper's half is proved with a stand-in"
fi

# An echo server on loopback. A TEST-NET address, routed through the tun,
# reaches it only by way of the forwarder behind hev and the group's rule.
cat > "$work/echo.py" <<'PY'
import socket, sys
listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
listener.bind(("127.0.0.1", int(sys.argv[1])))
listener.listen(16)
with open(sys.argv[2], "w") as f: f.write("ready")
while True:
    conn, _ = listener.accept()
    data = conn.recv(4096)
    conn.sendall(b"the engine in the pf group fetched this page\n" if data.startswith(b"GET") else data)
    conn.close()
PY
python3 -I "$work/echo.py" "$ECHO_PORT" "$work/echo.ready" > "$work/echo.log" 2>&1 &
ECHO=$!
for _ in $(seq 1 50); do [ -e "$work/echo.ready" ] && break; sleep 0.1; done
expect "the server behind the line listens" "$([ -e "$work/echo.ready" ] && echo yes || echo no)" '^yes$'

# A SOCKS forwarder behind hev: what a program dials through the utun is
# handed on, with the login the tun's config names, to the echo server.
cat > "$work/forward.py" <<'PY'
import select, socket, struct, sys, threading
port, target_port, user, password, log = int(sys.argv[1]), int(sys.argv[2]), sys.argv[3], sys.argv[4], sys.argv[5]
def read(conn, size):
    data = b""
    while len(data) < size:
        part = conn.recv(size - len(data))
        if not part: raise EOFError()
        data += part
    return data
def say(line):
    with open(log, "a") as out: out.write(line + "\n")
def serve(conn):
    try:
        conn.settimeout(15)
        _, count = read(conn, 2)
        if 2 not in read(conn, count): conn.sendall(b"\x05\xff"); return
        conn.sendall(b"\x05\x02")
        _, size = read(conn, 2)
        got_user = read(conn, size)
        got_pass = read(conn, read(conn, 1)[0])
        if got_user != user.encode() or got_pass != password.encode():
            conn.sendall(b"\x01\x01"); say("refused login"); return
        conn.sendall(b"\x01\x00")
        _, command, _, kind = read(conn, 4)
        if kind == 1: host = socket.inet_ntop(socket.AF_INET, read(conn, 4))
        elif kind == 3: host = read(conn, read(conn, 1)[0]).decode()
        else: say("address type %d" % kind); return
        where = "%s:%d" % (host, struct.unpack("!H", read(conn, 2))[0])
        if command != 1: conn.sendall(b"\x05\x07\x00\x01" + bytes(6)); return
        say("connect %s" % where)
        up = socket.create_connection(("127.0.0.1", target_port), 5)
        conn.sendall(b"\x05\x00\x00\x01" + bytes(6))
        both = [conn, up]
        while True:
            ready, _, _ = select.select(both, [], [], 5)
            if not ready: break
            for s in ready:
                data = s.recv(65536)
                if not data: return
                (up if s is conn else conn).sendall(data)
    except Exception as error: say("broken: %s" % error)
    finally: conn.close()
listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
listener.bind(("127.0.0.1", port))
listener.listen(64)
say("listening on 127.0.0.1:%d" % port)
while True:
    conn, _ = listener.accept()
    threading.Thread(target=serve, args=(conn,), daemon=True).start()
PY
as_user python3 -I "$work/forward.py" "$UPSTREAM_PORT" "$ECHO_PORT" ghostlane "p4ss w'rd" "$work/forward.log" > "$work/forward.err" 2>&1 &
FORWARDER=$!
for _ in $(seq 1 50); do grep -q '^listening' "$work/forward.log" 2>/dev/null && break; sleep 0.1; done
expect "the SOCKS forwarder behind the tun listens" "$(head -1 "$work/forward.log" 2>/dev/null)" "^listening on 127\.0\.0\.1:$UPSTREAM_PORT\$"

# A stand-in that follows the engine's run contract: it stays while stdin is
# open, says `ready` with a SOCKS port, ends when stdin closes, and fetches a
# page by dialing an address that is routed into the tun. This is what the
# helper must get right for a real engine (HostProcess.kt in the app).
cat > "$work/engine-stand-in.py" <<'PY'
#!/usr/bin/env python3
import json, socket, sys, threading
# argv: <echo address> <echo port> [run | once]
address, port = sys.argv[1], int(sys.argv[2])
def serve(conn):
    try:
        conn.settimeout(10)
        data = conn.recv(4096)
        up = socket.create_connection((address, port), 5)
        up.sendall(data if data else b"GET / HTTP/1.1\r\nHost: x\r\n\r\n")
        while True:
            got = up.recv(4096)
            if not got: break
            conn.sendall(got)
    except Exception as error:
        print(json.dumps({"event": "log", "source": "core", "level": "error", "line": str(error)}), flush=True)
    finally:
        conn.close()
listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
listener.bind(("127.0.0.1", 0))
listener.listen(16)
print(json.dumps({"event": "ready", "inbounds": [{"tag": "socks-in", "address": "127.0.0.1:%d" % listener.getsockname()[1]}]}), flush=True)
if "once" in sys.argv:
    conn, _ = listener.accept(); serve(conn); sys.exit(0)
stop = threading.Event()
def stdin_watch():
    while not stop.is_set():
        chunk = sys.stdin.buffer.read(1)
        if not chunk:
            print(json.dumps({"event": "ended", "reason": "stdin_closed"}), flush=True)
            listener.close()
            sys.exit(0)
threading.Thread(target=stdin_watch, daemon=True).start()
while True:
    try: conn, _ = listener.accept()
    except OSError: break
    threading.Thread(target=serve, args=(conn,), daemon=True).start()
PY
cp "$work/engine-stand-in.py" "$work/line/engine-stand-in.py"
chmod 755 "$work/line/engine-stand-in.py"
chown -R "$WHO" "$work/line"

# The helper, asked over a pipe as in the other Mac checks.
mkfifo "$work/ask"
"$HELPER" /nonexistent/sing-box "$WHO_UID" "$HEV" <"$work/ask" >"$work/said" 2>"$work/helper.err" &
DAEMON=$!
exec 3>"$work/ask"
ask() {
  local before; before=$(wc -l < "$work/said")
  printf '%s\n' "$1" >&3
  for _ in $(seq 1 300); do [ "$(wc -l < "$work/said")" -gt "$before" ] && break; sleep 0.1; done
  tail -1 "$work/said" | python3 -c '
import json,sys
try: r=json.loads(sys.stdin.readline())
except Exception: print("no reply"); sys.exit(0)
print("ok=%s state=%s hold=%s protocol=%s line=%s error=%s" % (r.get("ok"), r.get("state"), r.get("hold"), r.get("protocol"), r.get("line",0), r.get("error")))'
}
line_pid() { tail -1 "$work/said" | python3 -c 'import json,sys; print(json.loads(sys.stdin.readline()).get("line",0))'; }
tunnel_pid() { tail -1 "$work/said" | python3 -c 'import json,sys; print(json.loads(sys.stdin.readline()).get("pid",0))'; }
request_line() {
  python3 -I - "$1" "$2" "${@:3}" <<'PY'
import json, sys
program, output = sys.argv[1:3]
print(json.dumps({"verb": "line", "program": program, "output": output, "arguments": sys.argv[3:]}))
PY
}
request_tun() {
  python3 -I - "$FIXTURE" "$1" "$UPSTREAM_PORT" <<'PY'
import json, sys
request = json.load(open(sys.argv[1]))
name, port = sys.argv[2:4]
request["tun"]["name"] = name
request["socks"]["port"] = int(port)
# The tun keeps this address out of itself (as it keeps the line's server):
# a caller beside the tun reaches it by the physical interface, where the
# block refuses anybody but the tunnel's group.
request["exclude"] = ["1.1.1.1/32"]
request["hold"] = {"interface": name, "pass": ["1.1.1.1"]}
print(json.dumps(request))
PY
}
# The program under test is the stand-in, copied into the user's line
# directory above. What it prints is the app's file.
PROGRAM="$work/line/engine-stand-in.py"
LOG="$work/engine.log"
: > "$LOG"; chown "$WHO" "$LOG"; chmod 600 "$LOG"

say "a tunnel under hev behind the block"
expect "the helper speaks the fifth protocol" "$(ask '{"verb":"status"}')" '^ok=True state=idle hold=False protocol=5 '
expect "the tunnel runs and the block stands" "$(ask "$(request_tun utun239)")" '^ok=True state=running hold=True protocol=5 '
expect "hev runs in the group the block lets out" "$(ps -o gid= -p "$(tunnel_pid)" 2>/dev/null | tr -d ' ')" "^$GROUP\$"
expect "a TEST-NET address is routed into the tun" "$(route -n get "$TARGET" 2>/dev/null | awk '/interface:/{print $2}')" '^utun239$'

say "the program, started by the helper"
ARGS=("$TARGET" "$ECHO_PORT" run)
expect "the helper starts it" "$(ask "$(request_line "$PROGRAM" "$LOG" "${ARGS[@]}")")" '^ok=True state=running hold=True protocol=5 line=[1-9][0-9]* '
LPID=$(line_pid)
expect "it runs as the user who asked" "$(ps -o uid= -p "$LPID" | tr -d ' ')" "^$WHO_UID\$"
expect "in the group the block lets out" "$(ps -o gid= -p "$LPID" | tr -d ' ')" "^$GROUP\$"
expect "and stays there: stdin is held for the program's lifetime" "$(sleep 2; alive "$LPID")" '^there$'
expect "it says it is ready" "$(grep -c '"event"[[:space:]]*:[[:space:]]*"ready"' "$LOG" 2>/dev/null)" '^[1-9][0-9]*$'
PROXY="$(python3 -I - "$LOG" <<'PY'
import json, sys
for line in open(sys.argv[1], encoding="utf-8"):
    try: event = json.loads(line)
    except Exception: continue
    if event.get("event") == "ready":
        for inbound in event.get("inbounds", []):
            if inbound.get("tag") == "socks-in":
                print(inbound["address"]); break
        break
PY
)"
expect "its SOCKS port is on loopback" "$PROXY" '^127\.0\.0\.1:[0-9]+$'
FETCH="$(as_user python3 -I -c '
import socket,sys
host,port=sys.argv[1].split(":")
s=socket.create_connection((host,int(port)),8)
s.sendall(b"GET / HTTP/1.1\r\nHost: x\r\n\r\n")
print(s.recv(4096).decode(errors="replace").split("\n")[-2].strip())' "$PROXY" 2>&1)"
expect "it fetches the page through the line and the utun" "$FETCH" '^the engine in the pf group fetched this page$'
expect "the dial went through hev, into the tun" "$(grep -c "^connect $TARGET:$ECHO_PORT\$" "$work/forward.log" 2>/dev/null)" '^[1-9][0-9]*$'

say "a program outside the group is refused"
# Traffic routed into the tun is let out by pf's first rule whatever its
# group. What leaves by the physical interface is let out only from the
# tunnel's group, so the same user's request there is refused.
expect "the user cannot reach a server beside the tun" "$(as_user curl -s -o /dev/null -w '%{http_code}' --max-time 5 "http://1.1.1.1/" 2>/dev/null)" '^000$'

say "nothing left behind"
expect "the helper ends the program" "$(ask '{"verb":"line-stop"}')" '^ok=True state=running hold=True protocol=5 line=0 '
expect "it is gone" "$(alive "$LPID")" '^gone$'
expect "the tunnel is stopped" "$(ask '{"verb":"stop"}')" '^ok=True state=idle hold=False protocol=5 line=0 '
expect "no rule stands" "$(pfctl -a "$ANCHOR" -s rules 2>/dev/null | grep -c .)" '^0$'

kill -9 "$ECHO" 2>/dev/null
if [ -s "$work/helper.err" ]; then echo "--- the helper said on its error stream"; tail -5 "$work/helper.err" | cut -c1-200; fi
echo
[ "$failed" -eq 0 ] && echo "the program runs in the pf group" || echo "the program does not run in the pf group"
exit "$failed"
