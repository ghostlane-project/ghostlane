#!/bin/bash
# What a Mac does under the block a kill switch would load, asked of one. Run
# as root on a throwaway machine (a CI runner): it loads pf rules, brings a tun
# up over the machine's default route, sets the system's resolver, and kills
# the tun.
#
# Every block is in an anchor of its own and is gone seventy seconds after it
# was loaded, whatever the script does after: a run that hung under a block
# left its runner with no network and nothing to say. Nothing here waits on a
# name while a block stands without a limit on the wait.
#
#   sudo bash lab/macos-hold.sh /path/to/sing-box <the unprivileged user>
SB="$1"
WHO="$2"
WHO_UID="$(id -u "$WHO")"
WHO_GID="$(id -g "$WHO")"
# The group the tunnel's own process would run in: no group of that number
# exists, and the rule names the number.
GID=7302243
BASE='com.apple/zz.ghostlane-lab'
KEY='State:/Network/Service/org.olcbox.app.tunnel/DNS'
work="$(cd /tmp && pwd -P)/ghostlane-hold.$$"
mkdir -p "$work/line" "$work/tun"
chmod 755 "$work"

say() { printf '\n=== %s\n' "$*"; }
quiet() { grep -v 'ALTQ\|pfctl: Use of -f option\|present in the main ruleset\|could result in flushing\|See /etc/pf.conf\|rules cleared\|nat cleared\|dummynet cleared\|tables deleted\|^$' || true; }
# A command that gives up after so many seconds; the system has no `timeout`.
within() { local s="$1"; shift; perl -e 'alarm shift; exec @ARGV' "$s" "$@"; }
# A command as the unprivileged user, without sudo: sudo looks its host's name up first.
as_user() { python3 -c 'import os,sys; os.setgid(int(sys.argv[2])); os.setuid(int(sys.argv[1])); os.execvp(sys.argv[3], sys.argv[3:])' "$WHO_UID" "$WHO_GID" "$@"; }
# fetch <root|user> <url> [curl arguments]: the answer's code and how long it took. 000 is no answer.
fetch() {
  local who="$1" url="$2" out; shift 2
  if [ "$who" = root ]; then
    out=$(curl -4 -s -o /dev/null -w '%{http_code} in %{time_total}s' --max-time 5 "$@" "$url" 2>/dev/null)
  else
    out=$(as_user curl -4 -s -o /dev/null -w '%{http_code} in %{time_total}s' --max-time 5 "$@" "$url" 2>/dev/null)
  fi
  printf '%s' "${out:-nothing}"
}
by_address() { fetch "$1" "http://$SITE/"; }
by_tls() { fetch "$1" https://example.com/ --resolve "example.com:443:$SITE"; }
name_by_system() { within 4 dscacheutil -q host -a name "$1" 2>&1 | awk '/ip_address/{print $2}' | head -1; }
set_key() {
  scutil <<SC >/dev/null
d.init
d.add ServerAddresses * 172.19.0.2
d.add SupplementalMatchDomains * ""
set $KEY
SC
}
drop_key() { printf 'remove %s\n' "$KEY" | scutil >/dev/null 2>&1; }
# load <anchor>: rules on stdin, gone again in seventy seconds come what may.
load() {
  pfctl -a "$1" -f - 2>&1 | quiet
  ( sleep 70; pfctl -a "$1" -F all ) >/dev/null 2>&1 &
}
# What each rule of an anchor has seen.
counted() {
  pfctl -a "$1" -vs rules 2>/dev/null | awk '
    /\[ Evaluations/ { printf "      %-74s packets %s states %s\n", rule, $5, $9; next }
    /^ *\[/ { next }
    { rule = substr($0, 1, 74) }'
}
trap 'drop_key; for n in 1 2 3 4 5 6 7 8 9; do pfctl -a "$BASE-$n" -F all >/dev/null 2>&1; done' EXIT

say "the machine"
sw_vers | tr '\n' ' '; echo; uname -m
IF=$(route -n get default | awk '/interface:/{print $2}')
GW=$(route -n get default | awk '/gateway:/{print $2}')
SITE=$(dig +short +time=3 +tries=2 example.com A | grep -E '^[0-9.]+$' | head -1)
RESOLVERS=$(scutil --dns | awk '/nameserver\[/{print $3}' | sort -u | tr '\n' ' ')
RESOLVER=$(echo "$RESOLVERS" | awk '{print $1}')
echo "way out: $IF by $GW; example.com is $SITE; the system asks: $RESOLVERS"
echo "the main ruleset:"; pfctl -s rules 2>&1 | quiet | sed 's/^/   /'
TOKEN=$(pfctl -E 2>&1 | awk '/Token/{print $3}')
ifconfig pflog0 create 2>/dev/null; ifconfig pflog0 up 2>/dev/null
echo "before anything: by address $(by_address user), over TLS $(by_tls user)"

cp "$SB" "$work/line/sing-box"; cp "$SB" "$work/tun/sing-box"
chown -R "$WHO" "$work/line"
cat > "$work/line/line.json" <<JSON
{"log":{"level":"warn"},
 "inbounds":[{"type":"socks","tag":"in","listen":"127.0.0.1","listen_port":18821}],
 "outbounds":[{"type":"direct","tag":"direct"}]}
JSON
as_user "$work/line/sing-box" run -c "$work/line/line.json" >"$work/line/log" 2>&1 &
LINE=$!
sleep 1
echo "the line by itself: $(as_user curl -s -o /dev/null -w '%{http_code}' --max-time 5 --socks5 127.0.0.1:18821 "http://$SITE/" || echo 'no answer')"

# run_tun <config> <the name it takes> <group>: the tunnel's process, in that group.
run_tun() {
  python3 -c 'import os,sys; os.setgid(int(sys.argv[3])); os.execv(sys.argv[1], [sys.argv[1], "run", "-c", sys.argv[2]])' \
    "$work/tun/sing-box" "$1" "$3" >"$work/tun/log.$2" 2>&1 &
  TUN=$!
  for _ in $(seq 1 40); do ifconfig "$2" 2>/dev/null | grep -q 'inet 172.19.0.1 ' && break; sleep 0.25; done
  echo "   tun pid $TUN (group $(ps -o gid= -p "$TUN" | tr -d ' ')), $(ifconfig "$2" 2>/dev/null | grep -c 'inet 172.19.0.1 ') interface named $2, $(netstat -rn -f inet | grep -c " $2 ") routes"
}
# The app's shape with nothing answered by the tun: a tun, the line behind it
# over loopback, the line's own binary let straight out.
plain_config() {
  cat > "$work/tun/plain.json" <<JSON
{"log":{"level":"warn"},
 "inbounds":[{"type":"tun","tag":"tun-in","interface_name":"$1","address":["172.19.0.1/30","fdfe:dcba:9876::1/126"],"mtu":1500,"auto_route":true,"stack":"gvisor"}],
 "outbounds":[{"type":"socks","tag":"out","server":"127.0.0.1","server_port":18821,"version":"5"},
              {"type":"direct","tag":"direct","bind_interface":"$IF"}],
 "route":{"auto_detect_interface":true,
          "rules":[{"inbound":["tun-in"],"process_path":["$work/line/sing-box"],"outbound":"direct"},
                   {"action":"reject","ip_version":6}]},
 "experimental":{"clash_api":{"external_controller":"127.0.0.1:18822","secret":"labsecret"}}}
JSON
}
# What the tunnel's API lists: how many flows, by which way out, and how many with no program named.
flows() {
  curl -s --max-time 3 -H 'Authorization: Bearer labsecret' http://127.0.0.1:18822/connections | python3 -c '
import json,sys,collections
try: d=json.load(sys.stdin)
except Exception as e: print("   the API did not answer:", e); sys.exit(0)
c=d.get("connections") or []
ways=collections.Counter("/".join(x["chains"]) for x in c)
unnamed=sum(1 for x in c if not x["metadata"].get("processPath"))
print("   flows open:", len(c), dict(ways), "with no program named:", unnamed, "totals down", d["downloadTotal"], "up", d["uploadTotal"])'
}

WORKED=""
# variant <n> <what it is> <the rule for the tunnel> <the rule for the tunnel's own traffic> <the tunnel's group>
variant() {
  local n="$1" label="$2" tunrule="$3" ownrule="$4" group="$5"
  local ifn="utun13$n" anchor="$BASE-$n" first
  say "V$n: $label"
  printf '%s\n' \
    'pass out quick on lo0 all flags any no state' \
    "$tunrule" \
    "$ownrule" \
    'pass out quick inet proto udp from any port 68 to any port 67' \
    'block return out log quick all' | sed "s/TUNIF/$ifn/g; s/GID/$GID/g" | load "$anchor"
  pfctl -a "$anchor" -s rules 2>&1 | quiet | sed 's/^/   /'
  echo "   held, no tunnel: user $(by_address user), root $(by_address root)"
  within 24 tcpdump -l -n -e -ttt -i pflog0 -c 16 2>/dev/null | cut -c1-170 | sed 's/^/   refused: /' &
  local logger=$!
  plain_config "$ifn"
  run_tun "$work/tun/plain.json" "$ifn" "$group"
  first=$(by_address user)
  echo "   through the tunnel: by address $first, over TLS $(by_tls user)"
  echo "   and again:          by address $(by_address user), over TLS $(by_tls user)"
  echo "   a name asked of 8.8.8.8: $(as_user dig +time=2 +tries=1 +short example.org @8.8.8.8 2>&1 | tail -1 | cut -c1-60)"
  flows
  counted "$anchor"
  pfctl -s info 2>/dev/null | awk '/state-mismatch|state-insert|bad-timestamp|proto-cksum|normalize|short|memory/ && $2 > 0 {printf "      pf counter %s %s\n", $1, $2}'
  kill -9 "$TUN" 2>/dev/null; sleep 1.2
  echo "   that tunnel dead: user $(by_address user)"
  wait "$logger" 2>/dev/null
  pfctl -a "$anchor" -F all 2>&1 | quiet
  echo "   --- the tunnel's log"; tail -6 "$work/tun/log.$ifn" | cut -c1-200 | sed 's/^/   /'
  case "$first" in 000*|nothing) ;; *) [ -z "$WORKED" ] && WORKED="$n" ;; esac
}

STATEFUL='pass out quick on TUNIF all'
STATELESS='pass quick on TUNIF all flags any no state'
BY_GROUP='pass out quick proto { tcp, udp } from any to any group GID'
BY_ROOT='pass out quick proto { tcp, udp } from any to any user 0'
variant 1 "the tunnel passed with state, its own traffic by its group" "$STATEFUL" "$BY_GROUP" "$GID"
variant 2 "the tunnel passed without state, its own traffic by its group" "$STATELESS" "$BY_GROUP" "$GID"
variant 3 "the tunnel passed without state, its own traffic as root's" "$STATELESS" "$BY_ROOT" 0
variant 4 "the tunnel passed with state, its own traffic as root's" "$STATEFUL" "$BY_ROOT" 0
echo
echo "the first variant that carried: ${WORKED:-none}"

case "$WORKED" in
  1) TUNRULE="$STATEFUL"; OWNRULE="$BY_GROUP"; GROUP="$GID" ;;
  3) TUNRULE="$STATELESS"; OWNRULE="$BY_ROOT"; GROUP=0 ;;
  4) TUNRULE="$STATEFUL"; OWNRULE="$BY_ROOT"; GROUP=0 ;;
  *) TUNRULE="$STATELESS"; OWNRULE="$BY_GROUP"; GROUP="$GID" ;;
esac

# The app's own config for a room's session (SingBoxConfigDumpTest,
# desktop-tun-counted) as it would be written with names answered by the tun:
# every question that enters the tun is answered there, before the rule that
# lets the lines' binaries out, and what those binaries ask is answered with
# real addresses by <the resolver named>.
app_config() {
  python3 - "$work" "$IF" "$1" "$2" "$RESOLVER" <<'PY'
import json, sys
work, way_out, name, direct_kind, resolver = sys.argv[1:6]
config = json.load(open("lab/app-room-tun.json"))
tun = config["inbounds"][0]
tun["interface_name"] = name
config["inbounds"] = [tun]
out, direct = config["outbounds"]
out["server_port"] = 18821
out.pop("username", None); out.pop("password", None)
direct["bind_interface"] = way_out
lines = [work + "/line/sing-box", "/usr/bin/dig"]
kinds = {
    "dhcp": {"type": "dhcp", "tag": "dns-direct"},
    "local": {"type": "local", "tag": "dns-direct"},
    "udp": {"type": "udp", "tag": "dns-direct", "server": resolver},
}
config["dns"]["servers"] = [kinds[direct_kind] if s["tag"] == "dns-direct" else s for s in config["dns"]["servers"]]
config["dns"]["rules"] = [{"process_path": lines, "server": "dns-direct"}] + config["dns"]["rules"]
process, sniff, hijack, no_v6 = config["route"]["rules"]
process["process_path"] = lines
config["route"]["rules"] = [sniff, hijack, process, no_v6]
config["experimental"]["cache_file"]["path"] = work + "/tun/cache-" + name + ".db"
config["experimental"]["clash_api"] = {"external_controller": "127.0.0.1:18822", "secret": "labsecret"}
json.dump(config, open(work + "/tun/app.json", "w"))
PY
}
# names <n> <how the tun asks for the lines>
names() {
  local n="$1" kind="$2" ifn="utun14$1" anchor="$BASE-$((4 + $1))"
  say "N$n: every rule of the block, names answered by the tun, the lines' own asked by: $kind"
  printf '%s\n' \
    'pass out quick on lo0 all flags any no state' \
    "$TUNRULE" \
    "$OWNRULE" \
    'pass out quick inet proto udp from any port 68 to any port 67' \
    'block return out quick proto { tcp, udp } from any to any port 53' \
    'pass out quick inet from any to { 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 169.254.0.0/16, 224.0.0.0/4, 255.255.255.255 }' \
    'pass out quick inet6 from any to { fe80::/10, ff00::/8 }' \
    'block return out quick all' | sed "s/TUNIF/$ifn/g; s/GID/$GID/g" | load "$anchor"
  [ "$n" = 1 ] && pfctl -a "$anchor" -s rules 2>&1 | quiet | sed 's/^/   /'
  echo "   held, no tunnel: by address $(by_address user); the gateway $(ping -c 1 -t 2 "$GW" >/dev/null 2>&1 && echo answers a ping || echo does not answer a ping); a name asked of it: $(as_user dig +time=1 +tries=1 +short example.org "@$RESOLVER" 2>&1 | tail -1 | cut -c1-50)"
  app_config "$ifn" "$kind"
  run_tun "$work/tun/app.json" "$ifn" "$GROUP"
  set_key
  sleep 1
  echo "   the system asks now: $(scutil --dns | awk '/nameserver\[/{print $3}' | tr '\n' ' ' | cut -c1-70)"
  within 9 tcpdump -l -i "$ifn" -n -c 8 udp port 53 2>/dev/null | cut -c1-150 | sed 's/^/   seen on the tunnel: /' &
  within 9 tcpdump -l -i "$IF" -n -c 8 port 53 2>/dev/null | cut -c1-150 | sed "s/^/   seen on $IF: /" &
  sleep 1
  echo "   www.debian.org, by the system: $(name_by_system www.debian.org)"
  echo "   www.kernel.org, asked of the tun by a line's binary: $(as_user dig +time=3 +tries=1 +short www.kernel.org @172.19.0.2 2>&1 | tail -1 | cut -c1-60)"
  echo "   www.python.org, asked of 8.8.8.8 by a line's binary: $(as_user dig +time=3 +tries=1 +short www.python.org @8.8.8.8 2>&1 | tail -1 | cut -c1-60)"
  echo "   by address through the tunnel: $(by_address user)"
  echo "   by name through the tunnel: $(fetch user https://www.debian.org/)"
  echo "   by name again: $(fetch user https://www.mozilla.org/)"
  flows
  sleep 3
  drop_key
  kill -9 "$TUN" 2>/dev/null; sleep 1.2
  echo "   that tunnel dead and the key removed: by address $(by_address user); a name by the system: $(name_by_system www.gnu.org | grep . || echo none)"
  pfctl -a "$anchor" -F all 2>&1 | quiet
  echo "   --- the tunnel's log"; tail -6 "$work/tun/log.$ifn" | cut -c1-200 | sed 's/^/   /'
}
names 1 dhcp
names 2 udp
names 3 local

say "the end"
drop_key
[ -n "$TOKEN" ] && pfctl -X "$TOKEN" 2>&1 | quiet
echo "nothing standing: by address $(by_address user); a name by the system: $(name_by_system www.kernel.org)"
kill "$LINE" 2>/dev/null
echo "--- the line's log"; tail -8 "$work/line/log" | cut -c1-200
rm -rf "$work"
exit 0
