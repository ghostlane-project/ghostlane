#!/usr/bin/env bash
# desktop-check-os: windows
# desktop-check-paths: sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/desktop/engine/host/WindowsHostEngineRealTest.kt
# desktop-check-paths: sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/desktop/engine/host/WindowsDefaultTest.kt
#
# The Windows desktop on ghostlane-engine, as the app runs it (WD). windows-tun.ps1 holds
# hev, Wintun, the routes and the DNS guard in front of a stand-in engine; this
# one puts the app's own HostEngine, its default engine, behind them, and a
# room on the same program (WindowsHostEngineRealTest, which this script runs
# and holds to: every test of it run, none skipped, none failed, and then
# room.sh over the same program):
#   - HostEngine starts a VLESS REALITY Vision line to the official Xray 26.4.25
#     on loopback with its sockets bound to the interface the machine leaves by
#     (the alias the manager reads before the tun), hev with its Wintun adapter
#     hands the machine's traffic to the engine's port with the login the
#     manager gives it, and a page and a 2 MiB download are fetched by this
#     JVM's own stack from TEST-NET-3, an address only the server's redirect
#     answers: they crossed the adapter, hev, the engine and the line. The
#     adapter's counters moved; after the stop no adapter and no record stand;
#   - the same with the rules the Windows tunnel keeps in the core: an address
#     the user sends direct is not answered by the line, an address on no list is;
#   - room.sh: HostRoom's session is accepted by the engine's `check-config`,
#     the engine joins the room the session names on the interop pipe's loopback
#     hub and fails with its own `room` error as the manager reads it. It also
#     holds that the Windows engine carries rooms. That a room is bound to the
#     way out is WindowsDefaultTest's (the session's `bind`), and the engine's
#     own binding by IP_UNICAST_IF is windows-tun.ps1's.
#
# Not covered: a room that serves (no olcRTC server is reachable from a runner),
# so a room's own sockets leaving by the physical interface beside hev is the
# engine's bind test and the session's `bind` read together, not one run.
#
# Run by rust-desktop.yml on the Windows leg:
#   bash scripts/desktop-checks/windows-engine.sh
# with GHOSTLANE_ENGINE (the program), GHOSTLANE_CORE_COMMIT, DESKTOP_CHECK_OS
# (windows), DESKTOP_CHECK_WORK, and HEV_OUT holding hev.exe and wintun.dll.
# Needs an administrator, Python 3, curl, openssl, and Java 21 with the Android
# SDK for the Gradle build.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"

engine="${GHOSTLANE_ENGINE:?the program to check: GHOSTLANE_ENGINE}"
commit="${GHOSTLANE_CORE_COMMIT:?the pin the program was built from: GHOSTLANE_CORE_COMMIT}"
os="${DESKTOP_CHECK_OS:?linux, macos or windows: DESKTOP_CHECK_OS}"
work="${DESKTOP_CHECK_WORK:?a directory for this check alone: DESKTOP_CHECK_WORK}"
mkdir -p "$work"
work="$(cd "$work" && pwd)"
summary="${GITHUB_STEP_SUMMARY:-/dev/null}"
say() { echo "windows-engine: $*"; }
die() { echo "windows-engine: $*" >&2; echo "- windows-engine: $*" >> "$summary"; exit 1; }

py=""
for candidate in python3 python; do
  if command -v "$candidate" > /dev/null && "$candidate" -c 'import sys; sys.exit(sys.version_info < (3, 8))' 2> /dev/null; then
    py="$candidate"
    break
  fi
done
[ -n "$py" ] || die "no Python 3"
[ -x "$engine" ] || die "GHOSTLANE_ENGINE is no program: $engine"
[ "$os" = windows ] || die "this check is Windows's; DESKTOP_CHECK_OS is $os"
hev_out="${HEV_OUT:-}"
[ -n "$hev_out" ] && [ -f "$hev_out/hev.exe" ] && [ -f "$hev_out/wintun.dll" ] || die "HEV_OUT must hold hev.exe and wintun.dll"
admin="$(powershell.exe -NoProfile -NonInteractive -Command '([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)' | tr -d '\r')"
[ "$admin" = True ] || die "this check needs an administrator session"

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
print("ghostlane-engine %s at %s (%s; rooms %s, BoringSSL %s)" % (
    v["engine"], v["pin"][:12], v["target"], "yes" if v["rooms"] else "no", "yes" if v["boringssl"] else "no"))
PY
)" || die "the program is not the pin's: $said"
say "$said"

# Xray 26.4.25 as XTLS publishes it, checked against the sha256 of its .dgst.
# The Linux build's is scripts/xray-servers.txt's.
xray_version=26.4.25
case "$os:$(uname -m)" in
  linux:x86_64) asset=Xray-linux-64 ;;
  macos:arm64) asset=Xray-macos-arm64-v8a ;;
  macos:x86_64) asset=Xray-macos-64 ;;
  windows:*) asset=Xray-windows-64 ;;
  *) die "no Xray build is pinned here for $os on $(uname -m)" ;;
esac
case "$asset" in
  Xray-linux-64) sum="$(sed -n "s/^$xray_version \([0-9a-f]\{64\}\)\$/\1/p" "$root/scripts/xray-servers.txt")" ;;
  Xray-macos-arm64-v8a) sum=b9d5df9f37e482f17289b105341218aaa614363a29ebb2b6054805e5396d50fe ;;
  Xray-macos-64) sum=fde231da0e7edd29d7a7b51fee13cae2fa05dfc6583f31a15945d5c3302aedf5 ;;
  Xray-windows-64) sum=9f2852563d5196396cf41be8d843de61ce108e1e398ed93e93c09976f7791629 ;;
esac
[ -n "$sum" ] || die "no sha256 of $asset $xray_version"
mkdir -p "$work/xray"
curl -fsSL --retry 3 -o "$work/xray/$asset.zip" \
  "https://github.com/XTLS/Xray-core/releases/download/v$xray_version/$asset.zip" || die "$asset $xray_version could not be fetched"
xray="$("$py" -I - "$work/xray/$asset.zip" "$sum" "$work/xray" 2>&1 <<'PY'
import hashlib, os, stat, sys, zipfile
archive, want, out = sys.argv[1:4]
got = hashlib.sha256(open(archive, "rb").read()).hexdigest()
if got != want:
    sys.exit("the archive hashes to %s, the pin is %s" % (got, want))
name = "xray.exe" if os.name == "nt" else "xray"
with zipfile.ZipFile(archive) as z:
    z.extract(name, out)
path = os.path.join(out, name)
os.chmod(path, os.stat(path).st_mode | stat.S_IXUSR)
print(path)
PY
)" || die "Xray $xray_version: $xray"
xray_said="$("$xray" version | sed -n 1p)"
say "$xray_said"

# The servers: the page, the REALITY target, Xray in front of them. They run
# until the file `stop` appears in the work directory.
cat > "$work/servers.py" <<'PY'
import hashlib, http.server, json, os, socket, ssl, subprocess, sys, threading, time

work, xray = sys.argv[1], sys.argv[2]
DOWN, UP = 2 * 1024 * 1024, 1024 * 1024
TARGET, TARGET_PORT = "203.0.113.10", 80
# An address of the same TEST-NET-3 that only the rules in the second test send straight out.
UNLISTED = "203.0.113.20"
NAME = "xray.desktop-check.test"
SNI = "reality.desktop-check.test"
UUID = "5a4e8c2d-6a1f-4c3b-9d7e-2f1b0c9a8e71"


def pattern(size):
    out, block = bytearray(), hashlib.sha256(b"desktop-check").digest()
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
        if not isinstance(sys.exc_info()[1], ConnectionError):
            super().handle_error(request, client_address)


def reality_target():
    """What a REALITY server mirrors, as a site answers: TLS 1.3, h2 offered."""
    cert, key = os.path.join(work, "target.crt"), os.path.join(work, "target.key")
    env = dict(os.environ, MSYS_NO_PATHCONV="1")
    subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
                    "-subj", "/CN=" + SNI, "-keyout", key, "-out", cert], check=True, capture_output=True, env=env)
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


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


page = PageServer(("127.0.0.1", 0), Page)
threading.Thread(target=page.serve_forever, daemon=True).start()
target = reality_target()
out = subprocess.run([xray, "x25519"], check=True, capture_output=True, text=True).stdout
keys = [line.split(":", 1)[1].strip() for line in out.splitlines() if ":" in line]
if len(keys) < 2 or not all(keys[:2]):
    sys.exit("cannot read `xray x25519`: %r" % out)
private, public = keys[0], keys[1]
port = free_port()
config = {
    "log": {"loglevel": "warning"},
    "inbounds": [{
        "tag": "in", "listen": "127.0.0.1", "port": port, "protocol": "vless",
        "settings": {"clients": [{"id": UUID, "flow": "xtls-rprx-vision"}], "decryption": "none"},
        "streamSettings": {"network": "raw", "security": "reality", "realitySettings": {
            "dest": target, "serverNames": [SNI], "privateKey": private, "shortIds": ["ab12cd34"]}},
    }],
    "outbounds": [{"tag": "direct", "protocol": "freedom", "settings": {
        "redirect": "127.0.0.1:%d" % page.server_address[1], "ipsBlocked": [],
        "finalRules": [{"action": "allow", "ip": ["127.0.0.0/8"]}]}}],
}
with open(os.path.join(work, "xray-server.json"), "w") as f:
    json.dump(config, f, indent=2)
log = open(os.path.join(work, "xray-server.log"), "w")
server = subprocess.Popen([xray, "run", "-c", os.path.join(work, "xray-server.json")], stdout=log, stderr=subprocess.STDOUT)
deadline = time.monotonic() + 20
while True:
    if server.poll() is not None:
        sys.exit("Xray ended at its start; its log is xray-server.log")
    try:
        socket.create_connection(("127.0.0.1", port), timeout=0.5).close()
        break
    except OSError:
        if time.monotonic() > deadline:
            sys.exit("Xray did not open its port in 20 s")
        time.sleep(0.2)

link = ("vless://%s@{host}:%d?security=reality&encryption=none&pbk=%s&sid=ab12cd34&fp=chrome"
        "&sni=%s&flow=xtls-rprx-vision&type=tcp#desktop-check") % (UUID, port, public, SNI)
check = {
    "link": link.format(host="127.0.0.1"),
    "named_link": link.format(host=NAME),
    "name": NAME,
    "address": "127.0.0.1",
    "target": TARGET,
    "target_port": TARGET_PORT,
    "down_bytes": DOWN,
    "down_sha256": hashlib.sha256(Page.down).hexdigest(),
    "up_bytes": UP,
    "unlisted": UNLISTED,
    "outcomes": os.path.join(work, "outcomes.txt"),
}
with open(os.path.join(work, "check.json.part"), "w") as f:
    json.dump(check, f, indent=2)
os.replace(os.path.join(work, "check.json.part"), os.path.join(work, "check.json"))
print("servers: Xray on 127.0.0.1:%d, the page on 127.0.0.1:%d, the target on %s"
      % (port, page.server_address[1], target), flush=True)
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

: > "$work/outcomes.txt"
"$py" -I "$work/servers.py" "$work" "$xray" > "$work/servers.out" 2>&1 &
servers=$!
stop_servers() {
  touch "$work/stop"
  for _ in $(seq 20); do kill -0 "$servers" 2> /dev/null || break; sleep 0.5; done
  kill "$servers" 2> /dev/null || true
}
trap stop_servers EXIT
for _ in $(seq 120); do
  [ -f "$work/check.json" ] && break
  kill -0 "$servers" 2> /dev/null || break
  sleep 0.5
done
[ -f "$work/check.json" ] || die "the servers did not come up: $(tail -c 600 "$work/servers.out")"
say "$(cat "$work/servers.out")"

# WindowsHostEngineRealTest, as the app's build runs its tests. --rerun: what it reads
# is no input Gradle knows of, and a result from the build cache ran nothing.
results="$root/sharedUI/build/test-results/jvmTest"
class=org.olcbox.app.vpn.desktop.engine.host.WindowsHostEngineRealTest
rm -f "$results/TEST-$class.xml"
set +e
(
  cd "$root"
  WINDOWS_ENGINE_CHECK="$work/check.json" HEV_OUT="$hev_out" GHOSTLANE_ENGINE_BINARY="$engine" GHOSTLANE_CORE_COMMIT="$commit" \
    ./gradlew :sharedUI:jvmTest --rerun --tests "$class" --stacktrace
)
gradle=$?
set -e

source="$root/sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/desktop/engine/host/WindowsHostEngineRealTest.kt"
verdict="$("$py" -I - "$results/TEST-$class.xml" "$source" <<'PY'
import re, sys
import xml.etree.ElementTree as ET
xml, source = sys.argv[1], sys.argv[2]
written = len(re.findall(r"^\s*@Test\b", open(source, encoding="utf-8").read(), re.M))
try:
    suite = ET.parse(xml).getroot()
except (OSError, ET.ParseError) as e:
    sys.exit("no result of WindowsHostEngineRealTest (%s)" % e)
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

# room.sh over the same program: HostRoom's session, the engine's join and its room error. The work
# directory is its own; the summary lines are its own.
room_ok=0
room_work="$work/room"
mkdir -p "$room_work"
if DESKTOP_CHECK_WORK="$room_work" bash "$here/room.sh"; then room_ok=1; fi

{
  echo "#### the Windows desktop on ghostlane-engine"
  echo
  echo "- $said"
  echo "- the server: $xray_said ($asset)"
  echo "- WindowsHostEngineRealTest: $verdict"
  sed 's/^/- /' "$work/outcomes.txt"
  if [ "$room_ok" -eq 1 ]; then echo "- room.sh: passed on the Windows engine"; else echo "- room.sh: **failed** on the Windows engine"; fi
} >> "$summary"
say "WindowsHostEngineRealTest: $verdict"
cat "$work/outcomes.txt"
[ "$gradle" -eq 0 ] || die "Gradle failed ($gradle)"
[ "$held" -eq 0 ] || die "WindowsHostEngineRealTest did not pass whole: $verdict"
[ "$room_ok" -eq 1 ] || die "room.sh failed on the Windows engine"
say "passed"
