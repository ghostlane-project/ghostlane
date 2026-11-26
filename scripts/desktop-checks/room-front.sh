#!/usr/bin/env bash
# desktop-check-os: linux
# desktop-check-paths: sharedUI/src/jvmTest/kotlin/org/olcbox/app/net/core/RoomFrontDumpTest.kt
# desktop-check-paths: sharedUI/src/commonMain/composeResources/files/xray/**
# desktop-check-paths: sharedUI/src/commonMain/kotlin/org/olcbox/app/data/model/RoutingSettings.kt
#
# A room behind its front on ghostlane-engine: the core half the app writes
# for a room (CoreRoomFront, through RoomFrontDumpTest, which this script runs
# and holds to: every test of it run, none skipped, none failed) in front of
# a room on the `none` carrier, in one engine process, in proxy mode. The
# engine runs as the user, in a network namespace whose way out is a veth
# into another, the world; the room's far side, the olcRTC server, is in the
# world, and what the room carries reaches the world from there:
#   - the room alone: its SOCKS port, with the room's login, carries a fetch
#     to the far side. Without it the cases below would prove nothing;
#   - "only blocked sites": a name on Re:filter's list goes by the room, and
#     is never asked of the DNS from the client; a name the list does not
#     hold, an address and a datagram go straight out;
#   - Bypass Russia with a tunnel rule under the whole-TLD entry `.ru`: the
#     tunnel name goes by the room, another name under `.ru` straight out
#     (asked of the DNS from the client), a name no rule claims and a
#     datagram by the room;
#   - LAN sharing beside it: from the world, a client with no login and one
#     with a wrong login are refused, and with the login a fetch and a
#     datagram go by the room.
# Each front is first held to `ghostlane-engine check-config`; each run ends
# with status 0 when it is told to stop.
#
# Two namespaces joined by a veth pair, no IPv6:
#   client  out0 10.231.0.2/24, `default via 10.231.0.1`; its resolver is the
#           world's DNS. The engine runs here, as the user.
#   world   w0 10.231.0.1/24; on its loopback the far side: a DNS server on
#           10.232.0.53 (hub.test is the hub, every other name is the
#           origin), the interop hub on 10.232.0.20:7000 with the olcRTC
#           server in its room, and an HTTP origin and a UDP echo on
#           203.0.113.30 (TEST-NET-3: no rule sends it direct as a local
#           network, and neither Re:filter nor the Russian lists hold it).
# Each service logs the address every request came from: 10.231.0.2 is the
# client straight out, any other address the far side.
#
# Run by rust-desktop.yml on the Linux leg:
#   bash scripts/desktop-checks/room-front.sh
# with GHOSTLANE_ENGINE (the program), GHOSTLANE_CORE_COMMIT (the commit it
# must say it was built from), DESKTOP_CHECK_OS and DESKTOP_CHECK_WORK (an
# empty directory of its own). On another OS it says so and passes. Needs
# passwordless sudo, iproute2, Python 3.9 or later, git and Go (the olcRTC
# server, built at the fork's interop pin), and Java 21 with the Android SDK
# for the Gradle build.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"

engine="${GHOSTLANE_ENGINE:?the program to check: GHOSTLANE_ENGINE}"
commit="${GHOSTLANE_CORE_COMMIT:?the pin the program was built from: GHOSTLANE_CORE_COMMIT}"
os="${DESKTOP_CHECK_OS:?linux, macos or windows: DESKTOP_CHECK_OS}"
work="${DESKTOP_CHECK_WORK:?a directory for this check alone: DESKTOP_CHECK_WORK}"
summary="${GITHUB_STEP_SUMMARY:-/dev/null}"
say() { echo "room-front: $*"; }
die() { echo "room-front: $*" >&2; echo "- room-front: $*" >> "$summary"; exit 1; }

if [ "$os" != linux ]; then
  say "the namespaces are Linux's: nothing of this check runs on $os"
  exit 0
fi
mkdir -p "$work"
work="$(cd "$work" && pwd)"

# ---- what it needs ------------------------------------------------------------
sudo -n true 2> /dev/null || die "no passwordless sudo: no namespace can be made"
for tool in ip git; do
  command -v "$tool" > /dev/null || die "no $tool on this machine"
done
python3 -c 'import sys; sys.exit(sys.version_info < (3, 9))' 2> /dev/null || die "no Python 3.9 or later"
[ -x "$engine" ] || die "GHOSTLANE_ENGINE is no program: $engine"
engine="$(cd "$(dirname "$engine")" && pwd)/$(basename "$engine")"
go="$(command -v go || true)"
if [ -z "$go" ]; then
  # The runner's newest Go when none is on the path.
  for goroot in $(env | sed -n 's/^GOROOT_[0-9_]*_X64=//p' | sort -V); do
    [ -x "$goroot/bin/go" ] && go="$goroot/bin/go"
  done
fi
[ -n "$go" ] || die "no Go on this machine: the olcRTC server is built with it"
lists="$root/sharedUI/src/commonMain/composeResources/files/xray"
[ -s "$lists/refilter.dat" ] || die "no refilter.dat among the app's lists"

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
    sys.exit("it was built without the rooms, and every case here is a room")
print("ghostlane-engine %s at %s (%s; rooms yes, BoringSSL %s)" % (
    v["engine"], v["pin"][:12], v["target"], "yes" if v["boringssl"] else "no"))
PY
)" || die "the program is not the pin's: $said"
say "$said"

# ---- the names and the address the cases ask for, against the app's lists ---------
# A name on Re:filter's list (under its first root domain), and the names and
# the address that must be on no list the cases name.
mkdir -p "$work/geodata"
cp "$lists/refilter.dat" "$work/geodata/refilter.dat"
chmod 0644 "$work/geodata/refilter.dat"
listed="$(python3 -I - "$work/geodata/refilter.dat" "$lists/geoip-ru.txt" "$lists/geosite-category-ru.txt" "$lists/geosite-tld-ru.txt" 2>&1 <<'PY'
import ipaddress, re, sys

refilter, geoip_ru, category_ru, tld_ru = sys.argv[1:5]
PLAIN, REGEX, ROOT_DOMAIN, FULL = 0, 1, 2, 3
ORIGIN = ipaddress.ip_address("203.0.113.30")
# Each name with the lists it must not be on.
UNLISTED = {"direct.room-front.test": ["refilter"], "free.room-front.test": ["refilter", "ru"],
            "www.via-room.ru": ["refilter"], "direct.room-front.ru": ["refilter"]}


def varint(data, i):
    value, shift = 0, 0
    while True:
        if i >= len(data) or shift > 63:
            sys.exit("a varint of refilter.dat runs past its end")
        byte = data[i]
        i += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, i
        shift += 7


def fields(data):
    i = 0
    while i < len(data):
        key, i = varint(data, i)
        wire = key & 7
        if wire == 0:
            value, i = varint(data, i)
        elif wire == 2:
            size, i = varint(data, i)
            if i + size > len(data):
                sys.exit("a field of refilter.dat runs past its message")
            value, i = data[i:i + size], i + size
        else:
            sys.exit("a field of refilter.dat of wire type %d" % wire)
        yield key >> 3, value


def entry(data, code):
    """The submessages of the entry <code>: Domain or CIDR, as the core reads them."""
    for field, item in fields(data):
        parts = list(fields(item)) if field == 1 and isinstance(item, bytes) else []
        if any(f == 1 and isinstance(v, bytes) and v.decode("utf-8", "replace").upper() == code for f, v in parts):
            return [dict(fields(v)) for f, v in parts if f == 2 and isinstance(v, bytes)]
    sys.exit("refilter.dat holds no entry %s" % code)


def holds(names, name):
    for kind, value in names:
        if kind == ROOT_DOMAIN and (name == value or name.endswith("." + value)):
            return True
        if (kind == FULL and name == value) or (kind == PLAIN and value in name):
            return True
        if kind == REGEX and re.search(value, name):
            return True
    return False


def text_names(path):
    """A list of the app's in Xray's inline spelling, as (kind, value)."""
    kinds = {"domain": ROOT_DOMAIN, "full": FULL, "keyword": PLAIN, "regexp": REGEX}
    found = []
    for line in open(path, encoding="utf-8").read().splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            kind, _, value = line.partition(":")
            found.append((kinds.get(kind, PLAIN), value if kind in kinds else line))
    return found


data = open(refilter, "rb").read()
names = [(d.get(1, PLAIN), d.get(2, b"").decode("utf-8", "replace")) for d in entry(data, "DOMAINS")]
cidrs = [ipaddress.ip_network("%s/%d" % (ipaddress.ip_address(d.get(1, b"")), d.get(2, 0)), strict=False)
         for d in entry(data, "IPS")]
roots = [value for kind, value in names if kind == ROOT_DOMAIN and re.fullmatch(r"[a-z0-9-]+(\.[a-z0-9-]+)+", value)]
if not roots:
    sys.exit("refilter.dat:domains holds no root domain to fetch a page under")
ru = text_names(category_ru) + text_names(tld_ru)
if not holds(ru, "direct.room-front.ru") or not holds(ru, "www.via-room.ru"):
    sys.exit("the Russian lists do not hold .ru whole: the tunnel rule would be under nothing")
for name, against in UNLISTED.items():
    if "refilter" in against and holds(names, name):
        sys.exit("refilter.dat:domains holds %s, a name the cases need off it" % name)
    if "ru" in against and holds(ru, name):
        sys.exit("the Russian lists hold %s, a name the cases need off them" % name)
if any(ORIGIN in cidr for cidr in cidrs if cidr.version == 4):
    sys.exit("refilter.dat:ips holds %s, the origin" % ORIGIN)
for line in open(geoip_ru, encoding="utf-8").read().splitlines():
    line = line.strip()
    if line and not line.startswith("#") and ":" not in line and ORIGIN in ipaddress.ip_network(line, strict=False):
        sys.exit("geoip-ru holds %s, the origin" % ORIGIN)
print(roots[0])
PY
)" || die "the lists do not let the cases mean what they say: $listed"
say "a name on Re:filter's list: $listed; the others are on none the cases name"

# ---- the room's far side: the olcRTC server at the fork's interop pin --------------
# The fork's olc/interop at the engine's commit names the olcRTC engine's pin and
# holds the overlay that gives it the interop pipe the `none` carrier's rooms run
# on; the test hooks let it reach the test's addresses.
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
client="gl-s10-c-$$"
world="gl-s10-w-$$"
as_root() { sudo -n "$@"; }
in_client() { sudo -n ip netns exec "$client" "$@"; }
in_world() { sudo -n ip netns exec "$world" "$@"; }
cleanup() {
  set +e
  local ns
  for ns in "$client" "$world"; do
    sudo -n ip netns pids "$ns" 2> /dev/null | xargs -r sudo -n kill -9 2> /dev/null
    sudo -n ip netns del "$ns" 2> /dev/null
    sudo -n rm -rf "/etc/netns/$ns"
  done
}
trap cleanup EXIT

as_root ip netns add "$client"
as_root ip netns add "$world"
as_root ip link add out0 netns "$client" type veth peer name w0 netns "$world"

in_client sysctl -qw net.ipv6.conf.all.disable_ipv6=1 net.ipv6.conf.default.disable_ipv6=1
in_client ip link set lo up
in_client ip addr add 10.231.0.2/24 dev out0
in_client ip link set out0 up
in_client ip route add default via 10.231.0.1 dev out0

in_world sysctl -qw net.ipv6.conf.all.disable_ipv6=1 net.ipv6.conf.default.disable_ipv6=1
in_world ip link set lo up
in_world ip addr add 10.231.0.1/24 dev w0
in_world ip link set w0 up
for address in 10.232.0.20 10.232.0.53 203.0.113.30; do
  in_world ip addr add "$address/32" dev lo
done

as_root mkdir -p "/etc/netns/$client" "/etc/netns/$world"
echo "nameserver 10.232.0.53" | as_root tee "/etc/netns/$client/resolv.conf" > /dev/null
echo "nameserver 10.232.0.53" | as_root tee "/etc/netns/$world/resolv.conf" > /dev/null

# ---- the far side ------------------------------------------------------------------
# Each service's log is world-<service>.log, made here, so that the user reads
# what root's services append.
services="dns origin echo hub"
for service in $services; do : > "$work/world-$service.log"; done
: > "$work/olcrtc-server.log"

cat > "$work/world.py" <<'PY'
# The far side but the olcRTC server: one service per process, each line it
# logs one fact, the asker's address second.
import queue, socket, struct, sys, threading

MODE, LOG = sys.argv[1], sys.argv[2]
HUB, ORIGIN = "10.232.0.20", "203.0.113.30"
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
    except (OSError, EOFError, ValueError) as error:
        log("broken", peer[0], type(error).__name__)
    finally:
        conn.close()


def tcp(address, handle):
    server = socket.socket()
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(address)
    server.listen(64)
    log("ready")
    while True:
        conn, peer = server.accept()
        threading.Thread(target=guarded, args=(handle, conn, peer), daemon=True).start()


def dns():
    # An A record for every name: the hub's for hub.test, the origin's for
    # every other. No other type has an answer.
    server = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    server.bind(("10.232.0.53", 53))
    log("ready")
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
        log("query", peer[0], name, kind)
        question = query[12:at + 4]
        answer = b""
        if kind == 1:
            address = HUB if name == "hub.test" else ORIGIN
            answer = b"\xc0\x0c" + struct.pack(">HHIH", 1, 1, 30, 4) + socket.inet_aton(address)
        head = struct.pack(">HHHHHH", struct.unpack(">H", query[:2])[0], 0x8180, 1, 1 if answer else 0, 0, 0)
        server.sendto(head + question + answer, peer)


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
    server.bind((ORIGIN, 7))
    log("ready")
    while True:
        data, peer = server.recvfrom(65536)
        log("datagram", peer[0], len(data))
        server.sendto(data, peer)


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
elif MODE == "origin":
    tcp((ORIGIN, 80), origin)
elif MODE == "hub":
    tcp((HUB, 7000), hub)
else:
    sys.exit("no such service: " + MODE)
PY

for service in $services; do
  in_world python3 -I "$work/world.py" "$service" "$work/world-$service.log" 2>> "$work/world-errors.log" &
done
for service in $services; do
  for _ in $(seq 100); do
    grep -qx ready "$work/world-$service.log" && break
    sleep 0.1
  done
  grep -qx ready "$work/world-$service.log" || die "the far side's $service did not start: $(tail -c 600 "$work/world-errors.log" 2> /dev/null)"
done

# The olcRTC server, in the hub's room. A server built with the test hooks
# reads OLCRTC_TEST_ALLOW_PRIVATE_TARGETS: the origin is a documentation
# address, which its egress policy refuses otherwise.
key="4f6c635254432d74657374206b65792c206e6f7420612073656372657421212e"
room="s10-front"
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
say "the far side is up: the DNS, the origin, the echo, the hub and the olcRTC server in its room"

# ---- the cases, run in the client namespace as root; the engine runs as the user ----
cat > "$work/drive.py" <<'PY'
# drive.py cases <engine> <work> <uid> <gid> <world> <stage>: the stage's
# cases, a line `ok: ...` or `FAILED: ...` each, and the facts into
# outcomes.txt. `drive.py lan <mode> <host> <port>` is a device on the LAN,
# run in the world namespace: one question, one JSON line.
import json, os, queue, socket, struct, subprocess, sys, threading, time

KEY = "4f6c635254432d74657374206b65792c206e6f7420612073656372657421212e"
OUT0 = "10.231.0.2"
ORIGIN = "203.0.113.30"
ROOM = "tcp://hub.test:7000/s10-front"
PROXY = ("127.0.0.1", 10808)
LAN = (OUT0, 10809)
ROOM_LOGIN = ("s10-room-user", "s10-room-pass")
LAN_LOGIN = (b"s10-lan-user", b"s10-lan-pass")
PAYLOAD = b"s10: a datagram through the front"


class Refused(Exception):
    pass


def exact(conn, n):
    data = b""
    while len(data) < n:
        more = conn.recv(n - len(data))
        if not more:
            raise EOFError("closed after %d bytes" % len(data))
        data += more
    return data


def greet(proxy, login, within):
    """A SOCKS5 connection to proxy, past its method and its login."""
    conn = socket.create_connection(proxy, timeout=within)
    conn.settimeout(within)
    try:
        method = b"\x00" if login is None else b"\x02"
        conn.sendall(b"\x05\x01" + method)
        chosen = exact(conn, 2)
        if chosen != b"\x05" + method:
            raise Refused("the proxy chose " + chosen.hex())
        if login is not None:
            user, password = login
            conn.sendall(b"\x01" + bytes([len(user)]) + user + bytes([len(password)]) + password)
            status = exact(conn, 2)
            if status != b"\x01\x00":
                raise Refused("the login was refused: " + status.hex())
        return conn
    except BaseException:
        conn.close()
        raise


def address(host):
    try:
        return b"\x01" + socket.inet_aton(host)
    except OSError:
        return b"\x03" + bytes([len(host)]) + host.encode()


def reply(conn):
    """A SOCKS5 reply: its code and the address it names."""
    head = exact(conn, 4)
    if head[3] == 1:
        host = socket.inet_ntoa(exact(conn, 4))
    elif head[3] == 3:
        host = exact(conn, exact(conn, 1)[0]).decode("ascii", "replace")
    elif head[3] == 4:
        host = socket.inet_ntop(socket.AF_INET6, exact(conn, 16))
    else:
        raise Refused("a reply of address kind %d" % head[3])
    return head[1], host, struct.unpack(">H", exact(conn, 2))[0]


def fetch(proxy, host, path, login=None, within=20):
    """GET path from host:80 through the SOCKS5 proxy: the response, or why not."""
    try:
        conn = greet(proxy, login, within)
        with conn:
            conn.sendall(b"\x05\x01\x00" + address(host) + struct.pack(">H", 80))
            code, _, _ = reply(conn)
            if code != 0:
                return "CONNECT answered %d" % code
            conn.sendall(b"GET %s HTTP/1.1\r\nHost: %s\r\nConnection: close\r\n\r\n" % (path.encode(), host.encode()))
            data = b""
            while True:
                more = conn.recv(65536)
                if not more:
                    return data
                data += more
    except (OSError, EOFError, Refused) as error:
        return "%s: %s" % (type(error).__name__, error)


def datagram(proxy, host, port, login=None, within=20):
    """PAYLOAD to host:port through the SOCKS5 proxy's UDP relay: what came back, or why nothing.

    The association names the port the datagrams come from: an inbound with
    a login pins its association to that port."""
    try:
        control = greet(proxy, login, within)
    except (OSError, EOFError, Refused) as error:
        return "%s: %s" % (type(error).__name__, error)
    udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        udp.bind(("0.0.0.0", 0))
        control.sendall(b"\x05\x03\x00\x01\x00\x00\x00\x00" + struct.pack(">H", udp.getsockname()[1]))
        code, relay, relay_port = reply(control)
        if code != 0:
            return "UDP ASSOCIATE answered %d" % code
        if relay in ("0.0.0.0", "::"):
            relay = proxy[0]
        udp.settimeout(3)
        head = b"\x00\x00\x00" + address(host) + struct.pack(">H", port)
        for _ in range(int(within / 3) + 1):
            udp.sendto(head + PAYLOAD, (relay, relay_port))
            try:
                data, _ = udp.recvfrom(65536)
            except socket.timeout:
                continue
            kind = data[3] if len(data) > 3 else 0
            skip = {1: 10, 4: 22}.get(kind, 7 + (data[4] if len(data) > 4 else 0))
            return data[skip:]
        return "no datagram came back"
    except (OSError, EOFError, Refused) as error:
        return "%s: %s" % (type(error).__name__, error)
    finally:
        udp.close()
        control.close()


def ok_page(got):
    return isinstance(got, bytes) and got.startswith(b"HTTP/1.1 200") and got.endswith(b"the world says hello\n")


def lan(mode, host, port):
    """One question of a device on the LAN, its answer as a dict."""
    proxy = (host, port)
    if mode == "no-login":
        conn = socket.create_connection(proxy, timeout=5)
        conn.settimeout(5)
        conn.sendall(b"\x05\x01\x00")
        try:
            chosen = exact(conn, 2).hex()
        except (OSError, EOFError):
            chosen = "closed"
        # The request anyway, as a client that does not wait for the answer.
        answered = False
        try:
            conn.sendall(b"\x05\x01\x00" + address(ORIGIN) + struct.pack(">H", 80))
            conn.sendall(b"GET /lan-no-login HTTP/1.1\r\nHost: origin\r\nConnection: close\r\n\r\n")
            conn.settimeout(3)
            rest = conn.recv(65536)
            answered = rest.startswith(b"\x05\x00")
        except (OSError, EOFError):
            pass
        conn.close()
        return {"chosen": chosen, "answered": answered}
    if mode == "wrong-login":
        try:
            greet(proxy, (LAN_LOGIN[0], b"not-the-pass"), 5).close()
            return {"refused": False}
        except (OSError, EOFError, Refused) as error:
            return {"refused": True, "why": str(error)}
    if mode == "fetch":
        got = fetch(proxy, ORIGIN, "/lan", LAN_LOGIN)
        return {"ok": ok_page(got), "got": repr(got)[:200]}
    if mode == "datagram":
        got = datagram(proxy, ORIGIN, 7, LAN_LOGIN)
        return {"echoed": got == PAYLOAD, "got": repr(got)[:200]}
    return {"unknown": mode}


if sys.argv[1] == "lan":
    print(json.dumps(lan(sys.argv[2], sys.argv[3], int(sys.argv[4]))), flush=True)
    sys.exit(0)

ENGINE, WORK, UID, GID, WORLD, STAGE = sys.argv[2], sys.argv[3], int(sys.argv[4]), int(sys.argv[5]), sys.argv[6], sys.argv[7]
GEODATA = os.path.join(WORK, "geodata")
FRONTS = os.path.join(WORK, "fronts")
LISTED = open(os.path.join(WORK, "listed.txt")).read().strip()
failed = []
runs = 0


def check(ok, what):
    print(("ok: " if ok else "FAILED: ") + what, flush=True)
    with open(os.path.join(WORK, "outcomes.txt"), "a") as out:
        out.write(("ok: " if ok else "FAILED: ") + what + "\n")
    if not ok:
        failed.append(what)
    return ok


def world(service):
    with open(os.path.join(WORK, "world-" + service + ".log")) as log:
        return [line.split() for line in log.read().splitlines() if line != "ready"]


def wait_for(what, within):
    deadline = time.monotonic() + within
    while time.monotonic() < deadline:
        found = what()
        if found:
            return found
        time.sleep(0.1)
    return what()


def session(name, body):
    path = os.path.join(WORK, "session-" + name + ".json")
    if os.path.exists(path):
        os.remove(path)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    os.write(fd, json.dumps(dict(version=1, **body)).encode())
    os.close(fd)
    os.chown(path, UID, GID)
    return path


def as_user(arguments, **options):
    return dict(user=UID, group=GID, extra_groups=[], env={"PATH": "/usr/bin:/bin", "RUST_BACKTRACE": "1"},
                cwd=WORK, **options)


class Engine:
    """The engine as an app runs it: stdin held open, one event a line."""

    def __init__(self, *arguments):
        global runs
        runs += 1
        self.name = "engine-%s-%d" % (STAGE, runs)
        self.stderr = open(os.path.join(WORK, self.name + ".log"), "wb")
        self.process = subprocess.Popen(
            [ENGINE, *arguments],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.stderr,
            **as_user(arguments))
        self.lines, self.events = queue.Queue(), []
        threading.Thread(target=self.read, daemon=True).start()

    def read(self):
        for line in self.process.stdout:
            try:
                self.lines.put(json.loads(line))
            except ValueError:
                self.lines.put({"event": "unreadable", "line": line.decode("utf-8", "replace")[:300]})
        self.lines.put(None)

    def until(self, wanted, within):
        deadline = time.monotonic() + within
        while True:
            try:
                event = self.lines.get(timeout=max(0.0, deadline - time.monotonic()))
            except queue.Empty:
                return None
            if event is None:
                self.lines.put(None)
                return None
            self.events.append(event)
            if wanted(event):
                return event

    def stop(self):
        """Closes stdin; the exit status and the events to the end."""
        try:
            self.process.stdin.close()
        except OSError:
            pass
        try:
            status = self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.process.kill()
            status = self.process.wait()
        self.until(lambda event: False, 5)
        self.stderr.close()
        return status

    def show(self):
        print("  %s printed:" % self.name)
        for event in self.events:
            line = json.dumps(event)
            print("    " + (line if len(line) < 600 else line[:600] + "..."))
        with open(os.path.join(WORK, self.name + ".log"), errors="replace") as err:
            tail = err.read()[-3000:]
        if tail.strip():
            print("  its stderr ends with:\n" + "\n".join("    " + line for line in tail.splitlines()))


def served(event):
    return event.get("event") in ("ready", "ended")


def inbound(ready, tag):
    for listener in (ready or {}).get("inbounds") or []:
        if listener.get("tag") == tag:
            host, _, port = listener["address"].rpartition(":")
            return host.strip("[]"), int(port)
    return None


def requests(path):
    """The addresses the origin was asked for `path` from."""
    return [entry[1] for entry in world("origin") if entry[0] == "request" and entry[2] == path]


def queries(name):
    return [entry[1] for entry in world("dns") if entry[0] == "query" and entry[2] == name]


def echoes_since(mark):
    return [entry[1] for entry in world("echo")[mark:] if entry[0] == "datagram"]


def by_the_room(path, what):
    """`path` reached the origin, and only from the far side."""
    seen = wait_for(lambda: requests(path), 5)
    return check(bool(seen) and OUT0 not in seen, "%s: the origin saw it from the far side (%s)" % (what, seen))


def straight_out(path, what):
    """`path` reached the origin from the client alone."""
    seen = wait_for(lambda: requests(path), 5)
    return check(seen == [OUT0], "%s: the origin saw it from the client alone (%s)" % (what, seen))


def fetched(got, what):
    return check(ok_page(got), "%s: the page came back (%s)" % (what, "200" if ok_page(got) else repr(got)[:300]))


def ended_well(engine, status, what):
    last = engine.events[-1] if engine.events else None
    ok = status == 0 and last is not None and last.get("event") == "ended" and last.get("reason") == "stdin_closed"
    return check(ok, "%s: told to stop, it ended with status 0 (%s, %s)" % (what, status, last))


def room_half(device):
    return {"provider": "none", "rooms": [ROOM], "key": KEY, "dns": "10.232.0.53:53", "udp": True,
            "device_id": device, "socks": {"port": 0, "user": ROOM_LOGIN[0], "password": ROOM_LOGIN[1]}}


def front_session(case, device):
    with open(os.path.join(FRONTS, case + ".json")) as f:
        core = json.load(f)
    return {"core": core, "room": room_half(device), "geodata_dir": GEODATA}


def checked(case, path):
    """`check-config` of a front's session: ok, and no finding is an error."""
    done = subprocess.run([ENGINE, "check-config", "--session", path], capture_output=True, timeout=60,
                          **as_user(None))
    events = []
    for line in done.stdout.decode("utf-8", "replace").splitlines():
        try:
            events.append(json.loads(line))
        except ValueError:
            pass
    verdict = next((event for event in events if event.get("event") == "check_config"), None)
    errors = [d for d in (verdict or {}).get("diagnostics") or [] if d.get("severity") == "error"]
    remarks = ["%s %s: %s" % (d.get("source"), d.get("path"), d.get("message")) for d in (verdict or {}).get("diagnostics") or []]
    return check(done.returncode == 0 and bool(verdict) and verdict.get("ok") is True and not errors,
                 "%s: check-config takes the front (%s; %s)" % (
                     case, done.returncode, "; ".join(remarks)[:600] if remarks else "no remark"))


def start(case, path):
    engine = Engine("run", "--session", path)
    ready = engine.until(served, 60)
    up = check(bool(ready) and ready.get("event") == "ready", "%s: ready with both halves" % case)
    own = inbound(ready, "in") if up else None
    check(own == PROXY, "%s: the proxy's inbound on %s:%d (%s)" % (case, PROXY[0], PROXY[1], own))
    check(any(event.get("event") == "session_opened" for event in engine.events), "%s: its room's session opened" % case)
    return engine, own


def from_lan(mode, listener):
    done = subprocess.run(
        ["ip", "netns", "exec", WORLD, "python3", "-I", os.path.join(WORK, "drive.py"), "lan", mode,
         listener[0], str(listener[1])],
        capture_output=True, text=True, timeout=60)
    try:
        return json.loads(done.stdout.strip().splitlines()[-1])
    except (ValueError, IndexError):
        return {"broken": done.stdout[-500:] + done.stderr[-1500:]}


def the_room_alone():
    """The far side, and the room's login: a room with no core half."""
    engine = Engine("run", "--session", session("room-alone", {"room": room_half("s10-alone")}))
    ready = engine.until(served, 60)
    up = check(bool(ready) and ready.get("event") == "ready", "the room alone: ready, the server answers in its room")
    socks = inbound(ready, "room") if up else None
    if socks:
        login = (ROOM_LOGIN[0].encode(), ROOM_LOGIN[1].encode())
        got = fetch(socks, ORIGIN, "/alone-no-login")
        check(not ok_page(got) and not requests("/alone-no-login"),
              "the room alone: its port refuses a client without the room's login (%s)" % (repr(got)[:120],))
        fetched(fetch(socks, ORIGIN, "/alone", login), "the room alone, with its login")
        by_the_room("/alone", "the room alone")
        joins = [entry[1] for entry in world("hub") if entry[0] == "join"]
        check(OUT0 in joins, "the room alone: it joined the hub from the client (%s)" % (joins,))
    status = engine.stop()
    ended_well(engine, status, "the room alone")
    return engine


def blocked_only():
    """Only blocked sites: Re:filter's names by the room, everything else straight out."""
    case = "blocked-only"
    path = session(case, front_session(case, "s10-blocked"))
    if not checked(case, path):
        return None
    engine, own = start(case, path)
    if own:
        fetched(fetch(own, LISTED, "/blocked-listed"), "%s: %s, on the list" % (case, LISTED))
        by_the_room("/blocked-listed", "%s: the listed name" % case)
        asked = queries(LISTED)
        check(bool(asked) and OUT0 not in asked,
              "%s: the listed name was asked by the far side, never from the client (%s)" % (case, asked))
        fetched(fetch(own, "direct.room-front.test", "/blocked-unlisted"), "%s: a name off the list" % case)
        straight_out("/blocked-unlisted", "%s: the name off the list" % case)
        asked = queries("direct.room-front.test")
        check(bool(asked) and set(asked) == {OUT0}, "%s: the name off the list was asked from the client (%s)" % (case, asked))
        fetched(fetch(own, ORIGIN, "/blocked-address"), "%s: an address off the list" % case)
        straight_out("/blocked-address", "%s: the address off the list" % case)
        mark = len(world("echo"))
        got = datagram(own, ORIGIN, 7)
        check(got == PAYLOAD, "%s: a datagram to an address off the list came back (%r)" % (case, got))
        seen = echoes_since(mark)
        check(bool(seen) and set(seen) == {OUT0}, "%s: the echo saw the datagram from the client alone (%s)" % (case, seen))
    status = engine.stop()
    ended_well(engine, status, case)
    return engine


def bypass_russia():
    """Bypass Russia with the tunnel rule via-room.ru, under `.ru` whole; LAN sharing beside it."""
    case = "bypass-ru"
    path = session(case, front_session(case, "s10-bypass"))
    if not checked(case, path):
        return None
    engine, own = start(case, path)
    ready = next((event for event in engine.events if event.get("event") == "ready"), None)
    lan_in = inbound(ready, "lan-in")
    check(lan_in == LAN, "%s: the LAN inbound on %s:%d (%s)" % (case, LAN[0], LAN[1], lan_in))
    if own:
        fetched(fetch(own, "www.via-room.ru", "/bypass-tunnel-rule"), "%s: the tunnel rule's name" % case)
        by_the_room("/bypass-tunnel-rule", "%s: a tunnel name under .ru" % case)
        asked = queries("www.via-room.ru")
        check(OUT0 not in asked, "%s: the tunnel name was never asked from the client (%s)" % (case, asked))
        fetched(fetch(own, "direct.room-front.ru", "/bypass-ru"), "%s: another name under .ru" % case)
        straight_out("/bypass-ru", "%s: another name under .ru" % case)
        asked = queries("direct.room-front.ru")
        check(bool(asked) and set(asked) == {OUT0}, "%s: the name under .ru was asked from the client (%s)" % (case, asked))
        fetched(fetch(own, "free.room-front.test", "/bypass-unclaimed"), "%s: a name no rule claims" % case)
        by_the_room("/bypass-unclaimed", "%s: a name no rule claims" % case)
        mark = len(world("echo"))
        got = datagram(own, ORIGIN, 7)
        check(got == PAYLOAD, "%s: a datagram came back (%r)" % (case, got))
        seen = echoes_since(mark)
        check(bool(seen) and OUT0 not in seen, "%s: the echo saw the datagram from the far side (%s)" % (case, seen))
    if lan_in:
        answer = from_lan("no-login", lan_in)
        check(answer.get("chosen") in ("05ff", "closed") and answer.get("answered") is False,
              "%s, LAN: a client with no login is refused (%s)" % (case, answer))
        answer = from_lan("wrong-login", lan_in)
        check(answer.get("refused") is True, "%s, LAN: a wrong login is refused (%s)" % (case, answer))
        answer = from_lan("fetch", lan_in)
        check(answer.get("ok") is True, "%s, LAN: with the login, a fetch (%s)" % (case, answer))
        by_the_room("/lan", "%s, LAN: the fetch" % case)
        mark = len(world("echo"))
        answer = from_lan("datagram", lan_in)
        check(answer.get("echoed") is True, "%s, LAN: with the login, a datagram came back (%s)" % (case, answer))
        seen = echoes_since(mark)
        check(bool(seen) and OUT0 not in seen, "%s, LAN: the echo saw the datagram from the far side (%s)" % (case, seen))
        check(not requests("/lan-no-login"), "%s, LAN: the request with no login reached nothing" % case)
    status = engine.stop()
    ended_well(engine, status, case)
    return engine


stages = {"alone": [the_room_alone], "fronts": [blocked_only, bypass_russia]}[STAGE]
for stage in stages:
    mark = len(failed)
    engine = stage()
    if engine is not None and len(failed) > mark:
        engine.show()
print("%d failed" % len(failed))
sys.exit(1 if failed else 0)
PY
printf '%s\n' "$listed" > "$work/listed.txt"
: > "$work/outcomes.txt"

show_the_world() {
  for service in $services; do
    echo "-- the world's $service"
    tail -n 60 "$work/world-$service.log"
  done
  cat "$work/world-errors.log" 2> /dev/null || true
  echo "-- the olcRTC server's log, its end"
  tail -n 120 "$work/olcrtc-server.log"
}

drive() {
  in_client python3 -I "$work/drive.py" cases "$engine" "$work" "$(id -u)" "$(id -g)" "$world" "$1"
}

# 1. The far side, before anything of the app's: a room alone.
drive alone > "$work/alone.out" 2>&1 || {
  cat "$work/alone.out"
  show_the_world
  die "the room alone did not reach its far side: nothing below would prove anything"
}
cat "$work/alone.out"

# 2. The fronts, as the app writes them: RoomFrontDumpTest, as the app's build
# runs its tests. --rerun: what it reads is no input Gradle knows of, and a
# result from the build cache wrote nothing.
mkdir -p "$work/fronts"
python3 -I - "$work/request.json" "$work/fronts" <<'PY'
import json, sys
path, out = sys.argv[1], sys.argv[2]
request = {
    "out": out,
    "resolver": "10.232.0.53",
    "room_login": {"user": "s10-room-user", "pass": "s10-room-pass"},
    "cases": [
        {"name": "blocked-only", "mode": "BlockedOnly", "port": 10808},
        {"name": "bypass-ru", "mode": "BypassRussia", "tunnel": ["via-room.ru"], "port": 10808,
         "lan": {"address": "10.231.0.2", "port": 10809, "user": "s10-lan-user", "pass": "s10-lan-pass"}},
    ],
}
with open(path, "w") as f:
    json.dump(request, f, indent=2)
PY
results="$root/sharedUI/build/test-results/jvmTest"
class=org.olcbox.app.net.core.RoomFrontDumpTest
rm -f "$results/TEST-$class.xml"
set +e
(
  cd "$root"
  ROOM_FRONT_CHECK="$work/request.json" ./gradlew :sharedUI:jvmTest --rerun --tests "$class" --stacktrace
) > "$work/gradle.log" 2>&1
gradle=$?
set -e
tail -n 40 "$work/gradle.log"

source="$root/sharedUI/src/jvmTest/kotlin/org/olcbox/app/net/core/RoomFrontDumpTest.kt"
verdict="$(python3 -I - "$results/TEST-$class.xml" "$source" <<'PY'
import re, sys
import xml.etree.ElementTree as ET
xml, source = sys.argv[1], sys.argv[2]
written = len(re.findall(r"^\s*@Test\b", open(source, encoding="utf-8").read(), re.M))
try:
    suite = ET.parse(xml).getroot()
except (OSError, ET.ParseError) as e:
    sys.exit("no result of RoomFrontDumpTest (%s)" % e)
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
            bad.append("%s: %s %s" % (case.get("name"), kind, message[0][:200] if message else ""))
print("; ".join(bad) if bad else "%d of %d tests passed" % (n["tests"], written))
sys.exit(1 if bad else 0)
PY
)" && held=0 || held=1
say "RoomFrontDumpTest: $verdict"

fronts_ran=0
fronts=1
if [ "$gradle" -eq 0 ] && [ "$held" -eq 0 ]; then
  for case in blocked-only bypass-ru; do
    [ -s "$work/fronts/$case.json" ] || die "RoomFrontDumpTest wrote no $case.json"
  done
  # 3. The fronts in front of the room.
  fronts_ran=1
  drive fronts > "$work/fronts.out" 2>&1 && fronts=0 || fronts=$?
  cat "$work/fronts.out"
  [ "$fronts" -eq 0 ] || show_the_world
fi

{
  echo "#### a room behind its front on ghostlane-engine ($os)"
  echo
  echo "- $said"
  echo "- the far side: $server_said"
  echo "- RoomFrontDumpTest: $verdict"
  sed 's/^/- /' "$work/outcomes.txt"
} >> "$summary"
[ "$gradle" -eq 0 ] || die "Gradle failed ($gradle): $(grep -m 5 -E 'FAILED|error:|e: ' "$work/gradle.log" | head -c 1200)"
[ "$held" -eq 0 ] || die "RoomFrontDumpTest did not pass whole: $verdict"
[ "$fronts_ran" -eq 1 ] || die "the fronts did not run"
[ "$fronts" -eq 0 ] || die "a front did not do what its rules say: $(grep -c '^FAILED' "$work/fronts.out") cases failed"
say "passed"
