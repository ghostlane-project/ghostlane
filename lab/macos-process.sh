#!/bin/bash
# Why a tunnel that lets a line's binary out by its program sometimes does not:
# what sing-box says when it cannot name the program behind a connection, and
# whether the kernel's list of sockets is laid out the way sing-box reads it on
# this Mac. No block is loaded here.
#
#   sudo bash lab/macos-process.sh /path/to/sing-box <the unprivileged user>
SB="$1"
WHO="$2"
WHO_UID="$(id -u "$WHO")"
WHO_GID="$(id -g "$WHO")"
work="$(cd /tmp && pwd -P)/ghostlane-process.$$"
mkdir -p "$work/line" "$work/tun"
chmod 755 "$work"

say() { printf '\n=== %s\n' "$*"; }
within() { local s="$1"; shift; perl -e 'alarm shift; exec @ARGV' "$s" "$@"; }
as_user() { python3 -c 'import os,sys; os.setgid(int(sys.argv[2])); os.setuid(int(sys.argv[1])); os.execvp(sys.argv[3], sys.argv[3:])' "$WHO_UID" "$WHO_GID" "$@"; }
fetch() {
  local url="$1" out; shift
  out=$(as_user curl -4 -s -o /dev/null -w '%{http_code} in %{time_total}s' --max-time 5 "$@" "$url" 2>/dev/null)
  printf '%s' "${out:-nothing}"
}

say "the machine"
sw_vers | tr '\n' ' '; echo; uname -rm
IF=$(route -n get default | awk '/interface:/{print $2}')
SITE=$(dig +short +time=3 +tries=2 example.com A | grep -E '^[0-9.]+$' | head -1)
echo "way out: $IF; example.com is $SITE"

say "the kernel's list of sockets, record by record, against the sizes sing-box steps by"
python3 - <<'PY'
import ctypes, struct, collections
libc = ctypes.CDLL(None, use_errno=True)
def raw(name):
    size = ctypes.c_size_t(0)
    libc.sysctlbyname(name.encode(), None, ctypes.byref(size), None, 0)
    buf = ctypes.create_string_buffer(size.value + (1 << 16))
    size = ctypes.c_size_t(len(buf))
    if libc.sysctlbyname(name.encode(), buf, ctypes.byref(size), None, 0) != 0:
        raise OSError(ctypes.get_errno())
    return buf.raw[:size.value]
KINDS = {0x001: "socket", 0x002: "rcvbuf", 0x004: "sndbuf", 0x008: "stats", 0x010: "inpcb", 0x020: "tcpcb",
         0x040: "kcreg", 0x080: "kcb", 0x100: "evt", 0x200: "unpcb"}
rup8 = lambda n: (n + 7) & ~7
for name, assumed in (("net.inet.tcp.pcblist_n", 408 + 208), ("net.inet.udp.pcblist_n", 408)):
    b = raw(name)
    head = struct.unpack_from("<I", b, 0)[0]
    off, items, shape, starts = head, collections.Counter(), [], []
    while off + 8 <= len(b):
        length, kind = struct.unpack_from("<II", b, off)
        if kind not in KINDS or length < 8:
            break
        if kind == 0x010:
            if shape:
                items[tuple(shape)] += 1
            shape = []
            starts.append(off)
        shape.append((KINDS[kind], length))
        off += rup8(length)
    if shape:
        items[tuple(shape)] += 1
    print(f"   {name}: {len(b)} bytes, a head of {head}, {len(starts)} sockets, stepped by sing-box at {assumed}")
    for shape, count in items.most_common(4):
        print(f"      {count} sockets of {sum(rup8(l) for _, l in shape)} bytes:", ", ".join(f"{k} {l}" for k, l in shape))
    aligned = sum(1 for i, start in enumerate(starts) if start == head + i * assumed)
    print(f"      where sing-box looks for a socket, {aligned} of {len(starts)} begin")
PY

cp "$SB" "$work/line/sing-box"; cp "$SB" "$work/tun/sing-box"
chown -R "$WHO" "$work/line"
cat > "$work/line/line.json" <<JSON
{"log":{"level":"warn"},
 "inbounds":[{"type":"socks","tag":"in","listen":"127.0.0.1","listen_port":18821}],
 "outbounds":[{"type":"direct","tag":"direct"}]}
JSON
line_up() {
  as_user "$work/line/sing-box" run -c "$work/line/line.json" >>"$work/line/log" 2>&1 &
  LINE=$!
  for _ in $(seq 1 20); do nc -z 127.0.0.1 18821 2>/dev/null && break; sleep 0.1; done
}
tun_up() {
  cat > "$work/tun/$1.json" <<JSON
{"log":{"level":"info","timestamp":true},
 "inbounds":[{"type":"tun","tag":"tun-in","interface_name":"$1","address":["172.19.0.1/30","fdfe:dcba:9876::1/126"],"mtu":1500,"auto_route":true,"stack":"gvisor"}],
 "outbounds":[{"type":"socks","tag":"out","server":"127.0.0.1","server_port":18821,"version":"5"},
              {"type":"direct","tag":"direct","bind_interface":"$IF"}],
 "route":{"auto_detect_interface":true,
          "rules":[{"inbound":["tun-in"],"process_path":["$work/line/sing-box"],"outbound":"direct"},
                   {"action":"reject","ip_version":6}]},
 "experimental":{"clash_api":{"external_controller":"127.0.0.1:18822","secret":"labsecret"}}}
JSON
  "$work/tun/sing-box" run -c "$work/tun/$1.json" >"$work/tun/log.$1" 2>&1 &
  TUN=$!
  for _ in $(seq 1 40); do ifconfig "$1" 2>/dev/null | grep -q 'inet 172.19.0.1 ' && break; sleep 0.25; done
}
# One line a second: what the tunnel's API lists, and which destinations the flows with no program go to.
watch_flows() {
  local n
  for n in $(seq 1 "$1"); do
    curl -s --max-time 2 -H 'Authorization: Bearer labsecret' http://127.0.0.1:18822/connections | python3 -c '
import json,sys,collections
try: d=json.load(sys.stdin)
except Exception as e: print("      the API did not answer"); sys.exit(0)
c=d.get("connections") or []
ways=collections.Counter("/".join(x["chains"]) for x in c)
unnamed=[x for x in c if not x["metadata"].get("processPath")]
where=collections.Counter(x["metadata"]["destinationIP"]+":"+x["metadata"]["destinationPort"] for x in unnamed)
print("      flows", len(c), dict(ways), "with no program", len(unnamed), dict(where.most_common(3)))'
    sleep 1
  done
}
# What the tunnel's own log says of the programs it looked for.
said() {
  local log="$work/tun/log.$1"
  echo "   connections the tunnel took in: $(grep -c 'inbound connection\|inbound packet connection' "$log"); programs it named: $(grep -c 'found process path' "$log"); it could not name: $(grep -c 'failed to search process' "$log")"
  grep 'failed to search process' "$log" | sed 's/.*failed to search process: //' | sort | uniq -c | sort -rn | head -4 | sed 's/^/      said: /'
  echo "   the first it could not name:"; grep -n 'failed to search process' "$log" | head -2 | cut -c1-190 | sed 's/^/      /'
  echo "   named as the line's binary: $(grep -c "found process path: $work/line/sing-box" "$log"); sent out by that rule: $(grep -c 'outbound/direct\[direct\]' "$log")"
}

say "P1: the line up, then the tunnel, then a request at once"
line_up
tun_up utun151
echo "   at once: $(fetch "http://$SITE/")"
watch_flows 8
echo "   after eight seconds: $(fetch "http://$SITE/"), over TLS $(fetch https://example.com/ --resolve "example.com:443:$SITE")"
said utun151
kill -9 "$TUN"; sleep 1.5

say "P2: the tunnel first, with no line; three seconds; then the line; then a request"
kill "$LINE" 2>/dev/null; sleep 0.5
tun_up utun152
sleep 3
watch_flows 1
line_up
sleep 1
echo "   a request: $(fetch "http://$SITE/"), and another: $(fetch "http://$SITE/")"
watch_flows 6
echo "   after six seconds: $(fetch "http://$SITE/"), over TLS $(fetch https://example.com/ --resolve "example.com:443:$SITE")"
said utun152
kill -9 "$TUN"; sleep 1.5

say "P3: the line up, then the tunnel, and twenty requests at once"
tun_up utun153
sleep 4
burst=""
for n in $(seq 1 20); do ( fetch "http://$SITE/" >"$work/burst.$n" ) & burst="$burst $!"; done
wait $burst
echo "   answered: $(grep -l -v '^000' "$work"/burst.* 2>/dev/null | wc -l | tr -d ' ') of 20"
watch_flows 4
said utun153
kill -9 "$TUN"; sleep 1

say "the end"
kill "$LINE" 2>/dev/null
echo "--- the line's log"; tail -5 "$work/line/log" | cut -c1-200
rm -rf "$work"
exit 0
