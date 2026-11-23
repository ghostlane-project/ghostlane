#!/usr/bin/env bash
# The iPhone's pin of a server named by a hostname (iosApp/PacketTunnel/HostPin.swift):
#   - HostPin and DirectResolver, compiled with their test (iosApp/Tests/HostPinTests.swift)
#     by xcrun on a Mac, by any swift.org toolchain's swiftc elsewhere (SWIFTC=/path/to/swiftc);
#   - run against a stub resolver on loopback (python3), which answers past a CNAME with
#     its names compressed, sends a reply to no question first, has a name with only AAAA,
#     one it does not know, one it drops the first question for and one it never answers;
#   - the address the app writes for the server (IosCore.HOST_PIN_PLACEHOLDER) is the one
#     the extension replaces (HostPin.placeholder), and not the direct resolver's.
#
#   scripts/p6-tests/ios-host-pin.sh [root]     root defaults to the repository
# Needs bash and python3. With no Swift toolchain it fails on CI and skips elsewhere.
set -euo pipefail
root="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
tmp="$(mktemp -d)"
stub_pid=""
cleanup() {
  if [ -n "$stub_pid" ]; then kill "$stub_pid" 2>/dev/null || true; fi
  rm -rf "$tmp"
}
trap cleanup EXIT
fail=0
bad() { echo "FAIL $*"; fail=1; }

# The two halves of the hand-over name one placeholder.
kotlin="$(sed -n 's/.*const val HOST_PIN_PLACEHOLDER = "\([^"]*\)".*/\1/p' "$root/sharedUI/src/commonMain/kotlin/org/olcbox/app/net/IosCore.kt")"
swift="$(sed -n 's/.*static let placeholder = "\([^"]*\)".*/\1/p' "$root/iosApp/PacketTunnel/HostPin.swift")"
direct="$(sed -n 's/.*static let placeholder = "\([^"]*\)".*/\1/p' "$root/iosApp/PacketTunnel/DirectResolver.swift")"
if [ -z "$kotlin" ] || [ "$kotlin" != "$swift" ]; then
  bad "IosCore.HOST_PIN_PLACEHOLDER (${kotlin:-none}) and HostPin.placeholder (${swift:-none}) differ"
fi
if [ -z "$swift" ] || [ "$swift" = "$direct" ]; then
  bad "HostPin.placeholder (${swift:-none}) must be an address of its own, not the direct resolver's (${direct:-none})"
fi

swiftc="${SWIFTC:-}"
if [ -z "$swiftc" ]; then
  if command -v xcrun >/dev/null 2>&1; then
    swiftc="xcrun swiftc"
  elif command -v swiftc >/dev/null 2>&1; then
    swiftc="swiftc"
  elif [ -x /usr/share/swift/usr/bin/swiftc ]; then
    swiftc=/usr/share/swift/usr/bin/swiftc
  fi
fi
if [ -z "$swiftc" ]; then
  if [ -n "${CI:-}" ]; then
    bad "no Swift toolchain on this runner"
    exit 1
  fi
  echo "SKIP HostPin's Swift test: no Swift toolchain here (SWIFTC=/path/to/swiftc runs it)"
  exit "$fail"
fi

cat > "$tmp/stub.py" <<'PY'
import socket
import struct
import sys

A, AAAA, CNAME = 1, 28, 5


def encode(name):
    return b"".join(bytes([len(label)]) + label.encode() for label in name.split(".")) + b"\x00"


def record(name, rtype, data):
    return name + struct.pack("!HHIH", rtype, 1, 60, len(data)) + data


def v4(text):
    return socket.inet_pton(socket.AF_INET, text)


def v6(text):
    return socket.inet_pton(socket.AF_INET6, text)


sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind(("127.0.0.1", 0))
print(sock.getsockname()[1], flush=True)
dropped = set()
while True:
    query, peer = sock.recvfrom(512)
    if len(query) < 17:
        continue
    at, labels = 12, []
    while at < len(query) and query[at]:
        n = query[at]
        labels.append(query[at + 1:at + 1 + n].decode("ascii", "replace").lower())
        at += 1 + n
    at += 1
    if at + 4 > len(query):
        continue
    (qtype,) = struct.unpack("!H", query[at:at + 2])
    question = query[12:at + 4]
    name = ".".join(labels)

    def reply(records, rcode=0, ident=query[:2]):
        header = ident + struct.pack("!BBHHHH", 0x81, 0x80 | rcode, 1, len(records), 0, 0)
        sock.sendto(header + question + b"".join(records), peer)

    here = b"\xc0\x0c"
    if name == "silent.example.com":
        continue
    if name == "late.example.com" and (name, qtype) not in dropped:
        dropped.add((name, qtype))
        continue
    if name == "edge.example.com":
        # A reply to no question of the client's first, then the answer: a
        # CNAME, and the target's address under a pointer to the CNAME's data.
        reply([record(here, A, v4("198.18.0.1"))], ident=bytes([query[0], query[1] ^ 1]))
        target_at = 12 + len(question) + 12
        target = bytes([0xC0 | (target_at >> 8), target_at & 0xFF])
        address = record(target, A, v4("203.0.113.7")) if qtype == A else record(target, AAAA, v6("2001:db8::7"))
        reply([record(here, CNAME, encode("cdn.example.net")), address])
    elif name == "v6only.example.com":
        reply([record(here, AAAA, v6("2001:db8::6"))] if qtype == AAAA else [])
    elif name == "late.example.com":
        reply([record(here, A, v4("203.0.113.9"))] if qtype == A else [])
    else:
        reply([], rcode=3)
PY

# shellcheck disable=SC2086 # "xcrun swiftc" is two words
if ! $swiftc -parse-as-library \
  "$root/iosApp/PacketTunnel/HostPin.swift" \
  "$root/iosApp/PacketTunnel/DirectResolver.swift" \
  "$root/iosApp/Tests/HostPinTests.swift" \
  -o "$tmp/host-pin-tests"; then
  bad "HostPin and its test do not compile"
  exit 1
fi

exec 3< <(python3 -I "$tmp/stub.py")
stub_pid=$!
port=""
read -r -t 10 port <&3 || true
if ! [[ "$port" =~ ^[0-9]+$ ]]; then
  bad "the stub resolver did not start"
  exit 1
fi
if ! "$tmp/host-pin-tests" "$port"; then
  bad "HostPin's test"
fi
[ "$fail" -eq 0 ] && echo "ok: HostPin pins a hostname server through a stub resolver"
exit "$fail"
