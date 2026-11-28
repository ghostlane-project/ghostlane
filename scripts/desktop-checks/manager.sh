#!/usr/bin/env bash
# desktop-check-os: linux
# desktop-check-paths: sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/desktop/engine/host/DesktopManagerRealTest.kt
#
# E2E: a session started through the app's own manager. Every other script of
# this directory runs HostEngine, HostProcess or LinuxTunController directly;
# this one runs DesktopVpnManager, with the default engine factory, as the
# Connect button does (DesktopManagerRealTest, which this script runs and holds
# to: every test of it run, none skipped, none failed):
#   - the manager connects a VLESS REALITY Vision line to Xray 26.4.25 in a
#     network namespace, as the user, with hev as root through `sudo -n`; its
#     own check of the exit (https://1.1.1.1/cdn-cgi/trace through the line)
#     answers from the far side's server, so Connected is the manager's word;
#   - the engine the manager started is the program of GHOSTLANE_ENGINE;
#   - a page is fetched through the session's own SOCKS listener (what the
#     proxy mode of a desktop is: Linux has no system proxy, the listener is
#     the manager's `subscriptionFetchProxy`) and through the tun, by address;
#     the far side sees both from its own addresses, never from the client's;
#   - the manager's stop ends the session: no rule, route or device of the
#     tunnel, rp_filter as it was, and no engine or hev process left.
#
# Two namespaces joined by a veth pair, as linux-tun.sh makes them: client
# (out0 10.231.0.2/24, default route by the world) where the test runs as the
# user, and world (w0 10.231.0.1/24) with the far side on its loopback: DNS
# (10.232.0.53, 1.1.1.1), an HTTP origin on 10.232.0.30:80, the manager's exit
# check on 1.1.1.1:443 (a certificate of this run's own, trusted by the test's
# JVM alone), Xray on 10.232.0.40:443 and its REALITY target on 10.232.0.41:443.
#
# Run by rust-desktop.yml on the Linux leg:
#   bash scripts/desktop-checks/manager.sh
# with GHOSTLANE_ENGINE, GHOSTLANE_CORE_COMMIT, DESKTOP_CHECK_OS and
# DESKTOP_CHECK_WORK. On another OS it says so and passes (the Mac's manager
# goes through a root helper that a runner cannot approve). Needs passwordless
# sudo, iproute2, Python 3.9 or later, make and a C compiler (hev), curl,
# openssl, keytool, and Java 21 with the Android SDK for the Gradle build.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"

engine="${GHOSTLANE_ENGINE:?the program to check: GHOSTLANE_ENGINE}"
commit="${GHOSTLANE_CORE_COMMIT:?the pin the program was built from: GHOSTLANE_CORE_COMMIT}"
os="${DESKTOP_CHECK_OS:?linux, macos or windows: DESKTOP_CHECK_OS}"
work="${DESKTOP_CHECK_WORK:?a directory for this check alone: DESKTOP_CHECK_WORK}"
summary="${GITHUB_STEP_SUMMARY:-/dev/null}"
say() { echo "manager: $*"; }
die() { echo "manager: $*" >&2; echo "- manager: $*" >> "$summary"; exit 1; }

if [ "$os" != linux ]; then
  say "the manager's Linux tunnel is Linux's: nothing of it runs on $os"
  exit 0
fi
mkdir -p "$work"
work="$(cd "$work" && pwd)"

# ---- what it needs ------------------------------------------------------------
sudo -n true 2> /dev/null || die "no passwordless sudo: no namespace can be made"
for tool in ip make cc curl openssl keytool unshare; do
  command -v "$tool" > /dev/null || die "no $tool on this machine"
done
python3 -c 'import sys; sys.exit(sys.version_info < (3, 9))' 2> /dev/null || die "no Python 3.9 or later"
[ -x "$engine" ] || die "GHOSTLANE_ENGINE is no program: $engine"
# ---- the program says what it is ------------------------------------------------
"$engine" version > "$work/version.out" 2> "$work/version.log" || die "\`ghostlane-engine version\` failed: $(head -c 300 "$work/version.log")"
said="$(python3 -I - "$work/version.out" "$commit" 2>&1 <<'PY'
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
print("ghostlane-engine %s at %s (%s; BoringSSL %s)" % (
    v["engine"], v["pin"][:12], v["target"], "yes" if v["boringssl"] else "no"))
PY
)" || die "the program is not the pin's: $said"
say "$said"

# ---- hev, from the app's tree, as the Linux build makes it --------------------------
hev_tree="androidApp/src/main/jni/hev-socks5-tunnel"
hev_rev="$(git -C "$root" rev-parse "HEAD:$hev_tree" 2> /dev/null || echo unknown)"
rm -rf "$work/hev-src"
cp -a "$root/$hev_tree" "$work/hev-src"
make -C "$work/hev-src" -j"$(nproc)" exec REV_ID="${hev_rev:0:12}" > "$work/hev-build.log" 2>&1 ||
  die "hev did not build: $(tail -c 800 "$work/hev-build.log")"
hev="$work/hev-src/bin/hev-socks5-tunnel"
[ -x "$hev" ] || die "the build wrote no hev-socks5-tunnel"
say "hev-socks5-tunnel built from the app's tree ${hev_rev:0:12}"

# ---- Xray 26.4.25, the line's server, checked against the pinned sha256 ---------
xray_version=26.4.25
sum="$(sed -n "s/^$xray_version \([0-9a-f]\{64\}\)\$/\1/p" "$root/scripts/xray-servers.txt")"
[ -n "$sum" ] || die "scripts/xray-servers.txt pins no Xray $xray_version"
mkdir -p "$work/xray"
curl -fsSL --retry 3 -o "$work/xray/Xray-linux-64.zip" \
  "https://github.com/XTLS/Xray-core/releases/download/v$xray_version/Xray-linux-64.zip" ||
  die "Xray $xray_version could not be fetched"
xray="$(python3 -I - "$work/xray/Xray-linux-64.zip" "$sum" "$work/xray" 2>&1 <<'PY'
import hashlib, os, stat, sys, zipfile
archive, want, out = sys.argv[1:4]
got = hashlib.sha256(open(archive, "rb").read()).hexdigest()
if got != want:
    sys.exit("the archive hashes to %s, the pin is %s" % (got, want))
with zipfile.ZipFile(archive) as z:
    z.extract("xray", out)
path = os.path.join(out, "xray")
os.chmod(path, os.stat(path).st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
print(path)
PY
)" || die "Xray $xray_version: $xray"
xray_said="$("$xray" version | sed -n 1p)"
say "$xray_said"

# ---- the namespaces ---------------------------------------------------------------
client="gl-e2e-c-$$"
world="gl-e2e-w-$$"
as_root() { sudo -n "$@"; }
in_client() { sudo -n ip netns exec "$client" "$@"; }
in_world() { sudo -n ip netns exec "$world" "$@"; }
# The app's own directory for the test: hev keeps 63 bytes of a script's
# path (hev-config.c reads post-up-script and pre-down-script with
# strncpy(..., 64 - 1)), and one under the runner's work directory is longer.
app_data="$(mktemp -d /tmp/gl-e2e.XXXXXX)"
cleanup() {
  set +e
  local ns
  for ns in "$client" "$world"; do
    sudo -n ip netns pids "$ns" 2> /dev/null | xargs -r sudo -n kill -9 2> /dev/null
    sudo -n ip netns del "$ns" 2> /dev/null
    sudo -n rm -rf "/etc/netns/$ns"
  done
  sudo -n rm -rf "$app_data"
}
trap cleanup EXIT

as_root ip netns add "$client"
as_root ip netns add "$world"
as_root ip link add out0 netns "$client" type veth peer name w0 netns "$world"

in_client ip link set lo up
in_client ip addr add 10.231.0.2/24 dev out0
in_client ip -6 addr add fd00:231::2/64 dev out0 nodad
in_client ip link set out0 up
in_client ip route add default via 10.231.0.1 dev out0
in_client ip -6 route add default via fd00:231::1 dev out0
# Values the scripts must give back, not the zeroes they write.
in_client sysctl -qw net.ipv4.conf.all.rp_filter=2 net.ipv4.conf.default.rp_filter=1 net.ipv4.conf.out0.rp_filter=1

in_world ip link set lo up
in_world ip addr add 10.231.0.1/24 dev w0
in_world ip -6 addr add fd00:231::1/64 dev w0 nodad
in_world ip link set w0 up
for address in 10.232.0.30 10.232.0.40 10.232.0.41 10.232.0.53 1.1.1.1; do
  in_world ip addr add "$address/32" dev lo
done
in_world ip -6 addr add fd00:232::30/128 dev lo nodad

as_root mkdir -p "/etc/netns/$client" "/etc/netns/$world"
echo "nameserver 1.1.1.1" | as_root tee "/etc/netns/$client/resolv.conf" > /dev/null
echo "nameserver 10.232.0.53" | as_root tee "/etc/netns/$world/resolv.conf" > /dev/null

# ---- the far side ------------------------------------------------------------------
# Each service's log is world-<service>.log in the check's directory, made
# here, so that the test, the user, reads what root's services append.
logs="$work"
for service in dns origin trace target; do : > "$logs/world-$service.log"; done
: > "$work/xray-access.log"
: > "$work/xray-error.log"

cat > "$work/world.py" <<'PY'
# The far side but Xray: one service per process, each
# line it logs one fact, the asker's address second.
import socket, ssl, struct, sys, threading

MODE, LOG = sys.argv[1], sys.argv[2]
NAMES = {"line.test": "10.232.0.30"}
BODY = b"the world says hello\n"
lock = threading.Lock()


def log(*words):
    with lock, open(LOG, "a") as out:
        out.write(" ".join(str(word) for word in words) + "\n")


def exact(conn, n):
    data = b""
    while len(data) < n:
        more = conn.recv(n - len(data))
        if not more:
            raise EOFError
        data += more
    return data


def guarded(handle, conn, peer):
    try:
        handle(conn, peer)
    except (OSError, EOFError, ValueError, ssl.SSLError) as error:
        log("broken", peer[0], type(error).__name__)
    finally:
        conn.close()


def tcp(family, address, handle):
    server = socket.socket(family)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(address)
    server.listen(64)
    log("ready")
    while True:
        conn, peer = server.accept()
        threading.Thread(target=guarded, args=(handle, conn, peer), daemon=True).start()


def dns():
    # A for the names above; another type of theirs has no answer; every other
    # name does not exist. Asked on 10.232.0.53 and on 1.1.1.1.
    def serve(server):
        address = server.getsockname()[0]
        while True:
            query, peer = server.recvfrom(4096)
            if len(query) < 17:
                continue
            at, labels = 12, []
            while at < len(query) and query[at] != 0:
                size = query[at]
                labels.append(query[at + 1:at + 1 + size].decode("ascii", "replace"))
                at += 1 + size
            at += 1
            if at + 4 > len(query):
                continue
            kind = struct.unpack(">H", query[at:at + 2])[0]
            name = ".".join(labels).lower().rstrip(".")
            log("query", peer[0], name, kind, address)
            question = query[12:at + 4]
            answer, flags = b"", 0x8180
            if name not in NAMES:
                flags = 0x8183
            elif kind == 1:
                answer = b"\xc0\x0c" + struct.pack(">HHIH", 1, 1, 30, 4) + socket.inet_aton(NAMES[name])
            head = struct.pack(">HHHHHH", struct.unpack(">H", query[:2])[0], flags, 1, 1 if answer else 0, 0, 0)
            server.sendto(head + question + answer, peer)

    servers = []
    for address in ("10.232.0.53", "1.1.1.1"):
        server = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        server.bind((address, 53))
        servers.append(server)
    threads = [threading.Thread(target=serve, args=(server,), daemon=True) for server in servers]
    for thread in threads:
        thread.start()
    log("ready")
    for thread in threads:
        thread.join()


def origin(conn, peer):
    request = b""
    while b"\r\n\r\n" not in request:
        more = conn.recv(4096)
        if not more:
            return
        request += more
    line = request.split(b"\r\n", 1)[0].decode("ascii", "replace").split()
    log("request", peer[0], line[1] if len(line) > 1 else "-")
    conn.sendall(b"HTTP/1.1 200 OK\r\nContent-Length: %d\r\nConnection: close\r\n\r\n" % len(BODY) + BODY)


def target(cert, key):
    """What a REALITY server mirrors, as a site answers: TLS 1.3, h2 offered."""
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_3
    context.set_alpn_protocols(["h2", "http/1.1"])
    context.load_cert_chain(cert, key)

    def serve(conn, peer):
        conn.settimeout(15)
        with context.wrap_socket(conn, server_side=True) as tls:
            while tls.recv(4096):
                pass

    tcp(socket.AF_INET, ("10.232.0.41", 443), serve)


def trace(cert, key):
    """What 1.1.1.1/cdn-cgi/trace answers: the manager's own check of an exit."""
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(cert, key)

    def serve(conn, peer):
        conn.settimeout(15)
        with context.wrap_socket(conn, server_side=True) as tls:
            request = b""
            while b"\r\n\r\n" not in request:
                more = tls.recv(4096)
                if not more:
                    return
                request += more
            line = request.split(b"\r\n", 1)[0].decode("ascii", "replace").split()
            log("request", peer[0], line[1] if len(line) > 1 else "-")
            body = ("ip=%s\nloc=ZZ\n" % peer[0]).encode()
            tls.sendall(b"HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: %d\r\n"
                        b"Connection: close\r\n\r\n" % len(body) + body)

    return serve


if MODE == "dns":
    dns()
elif MODE == "target":
    target(sys.argv[3], sys.argv[4])
elif MODE == "origin":
    tcp(socket.AF_INET, ("10.232.0.30", 80), origin)
elif MODE == "trace":
    tcp(socket.AF_INET, ("1.1.1.1", 443), trace(sys.argv[5], sys.argv[6]))
else:
    sys.exit("no such service: " + MODE)
PY

openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj "/CN=reality.test" \
  -keyout "$work/target.key" -out "$work/target.crt" > /dev/null 2>&1 || die "openssl made no certificate"
# The exit check's server: a certificate for 1.1.1.1 of this run's own. Only the
# test's JVM trusts it (a store of its own below); nothing of the machine does.
openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj "/CN=1.1.1.1" \
  -addext "subjectAltName=IP:1.1.1.1,DNS:cloudflare.com" \
  -keyout "$work/trace.key" -out "$work/trace.crt" > /dev/null 2>&1 || die "openssl made no certificate for the exit check"
rm -f "$work/trust.p12"
keytool -importcert -noprompt -alias trace -file "$work/trace.crt" -keystore "$work/trust.p12" \
  -storetype PKCS12 -storepass changeit > "$work/keytool.log" 2>&1 || die "keytool made no trust store: $(tail -c 400 "$work/keytool.log")"
for service in dns origin trace target; do
  in_world python3 -I "$work/world.py" "$service" "$logs/world-$service.log" "$work/target.crt" "$work/target.key" "$work/trace.crt" "$work/trace.key" \
    2>> "$work/world-errors.log" &
done
for service in dns origin trace target; do
  for _ in $(seq 100); do
    grep -qx ready "$logs/world-$service.log" && break
    sleep 0.1
  done
  grep -qx ready "$logs/world-$service.log" || die "the far side's $service did not start: $(tail -c 600 "$work/world-errors.log" 2> /dev/null)"
done

# Xray, the line's server.
uuid="5a4e8c2d-6a1f-4c3b-9d7e-2f1b0c9a8e71"
keys="$("$xray" x25519)"
private="$(printf '%s\n' "$keys" | sed -n '/:/p' | sed -n '1s/^[^:]*:[[:space:]]*//p')"
public="$(printf '%s\n' "$keys" | sed -n '/:/p' | sed -n '2s/^[^:]*:[[:space:]]*//p')"
[ -n "$private" ] && [ -n "$public" ] || die "cannot read \`xray x25519\`: $keys"
python3 -I - "$work/xray-server.json" "$uuid" "$private" "$work/xray-access.log" "$work/xray-error.log" <<'PY'
import json, sys
path, uuid, private, access, error = sys.argv[1:6]
config = {
    "log": {"loglevel": "warning", "access": access, "error": error},
    "inbounds": [{
        "tag": "in", "listen": "10.232.0.40", "port": 443, "protocol": "vless",
        "settings": {"clients": [{"id": uuid, "flow": "xtls-rprx-vision"}], "decryption": "none"},
        "streamSettings": {"network": "raw", "security": "reality", "realitySettings": {
            "dest": "10.232.0.41:443", "serverNames": ["reality.test"], "privateKey": private,
            "shortIds": ["ab12cd34"]}},
    }],
    "outbounds": [{"tag": "direct", "protocol": "freedom", "settings": {
        "ipsBlocked": [], "finalRules": [{"action": "allow", "ip": ["10.232.0.0/24", "1.1.1.1/32"]}]}}],
}
with open(path, "w") as out:
    json.dump(config, out, indent=2)
PY
in_world "$xray" run -c "$work/xray-server.json" >> "$work/xray-error.log" 2>&1 &
for _ in $(seq 100); do
  in_world python3 -I -c 'import socket; socket.create_connection(("10.232.0.40", 443), 0.5).close()' 2> /dev/null && break
  sleep 0.2
done
in_world python3 -I -c 'import socket; socket.create_connection(("10.232.0.40", 443), 0.5).close()' 2> /dev/null ||
  die "Xray did not open its port: $(tail -c 600 "$work/xray-error.log")"

say "the far side is up: DNS, the origin, the exit check, Xray"

# ---- the scripts' resolvectl, written down instead of told to resolved -----------------
mkdir -p "$work/shim"
resolvectl_real="$(sudo -n sh -c 'command -v resolvectl' 2> /dev/null || true)"
resolvectl_log=""
if [ -n "$resolvectl_real" ]; then
  resolvectl_real="$(readlink -f "$resolvectl_real")"
  resolvectl_log="$work/resolvectl.log"
  : > "$resolvectl_log"
  cat > "$work/shim/resolvectl" <<SH
#!/bin/sh
printf '%s\n' "\$*" >> '$resolvectl_log'
SH
  chmod 0755 "$work/shim/resolvectl"
fi

: > "$work/outcomes.txt"
python3 -I - "$work/check.json" <<PY
import json, sys
json.dump({
    "engine": "$engine",
    "hev": "$hev",
    "link": "vless://$uuid@10.232.0.40:443?security=reality&encryption=none&pbk=$public&sid=ab12cd34&fp=chrome"
            "&sni=reality.test&flow=xtls-rprx-vision&type=tcp#manager",
    "app_data": "$app_data",
    "logs": "$logs",
    "xray_access": "$work/xray-access.log",
    "resolvectl": "$resolvectl_log",
    "outcomes": "$work/outcomes.txt",
}, open(sys.argv[1], "w"), indent=2)
PY

# ---- the test --------------------------------------------------------------------------
results="$root/sharedUI/build/test-results/jvmTest"
class=org.olcbox.app.vpn.desktop.engine.host.DesktopManagerRealTest
# Built and its dependencies resolved here, with the network: in the client
# namespace there is none but the far side. Every test is skipped by this run.
# No daemon is left holding Gradle's caches: a Gradle in another namespace
# cannot reach it over loopback to ask for them.
(cd "$root" && ./gradlew :sharedUI:jvmTest --no-daemon --tests "$class" --stacktrace) > "$work/gradle-warmup.log" 2>&1 ||
  die "the test did not build: $(tail -c 1500 "$work/gradle-warmup.log")"
(cd "$root" && ./gradlew --stop) > /dev/null 2>&1 || true
rm -f "$results/TEST-$class.xml"

# As root: a mount namespace with the stand-in over resolvectl, the client
# namespace, then the user again for Gradle and the test, whose own sudo -n
# runs hev and the scripts as the app's does.
cat > "$work/as-user.py" <<'PY'
import os, pwd, sys
uid, gid, cwd, argv = int(sys.argv[1]), int(sys.argv[2]), sys.argv[3], sys.argv[4:]
keep = {"LANG", "LC_ALL", "TZ", "TMPDIR", "CI", "GITHUB_ACTIONS", "RUNNER_TEMP", "RUNNER_OS", "RUNNER_ARCH",
        "MANAGER_CHECK", "HEV_SOCKS5_TUNNEL_BINARY", "GHOSTLANE_ENGINE_BINARY", "GHOSTLANE_CORE_COMMIT", "OLCBOX_LINUX_PRIVILEGE"}
secret = ("TOKEN", "SECRET", "PASSWORD", "KEY", "CREDENTIAL")
env = {k: v for k, v in os.environ.items()
       if (k in keep or k.startswith(("JAVA_", "ANDROID_", "GRADLE_"))) and not any(w in k.upper() for w in secret)}
user = pwd.getpwuid(uid)
env.update(HOME=os.environ["USER_HOME"], PATH=os.environ["USER_PATH"], USER=user.pw_name, LOGNAME=user.pw_name)
os.setgroups(os.getgrouplist(user.pw_name, gid))
os.setgid(gid)
os.setuid(uid)
os.chdir(cwd)
os.execvpe(argv[0], argv, env)
PY

set +e
sudo -n -E env USER_PATH="$PATH" USER_HOME="$HOME" \
  MANAGER_CHECK="$work/check.json" GHOSTLANE_ENGINE_BINARY="$engine" GHOSTLANE_CORE_COMMIT="$commit" \
  HEV_SOCKS5_TUNNEL_BINARY="$hev" \
  JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=$work/trust.p12 -Djavax.net.ssl.trustStorePassword=changeit -Djavax.net.ssl.trustStoreType=PKCS12" \
  OLCBOX_LINUX_PRIVILEGE=sudo \
  unshare --mount --propagation private -- \
  sh -c '[ -z "$1" ] || mount --bind "$2" "$1" || exit 1; shift 2; exec "$@"' _ "$resolvectl_real" "$work/shim/resolvectl" \
  ip netns exec "$client" python3 -I "$work/as-user.py" "$(id -u)" "$(id -g)" "$root" \
  ./gradlew :sharedUI:jvmTest --rerun --offline --no-daemon --tests "$class" --stacktrace
gradle=$?
set -e

source="$root/sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/desktop/engine/host/DesktopManagerRealTest.kt"
verdict="$(python3 -I - "$results/TEST-$class.xml" "$source" <<'PY'
import re, sys
import xml.etree.ElementTree as ET
xml, source = sys.argv[1], sys.argv[2]
written = len(re.findall(r"^\s*@Test\b", open(source, encoding="utf-8").read(), re.M))
try:
    suite = ET.parse(xml).getroot()
except (OSError, ET.ParseError) as e:
    sys.exit("no result of DesktopManagerRealTest (%s)" % e)
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
            message = (e.get("message") or "").splitlines()
            bad.append("%s: %s %s" % (case.get("name"), kind, message[0][:300] if message else ""))
print("; ".join(bad) if bad else "%d of %d tests passed" % (n["tests"], written))
sys.exit(1 if bad else 0)
PY
)" && held=0 || held=1

# What the test said, where it did not pass: its failures and the end of what it printed.
if [ "$held" -ne 0 ]; then
  python3 -I - "$results/TEST-$class.xml" <<'PY' || true
import sys
import xml.etree.ElementTree as ET
try:
    suite = ET.parse(sys.argv[1]).getroot()
except (OSError, ET.ParseError):
    sys.exit(0)
for case in suite.iter("testcase"):
    for kind in ("failure", "error"):
        for e in case.iter(kind):
            print("== %s: %s" % (case.get("name"), kind))
            print("\n".join((e.text or e.get("message") or "").splitlines()[:25]))
out = "".join(e.text or "" for e in suite.iter("system-out"))
print("== what the test printed, its end")
print("\n".join(out.splitlines()[-200:]))
PY
fi

# The runner's own namespace was not touched.
host_left=""
ip link show olcbox0 > /dev/null 2>&1 && host_left="$host_left olcbox0"
case "$(ip rule show; ip -6 rule show 2> /dev/null)" in
  *"lookup 51820"*) host_left="$host_left a rule of table 51820" ;;
esac

{
  echo "#### a session through DesktopVpnManager (E2E)"
  echo
  echo "- $said"
  echo "- hev-socks5-tunnel from the app's tree \`${hev_rev:0:12}\`; the line's server: $xray_said"
  echo "- DesktopManagerRealTest: $verdict"
  sed 's/^/- /' "$work/outcomes.txt"
} >> "$summary"
say "DesktopManagerRealTest: $verdict"
cat "$work/outcomes.txt"
[ -z "$host_left" ] || die "the runner's own namespace was changed:$host_left"
[ "$gradle" -eq 0 ] || die "Gradle failed ($gradle)"
[ "$held" -eq 0 ] || die "DesktopManagerRealTest did not pass whole: $verdict"
say "passed"
