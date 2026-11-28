#!/usr/bin/env bash
# K8's check of LAN sharing on a Mac (the Linux form is scripts/desktop-checks/
# lan.sh, two network namespaces; a Mac has none). The line's program, started
# by the helper in the tunnel's group beside the armed block, listens on a
# private address of this Mac, as the engine's LAN inbound does, and a client
# that is another device on the network reaches it:
#   - the client is a second address on the loopback (192.168.77.2) and the
#     listener a first (192.168.77.1), so the peer the listener sees is no
#     loopback address and the packets cross lo0 as a LAN's would cross the
#     interface; the packet filter's block stands the whole time;
#   - with the login a SOCKS5 CONNECT carries a page through the line and the
#     utun, and the listener saw the client's own address;
#   - with no login at all, with a wrong password and with a wrong user name
#     every attempt is refused and nothing reaches the tun;
#   - a stop closes the LAN port and the helper keeps nothing.
# What is not here: a datagram (UDP ASSOCIATE) and the engine's own login
# rules. The program is a stand-in that follows the engine's run contract, as in
# pf-group.sh; the Linux form and the engine's tests hold those. A real engine
# would take a session file this script does not write.
#
#   sudo bash scripts/mac-checks/lan.sh <helper-check> <hev> <user> <stand-in-line> [<engine>]
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

# ---- LAN sharing ------------------------------------------------------------
LAN_ADDR=192.168.77.1
CLIENT_ADDR=192.168.77.2
LAN_PORT=18951
LAN_USER=ghostlane
LAN_PASS=lan-pass
EXTRA_CLEAN='ifconfig lo0 -alias 192.168.77.1 2>/dev/null; ifconfig lo0 -alias 192.168.77.2 2>/dev/null; rm -f "$LINES"/lan-stand-in.py; rmdir "$LINES" 2>/dev/null'

# A stand-in for the engine with LAN sharing on: a SOCKS5 listener with a
# login (RFC 1929) on the LAN address. It follows the engine's run contract
# (a ready line, stdin held, an end when stdin closes) and carries a CONNECT by
# dialing the address asked for, which is routed into the tun.
cat > "$work/line/lan-stand-in.py" <<'PY'
#!/usr/bin/env python3
import json, select, socket, struct, sys, threading
# argv: <bind address> <port> <user> <password>
bind, port, user, password = sys.argv[1], int(sys.argv[2]), sys.argv[3].encode(), sys.argv[4].encode()
def say(level, line):
    print(json.dumps({"event": "log", "source": "core", "level": level, "line": line}), flush=True)
def read(conn, size):
    data = b""
    while len(data) < size:
        part = conn.recv(size - len(data))
        if not part: raise EOFError()
        data += part
    return data
def serve(conn, peer):
    try:
        conn.settimeout(15)
        _, count = read(conn, 2)
        methods = read(conn, count)
        if 2 not in methods:
            conn.sendall(b"\x05\xff"); say("access", "peer %s refused: no login offered" % peer); return
        conn.sendall(b"\x05\x02")
        _, size = read(conn, 2)
        got_user = read(conn, size)
        got_pass = read(conn, read(conn, 1)[0])
        if got_user != user or got_pass != password:
            conn.sendall(b"\x01\x01"); say("access", "peer %s refused: wrong login" % peer); return
        conn.sendall(b"\x01\x00")
        _, command, _, kind = read(conn, 4)
        if kind == 1: host = socket.inet_ntop(socket.AF_INET, read(conn, 4))
        elif kind == 3: host = read(conn, read(conn, 1)[0]).decode()
        else: return
        target = struct.unpack("!H", read(conn, 2))[0]
        if command != 1: conn.sendall(b"\x05\x07\x00\x01" + bytes(6)); return
        up = socket.create_connection((host, target), 5)
        conn.sendall(b"\x05\x00\x00\x01" + bytes(6))
        say("access", "peer %s accepted %s:%d" % (peer, host, target))
        both = [conn, up]
        while True:
            ready, _, _ = select.select(both, [], [], 5)
            if not ready: break
            for s in ready:
                data = s.recv(65536)
                if not data: return
                (up if s is conn else conn).sendall(data)
    except Exception as error:
        say("error", "peer %s: %s" % (peer, error))
    finally:
        conn.close()
listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
listener.bind((bind, port))
listener.listen(16)
print(json.dumps({"event": "ready", "inbounds": [{"tag": "lan", "address": "%s:%d" % (bind, port)}]}), flush=True)
def stdin_watch():
    while sys.stdin.buffer.read(1):
        pass
    print(json.dumps({"event": "ended", "reason": "stdin_closed"}), flush=True)
    import os; os._exit(0)
threading.Thread(target=stdin_watch, daemon=True).start()
while True:
    conn, address = listener.accept()
    threading.Thread(target=serve, args=(conn, address[0]), daemon=True).start()
PY
chmod 755 "$work/line/lan-stand-in.py"
chown -R "$ASKER" "$work/line"

# A client that is another device: its own address, a SOCKS5 login or none.
cat > "$work/lan-client.py" <<'PY'
import socket, struct, sys
# argv: <proxy host:port> <source address> <mode> <target host> <target port>
proxy, source, mode, host, port = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], int(sys.argv[5])
phost, pport = proxy.rsplit(":", 1)
def read(s, n):
    data = b""
    while len(data) < n:
        part = s.recv(n - len(data))
        if not part: raise EOFError()
        data += part
    return data
try:
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(10)
    s.bind((source, 0))
    s.connect((phost, int(pport)))
    s.sendall(b"\x05\x01\x00" if mode == "nologin" else b"\x05\x01\x02")
    method = read(s, 2)
    if method[1] == 0xFF:
        print("refused-method"); sys.exit(0)
    if method[1] == 0x00 and mode == "nologin":
        pass
    else:
        user = b"ghostlane" if mode != "wronguser" else b"somebody"
        password = b"lan-pass" if mode != "wrongpass" else b"not-the-password"
        s.sendall(b"\x01" + bytes([len(user)]) + user + bytes([len(password)]) + password)
        if read(s, 2)[1] != 0:
            print("refused-login"); sys.exit(0)
    s.sendall(b"\x05\x01\x00\x01" + socket.inet_aton(host) + struct.pack("!H", port))
    if read(s, 10)[1] != 0:
        print("refused-connect"); sys.exit(0)
    s.sendall(b"GET / HTTP/1.1\r\nHost: x\r\n\r\n")
    print("page " + s.recv(4096).decode(errors="replace").split("\n")[-2].strip())
except SystemExit:
    raise
except Exception as error:
    print("error %s" % error)
PY
chmod 644 "$work/lan-client.py"
client() { as_user python3 -I "$work/lan-client.py" "$LAN_ADDR:$LAN_PORT" "$CLIENT_ADDR" "$1" "$TARGET" "$ECHO_PORT"; }

say "a second address on the loopback is the LAN"
ifconfig lo0 alias "$LAN_ADDR" netmask 255.255.255.255
ifconfig lo0 alias "$CLIENT_ADDR" netmask 255.255.255.255
expect "both addresses are on lo0" "$(ifconfig lo0 | grep -c "inet 192\.168\.77\.[12] ")" '^2$'

say "a tunnel under hev behind the block"
expect "the tunnel runs and the block stands" "$(ask "$(request_tun utun239)")" '^ok=True state=running hold=True protocol=5 '
expect "hev runs in the group the block lets out" "$(ps -o gid= -p "$(tunnel_pid)" 2>/dev/null | tr -d ' ')" "^$GROUP\$"
expect "a TEST-NET address is routed into the tun" "$(route -n get "$TARGET" 2>/dev/null | awk '/interface:/{print $2}')" '^utun239$'

say "the line, with LAN sharing on"
LOG="$work/lan.log"
: > "$LOG"; chown "$ASKER" "$LOG"; chmod 600 "$LOG"
PROGRAM="$work/line/lan-stand-in.py"
expect "the helper starts it" "$(ask "$(request_line "$PROGRAM" "$LOG" "$LAN_ADDR" "$LAN_PORT" "$LAN_USER" "$LAN_PASS")")" '^ok=True state=running hold=True protocol=5 line=[1-9][0-9]* '
LPID=$(line_pid)
wait_until 10 log_has "$LOG" '"event"[[:space:]]*:[[:space:]]*"ready"'
expect "it says it is ready on the LAN address" "$(ready_address "$LOG" lan)" "^$LAN_ADDR:$LAN_PORT\$"
expect "it runs in the group the block lets out" "$(ps -o gid= -p "$LPID" | tr -d ' ')" "^$GROUP\$"

say "a client on the LAN"
expect "with the login a page comes through the line and the utun" "$(client ok)" '^page the engine in the pf group fetched this page$'
expect "the listener saw the client's own address" "$(grep -c "peer $CLIENT_ADDR accepted $TARGET:$ECHO_PORT" "$LOG")" '^1$'
expect "with no login at all it is refused" "$(client nologin)" '^refused-method$'
expect "with a wrong password it is refused" "$(client wrongpass)" '^refused-login$'
expect "with a wrong user name it is refused" "$(client wronguser)" '^refused-login$'
expect "only the one with the login reached the tun" "$(grep -c "^connect $TARGET:$ECHO_PORT\$" "$work/forward.log" 2>/dev/null)" '^1$'
expect "the block still stands" "$(pfctl -a "$ANCHOR" -s rules 2>/dev/null | grep -c '^block')" '^[1-9][0-9]*$'

say "a stop closes the LAN port"
expect "the helper ends the program" "$(ask '{"verb":"line-stop"}')" '^ok=True state=running hold=True protocol=5 line=0 '
expect "it is gone" "$(wait_until 5 gone "$LPID" && echo gone || echo there)" '^gone$'
expect "the port is closed" "$(as_user python3 -I -c '
import socket,sys
s=socket.socket(); s.settimeout(3); s.bind((sys.argv[2],0))
try: s.connect((sys.argv[1],int(sys.argv[3]))); print("open")
except Exception: print("closed")' "$LAN_ADDR" "$CLIENT_ADDR" "$LAN_PORT")" '^closed$'

say "nothing left behind"
expect "the tunnel is stopped" "$(ask '{"verb":"stop"}')" '^ok=True state=idle hold=False protocol=5 line=0 '
expect "no rule stands" "$(pfctl -a "$ANCHOR" -s rules 2>/dev/null | grep -c .)" '^0$'

kill -9 "$ECHO" 2>/dev/null
if [ -s "$work/helper.err" ]; then echo "--- the helper said on its error stream"; tail -5 "$work/helper.err" | cut -c1-200; fi
echo
[ "$failed" -eq 0 ] && echo "LAN sharing works on a Mac" || echo "LAN sharing does not do not work on a Mac"
exit "$failed"
