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
PART="${5:-bound}"
LAB_OS="${LAB_OS:-mac}"
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
empties_in() {
  python3 -c 'import os,sys,time,subprocess
os.setsid(); time.sleep(int(sys.argv[1])); subprocess.run(["/sbin/pfctl","-a",sys.argv[2],"-F","all"])' "$1" "$ANCHOR" >/dev/null 2>&1 &
}
empties_in 330

say() { printf '\n== %s\n' "$*"; }
# What was found so far, written where it outlives this machine: a commit status.
mark() {
  local text; text=$(printf '%s' "$2" | tr '\n"\\' '  /' | cut -c1-138)
  [ -n "${GITHUB_TOKEN:-}" ] || return 0
  curl -4 -s -o /dev/null --max-time 6 -X POST \
    -H "Authorization: Bearer $GITHUB_TOKEN" -H "Accept: application/vnd.github+json" \
    "https://api.github.com/repos/$GITHUB_REPOSITORY/statuses/$GITHUB_SHA" \
    -d "{\"state\":\"success\",\"context\":\"lab/$LAB_OS/$1\",\"description\":\"$text\"}" || true
}
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
    config["log"] = {"level": "debug", "output": work + "/" + name + ".log", "timestamp": True}
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

if [ "$PART" = bound ]; then

say "0. nothing up"
a=$(udp "$LINE_PROBE" -); b=$(udp "$LINE_PROBE" "$MINE"); c=$(code_of "http://$ELSEWHERE/")
note "unbound, asked directly" "$a"; note "bound to $MINE, asked directly" "$b"; note "a request elsewhere" "$c"
mark 0-nothing-up "unbound: $a | bound: $b | http $c"

started() {
  local reply; reply=$(ask "$1")
  echo "   $reply" | cut -c1-260
  if [[ "$reply" != ok=True* ]]; then
    tail -1 "$work/said" | python3 -c 'import json,sys; print("   its log:", json.loads(sys.stdin.readline()).get("logTail","")[-600:])'
    mark "$2-did-not-start" "$reply"
    ask "$(request release)" >/dev/null; exit 1
  fi
}
say "1. a tun, no block"
started "$(request start utun225 names)" 1
r1=$(udp "$OTHER_PROBE" -);       f1=$(flow "$r1"); note "unbound, a program the tun has no rule for" "$r1 | $f1"
r2=$(udp "$OTHER_PROBE" "$MINE"); f2=$(flow "$r2"); note "bound, a program the tun has no rule for" "$r2 | $f2"
r3=$(udp "$LINE_PROBE" "$MINE");  f3=$(flow "$r3"); note "bound, a line's program" "$r3 | $f3"
before=$(grep -c "$ELSEWHERE:80" "$work/utun225.log" 2>/dev/null)
c=$(bound_code "http://$ELSEWHERE/")
after=$(grep -c "$ELSEWHERE:80" "$work/utun225.log" 2>/dev/null)
note "TCP bound to $MINE" "$c; lines in the tun's log about it: $before -> $after"
mark 1-tun-no-block "unbound: ${r1#port * } [${f1:0:22}] | bound: ${r2#port * } [${f2:0:22}] | tcp bound $c log $before->$after"
echo "   $(ask "$(request stop)")"

say "2. a tun behind the block as the helper loads it"
started "$(request start utun226 names hold "$SERVER")" 2
PID=$(tunnel_pid)
[ "${PID:-0}" -gt 1 ] || { mark 2-no-pid "the helper named no process"; ask "$(request release)" >/dev/null; exit 1; }
r1=$(udp "$LINE_PROBE" -);        note "unbound, a line's program" "$r1 | $(flow "$r1")"
r2=$(udp "$LINE_PROBE" "$MINE");  note "bound, a line's program" "$r2 | $(flow "$r2")"
r3=$(udp "$OTHER_PROBE" "$MINE"); note "bound, a program the tun has no rule for" "$r3 | $(flow "$r3")"
t=$(bound_code "http://$ELSEWHERE/"); e=$(code_of "http://$ELSEWHERE/")
note "TCP bound to $MINE" "$t"; note "a request elsewhere" "$e"
mark 2-block-as-loaded "unbound line: ${r1#port * } | bound line: ${r2#port * } | bound other: ${r3#port * } | tcp bound $t | elsewhere $e"

say "3. the same tunnel, the block with a way into the tun for what is left on a physical interface"
# Whatever the rule does to this machine, the anchor is emptied in fifty seconds.
empties_in 50
mark 3a-before-the-rule "about to load route-to"
l=$(load_rules utun226 flags "$SERVER"); WAY=flags
if ! pfctl -a "$ANCHOR" -s rules 2>/dev/null | grep -q route-to; then l="$l; $(load_rules utun226 plain "$SERVER")"; WAY=plain; fi
note "loaded" "$l ($WAY)"
pfctl -a "$ANCHOR" -s rules 2>/dev/null | grep -n "route-to\|^block" | sed 's/^/   pf: /'
mark 3b-rule-loaded "$WAY: $l | $(pfctl -a "$ANCHOR" -s rules 2>/dev/null | grep route-to | cut -c1-70)"
e=$(code_of "http://$ELSEWHERE/"); note "a request elsewhere" "$e"
mark 3c-elsewhere "an ordinary request with the rule in place: $e"
r1=$(udp "$LINE_PROBE" "$MINE");  f1=$(flow "$r1"); note "bound, a line's program" "$r1 | $f1"
mark 3d-bound-line "bound udp, a line's program: ${r1#port * } [${f1:0:70}]"
r2=$(udp "$OTHER_PROBE" "$MINE"); f2=$(flow "$r2"); note "bound, a program the tun has no rule for" "$r2 | $f2"
mark 3e-bound-other "bound udp, another program: ${r2#port * } [${f2:0:70}]"
t=$(bound_code "http://$ELSEWHERE/"); note "TCP bound to $MINE" "$t"
mark 3f-bound-tcp "bound tcp: $t | the server the block lets out: $(code_of "http://$SERVER/")"

say "4. the tunnel's process killed, the rules as they are"
kill -9 "$PID"; sleep 1.2
r1=$(udp "$LINE_PROBE" -); r2=$(udp "$LINE_PROBE" "$MINE"); e=$(code_of "http://$ELSEWHERE/" 4); t=$(bound_code "http://$ELSEWHERE/")
note "the tun's interface" "$(ifconfig utun226 >/dev/null 2>&1 && echo there || echo gone)"
note "rules that send into it" "$(pfctl -a "$ANCHOR" -s rules 2>/dev/null | grep -c route-to)"
note "unbound" "$r1"; note "bound" "$r2"; note "a request elsewhere" "$e"; note "TCP bound" "$t"
FOUR="tun $(ifconfig utun226 >/dev/null 2>&1 && echo there || echo gone), route-to rules $(pfctl -a "$ANCHOR" -s rules 2>/dev/null | grep -c route-to) | unbound: ${r1#port * } | bound: ${r2#port * } | elsewhere $e | tcp bound $t"

say "6. released"
echo "   $(ask "$(request release)")"
sleep 1
a=$(udp "$LINE_PROBE" -)
note "rules standing" "$(rules_standing)"; note "unbound" "$a"
mark 4-tunnel-killed "$FOUR"
mark 6-released "rules $(rules_standing) | unbound: ${a#port * }"

else

say "7. a line's first words after a tunnel starts, behind the block as the helper loads it"
echo "   by turns: at once, and after the tun has named the program that asks (as the app waits)"
bad=0; rows=""
for i in $(seq 1 16); do
  name="utun$((226 + i))"
  reply=$(ask "$(request start "$name" names hold "$SERVER")")
  if [[ "$reply" != ok=True* ]]; then echo "   $i $name did not start: ${reply:0:200}"; mark 7-did-not-start "$i: $reply"; break; fi
  t0=$(now); waited="-"
  if [ $((i % 2)) -eq 0 ]; then
    waited="never"
    for _ in $(seq 1 40); do
      a=$(as_user dig +time=1 +tries=1 +short "$PROBE" @172.19.0.2 2>/dev/null | head -1)
      if [ "$a" = "127.0.0.53" ]; then waited="$(( $(now) - t0 ))ms"; break; fi
      sleep 0.05
    done
  fi
  name_got=$(as_user host -W 2 -t A www.python.org 172.19.0.2 2>&1 | awk '/has address/{print $4; exit}')
  tcp=$(through_line "http://$ELSEWHERE/" 4)
  took="$(( $(now) - t0 ))ms"
  verdict=ok
  case "$name_got" in 198.1[89].*|"") verdict=BAD ;; esac
  [ "$tcp" = 000 ] && verdict=BAD
  [ "$verdict" = BAD ] && bad=$((bad + 1))
  printf '   %2d %-7s %-44s wait %-7s name %-16s tcp %s all in %-7s %s\n' "$i" "$name" "${reply:0:44}" "$waited" "${name_got:-none}" "$tcp" "$took" "$verdict"
  rows="$rows$i:${waited}/${name_got:-none}/$tcp "
  if [ "$verdict" = BAD ]; then
    sed -e 's/\x1b\[[0-9;]*m//g' "$work/$name.log" 2>/dev/null | grep -E "$ELSEWHERE:80|www.python.org|process|ERROR|WARN|default interface" | sed -E 's/^[^ ]+ [^ ]+ [^ ]+ //' | cut -c1-170 | head -14 | sed 's/^/        /'
  fi
  ask "$(request stop keep)" >/dev/null
done
echo "   starts that went wrong: $bad of 16"
echo "   $(ask "$(request release)")"
sleep 1
mark 7-first-words "wrong: $bad of 16"
mark 7-rows-a "$(cut -c1-138 <<<"$rows")"
mark 7-rows-b "$(cut -c139-276 <<<"$rows")"

fi

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
