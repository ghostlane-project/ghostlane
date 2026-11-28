#!/usr/bin/env bash
# K8's check of a room's rules on a Mac (the Linux form is scripts/desktop-
# checks/room-front.sh, two namespaces; the front's own rules, which name goes
# by the room and which goes direct, are the engine's and the Linux form's).
# What is a Mac's is what the packet filter's block does to a room's engine:
# the room is joined by the interface that carries the internet, before the
# tunnel and past any tunnel's routes, and its program is the one the helper
# runs in the tunnel's group.
#   - the room's program is started before any tunnel, as the app does, and
#     holds a connection out by the physical interface;
#   - the tunnel then starts behind the block, and the connection that was open
#     before the block (it has no state in the filter) is not cut: the room
#     is not taken away by the start that follows it (1.0.464);
#   - from the group a new connection leaves by the physical interface under
#     the block, as a room gathering a new way does;
#   - the same user outside the group is refused there, and what is routed
#     into the tun still goes;
#   - a stop of the program and of the tunnel leaves no rule.
# The program is a stand-in that follows the engine's run contract
# (pf-group.sh); it holds a connection to a public server and opens another
# when its loopback port is asked.
#
#   sudo bash scripts/mac-checks/room-front.sh <helper-check> <hev> <user> <stand-in-line> [<engine>]
#
#
# Run as root on a Mac with a moment of network downtime: the helper loads a
# pf block and points the system's resolver at a tun. A trap takes all of it
# away, whatever happens.
set -uo pipefail
HELPER="$1"
HEV="$2"
ASKER="$3"
STAND_IN_BUILT="$4"
ENGINE="${5:-${GHOSTLANE_ENGINE:-}}"
ASKER_UID="$(id -u "$ASKER")"
ASKER_GID="$(id -g "$ASKER")"
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
as_user() { python3 -c 'import os,sys; os.setgid(int(sys.argv[2])); os.setuid(int(sys.argv[1])); os.execvp(sys.argv[3], sys.argv[3:])' "$ASKER_UID" "$ASKER_GID" "$@"; }
alive() { kill -0 "$1" 2>/dev/null && echo there || echo gone; }
leave() {
  exec 3>&- 2>/dev/null
  [ -n "${DAEMON:-}" ] && kill -9 "$DAEMON" 2>/dev/null
  [ -n "${ENGINE_PID:-}" ] && kill -9 "$ENGINE_PID" 2>/dev/null
  [ -n "${FORWARDER:-}" ] && kill "$FORWARDER" 2>/dev/null
  pkill -f "$work/forward.py" 2>/dev/null
  [ -n "${ECHO:-}" ] && kill "$ECHO" 2>/dev/null
  [ -n "${EXTRA_CLEAN:-}" ] && eval "$EXTRA_CLEAN"
  printf 'remove %s\n' "$KEY" | scutil >/dev/null 2>&1
  pfctl -a "$ANCHOR" -F all >/dev/null 2>&1
  dscacheutil -flushcache 2>/dev/null
  killall -HUP mDNSResponder 2>/dev/null
  rm -rf "$work" "$LINES/ghostlane-engine" "$LINES/engine-stand-in"
  rmdir "$LINES" 2>/dev/null
}
trap leave EXIT
# A check that ran before this one (pf-group.sh starts its forwarder through a
# function and leaves the python behind) can hold the forwarder's port.
pkill -f '/forward\.py' 2>/dev/null
sleep 0.5

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
: > "$work/forward.log"
chown "$ASKER" "$work/forward.log"
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
chown -R "$ASKER" "$work/line"

# The helper, asked over a pipe as in the other Mac checks.
mkfifo "$work/ask"
"$HELPER" /nonexistent/sing-box "$ASKER_UID" "$HEV" <"$work/ask" >"$work/said" 2>"$work/helper.err" &
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
# The tun keeps this address out of itself, but the block does not let it
# out by name: a caller beside the tun reaches it by the physical interface,
# where only the tunnel's group is let out.
request["exclude"] = ["8.8.8.8/32"]
request["hold"] = {"interface": name, "pass": []}
print(json.dumps(request))
PY
}

# ---- what the checks of this directory share (each script carries its own copy) ----
# Waits up to $1 seconds for the command after it to succeed.
wait_until() {
  local n=$(( $1 * 10 )); shift
  for _ in $(seq 1 "$n"); do "$@" && return 0; sleep 0.1; done
  return 1
}
# The address of the `socks-in` inbound (or the tag $2) a program said it was ready on.
ready_address() {
  python3 -I - "$1" "${2:-socks-in}" <<'PY'
import json, sys
for line in open(sys.argv[1], encoding="utf-8"):
    try: event = json.loads(line)
    except Exception: continue
    if event.get("event") == "ready":
        for inbound in event.get("inbounds", []):
            if inbound.get("tag") == sys.argv[2]:
                print(inbound["address"]); break
        break
PY
}
gone() { ! kill -0 "$1" 2>/dev/null; }
log_has() { grep -q "$2" "$1" 2>/dev/null; }
# A page fetched as the user through a plain relay at host:port.
fetch_relay() {
  as_user python3 -I -c '
import socket,sys
host,port=sys.argv[1].rsplit(":",1)
s=socket.create_connection((host,int(port)),8)
s.sendall(b"GET / HTTP/1.1\r\nHost: x\r\n\r\n")
print(s.recv(4096).decode(errors="replace").split("\n")[-2].strip())' "$1" 2>&1
}

# ---- a room's rules ---------------------------------------------------------
EXT=1.1.1.1
EXT_PORT=80
ROOM_PORT=18961
EXTRA_CLEAN='rm -f "$LINES"/room-stand-in.py; rmdir "$LINES" 2>/dev/null'

cat > "$work/line/room-stand-in.py" <<'PY'
#!/usr/bin/env python3
import json, os, socket, sys, threading, time
# argv: <address> <port> <interface> <loopback port>
address, port, iface, local = sys.argv[1], int(sys.argv[2]), sys.argv[3], int(sys.argv[4])
def say(level, line):
    print(json.dumps({"event": "log", "source": "room", "level": level, "line": line}), flush=True)
def open_bound():
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(8)
    s.setsockopt(socket.IPPROTO_IP, 25, socket.if_nametoindex(iface))  # IP_BOUND_IF
    s.connect((address, port))
    return s
# The room's way out, opened before there is a tunnel.
held = open_bound()
held.settimeout(1)
say("info", "held")
def keep():
    global held
    ticks = 0
    while True:
        time.sleep(0.5)
        try:
            held.sendall(b"HEAD / HTTP/1.1\r\nHost: x\r\n\r\n")
            try:
                if held.recv(4096) == b"":
                    # The far end timed it out: a new way, still by the interface.
                    say("info", "reopened")
                    held = open_bound()
                    held.settimeout(1)
            except socket.timeout:
                pass
            ticks += 1
            say("info", "tick %d" % ticks)
        except Exception as error:
            say("error", "broken: %s" % error)
            return
threading.Thread(target=keep, daemon=True).start()
# A way the room gathers later: a new connection by the same interface, asked
# for by whoever connects to the loopback port, answered with what came of it.
listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
listener.bind(("127.0.0.1", local))
listener.listen(8)
def gather():
    while True:
        conn, _ = listener.accept()
        try:
            open_bound().close()
            conn.sendall(b"new way ok\n")
        except Exception as error:
            conn.sendall(("new way failed: %s\n" % error).encode())
        conn.close()
threading.Thread(target=gather, daemon=True).start()
print(json.dumps({"event": "ready", "inbounds": [{"tag": "room", "address": "127.0.0.1:%d" % local}]}), flush=True)
while sys.stdin.buffer.read(1):
    pass
print(json.dumps({"event": "ended", "reason": "stdin_closed"}), flush=True)
os._exit(0)
PY
chmod 755 "$work/line/room-stand-in.py"
chown -R "$ASKER" "$work/line"
PROGRAM="$work/line/room-stand-in.py"
LOG="$work/room.log"
: > "$LOG"; chown "$ASKER" "$LOG"; chmod 600 "$LOG"
ticks() { grep -c '"line": "tick ' "$LOG" 2>/dev/null || true; }
asked_way() { as_user python3 -I -c '
import socket,sys
s=socket.create_connection(("127.0.0.1",int(sys.argv[1])),20)
print(s.recv(4096).decode(errors="replace").strip())' "$ROOM_PORT" 2>&1; }

# The far side is kept out of the tun, so that only the block decides.
request_tun() {
  python3 -I - "$FIXTURE" "$1" "$UPSTREAM_PORT" "$EXT" <<'PY'
import json, sys
request = json.load(open(sys.argv[1]))
name, port, ext = sys.argv[2:5]
request["tun"]["name"] = name
request["socks"]["port"] = int(port)
request["exclude"] = [ext + "/32"]
request["hold"] = {"interface": name, "pass": []}
print(json.dumps(request))
PY
}

say "the room is joined before any tunnel"
expect "no block stands yet" "$(ask '{"verb":"status"}')" '^ok=True state=idle hold=False protocol=5 '
expect "the helper starts the room's program, with no tunnel" "$(ask "$(request_line "$PROGRAM" "$LOG" "$EXT" "$EXT_PORT" "$IF" "$ROOM_PORT")")" '^ok=True state=idle hold=False protocol=5 line=[1-9][0-9]* '
LPID=$(line_pid)
expect "it runs in the group the block will let out" "$(ps -o gid= -p "$LPID" | tr -d ' ')" "^$GROUP\$"
wait_until 15 log_has "$LOG" '"line": "held"'
expect "its way out is held" "$(grep -c '"line": "held"' "$LOG")" '^1$'
wait_until 10 log_has "$LOG" '"line": "tick 2"'
expect "it is carrying" "$(ticks)" '^[2-9]$|^[1-9][0-9]+$'

say "the tunnel starts behind the block"
expect "the tunnel runs and the block stands" "$(ask "$(request_tun utun239)")" '^ok=True state=running hold=True protocol=5 line=[1-9][0-9]* '
TPID=$(tunnel_pid)
before=$(ticks)
sleep 5
after=$(ticks)
expect "the connection from before the block still carries" "$([ "$after" -ge $((before + 5)) ] && echo carries || echo "stalled: $before then $after")" '^carries$'
expect "nothing of it was cut" "$(grep -c '"level": "error"' "$LOG")" '^0$'
expect "the program is still there" "$(alive "$LPID")" '^there$'

say "a new way, from the group and from outside it"
expect "from the group a new connection leaves by the physical interface" "$(asked_way)" '^new way ok$'
expect "the same user, outside the group, is refused there" "$(as_user curl -s -o /dev/null -w '%{http_code}' --max-time 5 "http://$EXT/" 2>/dev/null)" '^000$'
expect "what is routed into the tun still goes" "$(route -n get "$TARGET" 2>/dev/null | awk '/interface:/{print $2}')" '^utun239$'

say "nothing left behind"
expect "the helper ends the program" "$(ask '{"verb":"line-stop"}')" '^ok=True state=running hold=True protocol=5 line=0 '
expect "it is gone" "$(wait_until 8 gone "$LPID" && echo gone || echo there)" '^gone$'
expect "the tunnel is stopped" "$(ask '{"verb":"stop"}')" '^ok=True state=idle hold=False protocol=5 line=0 '
expect "no rule stands" "$(pfctl -a "$ANCHOR" -s rules 2>/dev/null | grep -c .)" '^0$'

kill -9 "$ECHO" 2>/dev/null
if [ -s "$work/helper.err" ]; then echo "--- the helper said on its error stream"; tail -5 "$work/helper.err" | cut -c1-200; fi
echo
[ "$failed" -eq 0 ] && echo "the room's rules work on a Mac" || echo "the room's rules do not work on a Mac"
exit "$failed"
