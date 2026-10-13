#!/bin/bash
# What a Mac does, asked of one: the facts a kill switch for the desktop's
# tunnel has to stand on. Run as root on a throwaway machine (a CI runner): it
# loads pf rules, brings a tun up over the machine's default route, and kills it.
#
#   sudo bash lab/macos-tunnel.sh /path/to/sing-box <the unprivileged user>
SB="$1"
WHO="$2"
ANCHOR='com.apple/zz.ghostlane-lab'
# Under /tmp, where the unprivileged user can reach it: root's own temporary
# directory is closed to everybody else, and the line runs as that user.
work="$(cd /tmp && pwd -P)/ghostlane-lab.$$"
mkdir -p "$work"
chmod 755 "$work"

say() { printf '\n=== %s\n' "$*"; }
# One line per look: who asked, for what, and what came back.
probe() {
  local who="$1" what="$2" url="$3" out
  if [ "$who" = root ]; then
    out=$(curl -4 -s -o /dev/null -w '%{http_code}' --max-time 6 "$url" 2>/dev/null) || out="no answer"
  else
    out=$(sudo -u "$WHO" curl -4 -s -o /dev/null -w '%{http_code}' --max-time 6 "$url" 2>/dev/null) || out="no answer"
  fi
  printf '   %-6s %-46s %s\n' "$who" "$what" "$out"
}
quiet() { grep -v 'ALTQ\|No ALTQ\|pfctl: Use of -f option\|present in the main ruleset\|could result in flushing\|See pf.conf' || true; }

say "the machine"
sw_vers; uname -m; id; echo "unprivileged user: $WHO ($(id -u "$WHO"))"

say "pf as it stands"
pfctl -s info 2>&1 | quiet | head -4
echo "--- /etc/pf.conf"; grep -v '^#' /etc/pf.conf | grep -v '^$'
echo "--- /etc/pf.anchors/com.apple"; grep -v '^#' /etc/pf.anchors/com.apple | grep -v '^$'
echo "--- rules"; pfctl -s rules 2>&1 | quiet
echo "--- anchors"; pfctl -s Anchors -v 2>&1 | quiet | head -20

say "the way out"
route -n get default | sed -n 1,10p
IF=$(route -n get default | awk '/interface:/{print $2}')
GW=$(route -n get default | awk '/gateway:/{print $2}')
echo "interface $IF gateway $GW"

say "before anything"
probe user "example.com" https://example.com
probe user "1.1.1.1" https://1.1.1.1
probe user "1.0.0.1" https://1.0.0.1

say "E2: a block in an anchor under com.apple/, the main ruleset not touched"
printf 'block drop out quick inet proto tcp from any to 1.1.1.1 port 443\n' | pfctl -a "$ANCHOR" -f - 2>&1 | quiet
TOKEN=$(pfctl -E 2>&1 | awk '/Token/{print $3}')
echo "enabled with token: ${TOKEN:-none}"; pfctl -s info 2>&1 | quiet | head -1
pfctl -a "$ANCHOR" -s rules 2>&1 | quiet
probe user "1.1.1.1 (blocked, if the anchor is read)" https://1.1.1.1
probe user "1.0.0.1 (not named, passes)" https://1.0.0.1
pfctl -a "$ANCHOR" -F all 2>&1 | quiet
probe user "1.1.1.1 after the anchor is emptied" https://1.1.1.1

say "E3: the same block in an anchor of our own name, which nothing calls"
printf 'block drop out quick inet proto tcp from any to 1.1.1.1 port 443\n' | pfctl -a 'org.ghostlane.lab' -f - 2>&1 | quiet
probe user "1.1.1.1 (passes, if nothing reads it)" https://1.1.1.1
pfctl -a 'org.ghostlane.lab' -F all 2>&1 | quiet

say "E4: a pass for root's sockets and for one group's, a block for the rest"
printf '%s\n' \
  'pass out quick inet proto tcp from any to 1.1.1.1 port 443 user root' \
  'pass out quick inet proto tcp from any to 1.1.1.1 port 443 group 4242' \
  'block drop out quick inet proto tcp from any to 1.1.1.1 port 443' | pfctl -a "$ANCHOR" -f - 2>&1 | quiet
pfctl -a "$ANCHOR" -s rules 2>&1 | quiet
probe user "1.1.1.1 as the user (blocked)" https://1.1.1.1
probe root "1.1.1.1 as root (passes)" https://1.1.1.1
out=$(python3 -c 'import os,sys; os.setgid(4242); os.setuid(int(sys.argv[1])); os.execvp("curl", ["curl","-4","-s","-o","/dev/null","-w","%{http_code}","--max-time","6","https://1.1.1.1"])' "$(id -u "$WHO")" 2>/dev/null) || out="no answer"
printf '   %-6s %-46s %s\n' gid "1.1.1.1 as the user in group 4242 (passes)" "$out"
pfctl -a "$ANCHOR" -F all 2>&1 | quiet

say "E5: the desktop's tunnel, as the app builds it"
mkdir -p "$work/line" "$work/tun"
cp "$SB" "$work/line/sing-box"; cp "$SB" "$work/tun/sing-box"
chown -R "$WHO" "$work/line"
cat > "$work/line/line.json" <<JSON
{"log":{"level":"warn"},
 "inbounds":[{"type":"socks","tag":"in","listen":"127.0.0.1","listen_port":18821}],
 "outbounds":[{"type":"direct","tag":"direct"}]}
JSON
tun_config() {
cat > "$work/tun/tun.json" <<JSON
{"log":{"level":"warn"},
 "inbounds":[{"type":"tun","tag":"tun-in","interface_name":"$1","address":["172.19.0.1/30","fdfe:dcba:9876::1/126"],"mtu":1400,"auto_route":true,"stack":"gvisor"}],
 "outbounds":[{"type":"socks","tag":"out","server":"127.0.0.1","server_port":18821,"version":"5"},
              {"type":"direct","tag":"direct","bind_interface":"$IF"}],
 "route":{"auto_detect_interface":true,
          "rules":[{"inbound":["tun-in"],"process_path":["$work/line/sing-box","$(readlink -f "$work/line/sing-box")"],"outbound":"direct"},
                   {"action":"reject","ip_version":6}]},
 "experimental":{"clash_api":{"external_controller":"127.0.0.1:18822","secret":"labsecret"}}}
JSON
}
# tun_up <the name it is to take> [<a group to run in>]
tun_up() {
  tun_config "$1"
  if [ -n "${2:-}" ]; then
    python3 -c 'import os,sys; os.setgid(int(sys.argv[1])); os.execv(sys.argv[2], [sys.argv[2], "run", "-c", sys.argv[3]])' \
      "$2" "$work/tun/sing-box" "$work/tun/tun.json" >"$work/tun/log" 2>&1 &
  else
    "$work/tun/sing-box" run -c "$work/tun/tun.json" >"$work/tun/log" 2>&1 &
  fi
  TUN=$!
  for _ in $(seq 1 40); do ifconfig | grep -q 'inet 172.19.0.1 ' && break; sleep 0.25; done
  UTUN=$(ifconfig | awk '/^utun[0-9]+:/{name=$1} /inet 172.19.0.1 /{sub(":","",name); print name; exit}')
  echo "tun pid $TUN on ${UTUN:-no interface}"
}
sudo -u "$WHO" "$work/line/sing-box" run -c "$work/line/line.json" >"$work/line/log" 2>&1 &
LINE=$!
sleep 1
echo "the line: $(sudo -u "$WHO" curl -s -o /dev/null -w '%{http_code}' --max-time 4 --socks5-hostname 127.0.0.1:18821 https://example.com || echo 'not there')"
tun_up utun123
echo "--- routes through it"; netstat -rn -f inet | grep -c "$UTUN" | sed 's/^/   count: /'; netstat -rn -f inet | grep "$UTUN" | head -6
probe user "example.com, through the tunnel" https://example.com
echo "--- what its API says of that"
sudo -u "$WHO" curl -4 -s --max-time 20 --limit-rate 300k -o /dev/null https://speed.cloudflare.com/__down?bytes=8000000 &
SLOW=$!
sleep 2
curl -s -H 'Authorization: Bearer labsecret' http://127.0.0.1:18822/connections | python3 -c '
import json,sys
d=json.load(sys.stdin)
print("   totals down", d["downloadTotal"], "up", d["uploadTotal"])
for c in d["connections"]:
    print("   flow", c["chains"], "program", repr(c["metadata"]["processPath"]), "down", c["download"])'
kill "$SLOW" 2>/dev/null

say "E5: the tunnel's process killed outright, nothing holding"
kill -9 "$TUN"; sleep 1.5
echo "--- the interface"; ifconfig "$UTUN" 2>&1 | head -2
echo "--- routes still naming it"; netstat -rn -f inet | grep -c "$UTUN" | sed 's/^/   count: /'
echo "--- the default route"; route -n get default 2>&1 | awk '/gateway:|interface:/{print "   "$0}'
probe user "example.com (a number: it went out directly)" https://example.com

say "E6: the same death with a block standing, the tunnel's sing-box in a group of its own"
tun_up utun124 4242
ps -o pid,uid,gid,comm -p "$TUN" | sed 's/^/   /'
block_for() {
  printf '%s\n' \
    'pass out quick on lo0 all flags any no state' \
    "pass out quick on $1 all" \
    'pass out quick inet proto udp from any port 68 to any port 67' \
    'pass out quick proto { tcp, udp } from any to any group 4242' \
    'block drop out quick all' | pfctl -a "$ANCHOR" -f - 2>&1 | quiet
}
block_for "$UTUN"
pfctl -a "$ANCHOR" -s rules 2>&1 | quiet
probe user "example.com, tunnel up, block standing" https://example.com
echo "--- where a name goes while the tunnel is up"
echo "   the system's resolvers: $(scutil --dns | awk '/nameserver\[/{print $3}' | sort -u | tr '\n' ' ')"
echo "   route to the first of them: $(route -n get "$(scutil --dns | awk '/nameserver\[/{print $3; exit}')" 2>/dev/null | awk '/interface:/{print $2}')"
( tcpdump -i "$UTUN" -n -c 4 udp port 53 2>/dev/null | sed 's/^/   on the tunnel: /' ) & DUMP1=$!
( tcpdump -i "$IF" -n -c 4 udp port 53 2>/dev/null | sed "s/^/   on $IF: /" ) & DUMP2=$!
sleep 1
sudo -u "$WHO" dscacheutil -q host -a name "fresh-$RANDOM.cloudflare.com" >/dev/null 2>&1
sudo -u "$WHO" dscacheutil -q host -a name "fresh-$RANDOM.example.org" >/dev/null 2>&1
sleep 2
kill "$DUMP1" "$DUMP2" 2>/dev/null; pkill -x tcpdump 2>/dev/null; sleep 0.3
echo "--- what the tunnel's API lists while a download runs"
sudo -u "$WHO" curl -4 -s --max-time 20 --limit-rate 300k -o /dev/null 'https://speed.cloudflare.com/__down?bytes=8000000' &
SLOW=$!
sleep 2
curl -s -H 'Authorization: Bearer labsecret' http://127.0.0.1:18822/connections | python3 -c '
import json,sys
d=json.load(sys.stdin)
print("   totals down", d["downloadTotal"], "up", d["uploadTotal"])
for c in d["connections"]:
    print("   flow", c["chains"], "program", repr(c["metadata"]["processPath"]), "down", c["download"])'
kill "$SLOW" 2>/dev/null
kill -9 "$TUN"; sleep 1.5
probe user "example.com, tunnel dead (no answer: held)" https://example.com
probe root "example.com as root, tunnel dead (held too)" https://example.com
echo "--- names, while it is held"
name="held-$RANDOM.example.com"
printf '   %-6s %-46s %s\n' user "dig $name @8.8.8.8" "$(sudo -u "$WHO" dig +time=2 +tries=1 +short "$name" @8.8.8.8 2>&1 | tail -1 | cut -c1-60)"
printf '   %-6s %-46s %s\n' user "the system's resolver, a name not cached" "$(sudo -u "$WHO" dscacheutil -q host -a name "fresh-$RANDOM.cloudflare.com" 2>&1 | head -2 | tr '\n' ' ' | cut -c1-60)"

say "E7: a new tunnel brought up behind the block, which is told its name first"
block_for utun125
probe user "example.com, block renamed, no tunnel yet (held)" https://example.com
tun_up utun125 4242
probe user "example.com, new tunnel up behind the block" https://example.com
kill -9 "$TUN"; sleep 1.5
probe user "example.com, that tunnel dead (held)" https://example.com

say "E8: the tunnel's interface named without its number"
printf '%s\n' \
  'pass out quick on lo0 all flags any no state' \
  'pass out quick on utun all' \
  'pass out quick inet proto udp from any port 68 to any port 67' \
  'pass out quick proto { tcp, udp } from any to any group 4242' \
  'block drop out quick all' | pfctl -a "$ANCHOR" -f - 2>&1 | quiet
tun_up utun126 4242
probe user "example.com, through utun126, rule says utun" https://example.com
kill -9 "$TUN"; sleep 1.5
pfctl -a "$ANCHOR" -F all 2>&1 | quiet
probe user "example.com once the block is taken away" https://example.com

say "the end"
[ -n "$TOKEN" ] && pfctl -X "$TOKEN" 2>&1 | quiet
pfctl -s info 2>&1 | quiet | head -1
kill "$LINE" 2>/dev/null
echo "--- the tun's log"; tail -8 "$work/tun/log"
rm -rf "$work"
exit 0
