#!/bin/bash
# What a socket bound to the physical interface's own address does beside a tun
# on a Mac (a WebRTC engine binds every ICE socket that way), what the kill
# switch's block does to it, and whether the packet filter can put it into the
# tun instead. Then: what a line's program gets in the first moments of a tun.
#
#   sudo bash lab/macos-bound.sh <helper-check> <sing-box> <user> <udpprobe>
set -u
HELPER="$1"
SB="$2"
WHO="$3"
PROBE_BIN="$4"
WHO_UID="$(id -u "$WHO")"
WHO_GID="$(id -g "$WHO")"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REFERENCE="$root/sharedUI/src/jvmTest/resources/reference-configs/desktop-tun-macos-names.json"
ANCHOR='com.apple/100.org.olcbox.app.hold'
KEY='State:/Network/Service/org.olcbox.app.tunnel/DNS'
PROBE='names-its-programs.ghostlane.invalid'
GROUP=7302243
work="$(cd /tmp && pwd -P)/ghostlane-lab.$$"
mkdir -p "$work/line" "$work/other"
chmod 755 "$work"
( sleep 540; pfctl -a "$ANCHOR" -F all; printf 'remove %s\n' "$KEY" | scutil ) >/dev/null 2>&1 &

say() { printf '\n== %s\n' "$*"; }
note() { printf '   %-58s %s\n' "$1" "$2"; }
within() { local s="$1"; shift; perl -e 'alarm shift; exec @ARGV' "$s" "$@"; }
now() { perl -MTime::HiRes=time -e 'printf "%d\n", time*1000'; }
as_user() { python3 -c 'import os,sys; os.setgid(int(sys.argv[2])); os.setuid(int(sys.argv[1])); os.execvp(sys.argv[3], sys.argv[3:])' "$WHO_UID" "$WHO_GID" "$@"; }
code_of() { local out; out=$(as_user curl -4 -s -o /dev/null -w '%{http_code}' --max-time "${2:-5}" "$1" 2>/dev/null); printf '%s' "${out:-000}"; }
bound_code() { local out; out=$(as_user curl -4 -s -o /dev/null -w '%{http_code}' --max-time 5 --interface "$MINE" "$1" 2>/dev/null); printf '%s' "${out:-000}"; }
through_line() { local out; out=$(as_user curl -4 -s -o /dev/null -w '%{http_code}' --max-time "${2:-4}" --socks5 127.0.0.1:18821 "$1" 2>/dev/null); printf '%s' "${out:-000}"; }
rules_standing() { pfctl -a "$ANCHOR" -s rules 2>/dev/null | grep -c .; }
udp() { as_user "$1" "$2" "$STUN_IP" "$STUN_PORT" 2500 2>&1 | tail -1; }
# What the tun's sing-box wrote of the flow that left a local port, if it saw one.
flow() {
  local port; port=$(awk '{print $2}' <<<"$1")
  local id; id=$(cat "$work"/utun*.log 2>/dev/null | sed -e 's/\x1b\[[0-9;]*m//g' | grep -E "from [0-9.]+:$port\$" | tail -1 | sed -E 's/.*\[([0-9]+) [^]]*\].*/\1/')
  if [ -z "$id" ]; then echo "not seen by the tun"; return; fi
  cat "$work"/utun*.log | sed -e 's/\x1b\[[0-9;]*m//g' | grep -F "[$id " | sed -E 's/^.*\] //' | cut -c1-110 | head -4 | tr '\n' ';'
  echo
}

say "the machine"
sw_vers | tr '\n' ' '; echo
IF=$(route -n get default | awk '/interface:/{print $2}')
MINE=$(ipconfig getifaddr "$IF")
SERVER=$(dig +short +time=3 +tries=2 example.com A | grep -E '^[0-9.]+$' | head -1)
ELSEWHERE=$(dig +short +time=3 +tries=2 one.one.one.one A | grep -E '^[0-9.]+$' | head -1)
DIG="$(python3 -c 'import os,sys; print(os.path.realpath(sys.argv[1]))' "$(command -v dig)")"
cp "$PROBE_BIN" "$work/line/udpprobe"; cp "$PROBE_BIN" "$work/other/udpprobe"
cp "$SB" "$work/line/sing-box"
chown -R "$WHO" "$work/line" "$work/other"
LINE_PROBE="$work/line/udpprobe"
OTHER_PROBE="$work/other/udpprobe"
STUN_IP=""; STUN_PORT=""
for candidate in "stun.cloudflare.com 3478" "stun.l.google.com 19302" "stun1.l.google.com 19302" "stun.nextcloud.com 3478"; do
  host_name="${candidate% *}"; port="${candidate#* }"
  ip=$(dig +short +time=3 +tries=2 "$host_name" A | grep -E '^[0-9.]+$' | head -1)
  [ -n "$ip" ] || continue
  if as_user "$OTHER_PROBE" - "$ip" "$port" 2500 | grep -q answered; then STUN_IP="$ip"; STUN_PORT="$port"; echo "asks $host_name ($ip:$port)"; break; fi
done
echo "way out: $IF, this Mac's address on it: $MINE; the server a block lets out: $SERVER; elsewhere: $ELSEWHERE"
[ -n "$STUN_IP" ] || { echo "no STUN server answers from here: nothing to measure"; exit 1; }

cat > "$work/line/line.json" <<JSON
{"log":{"level":"warn"},
 "inbounds":[{"type":"socks","tag":"in","listen":"127.0.0.1","listen_port":18821}],
 "outbounds":[{"type":"direct","tag":"direct"}]}
JSON
start_line() { as_user "$work/line/sing-box" run -c "$work/line/line.json" >>"$work/line/log" 2>&1 & LINE=$!; sleep 1; }
start_line

python3 - "$work" "$IF" "$DIG" "$SERVER" "$REFERENCE" <<'PY'
import json, sys
work, way_out, app, server, reference = sys.argv[1:6]
config = json.load(open(reference))
tun = config["inbounds"][0]
tun["route_exclude_address"] = [server + "/32"]
out, direct = config["outbounds"]
out["server_port"] = 18821
direct["bind_interface"] = way_out
lines = [work + "/line/sing-box", "/usr/bin/host", work + "/line/udpprobe"]
rules = config["dns"]["rules"]
rules[0]["process_path"] = [app]
rules[2]["process_path"] = lines
config["route"]["rules"][2]["process_path"] = lines
for n in range(225, 256):
    name = "utun%d" % n
    tun["interface_name"] = name
    config["log"] = {"level": "debug", "output": work + "/" + name + ".log", "timestamp": True, "disable_color": True}
    json.dump(config, open(work + "/" + name + ".json", "w"))
PY
request() {
  python3 - "$work" "$@" <<'PY'
import json, sys
work, verb, rest = sys.argv[1], sys.argv[2], sys.argv[3:]
r = {"verb": verb}
if verb == "start":
    name = rest[0]
    r["config"] = open(work + "/" + name + ".json").read()
    if "names" in rest: r["names"] = "172.19.0.2"
    if "hold" in rest: r["hold"] = {"interface": name, "pass": [a for a in rest[1:] if a[0].isdigit()]}
elif rest:
    r["hold"] = rest[0]
print(json.dumps(r))
PY
}
mkfifo "$work/ask"
"$HELPER" "$SB" <"$work/ask" >"$work/said" 2>"$work/helper.err" &
DAEMON=$!
exec 3>"$work/ask"
ask() {
  local before; before=$(wc -l <"$work/said")
  printf '%s\n' "$1" >&3
  for _ in $(seq 1 150); do [ "$(wc -l <"$work/said")" -gt "$before" ] && break; sleep 0.1; done
  tail -1 "$work/said" | python3 -c '
import json,sys
try: r=json.loads(sys.stdin.readline())
except Exception as e: print("no reply"); sys.exit(0)
print("ok=%s state=%s hold=%s error=%s" % (r.get("ok"), r.get("state"), r.get("hold"), r.get("error")))'
}
tunnel_pid() { tail -1 "$work/said" | python3 -c 'import json,sys; print(json.loads(sys.stdin.readline()).get("pid",0))'; }
# The block as the helper writes it (TrafficHold.swift), and with one rule more
# before its last: what would be refused on a physical interface goes into the tun.
rules_text() {
  local tun="$1" way_in="$2"; shift 2
  echo "pass out quick on lo0 all flags any no state"
  echo "pass quick on $tun all flags any no state"
  echo "pass out quick proto { tcp, udp } from any to any group $GROUP"
  echo "pass out quick inet proto udp from any port 68 to any port 67"
  echo "pass out quick inet6 proto udp from any port 546 to any port 547"
  if [ "$#" -gt 0 ]; then echo "pass out quick inet from any to { $(IFS=,; echo "$*") }"; fi
  echo "block return out quick proto { tcp, udp } from any to any port 53"
  echo "pass out quick inet from any to { 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 169.254.0.0/16, 224.0.0.0/4, 255.255.255.255 }"
  echo "pass out quick inet6 from any to { fe80::/10, fc00::/7, ff02::/16 }"
  case "$way_in" in
    plain) echo "pass out quick route-to ($tun 172.19.0.2) inet proto { tcp, udp } from any to any no state" ;;
    flags) echo "pass out quick route-to ($tun 172.19.0.2) inet proto { tcp, udp } from any to any flags any no state" ;;
  esac
  echo "block return out quick all"
}
load_rules() {
  local out; out=$(rules_text "$@" | pfctl -a "$ANCHOR" -f - 2>&1); local status=$?
  echo "pfctl said $status ${out:+: $(tr '\n' ' ' <<<"$out" | cut -c1-200)}"
}

say "0. nothing up"
note "unbound, asked directly" "$(udp "$LINE_PROBE" -)"
note "bound to $MINE, asked directly" "$(udp "$LINE_PROBE" "$MINE")"
note "a request elsewhere" "$(code_of "http://$ELSEWHERE/")"

say "1. a tun, no block"
echo "   $(ask "$(request start utun225 names)")"
r=$(udp "$OTHER_PROBE" -);       note "unbound, a program the tun has no rule for" "$r | $(flow "$r")"
r=$(udp "$OTHER_PROBE" "$MINE"); note "bound, a program the tun has no rule for" "$r | $(flow "$r")"
r=$(udp "$LINE_PROBE" "$MINE");  note "bound, a line's program" "$r | $(flow "$r")"
before=$(grep -c "$ELSEWHERE:80" "$work/utun225.log" 2>/dev/null)
c=$(bound_code "http://$ELSEWHERE/")
after=$(grep -c "$ELSEWHERE:80" "$work/utun225.log" 2>/dev/null)
note "TCP bound to $MINE" "$c; lines in the tun's log about it: $before -> $after"
echo "   $(ask "$(request stop)")"

say "2. a tun behind the block as the helper loads it"
echo "   $(ask "$(request start utun226 names hold "$SERVER")")"
PID=$(tunnel_pid)
r=$(udp "$LINE_PROBE" -);        note "unbound, a line's program" "$r | $(flow "$r")"
r=$(udp "$LINE_PROBE" "$MINE");  note "bound, a line's program" "$r | $(flow "$r")"
r=$(udp "$OTHER_PROBE" "$MINE"); note "bound, a program the tun has no rule for" "$r | $(flow "$r")"
note "TCP bound to $MINE" "$(bound_code "http://$ELSEWHERE/")"
note "a request elsewhere" "$(code_of "http://$ELSEWHERE/")"

say "3. the same tunnel, the block with a way into the tun for what is left on a physical interface"
note "loaded (flags any)" "$(load_rules utun226 flags "$SERVER")"
if ! pfctl -a "$ANCHOR" -s rules 2>/dev/null | grep -q route-to; then
  note "loaded (no flags)" "$(load_rules utun226 plain "$SERVER")"; WAY=plain
else
  WAY=flags
fi
pfctl -a "$ANCHOR" -s rules 2>/dev/null | grep -n "route-to\|^block" | sed 's/^/   pf: /'
r=$(udp "$LINE_PROBE" "$MINE");  note "bound, a line's program" "$r | $(flow "$r")"
r=$(udp "$OTHER_PROBE" "$MINE"); note "bound, a program the tun has no rule for" "$r | $(flow "$r")"
r=$(udp "$LINE_PROBE" -);        note "unbound, a line's program" "$r | $(flow "$r")"
note "TCP bound to $MINE" "$(bound_code "http://$ELSEWHERE/")"
note "a request elsewhere" "$(code_of "http://$ELSEWHERE/")"
note "the server the block lets out" "$(code_of "http://$SERVER/")"
kill "$LINE" 2>/dev/null; sleep 0.5
r=$(udp "$LINE_PROBE" "$MINE");  note "the line stopped: bound, a line's program" "$r | $(flow "$r")"
r=$(udp "$OTHER_PROBE" "$MINE"); note "the line stopped: bound, another program" "$r | $(flow "$r")"
start_line

say "4. the tunnel's process killed, the rules as they are"
kill -9 "$PID"; sleep 1.2
note "the helper" "$(ask "$(request status)")"
note "the tun's interface" "$(ifconfig utun226 >/dev/null 2>&1 && echo there || echo gone)"
note "rules that send into it" "$(pfctl -a "$ANCHOR" -s rules 2>/dev/null | grep -c route-to)"
note "unbound" "$(udp "$LINE_PROBE" -)"
note "bound" "$(udp "$LINE_PROBE" "$MINE")"
note "a request elsewhere" "$(code_of "http://$ELSEWHERE/")"
note "TCP bound to $MINE" "$(bound_code "http://$ELSEWHERE/")"
note "the server the block lets out" "$(code_of "http://$SERVER/")"

say "5. a way into a tun that never was"
note "loaded" "$(load_rules utun250 "$WAY" "$SERVER")"
note "rules that send into it" "$(pfctl -a "$ANCHOR" -s rules 2>/dev/null | grep -c route-to)"
note "unbound" "$(udp "$LINE_PROBE" -)"
note "bound" "$(udp "$LINE_PROBE" "$MINE")"
note "a request elsewhere" "$(code_of "http://$ELSEWHERE/")"

say "6. released"
echo "   $(ask "$(request release)")"
note "rules standing" "$(rules_standing)"
note "unbound" "$(udp "$LINE_PROBE" -)"
note "bound" "$(udp "$LINE_PROBE" "$MINE")"

say "7. a line's first words after a tunnel starts, behind the block"
echo "   by turns: at once, and after the tun has named the program that asks (as the app waits)"
bad=0
for i in $(seq 1 16); do
  name="utun$((226 + i))"
  reply=$(ask "$(request start "$name" names hold "$SERVER")")
  load_rules "$name" "$WAY" "$SERVER" >/dev/null
  t0=$(now); waited="-"
  if [ $((i % 2)) -eq 0 ]; then
    waited="never"
    for _ in $(seq 1 60); do
      a=$(as_user dig +time=1 +tries=1 +short "$PROBE" @172.19.0.2 2>/dev/null | head -1)
      if [ "$a" = "127.0.0.53" ]; then waited="$(( $(now) - t0 ))ms"; break; fi
      sleep 0.05
    done
  fi
  name_got=$(as_user host -W 2 -t A www.python.org 172.19.0.2 2>&1 | awk '/has address/{print $4; exit}')
  tcp=$(through_line "http://$ELSEWHERE/" 4)
  u=$(udp "$LINE_PROBE" "$MINE")
  took="$(( $(now) - t0 ))ms"
  verdict=ok
  case "$name_got" in 198.1[89].*|"") verdict=BAD ;; esac
  [ "$tcp" = 000 ] && verdict=BAD
  grep -q answered <<<"$u" || verdict=BAD
  [ "$verdict" = BAD ] && bad=$((bad + 1))
  printf '   %2d %-7s wait %-7s name %-16s tcp %s udp %-34s all in %-7s %s\n' "$i" "$name" "$waited" "${name_got:-none}" "$tcp" "$u" "$took" "$verdict"
  if [ "$verdict" = BAD ]; then
    sed -e 's/\x1b\[[0-9;]*m//g' "$work/$name.log" | grep -E "$ELSEWHERE:80|www.python.org|udpprobe|process|ERROR|WARN" | sed -E 's/^[^ ]+ [^ ]+ [^ ]+ //' | cut -c1-170 | head -12 | sed 's/^/        /'
  fi
  ask "$(request stop keep)" >/dev/null
done
echo "   starts that went wrong: $bad of 16"
echo "   $(ask "$(request release)")"

exec 3>&-
kill "$LINE" 2>/dev/null
kill -TERM "$DAEMON" 2>/dev/null
sleep 1
pfctl -a "$ANCHOR" -F all >/dev/null 2>&1
printf 'remove %s\n' "$KEY" | scutil >/dev/null 2>&1
if [ -s "$work/helper.err" ]; then echo "--- the helper said on its error stream"; tail -5 "$work/helper.err" | cut -c1-200; fi
rm -rf "$work"
echo
echo "asked"
