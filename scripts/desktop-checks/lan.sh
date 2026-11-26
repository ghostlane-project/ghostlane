#!/usr/bin/env bash
# desktop-check-os: linux
# desktop-check-paths: sharedUI/src/jvmMain/kotlin/org/olcbox/app/vpn/DesktopLanProxy.kt
# desktop-check-paths: sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/DesktopLanProxy*Test.kt
# desktop-check-paths: sharedUI/src/jvmMain/kotlin/org/olcbox/app/vpn/desktop/engine/host/HostEngine.kt
# desktop-check-paths: sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/desktop/engine/host/HostEngineTest.kt
#
# LAN sharing on ghostlane-engine (D5), in two network namespaces: HostEngine
# starts the line's own core at the pin with CoreInbound.Lan on a private
# address of this machine (the near end of a veth pair) and a VLESS REALITY
# Vision line to Xray 26.4.25, DesktopLanProxy starts no second process and
# binds no port of its own, and a client in the namespace at the far end
# reaches it as another device on the network would (DesktopLanProxyRealTest,
# which this script runs and holds to: every test of it run, none skipped,
# none failed):
#   - with the login, the client's SOCKS5 CONNECT carries a download and an
#     upload byte for byte, and its UDP ASSOCIATE (the client binding first and
#     declaring its port) carries a datagram out and the answer back, all over
#     the line's own core (one ghostlane-engine process, no second one);
#   - with no login at all, with the wrong password, with the wrong username,
#     and with a method list that offers no login first, every attempt is
#     refused, TCP and datagram alike;
#   - a stop closes the LAN port, and no engine is left;
#   - a LAN port another program holds fails the line's start with the engine's
#     sentence.
#
# Target 203.0.113.10 (TEST-NET-3) sits on this machine's loopback and is
# unreachable from the client namespace except through the line's Xray server.
# The addresses of the pair are 192.168.77.1 (this machine's end) and
# 192.168.77.2 (the namespace's); everything added is removed on exit. A room's
# leg of LAN sharing is S10's.
#
# Run by rust-desktop.yml on the Linux leg:
#   bash scripts/desktop-checks/lan.sh
# with GHOSTLANE_ENGINE (the program), GHOSTLANE_CORE_COMMIT (the commit it
# must say it was built from), DESKTOP_CHECK_OS (linux) and DESKTOP_CHECK_WORK
# (an empty directory of its own). Needs Python 3, curl, openssl, sudo with
# ip(8), and Java 21 with the Android SDK for the Gradle build.
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
for tool in ip curl openssl; do
  command -v "$tool" > /dev/null || die "no $tool"
done

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

# Xray 26.4.25, the line's server, checked against scripts/xray-servers.txt.
xray_version=26.4.25
sum="$(sed -n "s/^$xray_version \([0-9a-f]\{64\}\)\$/\1/p" "$root/scripts/xray-servers.txt")"
[ -n "$sum" ] || die "no sha256 of Xray-linux-64 $xray_version"
mkdir -p "$work/xray"
curl -fsSL --retry 3 -o "$work/xray/Xray-linux-64.zip" \
  "https://github.com/XTLS/Xray-core/releases/download/v$xray_version/Xray-linux-64.zip" ||
  die "Xray $xray_version could not be fetched"
xray="$("$py" -I - "$work/xray/Xray-linux-64.zip" "$sum" "$work/xray" 2>&1 <<'PY'
import hashlib, os, stat, sys, zipfile
archive, want, out = sys.argv[1:4]
got = hashlib.sha256(open(archive, "rb").read()).hexdigest()
if got != want:
    sys.exit("the archive hashes to %s, the pin is %s" % (got, want))
with zipfile.ZipFile(archive) as z:
    z.extract("xray", out)
path = os.path.join(out, "xray")
os.chmod(path, os.stat(path).st_mode | stat.S_IXUSR)
print(path)
PY
)" || die "Xray $xray_version: $xray"
xray_said="$("$xray" version | sed -n 1p)"
say "$xray_said"

# The namespace, the veth pair between it and this machine, and the target
# address on this machine's loopback (unreachable from the client namespace).
ns=gl-lan-check
near=gl-lan0
far=gl-lan1
lan_address=192.168.77.1
far_address=192.168.77.2
target_ip=203.0.113.10
teardown() {
  touch "$work/stop" 2> /dev/null || true
  [ -z "${servers:-}" ] || { for _ in $(seq 20); do kill -0 "$servers" 2> /dev/null || break; sleep 0.5; done; kill "$servers" 2> /dev/null || true; }
  sudo -n ip netns del "$ns" 2> /dev/null || true
  sudo -n ip link del "$near" 2> /dev/null || true
  sudo -n ip addr del "$target_ip/32" dev lo 2> /dev/null || true
}
trap teardown EXIT
sudo -n ip netns del "$ns" 2> /dev/null || true
sudo -n ip link del "$near" 2> /dev/null || true
sudo -n ip addr del "$target_ip/32" dev lo 2> /dev/null || true
sudo -n ip addr add "$target_ip/32" dev lo
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

# The servers: the HTTP page and the UDP echo on 203.0.113.10, and Xray 26.4.25
# in front of them as the VLESS REALITY Vision line's server.
cat > "$work/servers.py" <<'PY'
import hashlib, http.server, json, os, secrets, socket, ssl, subprocess, sys, threading, time

work, xray, lan_address, target_ip = sys.argv[1:5]
DOWN, UP = 2 * 1024 * 1024, 1024 * 1024
SNI = "reality.lan-check.test"
UUID = "5a4e8c2d-6a1f-4c3b-9d7e-2f1b0c9a8e71"
LAN_USER, LAN_PASS = "lan-" + secrets.token_hex(4), secrets.token_hex(16)
origin_log = open(os.path.join(work, "origin.log"), "a", buffering=1)
log_lock = threading.Lock()


def note(line):
    with log_lock:
        origin_log.write(line + "\n")


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
        note("http GET %s" % self.path)
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
        note("http POST %s" % self.path)
        body = digest.hexdigest().encode()
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


class PageServer(http.server.ThreadingHTTPServer):
    daemon_threads = True

    def handle_error(self, request, client_address):
        pass


def reality_target():
    cert, key = os.path.join(work, "target.crt"), os.path.join(work, "target.key")
    subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
                    "-subj", "/CN=" + SNI, "-keyout", key, "-out", cert], check=True, capture_output=True)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_3
    context.set_alpn_protocols(["h2", "http/1.1"])
    context.load_cert_chain(cert, key)
    listener = socket.socket()
    listener.bind(("127.0.0.1", 0))
    listener.listen(64)

    def serve(conn):
        try:
            conn.settimeout(15)
            with context.wrap_socket(conn, server_side=True) as tls:
                while tls.recv(4096):
                    pass
        except (OSError, ssl.SSLError):
            pass
        finally:
            conn.close()

    def accept():
        while True:
            try:
                conn, _ = listener.accept()
            except OSError:
                return
            threading.Thread(target=serve, args=(conn,), daemon=True).start()

    threading.Thread(target=accept, daemon=True).start()
    return "127.0.0.1:%d" % listener.getsockname()[1]


page = PageServer((target_ip, 0), Page)
threading.Thread(target=page.serve_forever, daemon=True).start()

echo = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
echo.bind((target_ip, 0))


def echo_loop():
    while True:
        try:
            data, peer = echo.recvfrom(65535)
        except OSError:
            return
        note("udp %d" % len(data))
        echo.sendto(b"echo:" + data, peer)


threading.Thread(target=echo_loop, daemon=True).start()

target = reality_target()
out = subprocess.run([xray, "x25519"], check=True, capture_output=True, text=True).stdout
keys = [line.split(":", 1)[1].strip() for line in out.splitlines() if ":" in line]
if len(keys) < 2 or not all(keys[:2]):
    sys.exit("cannot read `xray x25519`: %r" % out)
private, public = keys[0], keys[1]
with socket.socket() as s:
    s.bind(("127.0.0.1", 0))
    xray_port = s.getsockname()[1]

config = {
    "log": {
        "loglevel": "warning",
        "access": os.path.join(work, "xray-access.log"),
        "error": os.path.join(work, "xray-server.log"),
    },
    "inbounds": [{
        "tag": "in", "listen": "127.0.0.1", "port": xray_port, "protocol": "vless",
        "settings": {"clients": [{"id": UUID, "flow": "xtls-rprx-vision"}], "decryption": "none"},
        "streamSettings": {"network": "raw", "security": "reality", "realitySettings": {
            "dest": target, "serverNames": [SNI], "privateKey": private, "shortIds": ["ab12cd34"]}},
    }],
    "outbounds": [{"tag": "direct", "protocol": "freedom", "settings": {
        "ipsBlocked": [], "finalRules": [{"action": "allow", "ip": [target_ip + "/32"]}]}}],
}
with open(os.path.join(work, "xray-server.json"), "w") as f:
    json.dump(config, f, indent=2)
server = subprocess.Popen([xray, "run", "-c", os.path.join(work, "xray-server.json")],
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
deadline = time.monotonic() + 20
while True:
    if server.poll() is not None:
        sys.exit("Xray ended at its start; its log is xray-server.log")
    try:
        socket.create_connection(("127.0.0.1", xray_port), timeout=0.5).close()
        break
    except OSError:
        if time.monotonic() > deadline:
            sys.exit("Xray did not open its port in 20 s")
        time.sleep(0.2)

while True:
    with socket.socket() as probe:
        probe.bind((lan_address, 0))
        lan_port = probe.getsockname()[1]
    if lan_port != 10808:
        break

link = ("vless://%s@127.0.0.1:%d?security=reality&encryption=none&pbk=%s&sid=ab12cd34&fp=chrome"
        "&sni=%s&flow=xtls-rprx-vision&type=tcp#lan-check") % (UUID, xray_port, public, SNI)
check = {
    "link": link,
    "lan_address": lan_address, "lan_port": lan_port, "lan_user": LAN_USER, "lan_pass": LAN_PASS,
    "target": target_ip, "page_port": page.server_address[1], "echo_port": echo.getsockname()[1],
    "down_bytes": DOWN, "down_sha256": hashlib.sha256(Page.down).hexdigest(), "up_bytes": UP,
    "ready": os.path.join(work, "ready"), "done": os.path.join(work, "done"),
}
with open(os.path.join(work, "check.json.part"), "w") as f:
    json.dump(check, f, indent=2)
os.replace(os.path.join(work, "check.json.part"), os.path.join(work, "check.json"))
print("servers: Xray on 127.0.0.1:%d, the page on %s:%d, the echo on %s:%d, the LAN on %s:%d"
      % (xray_port, target_ip, check["page_port"], target_ip, check["echo_port"], lan_address, lan_port), flush=True)
while not os.path.exists(os.path.join(work, "stop")) and server.poll() is None:
    time.sleep(0.5)
code = server.poll()
server.terminate()
try:
    server.wait(5)
except subprocess.TimeoutExpired:
    server.kill()
if code is not None:
    sys.exit("Xray ended while the check ran (%s); its log is xray-server.log" % code)
PY

# The client of the namespace: another device on the network.
cat > "$work/client.py" <<'PY'
import hashlib, json, socket, struct, sys

check = json.load(open(sys.argv[1]))
HOST, PORT = check["lan_address"], check["lan_port"]
TARGET, PAGE_PORT, ECHO_PORT = check["target"], check["page_port"], check["echo_port"]
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
        rep, _, _ = request(conn, 1, TARGET, PAGE_PORT)
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
    except Exception as e:
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


# Target is unreachable from the client namespace without the proxy.
def unreachable_direct():
    s = socket.socket()
    s.settimeout(2)
    try:
        s.connect((TARGET, PAGE_PORT))
    except OSError:
        outcome("the target is unreachable outside the line", True)
        return
    finally:
        s.close()
    outcome("the target is unreachable outside the line", False, "connected directly")


attempt("the target is unreachable outside the line", unreachable_direct)

# Refused: no login at all, a wrong password, a wrong username, and a client
# that lists the no-login method first (the server must still demand the login).
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
        # The core pins an association that demands a login to the socket the
        # client declares, so the client binds first and says its port (an
        # all-zero address waits for F14).
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.bind(("0.0.0.0", 0))
        sock.settimeout(2)
        try:
            rep, addr, bport = request(conn, 3, "0.0.0.0", sock.getsockname()[1])
            if rep != 0:
                raise ConnectionError("UDP ASSOCIATE replied %d" % rep)
            relay = (HOST if addr in ("0.0.0.0", "::") else addr, bport)
            payload = b"lan-check-" + bytes(range(64))
            head = b"\x00\x00\x00\x01" + socket.inet_aton(TARGET) + struct.pack(">H", ECHO_PORT)
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
        finally:
            sock.close()


attempt("a datagram out and its answer back", datagram)
sys.exit(1 if failed else 0)
PY

: > "$work/origin.log"
: > "$work/xray-access.log"
: > "$work/xray-server.log"
rm -f "$work/ready" "$work/done" "$work/stop" "$work/check.json"
"$py" -I "$work/servers.py" "$work" "$xray" "$lan_address" "$target_ip" > "$work/servers.out" 2>&1 &
servers=$!
for _ in $(seq 120); do
  [ -f "$work/check.json" ] && break
  kill -0 "$servers" 2> /dev/null || break
  sleep 0.5
done
[ -f "$work/check.json" ] || die "the servers did not come up: $(tail -c 600 "$work/servers.out")"
say "$(cat "$work/servers.out")"

# DesktopLanProxyRealTest, as the app's build runs its tests, in the
# background: it starts the line's own core and DesktopLanProxy, writes `ready`,
# waits for `done`. --rerun: what it reads is no input Gradle knows of.
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
running_count=0
for _ in $(seq 1800); do
  [ -f "$work/ready" ] && break
  kill -0 "$gradle_pid" 2> /dev/null || break
  sleep 1
done
if [ -f "$work/ready" ]; then
  running_pids="$(pgrep -f 'ghostlane-engine run --session' || true)"
  running_count="$(printf '%s\n' "$running_pids" | sed '/^$/d' | wc -l | tr -d ' ')"
  say "the line's core is up ($running_count ghostlane-engine process); a client from the namespace"
  set +e
  sudo -n ip netns exec "$ns" "$py" -I "$work/client.py" "$work/check.json" > "$work/client.out" 2>&1
  client=$?
  set -e
  cat "$work/client.out"
  touch "$work/done"
else
  say "the line's core never came up (Gradle ended first)"
  touch "$work/done"
fi
set +e
wait "$gradle_pid"
gradle=$?
set -e
tail -n 20 "$work/gradle.out"
echo "--- what the origin saw"
cat "$work/origin.log"
echo "--- what Xray saw"
tail -n 30 "$work/xray-access.log" 2> /dev/null || true
echo "--- the test's own output"
"$py" -I - "$results/TEST-$class.xml" <<'PY' || true
import sys
import xml.etree.ElementTree as ET
for e in ET.parse(sys.argv[1]).getroot().iter("system-out"):
    print((e.text or "")[-3000:])
PY

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

# What the line's own core carried, with no second engine process.
line_check=""
[ "$running_count" -eq 1 ] || line_check="$line_check; $running_count ghostlane-engine processes ran while serving (expected 1)"
for want in "http GET /down" "http POST /up"; do
  grep -qxF "$want" "$work/origin.log" || line_check="$line_check; the origin never saw \`$want\`"
done
grep -q '^udp ' "$work/origin.log" || line_check="$line_check; the origin never saw a datagram"
grep -qF "$target_ip:" "$work/xray-access.log" || line_check="$line_check; Xray's access log never saw $target_ip"
# No engine is left once the line is stopped.
left="$(pgrep -f 'ghostlane-engine run --session' || true)"
[ -z "$left" ] || line_check="$line_check; an engine is still running (pid $(echo "$left" | tr '\n' ' '))"
sudo -n ip netns exec "$ns" "$py" -I -c '
import socket, sys
s = socket.socket(); s.settimeout(2)
try:
    s.connect((sys.argv[1], int(sys.argv[2])))
except OSError:
    sys.exit(0)
sys.exit(1)' "$lan_address" "$(sed -n 's/.*"lan_port": \([0-9]*\).*/\1/p' "$work/check.json")" || line_check="$line_check; the namespace still reaches the LAN port after the stop"

{
  echo "#### LAN sharing on ghostlane-engine ($os)"
  echo
  echo "- $said"
  echo "- the line's server: $xray_said"
  echo "- DesktopLanProxyRealTest: $verdict"
  echo "- the client in the namespace: $([ "$client" -eq 0 ] && echo passed || echo "failed ($client)")"
  [ -z "$line_check" ] || echo "- the line: ${line_check#; }"
  sed 's/^/  /' "$work/client.out" 2> /dev/null || true
} >> "$summary"
say "DesktopLanProxyRealTest: $verdict"
[ "$client" -eq 0 ] || die "the client failed ($client)"
[ "$gradle" -eq 0 ] || die "Gradle failed ($gradle)"
[ "$held" -eq 0 ] || die "DesktopLanProxyRealTest did not pass whole: $verdict"
[ -z "$line_check" ] || die "the line: ${line_check#; }"
say "passed"
