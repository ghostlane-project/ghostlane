#!/usr/bin/env bash
# desktop-check-os: linux
#
# LAN sharing on ghostlane-engine, in two network namespaces: the app's own
# DesktopLanProxy starts the Core build's program at the pin on a private
# address of this machine (the near end of a veth pair), in front of the
# port a session would hold, and a client in the namespace at the far end
# reaches it as another device on the network would
# (DesktopLanProxyRealTest, which this script runs and holds to: every test of
# it run, none skipped, none failed):
#   - with the login, the client's SOCKS5 CONNECT carries a download and an
#     upload byte for byte, and its UDP ASSOCIATE carries a datagram out and
#     the answer back, all through the session's port and its own login;
#   - with no login at all, with the wrong password, and with a method list
#     that offers no login first, every attempt is refused, TCP and datagram
#     alike;
#   - a stop closes the port, and no engine is left;
#   - a port another program holds fails the start with the app's sentence.
# The session's port is a SOCKS5 server of this script's own on loopback that
# demands a login and takes TEST-NET-3's 203.0.113.10 for the page and the
# datagram echo (a request that did not go through the chain goes nowhere);
# it logs what it carried, and the script holds the chain to that log.
#
# The addresses are 192.168.77.1 (this machine's end) and 192.168.77.2 (the
# namespace's); both are removed again, however the script ends. A room's leg
# of LAN sharing is not here.
#
# Run by rust-desktop.yml on each leg the line above names:
#   bash scripts/desktop-checks/lan.sh
# with GHOSTLANE_ENGINE (the program), GHOSTLANE_CORE_COMMIT (the commit it
# must say it was built from), DESKTOP_CHECK_OS (linux) and DESKTOP_CHECK_WORK
# (an empty directory of its own). Needs Python 3, sudo with ip(8), and Java 21
# with the Android SDK for the Gradle build.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"

engine="${GHOSTLANE_ENGINE:?the program to check: GHOSTLANE_ENGINE}"
commit="${GHOSTLANE_CORE_COMMIT:?the pin the program was built from: GHOSTLANE_CORE_COMMIT}"
os="${DESKTOP_CHECK_OS:?linux: DESKTOP_CHECK_OS}"
work="${DESKTOP_CHECK_WORK:?a directory for this check alone: DESKTOP_CHECK_WORK}"
mkdir -p "$work"
work="$(cd "$work" && pwd)"
summary="${GITHUB_STEP_SUMMARY:-/dev/null}"
say() { echo "lan: $*"; }
die() { echo "lan: $*" >&2; echo "- lan: $*" >> "$summary"; exit 1; }

[ "$os" = linux ] || die "this check is for Linux; on a Mac it needs no namespaces and is another script"
py=""
for candidate in python3 python; do
  if command -v "$candidate" > /dev/null && "$candidate" -c 'import sys; sys.exit(sys.version_info < (3, 8))' 2> /dev/null; then
    py="$candidate"
    break
  fi
done
[ -n "$py" ] || die "no Python 3"
[ -x "$engine" ] || die "GHOSTLANE_ENGINE is no program: $engine"
sudo -n true 2> /dev/null || die "no sudo without a password: the namespaces need it"
command -v ip > /dev/null || die "no ip(8)"

# The program says what it is: the contract this app writes, and the pin.
"$engine" version > "$work/version.jsonl" 2> "$work/version.err" || die "\`ghostlane-engine version\` failed: $(head -c 300 "$work/version.err")"
said="$("$py" -I - "$work/version.jsonl" "$commit" 2>&1 <<'PY'
import json, sys
path, commit = sys.argv[1], sys.argv[2]
lines = [l for l in open(path, encoding="utf-8").read().splitlines() if l.strip()]
v = json.loads(lines[0]) if lines else {}
if v.get("event") != "version":
    sys.exit("its first line is no `version` event: %r" % (lines[:1],))
if v.get("protocol") != 1:
    sys.exit("it reads protocol %r; the app writes protocol 1" % v.get("protocol"))
if v.get("pin") != commit:
    sys.exit("it was built from %s, not from the pin %s" % (v.get("pin"), commit))
print("ghostlane-engine %s at %s (%s)" % (v["engine"], v["pin"][:12], v["target"]))
PY
)" || die "the program is not the pin's: $said"
say "$said"

# The namespace, and the veth pair between it and this machine.
ns=gl-lan-check
near=gl-lan0
far=gl-lan1
lan_address=192.168.77.1
far_address=192.168.77.2
teardown() {
  touch "$work/stop" 2> /dev/null || true
  [ -z "${servers:-}" ] || { for _ in $(seq 20); do kill -0 "$servers" 2> /dev/null || break; sleep 0.5; done; kill "$servers" 2> /dev/null || true; }
  sudo -n ip netns del "$ns" 2> /dev/null || true
  sudo -n ip link del "$near" 2> /dev/null || true
}
trap teardown EXIT
sudo -n ip netns del "$ns" 2> /dev/null || true
sudo -n ip link del "$near" 2> /dev/null || true
sudo -n ip netns add "$ns"
sudo -n ip link add "$near" type veth peer name "$far"
sudo -n ip link set "$far" netns "$ns"
sudo -n ip addr add "$lan_address/24" dev "$near"
sudo -n ip link set "$near" up
sudo -n ip netns exec "$ns" ip addr add "$far_address/24" dev "$far"
sudo -n ip netns exec "$ns" ip link set "$far" up
sudo -n ip netns exec "$ns" ip link set lo up
sudo -n ip netns exec "$ns" ping -c 1 -W 2 "$lan_address" > /dev/null || die "the namespace does not reach this machine's end of the pair"

# LAN sharing pins the network by the default gateway and its link-layer
# address, and a runner's gateway has a neighbour entry only after traffic.
gateway="$(ip route show default | awk '/dev/ {print $3; exit}')"
[ -z "$gateway" ] || ping -c 1 -W 2 "$gateway" > /dev/null 2>&1 || true

# The servers: the page and the datagram echo behind the session's port, and
# the session's port itself. They run until the file `stop` appears.
cat > "$work/servers.py" <<'PY'
import hashlib, http.server, json, os, secrets, select, socket, struct, sys, threading, time

work, lan_address = sys.argv[1], sys.argv[2]
DOWN, UP = 2 * 1024 * 1024, 1024 * 1024
TARGET = "203.0.113.10"
USER, PASS = "session-" + secrets.token_hex(4), secrets.token_hex(16)
LAN_USER, LAN_PASS = "lan-" + secrets.token_hex(4), secrets.token_hex(16)
logfile = open(os.path.join(work, "upstream.log"), "a", buffering=1)
log_lock = threading.Lock()


def note(line):
    with log_lock:
        logfile.write(line + "\n")


def pattern(size):
    out, block = bytearray(), hashlib.sha256(b"lan-check").digest()
    while len(out) < size:
        block = hashlib.sha256(block).digest()
        out += block * 64
    return bytes(out[:size])


class Page(http.server.BaseHTTPRequestHandler):
    down = pattern(DOWN)

    def log_message(self, *args):
        pass

    def do_GET(self):
        body = self.down if self.path.endswith("/down") else b"page"
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        length, digest = int(self.headers.get("Content-Length", "0")), hashlib.sha256()
        while length > 0:
            chunk = self.rfile.read(min(length, 65536))
            if not chunk:
                break
            digest.update(chunk)
            length -= len(chunk)
        body = digest.hexdigest().encode()
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


class PageServer(http.server.ThreadingHTTPServer):
    daemon_threads = True

    def handle_error(self, request, client_address):
        pass


page = PageServer(("127.0.0.1", 0), Page)
threading.Thread(target=page.serve_forever, daemon=True).start()

echo = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
echo.bind(("127.0.0.1", 0))


def echo_loop():
    while True:
        try:
            data, peer = echo.recvfrom(65535)
        except OSError:
            return
        echo.sendto(b"echo:" + data, peer)


threading.Thread(target=echo_loop, daemon=True).start()


def recv_exact(conn, n):
    out = b""
    while len(out) < n:
        chunk = conn.recv(n - len(out))
        if not chunk:
            raise ConnectionError("closed")
        out += chunk
    return out


def read_address(conn):
    atyp = recv_exact(conn, 1)[0]
    if atyp == 1:
        host = socket.inet_ntoa(recv_exact(conn, 4))
    elif atyp == 3:
        host = recv_exact(conn, recv_exact(conn, 1)[0]).decode()
    elif atyp == 4:
        host = socket.inet_ntop(socket.AF_INET6, recv_exact(conn, 16))
    else:
        raise ConnectionError("address type %d" % atyp)
    return host, struct.unpack(">H", recv_exact(conn, 2))[0]


def parse_datagram(data):
    if len(data) < 10 or data[2] != 0:
        return None
    atyp = data[3]
    if atyp == 1:
        host, rest = socket.inet_ntoa(data[4:8]), data[8:]
    elif atyp == 3:
        n = data[4]
        host, rest = data[5:5 + n].decode(), data[5 + n:]
    else:
        return None
    return host, struct.unpack(">H", rest[:2])[0], rest[2:]


def pump(a, b):
    try:
        while True:
            data = a.recv(65536)
            if not data:
                break
            b.sendall(data)
    except OSError:
        pass
    finally:
        for s in (a, b):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass


def udp_relay(control, bound):
    """UDP ASSOCIATE: datagrams for TARGET:53 go to the echo; the answer comes back wrapped."""
    out = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    client = None
    control.setblocking(False)
    try:
        while True:
            ready, _, _ = select.select([bound, out, control], [], [], 1)
            if control in ready:
                try:
                    if control.recv(1) == b"":
                        return
                except BlockingIOError:
                    pass
                except OSError:
                    return
            if bound in ready:
                data, client = bound.recvfrom(65535)
                parsed = parse_datagram(data)
                if parsed:
                    host, port, payload = parsed
                    dest = ("127.0.0.1", echo.getsockname()[1]) if (host, port) == (TARGET, 53) else (host, port)
                    note("udp %s:%d" % (host, port))
                    out.sendto(payload, dest)
            if out in ready and client:
                payload, _ = out.recvfrom(65535)
                head = b"\x00\x00\x00\x01" + socket.inet_aton(TARGET) + struct.pack(">H", 53)
                bound.sendto(head + payload, client)
    finally:
        out.close()
        bound.close()


def serve(conn):
    try:
        conn.settimeout(30)
        ver, n = recv_exact(conn, 2)
        methods = recv_exact(conn, n)
        if ver != 5 or 2 not in methods:
            conn.sendall(b"\x05\xff")
            note("refused: no login offered")
            return
        conn.sendall(b"\x05\x02")
        _, ulen = recv_exact(conn, 2)
        user = recv_exact(conn, ulen)
        plen = recv_exact(conn, 1)[0]
        password = recv_exact(conn, plen)
        if user.decode() != USER or password.decode() != PASS:
            conn.sendall(b"\x01\x01")
            note("refused: wrong login")
            return
        conn.sendall(b"\x01\x00")
        _, cmd, _ = recv_exact(conn, 3)
        host, port = read_address(conn)
        if cmd == 1:
            dest = ("127.0.0.1", page.server_address[1]) if (host, port) == (TARGET, 80) else (host, port)
            note("connect %s:%d" % (host, port))
            try:
                remote = socket.create_connection(dest, timeout=10)
            except OSError:
                conn.sendall(b"\x05\x05\x00\x01\x00\x00\x00\x00\x00\x00")
                return
            conn.sendall(b"\x05\x00\x00\x01\x00\x00\x00\x00\x00\x00")
            conn.settimeout(None)
            remote.settimeout(None)
            t = threading.Thread(target=pump, args=(remote, conn), daemon=True)
            t.start()
            pump(conn, remote)
            t.join(5)
            remote.close()
        elif cmd == 3:
            bound = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            bound.bind(("127.0.0.1", 0))
            conn.sendall(b"\x05\x00\x00\x01" + socket.inet_aton("127.0.0.1") + struct.pack(">H", bound.getsockname()[1]))
            note("associate")
            conn.settimeout(None)
            udp_relay(conn, bound)
        else:
            conn.sendall(b"\x05\x07\x00\x01\x00\x00\x00\x00\x00\x00")
    except (OSError, ConnectionError):
        pass
    finally:
        conn.close()


listener = socket.socket()
listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
listener.bind(("127.0.0.1", 0))
listener.listen(64)


def accept():
    while True:
        try:
            conn, _ = listener.accept()
        except OSError:
            return
        threading.Thread(target=serve, args=(conn,), daemon=True).start()


threading.Thread(target=accept, daemon=True).start()
with socket.socket() as probe:
    probe.bind((lan_address, 0))
    lan_port = probe.getsockname()[1]
check = {
    "lan_address": lan_address, "lan_port": lan_port, "lan_user": LAN_USER, "lan_pass": LAN_PASS,
    "upstream_port": listener.getsockname()[1], "upstream_user": USER, "upstream_pass": PASS,
    "target": TARGET, "down_bytes": DOWN, "down_sha256": hashlib.sha256(Page.down).hexdigest(), "up_bytes": UP,
    "ready": os.path.join(work, "ready"), "done": os.path.join(work, "done"),
}
with open(os.path.join(work, "check.json.part"), "w") as f:
    json.dump(check, f, indent=2)
os.replace(os.path.join(work, "check.json.part"), os.path.join(work, "check.json"))
print("servers: the session's port on 127.0.0.1:%d, the page on 127.0.0.1:%d, the LAN on %s:%d"
      % (check["upstream_port"], page.server_address[1], lan_address, lan_port), flush=True)
while not os.path.exists(os.path.join(work, "stop")):
    time.sleep(0.5)
PY

# The client of the namespace: another device on the network.
cat > "$work/client.py" <<'PY'
import hashlib, json, socket, struct, sys, time

check = json.load(open(sys.argv[1]))
HOST, PORT = check["lan_address"], check["lan_port"]
TARGET = check["target"]
failed = []


def outcome(name, ok, detail=""):
    print(("- %s: %s%s" % (name, "ok" if ok else "FAILED", (" (" + detail + ")") if detail else "")), flush=True)
    if not ok:
        failed.append(name)


def recv_exact(conn, n):
    out = b""
    while len(out) < n:
        chunk = conn.recv(n - len(out))
        if not chunk:
            raise ConnectionError("closed after %d of %d bytes" % (len(out), n))
        out += chunk
    return out


def greet(conn, methods, user=None, password=None):
    """The SOCKS5 greeting and login. Returns None when the server accepted, else the words of the refusal."""
    conn.sendall(bytes([5, len(methods)]) + bytes(methods))
    reply = recv_exact(conn, 2)
    if reply[0] != 5:
        return "not SOCKS5: %r" % reply
    if reply[1] == 0xFF:
        return "no method accepted"
    if reply[1] == 0:
        return None
    if reply[1] != 2:
        return "method %d chosen" % reply[1]
    u, p = (user or "").encode(), (password or "").encode()
    conn.sendall(b"\x01" + bytes([len(u)]) + u + bytes([len(p)]) + p)
    status = recv_exact(conn, 2)
    return None if status[1] == 0 else "login refused"


def opened(methods, user=None, password=None):
    conn = socket.create_connection((HOST, PORT), timeout=10)
    conn.settimeout(15)
    return conn, greet(conn, methods, user, password)


def request(conn, cmd, host, port):
    conn.sendall(bytes([5, cmd, 0, 1]) + socket.inet_aton(host) + struct.pack(">H", port))
    head = recv_exact(conn, 4)
    if head[3] == 1:
        addr, bport = socket.inet_ntoa(recv_exact(conn, 4)), struct.unpack(">H", recv_exact(conn, 2))[0]
    elif head[3] == 4:
        addr, bport = socket.inet_ntop(socket.AF_INET6, recv_exact(conn, 16)), struct.unpack(">H", recv_exact(conn, 2))[0]
    else:
        raise ConnectionError("reply address type %d" % head[3])
    return head[1], addr, bport


def http(path, method="GET", body=b""):
    conn, why = opened([2], check["lan_user"], check["lan_pass"])
    if why:
        raise ConnectionError(why)
    with conn:
        rep, _, _ = request(conn, 1, TARGET, 80)
        if rep != 0:
            raise ConnectionError("CONNECT replied %d" % rep)
        head = "%s %s HTTP/1.0\r\nHost: %s\r\nContent-Length: %d\r\n\r\n" % (method, path, TARGET, len(body))
        conn.sendall(head.encode() + body)
        data = b""
        while True:
            chunk = conn.recv(65536)
            if not chunk:
                break
            data += chunk
    header, _, payload = data.partition(b"\r\n\r\n")
    if not header.startswith(b"HTTP/1.") or b" 200 " not in header.split(b"\r\n")[0]:
        raise ConnectionError("answer %r" % header[:60])
    return payload


def attempt(name, fn):
    try:
        fn()
    except Exception as e:  # the check's own words
        outcome(name, False, "%s: %s" % (type(e).__name__, e))


def refused(name, methods, user=None, password=None):
    def go():
        try:
            conn, why = opened(methods, user, password)
        except OSError as e:
            outcome(name, False, "could not reach the listener: %s" % e)
            return
        with conn:
            outcome(name, why is not None, why or "the server let the client in")
    attempt(name, go)


# Refused: no login at all, a wrong password, and a client that lists the
# no-login method first (the server must still demand the login).
refused("no login is refused", [0])
refused("a wrong password is refused", [2], check["lan_user"], "not-" + check["lan_pass"])
refused("a wrong username is refused", [2], "not-" + check["lan_user"], check["lan_pass"])


def no_login_first():
    conn = socket.create_connection((HOST, PORT), timeout=10)
    conn.settimeout(15)
    with conn:
        conn.sendall(b"\x05\x02\x00\x02")
        reply = recv_exact(conn, 2)
        outcome("a list that offers no login first still gets the login asked", reply == b"\x05\x02", repr(reply))


attempt("a list that offers no login first still gets the login asked", no_login_first)


def udp_without_login():
    conn = socket.create_connection((HOST, PORT), timeout=10)
    conn.settimeout(15)
    with conn:
        why = greet(conn, [0])
        if why is None:
            # The server took no login: ask for the relay anyway, and fail if it is given.
            rep, _, _ = request(conn, 3, "0.0.0.0", 0)
            outcome("a datagram relay is not given without the login", False, "UDP ASSOCIATE replied %d" % rep)
        else:
            outcome("a datagram relay is not given without the login", True, why)


attempt("a datagram relay is not given without the login", udp_without_login)


def download():
    body = http("/down")
    outcome("the download with the login", len(body) == check["down_bytes"] and hashlib.sha256(body).hexdigest() == check["down_sha256"],
            "%d bytes" % len(body))


def upload():
    body = bytes(range(256)) * (check["up_bytes"] // 256)
    digest = http("/up", "POST", body)
    outcome("the upload with the login", digest.decode() == hashlib.sha256(body).hexdigest(), digest.decode()[:16])


attempt("the download with the login", download)
attempt("the upload with the login", upload)


def datagram():
    conn, why = opened([2], check["lan_user"], check["lan_pass"])
    if why:
        raise ConnectionError(why)
    with conn:
        rep, addr, bport = request(conn, 3, "0.0.0.0", 0)
        if rep != 0:
            raise ConnectionError("UDP ASSOCIATE replied %d" % rep)
        relay = (HOST if addr in ("0.0.0.0", "::") else addr, bport)
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.settimeout(2)
        payload = b"lan-check-" + bytes(range(64))
        head = b"\x00\x00\x00\x01" + socket.inet_aton(TARGET) + struct.pack(">H", 53)
        got = None
        for _ in range(5):
            sock.sendto(head + payload, relay)
            try:
                data, _ = sock.recvfrom(65535)
            except socket.timeout:
                continue
            got = data[10:]
            break
        outcome("a datagram out and its answer back", got == b"echo:" + payload, "relay %s:%d" % relay)


attempt("a datagram out and its answer back", datagram)
sys.exit(1 if failed else 0)
PY

: > "$work/upstream.log"
: > "$work/outcomes.txt"
rm -f "$work/ready" "$work/done" "$work/stop" "$work/check.json"
"$py" -I "$work/servers.py" "$work" "$lan_address" > "$work/servers.out" 2>&1 &
servers=$!
for _ in $(seq 120); do
  [ -f "$work/check.json" ] && break
  kill -0 "$servers" 2> /dev/null || break
  sleep 0.5
done
[ -f "$work/check.json" ] || die "the servers did not come up: $(tail -c 600 "$work/servers.out")"
say "$(cat "$work/servers.out")"

# DesktopLanProxyRealTest, as the app's build runs its tests, in the
# background: it starts the proxy, writes `ready`, waits for `done`.
# --rerun: what it reads is no input Gradle knows of.
results="$root/sharedUI/build/test-results/jvmTest"
class=org.olcbox.app.vpn.DesktopLanProxyRealTest
rm -f "$results/TEST-$class.xml"
(
  cd "$root"
  LAN_CHECK="$work/check.json" GHOSTLANE_ENGINE_BINARY="$engine" GHOSTLANE_CORE_COMMIT="$commit" \
    ./gradlew :sharedUI:jvmTest --rerun --tests "$class" --stacktrace
) > "$work/gradle.out" 2>&1 &
gradle_pid=$!

client=-1
for _ in $(seq 1800); do
  [ -f "$work/ready" ] && break
  kill -0 "$gradle_pid" 2> /dev/null || break
  sleep 1
done
if [ -f "$work/ready" ]; then
  say "the proxy is up; a client from the namespace"
  set +e
  sudo -n ip netns exec "$ns" "$py" -I "$work/client.py" "$work/check.json" > "$work/client.out" 2>&1
  client=$?
  set -e
  cat "$work/client.out"
  touch "$work/done"
else
  say "the proxy never came up (Gradle ended first)"
  touch "$work/done"
fi
set +e
wait "$gradle_pid"
gradle=$?
set -e
tail -n 60 "$work/gradle.out"

source="$root/sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/DesktopLanProxyRealTest.kt"
verdict="$("$py" -I - "$results/TEST-$class.xml" "$source" <<'PY'
import re, sys
import xml.etree.ElementTree as ET
xml, source = sys.argv[1], sys.argv[2]
written = len(re.findall(r"^\s*@Test\b", open(source, encoding="utf-8").read(), re.M))
try:
    suite = ET.parse(xml).getroot()
except (OSError, ET.ParseError) as e:
    sys.exit("no result of DesktopLanProxyRealTest (%s)" % e)
n = {k: int(suite.get(k, "0")) for k in ("tests", "skipped", "failures", "errors")}
bad = []
if n["tests"] != written:
    bad.append("%d tests ran of the %d written" % (n["tests"], written))
for k in ("skipped", "failures", "errors"):
    if n[k]:
        bad.append("%d %s" % (n[k], k))
for case in suite.iter("testcase"):
    for kind in ("failure", "error", "skipped"):
        for e in case.iter(kind):
            bad.append("%s: %s %s" % (case.get("name"), kind, (e.get("message") or "").splitlines()[0][:200] if e.get("message") else ""))
print("; ".join(bad) if bad else "%d of %d tests passed" % (n["tests"], written))
sys.exit(1 if bad else 0)
PY
)" && held=0 || held=1

# What the chain carried, by the session's port's own log.
chain=""
for want in "connect 203.0.113.10:80" "associate" "udp 203.0.113.10:53"; do
  grep -qxF "$want" "$work/upstream.log" || chain="$chain; the session's port never saw \`$want\`"
done
grep -q '^refused' "$work/upstream.log" && chain="$chain; the chain reached the session's port with a login it refused"
# No engine is left once the proxy is stopped.
left="$(pgrep -f 'ghostlane-engine run --session' || true)"
[ -z "$left" ] || chain="$chain; an engine is still running (pid $(echo "$left" | tr '\n' ' '))"
sudo -n ip netns exec "$ns" "$py" -I -c '
import socket, sys
s = socket.socket(); s.settimeout(2)
try:
    s.connect((sys.argv[1], int(sys.argv[2])))
except OSError:
    sys.exit(0)
sys.exit(1)' "$lan_address" "$(sed -n 's/.*"lan_port": \([0-9]*\).*/\1/p' "$work/check.json")" || chain="$chain; the namespace still reaches the LAN port after the stop"

{
  echo "#### LAN sharing on ghostlane-engine ($os)"
  echo
  echo "- $said"
  echo "- DesktopLanProxyRealTest: $verdict"
  echo "- the client in the namespace: $([ "$client" -eq 0 ] && echo passed || echo "failed ($client)")"
  [ -z "$chain" ] || echo "- the chain: ${chain#; }"
  sed 's/^/  /' "$work/client.out" 2> /dev/null || true
} >> "$summary"
say "DesktopLanProxyRealTest: $verdict"
[ "$client" -eq 0 ] || die "the client failed ($client)"
[ "$gradle" -eq 0 ] || die "Gradle failed ($gradle)"
[ "$held" -eq 0 ] || die "DesktopLanProxyRealTest did not pass whole: $verdict"
[ -z "$chain" ] || die "the chain: ${chain#; }"
say "passed"
