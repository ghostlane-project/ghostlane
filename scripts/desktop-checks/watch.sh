#!/usr/bin/env bash
# desktop-check-os: linux
#
# K2 for the physical interface watch (PhysicalInterfaceWatch): the default
# route of a machine moves from one network to another under a standing tun,
# and the line, the app's ghostlane-engine bound to the network that went
# away, is started again on the new one behind the same tun
# (PhysicalInterfaceWatchRealTest, which this script runs and holds to: every
# test of it run, none skipped, none failed):
#   - before the move a fetch through the tun is carried by the line, which
#     leaves by the first network;
#   - right after it nothing passes: the line is still bound to the network
#     that went away;
#   - the watch sees the move, told once, and the line is started again bound
#     to the second network; a fetch through the same tun (the same device
#     under the same hev process) is carried again, from the second network.
#
# Two namespaces joined by two veth pairs, one per network:
#   machine  neta0 10.241.1.2/24 (the first network) and netb0 10.241.2.2/24
#            (the second); the default route by 10.241.1.1 on neta0. hev,
#            built from the app's tree and run as root, holds the tun olcbox0;
#            table 51820 is its default route, and the rules send every
#            socket there but root's and those bound to an interface, as the
#            app's Linux tunnel does. The test (Gradle, as the user) runs here.
#   world    peera0 10.241.1.1/24 and peerb0 10.241.2.1/24; on its loopback
#            a VLESS server over plain TCP (10.242.0.10:443), the line's, and
#            an HTTP origin (10.242.0.30:80), each writing down the address
#            every connection came from.
# The move is the test's own, through sudo -n: neta0 goes down, and the
# default route leaves by 10.241.2.1 on netb0.
#
# Nothing of the runner's own network, routes or resolver changes.
#
# Run by rust-desktop.yml on the Linux leg:
#   bash scripts/desktop-checks/watch.sh
# with GHOSTLANE_ENGINE (the program), GHOSTLANE_CORE_COMMIT (the commit it
# must say it was built from), DESKTOP_CHECK_OS and DESKTOP_CHECK_WORK (an
# empty directory of its own). On another OS it says so and passes. Needs
# passwordless sudo, iproute2, Python 3.9 or later, make and a C compiler
# (hev), and Java 21 with the Android SDK for the Gradle build.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"

engine="${GHOSTLANE_ENGINE:?the program to check: GHOSTLANE_ENGINE}"
commit="${GHOSTLANE_CORE_COMMIT:?the pin the program was built from: GHOSTLANE_CORE_COMMIT}"
os="${DESKTOP_CHECK_OS:?linux, macos or windows: DESKTOP_CHECK_OS}"
work="${DESKTOP_CHECK_WORK:?a directory for this check alone: DESKTOP_CHECK_WORK}"
summary="${GITHUB_STEP_SUMMARY:-/dev/null}"
say() { echo "watch: $*"; }
die() { echo "watch: $*" >&2; echo "- watch: $*" >> "$summary"; exit 1; }

if [ "$os" != linux ]; then
  say "network namespaces are Linux's: the Mac's form of this check is another script's"
  exit 0
fi
mkdir -p "$work"
work="$(cd "$work" && pwd)"

# ---- what it needs ------------------------------------------------------------
sudo -n true 2> /dev/null || die "no passwordless sudo: no namespace can be made"
for tool in ip make cc; do
  command -v "$tool" > /dev/null || die "no $tool on this machine"
done
python3 -c 'import sys; sys.exit(sys.version_info < (3, 9))' 2> /dev/null || die "no Python 3.9 or later"
[ -x "$engine" ] || die "GHOSTLANE_ENGINE is no program: $engine"

# ---- the program says what it is ----------------------------------------------
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
print("ghostlane-engine %s at %s (%s)" % (v["engine"], v["pin"][:12], v["target"]))
PY
)" || die "the program is not the pin's: $said"
say "$said"

# ---- hev, from the app's tree --------------------------------------------------
hev_tree="androidApp/src/main/jni/hev-socks5-tunnel"
hev_rev="$(git -C "$root" rev-parse "HEAD:$hev_tree" 2> /dev/null || echo unknown)"
rm -rf "$work/hev-src"
cp -a "$root/$hev_tree" "$work/hev-src"
make -C "$work/hev-src" -j"$(nproc)" exec REV_ID="${hev_rev:0:12}" > "$work/hev-build.log" 2>&1 ||
  die "hev did not build: $(tail -c 800 "$work/hev-build.log")"
hev="$work/hev-src/bin/hev-socks5-tunnel"
[ -x "$hev" ] || die "the build wrote no hev-socks5-tunnel"
say "hev-socks5-tunnel built from the app's tree ${hev_rev:0:12}"

# ---- the namespaces --------------------------------------------------------------
machine="gl-l4-m-$$"
world="gl-l4-w-$$"
in_machine() { sudo -n ip netns exec "$machine" "$@"; }
in_world() { sudo -n ip netns exec "$world" "$@"; }
cleanup() {
  set +e
  local ns
  for ns in "$machine" "$world"; do
    sudo -n ip netns pids "$ns" 2> /dev/null | xargs -r sudo -n kill -9 2> /dev/null
    sudo -n ip netns del "$ns" 2> /dev/null
  done
}
trap cleanup EXIT

sudo -n ip netns add "$machine"
sudo -n ip netns add "$world"
sudo -n ip link add neta0 netns "$machine" type veth peer name peera0 netns "$world"
sudo -n ip link add netb0 netns "$machine" type veth peer name peerb0 netns "$world"

for ns in "$machine" "$world"; do
  sudo -n ip netns exec "$ns" sysctl -qw net.ipv6.conf.all.disable_ipv6=1 net.ipv6.conf.default.disable_ipv6=1
  sudo -n ip netns exec "$ns" sysctl -qw net.ipv4.conf.all.rp_filter=0 net.ipv4.conf.default.rp_filter=0
  sudo -n ip netns exec "$ns" ip link set lo up
done

in_machine ip addr add 10.241.1.2/24 dev neta0
in_machine ip addr add 10.241.2.2/24 dev netb0
in_machine ip link set neta0 up
in_machine ip link set netb0 up
in_machine ip route add default via 10.241.1.1 dev neta0

in_world ip addr add 10.241.1.1/24 dev peera0
in_world ip addr add 10.241.2.1/24 dev peerb0
in_world ip link set peera0 up
in_world ip link set peerb0 up
for address in 10.242.0.10 10.242.0.30; do
  in_world ip addr add "$address/32" dev lo
done

# ---- the far side ------------------------------------------------------------------
# Each service's log is made here, so that the test, the user, reads what
# root's services append.
for service in line origin; do : > "$work/world-$service.log"; done
cat > "$work/world.py" <<'PY'
# The far side: the line's VLESS server over plain TCP and the HTTP origin
# behind it. Each line a service logs is one fact, the asker's address second.
import socket, sys, threading

MODE, LOG = sys.argv[1], sys.argv[2]
USER = bytes(range(16))
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


def pump(src, dst):
    try:
        while True:
            data = src.recv(65536)
            if not data:
                break
            dst.sendall(data)
    except OSError:
        pass
    try:
        dst.shutdown(socket.SHUT_WR)
    except OSError:
        pass


def origin(conn, peer):
    request = b""
    while b"\r\n\r\n" not in request:
        more = conn.recv(4096)
        if not more:
            return
        request += more
    log("request", peer[0], request.split(b"\r\n", 1)[0].decode("ascii", "replace"))
    conn.sendall(b"HTTP/1.1 200 OK\r\nContent-Length: %d\r\nConnection: close\r\n\r\n" % len(BODY) + BODY)


def vless(conn, peer):
    head = exact(conn, 18)
    if head[0] != 0 or head[1:17] != USER:
        log("refused", peer[0])
        return
    exact(conn, head[17])
    command, port_hi, port_lo, kind = exact(conn, 4)
    if kind == 1:
        host = socket.inet_ntoa(exact(conn, 4))
    elif kind == 2:
        host = exact(conn, exact(conn, 1)[0]).decode("ascii", "replace")
    else:
        log("refused", peer[0], "address kind", kind)
        return
    port = port_hi * 256 + port_lo
    log("carried", peer[0], command, host, port)
    target = socket.create_connection((host, port), timeout=10)
    conn.sendall(b"\x00\x00")
    up = threading.Thread(target=pump, args=(conn, target), daemon=True)
    up.start()
    pump(target, conn)
    up.join(10)
    target.close()


def guarded(handle, conn, peer):
    try:
        handle(conn, peer)
    except (OSError, EOFError, ValueError) as error:
        log("broken", peer[0], type(error).__name__)
    finally:
        conn.close()


address, handle = {"line": (("10.242.0.10", 443), vless), "origin": (("10.242.0.30", 80), origin)}[MODE]
server = socket.socket()
server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
server.bind(address)
server.listen(64)
log("ready")
while True:
    conn, peer = server.accept()
    threading.Thread(target=guarded, args=(handle, conn, peer), daemon=True).start()
PY
for service in line origin; do
  in_world python3 -I "$work/world.py" "$service" "$work/world-$service.log" 2>> "$work/world-errors.log" &
done
for service in line origin; do
  for _ in $(seq 1 100); do
    grep -qx ready "$work/world-$service.log" && break
    sleep 0.1
  done
  grep -qx ready "$work/world-$service.log" || die "the world's $service did not start: $(tail -c 600 "$work/world-errors.log" 2> /dev/null)"
done

# ---- the standing tun ---------------------------------------------------------------
# hev's file is this check's own, the shape of the app's: the tun, and the
# line's port with a login. The port is the one the test's line listens on.
port=17080
username=watch-user
password="$(python3 -I -c 'import secrets; print(secrets.token_hex(12))')"
cat > "$work/hev.yml" <<YAML
tunnel:
  name: olcbox0
  mtu: 1500
  multi-queue: false
  ipv4: 10.0.88.88

socks5:
  address: 127.0.0.1
  port: $port
  username: '$username'
  password: '$password'

misc:
  log-file: stderr
  log-level: warn
YAML
chmod 0600 "$work/hev.yml"
in_machine "$hev" "$work/hev.yml" > "$work/hev.log" 2>&1 &
# `ip link show ... up` names the device only while it is up.
tun_up() { [ -n "$(in_machine ip link show dev olcbox0 up 2> /dev/null || true)" ]; }
for _ in $(seq 1 100); do
  tun_up && break
  sleep 0.1
done
tun_up || die "hev made no tun: $(tail -c 600 "$work/hev.log")"
hev_pid=""
for pid in $(sudo -n ip netns pids "$machine"); do
  if [ "$(cat "/proc/$pid/comm" 2> /dev/null)" = hev-socks5-tunn ]; then hev_pid="$pid"; fi
done
[ -n "$hev_pid" ] || die "hev is not running in the machine's namespace"
in_machine sysctl -qw net.ipv4.conf.olcbox0.rp_filter=0
in_machine ip route add default dev olcbox0 table 51820
in_machine ip rule add uidrange 0-0 lookup main pref 10
in_machine ip rule add lookup 51820 pref 20
tun_index="$(in_machine cat /sys/class/net/olcbox0/ifindex)"
say "hev holds olcbox0 (index $tun_index, process $hev_pid); the machine leaves by neta0"

# ---- the move, and what the test reads --------------------------------------------------
cat > "$work/move.sh" <<'SH'
#!/usr/bin/env bash
# The first network goes away, and the default route leaves by the second.
set -euo pipefail
ip link set neta0 down
ip route replace default via 10.241.2.1 dev netb0
SH
: > "$work/outcomes.txt"
python3 -I - "$work/check.json" <<PY
import json, sys
json.dump({
    "link": "vless://00010203-0405-0607-0809-0a0b0c0d0e0f@10.242.0.10:443?security=none&encryption=none&type=tcp#watch",
    "port": $port,
    "username": "$username",
    "password": "$password",
    "origin": "10.242.0.30",
    "origin_port": 80,
    "body": "the world says hello\n",
    "first": "neta0",
    "second": "netb0",
    "first_address": "10.241.1.2",
    "second_address": "10.241.2.2",
    "move": ["sudo", "-n", "bash", "$work/move.sh"],
    "line_log": "$work/world-line.log",
    "origin_log": "$work/world-origin.log",
    "tun": "olcbox0",
    "tun_index": $tun_index,
    "hev_pid": $hev_pid,
    "outcomes": "$work/outcomes.txt",
}, open(sys.argv[1], "w"), indent=2)
PY
chmod 0600 "$work/check.json"

# ---- the test ------------------------------------------------------------------------------
results="$root/sharedUI/build/test-results/jvmTest"
class=org.olcbox.app.vpn.desktop.PhysicalInterfaceWatchRealTest
# Built and its dependencies resolved here, with the network: in the
# machine's namespace there is none but the far side. Every test is skipped
# by this run. No daemon is left holding Gradle's caches: a Gradle in another
# namespace cannot reach it over loopback to ask for them.
(cd "$root" && ./gradlew :sharedUI:jvmTest --no-daemon --tests "$class" --stacktrace) > "$work/gradle-warmup.log" 2>&1 ||
  die "the test did not build: $(tail -c 1500 "$work/gradle-warmup.log")"
(cd "$root" && ./gradlew --stop) > /dev/null 2>&1 || true
rm -f "$results/TEST-$class.xml"

# As root into the machine's namespace, then the user again for Gradle and
# the test, whose own sudo -n makes the move.
cat > "$work/as-user.py" <<'PY'
import os, pwd, sys
uid, gid, cwd, argv = int(sys.argv[1]), int(sys.argv[2]), sys.argv[3], sys.argv[4:]
keep = {"LANG", "LC_ALL", "TZ", "TMPDIR", "CI", "GITHUB_ACTIONS", "RUNNER_TEMP", "RUNNER_OS", "RUNNER_ARCH",
        "INTERFACE_WATCH_CHECK", "GHOSTLANE_ENGINE_BINARY", "GHOSTLANE_CORE_COMMIT"}
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
  INTERFACE_WATCH_CHECK="$work/check.json" GHOSTLANE_ENGINE_BINARY="$engine" GHOSTLANE_CORE_COMMIT="$commit" \
  ip netns exec "$machine" python3 -I "$work/as-user.py" "$(id -u)" "$(id -g)" "$root" \
  ./gradlew :sharedUI:jvmTest --rerun --offline --no-daemon --tests "$class" --stacktrace
gradle=$?
set -e

source="$root/sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/desktop/PhysicalInterfaceWatchRealTest.kt"
verdict="$(python3 -I - "$results/TEST-$class.xml" "$source" <<'PY'
import re, sys
import xml.etree.ElementTree as ET
xml, source = sys.argv[1], sys.argv[2]
written = len(re.findall(r"^\s*@Test\b", open(source, encoding="utf-8").read(), re.M))
try:
    suite = ET.parse(xml).getroot()
except (OSError, ET.ParseError) as e:
    sys.exit("no result of PhysicalInterfaceWatchRealTest (%s)" % e)
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
print("\n".join(out.splitlines()[-120:]))
PY
  echo "== hev"; tail -n 20 "$work/hev.log" || true
  echo "== the line's server"; tail -n 20 "$work/world-line.log" || true
fi

# The runner's own namespace was not touched.
host_left=""
ip link show olcbox0 > /dev/null 2>&1 && host_left="$host_left olcbox0"
case "$(ip rule show)" in
  *"lookup 51820"*) host_left="$host_left a rule of table 51820" ;;
esac

{
  echo "#### the physical interface watch (K2)"
  echo
  echo "- $said"
  echo "- hev-socks5-tunnel from the app's tree \`${hev_rev:0:12}\`"
  echo "- PhysicalInterfaceWatchRealTest: $verdict"
  sed 's/^/- /' "$work/outcomes.txt"
} >> "$summary"
say "PhysicalInterfaceWatchRealTest: $verdict"
cat "$work/outcomes.txt"
[ -z "$host_left" ] || die "the runner's own namespace was changed:$host_left"
[ "$gradle" -eq 0 ] || die "Gradle failed ($gradle)"
[ "$held" -eq 0 ] || die "PhysicalInterfaceWatchRealTest did not pass whole: $verdict"
say "passed"
