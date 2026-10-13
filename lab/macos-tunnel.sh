#!/bin/bash
# What a Mac does, asked of one: the facts a kill switch for the desktop's
# tunnel has to stand on. Run as root on a throwaway machine (a CI runner): it
# loads pf rules, brings a tun up over the machine's default route, and kills it.
#
# No block here outlives forty seconds whatever the script does after loading
# it: a run of this that hung under a block left its runner with no network and
# nothing to say. And nothing here waits on a name while a block stands.
#
#   sudo bash lab/macos-tunnel.sh /path/to/sing-box <the unprivileged user>
SB="$1"
WHO="$2"
WHO_UID="$(id -u "$WHO")"
WHO_GID="$(id -g "$WHO")"
ANCHOR='com.apple/zz.ghostlane-lab'
work="$(cd /tmp && pwd -P)/ghostlane-lab.$$"
mkdir -p "$work"
chmod 755 "$work"

say() { printf '\n=== %s\n' "$*"; }
quiet() { grep -v 'ALTQ\|pfctl: Use of -f option\|present in the main ruleset\|could result in flushing\|See /etc/pf.conf\|rules cleared\|nat cleared\|dummynet cleared\|tables deleted\|^$' || true; }
# A command that gives up after so many seconds; the system has no `timeout`.
within() { local s="$1"; shift; perl -e 'alarm shift; exec @ARGV' "$s" "$@"; }
# A command as the unprivileged user, without sudo: sudo looks its host's name up first.
as_user() { python3 -c 'import os,sys; os.setgid(int(sys.argv[2])); os.setuid(int(sys.argv[1])); os.execvp(sys.argv[3], sys.argv[3:])' "$WHO_UID" "$WHO_GID" "$@"; }
# One line per look: who asked, and what came back. By address: a name is another question.
probe() {
  local who="$1" what="$2" out
  if [ "$who" = root ]; then
    out=$(curl -4 -s -o /dev/null -w '%{http_code}' --max-time 5 --resolve "example.com:443:$SITE" https://example.com 2>/dev/null) || out="no answer"
  else
    out=$(as_user curl -4 -s -o /dev/null -w '%{http_code}' --max-time 5 --resolve "example.com:443:$SITE" https://example.com 2>/dev/null) || out="no answer"
  fi
  printf '   %-6s %-52s %s\n' "$who" "$what" "$out"
}
empty() { pfctl -a "$ANCHOR" -F all 2>&1 | quiet; }
# The block, and with it the forty seconds after which it is gone come what may.
block_for() {
  printf '%s\n' \
    'pass out quick on lo0 all flags any no state' \
    "pass out quick on $1 all" \
    'pass out quick inet proto udp from any port 68 to any port 67' \
    'pass out quick proto { tcp, udp } from any to any group 4242' \
    'block drop out quick all' | pfctl -a "$ANCHOR" -f - 2>&1 | quiet
  ( sleep 40; pfctl -a "$ANCHOR" -F all >/dev/null 2>&1 ) &
}

say "the machine"
sw_vers | tr '\n' ' '; echo; uname -m
IF=$(route -n get default | awk '/interface:/{print $2}')
GW=$(route -n get default | awk '/gateway:/{print $2}')
SITE=$(dig +short +time=3 +tries=2 example.com A | grep -E '^[0-9.]+$' | head -1)
RESOLVERS=$(scutil --dns | awk '/nameserver\[/{print $3}' | sort -u | tr '\n' ' ')
echo "way out: $IF by $GW; example.com is $SITE; the system asks: $RESOLVERS"
TOKEN=$(pfctl -E 2>&1 | awk '/Token/{print $3}')
probe user "before anything"

mkdir -p "$work/line" "$work/tun"
cp "$SB" "$work/line/sing-box"; cp "$SB" "$work/tun/sing-box"
chown -R "$WHO" "$work/line"
cat > "$work/line/line.json" <<JSON
{"log":{"level":"warn"},
 "inbounds":[{"type":"socks","tag":"in","listen":"127.0.0.1","listen_port":18821}],
 "outbounds":[{"type":"direct","tag":"direct"}]}
JSON
# tun_up <the name it is to take> [<group>] — the app's shape: a tun, the line
# behind it over loopback, the line's own binary let straight out; in root's own
# group unless told another.
tun_up() {
  cat > "$work/tun/tun.json" <<JSON
{"log":{"level":"warn"},
 "inbounds":[{"type":"tun","tag":"tun-in","interface_name":"$1","address":["172.19.0.1/30","fdfe:dcba:9876::1/126"],"mtu":1400,"auto_route":true,"stack":"gvisor"}],
 "outbounds":[{"type":"socks","tag":"out","server":"127.0.0.1","server_port":18821,"version":"5"},
              {"type":"direct","tag":"direct","bind_interface":"$IF"}],
 "route":{"auto_detect_interface":true,
          "rules":[{"inbound":["tun-in"],"process_path":["$work/line/sing-box"],"outbound":"direct"},
                   {"action":"reject","ip_version":6}]},
 "experimental":{"clash_api":{"external_controller":"127.0.0.1:18822","secret":"labsecret"}}}
JSON
  python3 -c 'import os,sys; os.setgid(int(sys.argv[3])); os.execv(sys.argv[1], [sys.argv[1], "run", "-c", sys.argv[2]])' \
    "$work/tun/sing-box" "$work/tun/tun.json" "${2:-0}" >"$work/tun/log.$1" 2>&1 &
  TUN=$!
  for _ in $(seq 1 40); do ifconfig "$1" 2>/dev/null | grep -q 'inet 172.19.0.1 ' && break; sleep 0.25; done
  echo "   tun pid $TUN (group $(ps -o gid= -p "$TUN" | tr -d ' ')), $(ifconfig "$1" 2>/dev/null | grep -c 'inet 172.19.0.1 ') interface named $1, $(netstat -rn -f inet | grep -c " $1 ") routes"
}
# What the tunnel's API lists while something is being fetched through it.
flows() {
  as_user curl -4 -s --max-time 8 --limit-rate 200k -o /dev/null --resolve "example.com:443:$SITE" https://example.com/ &
  as_user curl -4 -s --max-time 8 --limit-rate 200k -o /dev/null 'http://speed.cloudflare.com/__down?bytes=4000000' &
  sleep 2
  curl -s --max-time 3 -H 'Authorization: Bearer labsecret' http://127.0.0.1:18822/connections | python3 -c '
import json,sys
try: d=json.load(sys.stdin)
except Exception as e: print("   the API did not answer:", e); sys.exit(0)
print("   totals down", d["downloadTotal"], "up", d["uploadTotal"])
for c in d["connections"][:8]:
    print("   flow", c["chains"], "to", c["metadata"]["destinationIP"]+":"+c["metadata"]["destinationPort"], "program", repr(c["metadata"]["processPath"]), "down", c["download"])'
}
as_user "$work/line/sing-box" run -c "$work/line/line.json" >"$work/line/log" 2>&1 &
LINE=$!
sleep 1
echo "the line by itself: $(as_user curl -s -o /dev/null -w '%{http_code}' --max-time 5 --socks5 127.0.0.1:18821 --resolve "example.com:443:$SITE" https://example.com || echo 'no answer')"

say "A0: the tunnel up in root's own group, nothing held"
tun_up utun122
probe user "through the tunnel"
flows
kill -9 "$TUN"; sleep 1.5

say "A1: the tunnel up in a group of its own, nothing held"
tun_up utun121 4242
probe user "through the tunnel"
flows
kill -9 "$TUN"; sleep 1.5

say "A: the tunnel up, nothing held: where a name goes"
tun_up utun123
probe user "through the tunnel"
for r in $RESOLVERS; do echo "   route to the resolver $r: $(route -n get "$r" 2>/dev/null | awk '/interface:/{print $2}')"; done
within 5 tcpdump -i utun123 -n -c 6 udp port 53 2>/dev/null | sed 's/^/   seen on the tunnel: /' &
within 5 tcpdump -i "$IF" -n -c 6 udp port 53 2>/dev/null | sed "s/^/   seen on $IF: /" &
sleep 1
within 4 dscacheutil -q host -a name "fresh-$RANDOM.cloudflare.com" >/dev/null 2>&1
sleep 4.5
echo "   (nothing above this line and under the two routes: no question was seen)"

say "B: the tunnel dies with nothing held"
kill -9 "$TUN"; sleep 1.5
echo "   the interface: $(ifconfig utun123 2>&1 | head -1); routes naming it: $(netstat -rn -f inet | grep -c ' utun123 ')"
probe user "after its death (a number: straight out)"

say "C: the block, then a tunnel brought up behind it"
block_for utun124
pfctl -a "$ANCHOR" -s rules 2>&1 | quiet | sed 's/^/   /'
probe user "block standing, no tunnel (held)"
probe root "the same, as root (held)"
tun_up utun124 4242
probe user "tunnel up behind the block"
echo "   a name, by the system's resolver: $(within 5 dscacheutil -q host -a name "fresh-$RANDOM.example.org" 2>&1 | grep -c ip_address) address(es)"
echo "   a name, asked of 8.8.8.8: $(as_user dig +time=2 +tries=1 +short example.org @8.8.8.8 2>&1 | head -1)"
kill -9 "$TUN"; sleep 1.5
probe user "that tunnel dead (held)"
echo "   a name, asked of 8.8.8.8, held: $(as_user dig +time=2 +tries=1 +short example.org @8.8.8.8 2>&1 | tail -1 | cut -c1-48)"
empty
probe user "the block taken away"

say "D: the tunnel's interface named without its number"
printf '%s\n' \
  'pass out quick on lo0 all flags any no state' \
  'pass out quick on utun all' \
  'pass out quick inet proto udp from any port 68 to any port 67' \
  'pass out quick proto { tcp, udp } from any to any group 4242' \
  'block drop out quick all' | pfctl -a "$ANCHOR" -f - 2>&1 | quiet
( sleep 40; pfctl -a "$ANCHOR" -F all >/dev/null 2>&1 ) &
tun_up utun125 4242
probe user "through utun125, the rule saying utun"
kill -9 "$TUN"; sleep 1
empty

say "the end"
[ -n "$TOKEN" ] && pfctl -X "$TOKEN" 2>&1 | quiet
probe user "nothing standing"
kill "$LINE" 2>/dev/null
for f in "$work"/tun/log.*; do echo "--- $(basename "$f")"; tail -3 "$f" | cut -c1-220; done
rm -rf "$work"
exit 0
