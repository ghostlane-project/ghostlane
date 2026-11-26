#!/usr/bin/env bash
# desktop-check-os: linux
# desktop-check-paths: sharedUI/src/jvmMain/kotlin/org/olcbox/app/vpn/desktop/LinuxTunController.kt
# desktop-check-paths: sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/desktop/LinuxTunRealTest.kt
# desktop-check-paths: androidApp/src/main/jni/hev-socks5-tunnel/**
#
# The files above are what this check holds: a change to any of them starts
# Rust desktop (scripts/p6-tests/desktop-check-paths.sh holds its paths to
# them).
#
# K2 on ghostlane-engine: the Linux tunnel as the app makes it, in network
# namespaces, with the real hev built from the app's tree, the engine as the
# user and a room behind its front. LinuxTunController writes hev's file and
# the up and down scripts and runs them as root through `sudo -n`; the engine
# is the app's own child (LinuxTunRealTest, which this script runs and holds
# to: every test of it run, none skipped, none failed):
#   - a core line (VLESS REALITY Vision to Xray 26.4.25) behind the tun: TCP
#     by address and by name (hev's mapdns hands the name to the engine and the
#     line carries it), a datagram, IPv6 refused at once; the engine runs as
#     the user and its line leaves by the namespace's way out, bound to it;
#   - a room on the `none` carrier behind its front, in one engine process:
#     TCP and a datagram by the room, a name the front's rules send direct
#     leaving by the way out;
#   - kill -9 of the engine and of hev: nothing leaves outside the tunnel, and
#     the app's stop leaves no rule, route, device or changed rp_filter;
#   - the kill switch: after kill -9 of hev nothing leaves until the hold is
#     released, and then nothing is left.
#
# Two namespaces joined by a veth pair:
#   client  out0 10.231.0.2/24 and fd00:231::2/64, default routes by the world;
#           its resolver is 1.1.1.1, the one the tun names. The test (Gradle,
#           as the user) runs here.
#   world   w0 10.231.0.1/24 and fd00:231::1/64; on its loopback the far side:
#           a DNS server on 10.232.0.53 and 1.1.1.1 (hub.test, room.test,
#           line.test and direct.test), an HTTP origin on 10.232.0.30:80 and
#           [fd00:232::30]:80, a UDP echo on 10.232.0.30:7, the interop hub on
#           10.232.0.20:7000 with the olcRTC server in its room, Xray on
#           10.232.0.40:443 and its REALITY target on 10.232.0.41:443.
# Each service logs the address every request came from: what it saw from
# 10.231.0.2 or fd00:231::2 left the client outside the tunnel.
#
# resolved is not per namespace: the scripts' resolvectl is a stand-in bound
# over the real one in a mount namespace of the test's own, which writes down
# what it was asked. Nothing of the runner's own network, routes or resolver
# changes.
#
# Run by rust-desktop.yml on the Linux leg:
#   bash scripts/desktop-checks/linux-tun.sh
# with GHOSTLANE_ENGINE (the program), GHOSTLANE_CORE_COMMIT (the commit it
# must say it was built from), DESKTOP_CHECK_OS and DESKTOP_CHECK_WORK (an
# empty directory of its own). On another OS it says so and passes. Needs
# passwordless sudo, iproute2, Python 3.9 or later, make and a C compiler
# (hev), curl and openssl, git and Go (the olcRTC server, built at the fork's
# interop pin), and Java 21 with the Android SDK for the Gradle build.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"

engine="${GHOSTLANE_ENGINE:?the program to check: GHOSTLANE_ENGINE}"
commit="${GHOSTLANE_CORE_COMMIT:?the pin the program was built from: GHOSTLANE_CORE_COMMIT}"
os="${DESKTOP_CHECK_OS:?linux, macos or windows: DESKTOP_CHECK_OS}"
work="${DESKTOP_CHECK_WORK:?a directory for this check alone: DESKTOP_CHECK_WORK}"
summary="${GITHUB_STEP_SUMMARY:-/dev/null}"
say() { echo "linux-tun: $*"; }
die() { echo "linux-tun: $*" >&2; echo "- linux-tun: $*" >> "$summary"; exit 1; }

if [ "$os" != linux ]; then
  say "the Linux tunnel is Linux's: nothing of it runs on $os"
  exit 0
fi
mkdir -p "$work"
work="$(cd "$work" && pwd)"

# ---- what it needs ------------------------------------------------------------
sudo -n true 2> /dev/null || die "no passwordless sudo: no namespace can be made"
for tool in ip make cc curl openssl git unshare; do
  command -v "$tool" > /dev/null || die "no $tool on this machine"
done
python3 -c 'import sys; sys.exit(sys.version_info < (3, 9))' 2> /dev/null || die "no Python 3.9 or later"
[ -x "$engine" ] || die "GHOSTLANE_ENGINE is no program: $engine"
go="$(command -v go || true)"
if [ -z "$go" ]; then
  # The runner's newest Go when none is on the path.
  for goroot in $(env | sed -n 's/^GOROOT_[0-9_]*_X64=//p' | sort -V); do
    [ -x "$goroot/bin/go" ] && go="$goroot/bin/go"
  done
fi
[ -n "$go" ] || die "no Go on this machine: the olcRTC server is built with it"

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
if not v.get("rooms"):
    sys.exit("it was built without the rooms, and a room behind its front is one of the cases")
print("ghostlane-engine %s at %s (%s; rooms yes, BoringSSL %s)" % (
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

# ---- the room's far side: the olcRTC server at the fork's interop pin --------------
# The fork's olc/interop at the engine's commit names the olcRTC engine's pin and
# holds the overlay that gives it the interop pipe the `none` carrier's rooms run
# on; the test hooks let it reach the test's private addresses.
core_repo="$(bash -c '. "$1"; printf %s "$GHOSTLANE_CORE_REPO"' _ "$root/scripts/ghostlane-core-pin.sh")"
fork="$work/fork"
rm -rf "$fork"
git init -q "$fork"
git -C "$fork" remote add origin "https://github.com/$core_repo"
git -C "$fork" config core.sparseCheckout true
printf '/olc/interop/\n' > "$fork/.git/info/sparse-checkout"
git -C "$fork" fetch -q --depth 1 --filter=blob:none origin "$commit" || die "the fork's commit $commit could not be fetched"
git -C "$fork" -c advice.detachedHead=false checkout -q --detach FETCH_HEAD || die "the fork's olc/interop could not be checked out"
[ -f "$fork/olc/interop/lib.sh" ] || die "the fork at $commit has no olc/interop/lib.sh"
server="$work/olcrtc"
(
  set -e
  # shellcheck source=/dev/null
  . "$fork/olc/interop/lib.sh"
  olc_prepare "$work/olcrtc-src"
  cd "$work/olcrtc-src"
  GOTOOLCHAIN=auto GOFLAGS=-mod=readonly "$go" build -trimpath -buildvcs=false \
    -tags olcrtc_testhooks,"$OLC_GO_TAGS" -o "$server" ./cmd/olcrtc
  olc_guard "$work/olcrtc-src"
  echo "the olcRTC server at ${OLC_PIN:0:12}, $(GOTOOLCHAIN=auto "$go" version)"
) > "$work/olcrtc-build.log" 2>&1 || die "the olcRTC server did not build: $(tail -c 1200 "$work/olcrtc-build.log")"
[ -x "$server" ] || die "the build wrote no olcRTC server"
server_said="$(tail -n 1 "$work/olcrtc-build.log")"
say "$server_said"

# ---- the namespaces ---------------------------------------------------------------
client="gl-l2-c-$$"
world="gl-l2-w-$$"
as_root() { sudo -n "$@"; }
in_client() { sudo -n ip netns exec "$client" "$@"; }
in_world() { sudo -n ip netns exec "$world" "$@"; }
# The app's own directory for the test: hev keeps 63 bytes of a script's
# path (hev-config.c reads post-up-script and pre-down-script with
# strncpy(..., 64 - 1)), and one under the runner's work directory is longer.
app_data="$(mktemp -d /tmp/gl-l2.XXXXXX)"
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
for address in 10.232.0.20 10.232.0.30 10.232.0.40 10.232.0.41 10.232.0.53 1.1.1.1; do
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
for service in dns origin origin6 echo hub target; do : > "$logs/world-$service.log"; done
: > "$work/xray-access.log"
: > "$work/xray-error.log"
: > "$work/olcrtc-server.log"

cat > "$work/world.py" <<'PY'
# The far side but Xray and the olcRTC server: one service per process, each
# line it logs one fact, the asker's address second.
import queue, socket, ssl, struct, sys, threading

MODE, LOG = sys.argv[1], sys.argv[2]
NAMES = {"hub.test": "10.232.0.20", "room.test": "10.232.0.30", "line.test": "10.232.0.30",
         "direct.test": "10.232.0.30"}
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


def echo():
    server = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    server.bind(("10.232.0.30", 7))
    log("ready")
    while True:
        data, peer = server.recvfrom(65536)
        log("datagram", peer[0], len(data))
        server.sendto(data, peer)


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


# The interop pipe's hub: frames of `u32 BE length | kind | payload`. A
# participant's first frame is its JOIN (kind 1) naming its room; DATA (2) and
# DGRAM (3) go to every other participant of the room, unchanged; each join and
# leave tells every participant how many others the room holds (PEERS, 4, a
# u16 BE), as the fork's interop hub does.
rooms = {}


class Member:
    def __init__(self, conn):
        self.conn, self.out = conn, queue.Queue()

    def write(self):
        while True:
            frame = self.out.get()
            if frame is None:
                return
            try:
                self.conn.sendall(frame)
            except OSError:
                return


def frame(kind, payload):
    return struct.pack(">IB", 1 + len(payload), kind) + payload


def read_frame(conn):
    size = struct.unpack(">I", exact(conn, 4))[0]
    if size == 0 or size - 1 > 64 << 10:
        raise ValueError("frame of %d" % size)
    body = exact(conn, size)
    return body[0], body[1:]


def announce(room):
    with lock:
        members = list(rooms.get(room, ()))
    for member in members:
        member.out.put(frame(4, struct.pack(">H", max(len(members) - 1, 0))))


def hub(conn, peer):
    conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    kind, payload = read_frame(conn)
    if kind != 1 or not payload:
        return
    room = payload.decode("utf-8", "replace").split("@", 1)[0]
    member = Member(conn)
    with lock:
        rooms.setdefault(room, set()).add(member)
    threading.Thread(target=member.write, daemon=True).start()
    log("join", peer[0], room)
    announce(room)
    try:
        while True:
            kind, payload = read_frame(conn)
            if kind not in (2, 3):
                continue
            with lock:
                others = [other for other in rooms.get(room, ()) if other is not member]
            for other in others:
                other.out.put(frame(kind, payload))
    finally:
        with lock:
            rooms[room].discard(member)
        member.out.put(None)
        log("left", peer[0], room)
        announce(room)


if MODE == "dns":
    dns()
elif MODE == "echo":
    echo()
elif MODE == "target":
    target(sys.argv[3], sys.argv[4])
elif MODE == "origin":
    tcp(socket.AF_INET, ("10.232.0.30", 80), origin)
elif MODE == "origin6":
    tcp(socket.AF_INET6, ("fd00:232::30", 80), origin)
elif MODE == "hub":
    tcp(socket.AF_INET, ("10.232.0.20", 7000), hub)
else:
    sys.exit("no such service: " + MODE)
PY

openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj "/CN=reality.test" \
  -keyout "$work/target.key" -out "$work/target.crt" > /dev/null 2>&1 || die "openssl made no certificate"
for service in dns origin origin6 echo hub target; do
  in_world python3 -I "$work/world.py" "$service" "$logs/world-$service.log" "$work/target.crt" "$work/target.key" \
    2>> "$work/world-errors.log" &
done
for service in dns origin origin6 echo hub target; do
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
        "ipsBlocked": [], "finalRules": [{"action": "allow", "ip": ["10.232.0.0/24"]}]}}],
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

# The olcRTC server, in the hub's room.
key="4f6c635254432d74657374206b65792c206e6f7420612073656372657421212e"
room="l2-tun"
cat > "$work/olcrtc-server.yaml" <<YAML
mode: srv
auth: { provider: none }
engine: { name: interop-pipe, url: "tcp://10.232.0.20:7000/$room" }
crypto: { key: "$key" }
net: { transport: datachannel, dns: "10.232.0.53:53" }
udp: { enabled: true }
debug: true
YAML
chmod 0600 "$work/olcrtc-server.yaml"
(cd "$work" && exec sudo -n ip netns exec "$world" env OLCRTC_TEST_ALLOW_PRIVATE_TARGETS=1 OLCRTC_TEST_BRIDGE_DELAY=0s \
  "$server" "$work/olcrtc-server.yaml" >> "$work/olcrtc-server.log" 2>&1) &
for _ in $(seq 600); do
  grep -q "Link connected" "$work/olcrtc-server.log" && break
  sleep 0.1
done
grep -q "Link connected" "$work/olcrtc-server.log" ||
  die "the olcRTC server did not join its room within 60 s: $(tail -c 1200 "$work/olcrtc-server.log")"
say "the far side is up: DNS, the origin, the echo, Xray, the hub and the olcRTC server in its room"

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
            "&sni=reality.test&flow=xtls-rprx-vision&type=tcp#linux-tun",
    "room": {"provider": "none", "rooms": ["tcp://hub.test:7000/$room"], "key": "$key",
             "dns": "10.232.0.53:53", "udp": True},
    "app_data": "$app_data",
    "logs": "$logs",
    "xray_access": "$work/xray-access.log",
    "resolvectl": "$resolvectl_log",
    "outcomes": "$work/outcomes.txt",
}, open(sys.argv[1], "w"), indent=2)
PY

# ---- the test --------------------------------------------------------------------------
results="$root/sharedUI/build/test-results/jvmTest"
class=org.olcbox.app.vpn.desktop.LinuxTunRealTest
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
        "LINUX_TUN_CHECK", "GHOSTLANE_ENGINE_BINARY", "GHOSTLANE_CORE_COMMIT", "OLCBOX_LINUX_PRIVILEGE"}
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
  LINUX_TUN_CHECK="$work/check.json" GHOSTLANE_ENGINE_BINARY="$engine" GHOSTLANE_CORE_COMMIT="$commit" \
  OLCBOX_LINUX_PRIVILEGE=sudo \
  unshare --mount --propagation private -- \
  sh -c '[ -z "$1" ] || mount --bind "$2" "$1" || exit 1; shift 2; exec "$@"' _ "$resolvectl_real" "$work/shim/resolvectl" \
  ip netns exec "$client" python3 -I "$work/as-user.py" "$(id -u)" "$(id -g)" "$root" \
  ./gradlew :sharedUI:jvmTest --rerun --offline --no-daemon --tests "$class" --stacktrace
gradle=$?
set -e

source="$root/sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/desktop/LinuxTunRealTest.kt"
verdict="$(python3 -I - "$results/TEST-$class.xml" "$source" <<'PY'
import re, sys
import xml.etree.ElementTree as ET
xml, source = sys.argv[1], sys.argv[2]
written = len(re.findall(r"^\s*@Test\b", open(source, encoding="utf-8").read(), re.M))
try:
    suite = ET.parse(xml).getroot()
except (OSError, ET.ParseError) as e:
    sys.exit("no result of LinuxTunRealTest (%s)" % e)
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
  echo "#### the Linux tunnel on ghostlane-engine (K2)"
  echo
  echo "- $said"
  echo "- hev-socks5-tunnel from the app's tree \`${hev_rev:0:12}\`; the line's server: $xray_said"
  echo "- the room's far side: $server_said"
  echo "- LinuxTunRealTest: $verdict"
  sed 's/^/- /' "$work/outcomes.txt"
} >> "$summary"
say "LinuxTunRealTest: $verdict"
cat "$work/outcomes.txt"
[ -z "$host_left" ] || die "the runner's own namespace was changed:$host_left"
[ "$gradle" -eq 0 ] || die "Gradle failed ($gradle)"
[ "$held" -eq 0 ] || die "LinuxTunRealTest did not pass whole: $verdict"
say "passed"
