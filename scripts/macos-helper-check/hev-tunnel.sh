#!/bin/bash
# Asks the Mac's tunnel helper for a tunnel under hev, its fifth protocol, on a
# Mac: the helper's own code (every file of desktopApp/tunneldaemon but its
# main, with scripts/macos-helper-check/main.swift), hev as the app's tree
# builds it (scripts/build-hev-macos.sh), and a SOCKS server that stands in for
# the engine and dials nowhere (socks-stand-in.py). The requests are the one
# the app's own test holds the app to
# (sharedUI/src/jvmTest/resources/macos-helper/tun-request.json), with this
# Mac's port, names and exclusions put in. What it asks: the utun hev brings
# up, the routes of both families the helper lays through it and around what
# it is told to keep out, and that nothing of either is left when hev is
# killed outright or the helper is stopped as launchd stops it.
#
# Run as root, on a machine that can lose its network for a moment: it takes
# the Mac's routes into a tun that answers itself, points the system's
# resolver at it and kills it. A CI runner, or a Mac of your own with nothing
# else going on. Whatever happens, a trap takes away what it leaves: the
# helper, hev and with it its utun and every route through it, the resolver's
# key and the block; and fails the check if a utun of it is still there. A
# Mac on which hev cannot make its utun fails here; it is never a skip.
#
#   sudo scripts/macos-helper-check/hev-tunnel.sh ./helper-check ./hev-socks5-tunnel "$USER"
set -u
HELPER="$1"
HEV="$2"
WHO="$3"
WHO_UID="$(id -u "$WHO")"
WHO_GID="$(id -g "$WHO")"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
FIXTURE="$root/sharedUI/src/jvmTest/resources/macos-helper/tun-request.json"
ANCHOR='com.apple/100.org.olcbox.app.hold'
KEY='State:/Network/Service/org.olcbox.app.tunnel/DNS'
STATE='/Library/Application Support/org.olcbox.app'
GROUP=7302243
PORT=18835
LOGIN_USER='gl-check'
LOGIN_PASSWORD="it's a pass word"
work="$(cd /tmp && pwd -P)/ghostlane-hev-check.$$"
mkdir -p "$work"
chmod 755 "$work"

failed=0
say() { printf '\n== %s\n' "$*"; }
# expect <what is expected> <what was found> <a pattern it has to match>
expect() {
  if [[ "$2" =~ $3 ]]; then
    printf '   ok    %s: %s\n' "$1" "$2"
  else
    printf '   FAIL  %s: found "%s", expected to match %s\n' "$1" "$2" "$3"
    failed=1
  fi
}
# A command as the unprivileged user, without sudo: sudo looks its host's name up first.
as_user() { python3 -c 'import os,sys; os.setgid(int(sys.argv[2])); os.setuid(int(sys.argv[1])); os.execvp(sys.argv[3], sys.argv[3:])' "$WHO_UID" "$WHO_GID" "$@"; }

leave() {
  exec 3>&- 2>/dev/null
  [ -n "${DAEMON:-}" ] && kill -9 "$DAEMON" 2>/dev/null
  # hev killed outright takes its utun, and every route through it, with it:
  # the routes are the helper's, and this script lays none of its own.
  pkill -9 -f "$STATE/bin/hev-socks5-tunnel" 2>/dev/null
  [ -n "${STAND:-}" ] && kill "$STAND" 2>/dev/null
  printf 'remove %s\n' "$KEY" | scutil >/dev/null 2>&1
  pfctl -a "$ANCHOR" -F all >/dev/null 2>&1
  # The names hev made up are not to be the next check's answers.
  dscacheutil -flushcache 2>/dev/null
  killall -HUP mDNSResponder 2>/dev/null
  local left=0 t
  sleep 0.5
  for t in utun235 utun236 utun237; do
    if ifconfig "$t" >/dev/null 2>&1; then echo "LEFT: $t is still there"; left=1; fi
  done
  if netstat -rn | grep -qE ' utun23[5-7] *$| utun23[5-7] '; then echo "LEFT: routes through the check's utuns"; left=1; fi
  rm -rf "$work"
  [ "$left" -eq 0 ] || exit 1
}
trap leave EXIT

up_holding() { ifconfig "$1" 2>/dev/null | awk '/flags=/{up=($0 ~ /<UP/)} /inet 172\.19\.0\.1 /{v4=1} /inet6 fdfe:dcba:9876::1 /{v6=1} /mtu /{for(i=1;i<NF;i++) if($i=="mtu") m=$(i+1)} END{printf "up=%s ipv4=%s ipv6=%s mtu=%s", up?"yes":"no", v4?"yes":"no", v6?"yes":"no", m?m:"none"}'; }
gone() { if ifconfig "$1" >/dev/null 2>&1; then echo there; else echo gone; fi; }
hev_running() { pgrep -f "$STATE/bin/hev-socks5-tunnel" >/dev/null && echo running || echo none; }
alive() { kill -0 "$1" 2>/dev/null && echo there || echo gone; }
# The interface the system sends [address] out of: via [-inet6] <address>
via() { route -n get "$@" 2>/dev/null | awk '/interface:/{print $2; found=1} END{if(!found) print "none"}'; }
# Whether that is [utun]: an address kept out of the tun goes wherever the machine sent it.
into() { local i; i=$(via "${@:2}"); [ "$i" = "$1" ] && echo "into the tun" || echo "not into the tun ($i)"; }
routes_through() { netstat -rn | grep -cE " $1 *\$| $1 "; }
key_gone() { printf 'show %s\n' "$KEY" | scutil 2>/dev/null | grep -c ServerAddresses; }
# Who answers a plain request as the user: the stand-in (what it says), a server
# of its own (its status), or nobody.
answered() {
  local out code n
  for n in 1 2 3; do
    out=$(as_user curl -g -s --max-time 5 -w '\n%{http_code}' "$@" 2>/dev/null)
    code=$(printf '%s' "$out" | tail -1)
    [ -n "$code" ] && [ "$code" != 000 ] && break
    sleep 1
  done
  case "$out" in
    "stand-in saw "*) printf '%s\n' "$out" | head -1 ;;
    *) if [ -n "$code" ] && [ "$code" != 000 ]; then echo "a server, $code"; else echo nobody; fi ;;
  esac
}
# How many routes the helper lays around [holes]: the whole of each family but
# its first /8, less the holes, in the fewest prefixes. Counted apart from the
# helper's own code, by Python's.
plan_count() {
  python3 -I - "$@" <<'COUNT'
import ipaddress, sys
def left(whole, holes):
    inside = [h for h in holes if h.overlaps(whole)]
    if not inside: return [whole]
    if any(h.prefixlen <= whole.prefixlen for h in inside): return []
    low, high = whole.subnets(prefixlen_diff=1)
    return left(low, inside) + left(high, inside)
holes = [ipaddress.ip_network(a, strict=False) for a in sys.argv[1:]]
v4 = [ipaddress.ip_network("0.0.0.0/8")] + [h for h in holes if h.version == 4]
v6 = [ipaddress.ip_network("::/8")] + [h for h in holes if h.version == 6]
print(len(left(ipaddress.ip_network("0.0.0.0/0"), v4)) + len(left(ipaddress.ip_network("::/0"), v6)))
COUNT
}

say "the machine"
sw_vers | tr '\n' ' '; echo
IF=$(route -n get default | awk '/interface:/{print $2}')
GATEWAY=$(route -n get default | awk '/gateway:/{print $2}')
echo "way out: $IF by $GATEWAY, $(uname -m)"
expect "hev is a program for this Mac" "$("$HEV" --version 2>&1 | grep -c '^Version: ')" '^1$'
# A server that answers, to keep out of the tunnel, asked before there is one.
SERVER=$(dig +short +time=3 +tries=2 example.com A | grep -E '^[0-9.]+$' | head -1)
expect "a server to keep out of the tunnel" "$SERVER" '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$'
expect "which answers" "$(answered -4 "http://$SERVER/")" '^a server, [1-5][0-9][0-9]$'
NAME="hev-check-$$.example.org"

# The engine's stand-in, as the user, with the login the requests carry.
: > "$work/stand-in.log"
chown "$WHO" "$work/stand-in.log"
as_user python3 "$here/socks-stand-in.py" "$PORT" "$LOGIN_USER" "$LOGIN_PASSWORD" "$work/stand-in.log" &
STAND=$!
for _ in $(seq 1 50); do grep -q '^listening' "$work/stand-in.log" && break; sleep 0.1; done
expect "the stand-in listens" "$(head -1 "$work/stand-in.log")" "^listening on 127\.0\.0\.1:$PORT\$"

# request tun <utun> [names] [hold] [exclude:<a>,<b>...] [set:<field>=<json>]... | request <verb> [keep]
request() {
  python3 - "$FIXTURE" "$PORT" "$LOGIN_USER" "$LOGIN_PASSWORD" "$@" <<'PY'
import json, sys
fixture, port, user, password, verb, rest = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4], sys.argv[5], sys.argv[6:]
if verb != "tun":
    r = {"verb": verb}
    if rest: r["hold"] = rest[0]
    print(json.dumps(r)); sys.exit(0)
r = json.load(open(fixture))
name = rest[0]
r["tun"]["name"] = name
r["socks"].update(port=port, username=user, password=password)
for key in ("names", "hold", "exclude"):
    r.pop(key, None)
for word in rest[1:]:
    if word == "names": r["names"] = r["mapdns"]["address"]
    elif word == "hold": r["hold"] = {"interface": name, "pass": []}
    elif word.startswith("exclude:"): r["exclude"] = word[8:].split(",")
    elif word.startswith("set:"):
        path, value = word[4:].split("=", 1)
        where = r
        *outer, last = path.split(".")
        for part in outer: where = where.setdefault(part, {})
        where[last] = json.loads(value)
print(json.dumps(r))
PY
}
mkfifo "$work/ask"
"$HELPER" /nonexistent/sing-box "$WHO_UID" "$HEV" <"$work/ask" >"$work/said" 2>"$work/helper.err" &
DAEMON=$!
exec 3>"$work/ask"
# ask <a request's line>: sends it and prints the reply's facts on one line
ask() {
  local before; before=$(wc -l <"$work/said")
  printf '%s\n' "$1" >&3
  for _ in $(seq 1 150); do [ "$(wc -l <"$work/said")" -gt "$before" ] && break; sleep 0.1; done
  tail -1 "$work/said" | python3 -c '
import json,sys
try: r=json.loads(sys.stdin.readline())
except Exception as e: print("no reply"); sys.exit(0)
print("ok=%s state=%s hold=%s protocol=%s tunnel=%s routes=%s error=%s" % (r.get("ok"), r.get("state"), r.get("hold"), r.get("protocol"), r.get("tunnel", "none"), r.get("routes", "none"), r.get("error")))'
}
said() { tail -1 "$work/said" | python3 -c 'import json,sys; print(json.loads(sys.stdin.readline()).get(sys.argv[1], ""))' "$1"; }

say "at rest"
expect "the helper speaks the fifth protocol and runs no tunnel" "$(ask "$(request status)")" '^ok=True state=idle hold=False protocol=5 tunnel=none routes=0 '

say "a request that names a script, or anything but hev's fields, is refused and starts nothing"
cat > "$work/up.sh" <<SH
#!/bin/sh
touch "$work/a-script-ran"
SH
chmod 755 "$work/up.sh"
expect "a script in the tun" "$(ask "$(request tun utun235 "set:tun.post-up-script=\"$work/up.sh\"")")" '^ok=False .*no script.* tun\.post-up-script is refused'
expect "a script beside it" "$(ask "$(request tun utun235 "set:pre-down-script=\"$work/up.sh\"")")" '^ok=False .* pre-down-script is refused'
expect "a config of its own" "$(ask "$(request tun utun235 'set:config="tunnel:\n  name: utun235\n"')")" '^ok=False .*no field config'
expect "a login that ends a line and writes a key after it" "$(ask "$(request tun utun235 "set:socks.password=\"x'\\n  post-up: $work/up.sh\"")")" '^ok=False .*SOCKS login'
expect "a SOCKS port elsewhere than the loopback" "$(ask "$(request tun utun235 'set:socks.address="192.0.2.1"')")" '^ok=False .*loopback'
expect "an interface that is not a utun" "$(ask "$(request tun utun235 'set:tun.name="en0"')")" '^ok=False .*not a utun'
expect "a name to keep out" "$(ask "$(request tun utun235 'exclude:example.com')")" '^ok=False .*keeps out addresses and networks'
expect "no script ran" "$([ -e "$work/a-script-ran" ] && echo ran || echo none)" '^none$'
expect "no hev runs" "$(hev_running)" '^none$'
expect "and no utun was made" "$(gone utun235)" '^gone$'
expect "nor a route laid" "$(via 192.0.2.10)" "^$IF\$"

say "a tunnel under hev, around a server of each family"
ROUTES=$(plan_count "$SERVER/32" 2001:db8::7/128)
expect "it runs, through the routes laid around them" "$(ask "$(request tun utun235 "exclude:$SERVER/32,2001:db8::7/128")")" "^ok=True state=running hold=False protocol=5 tunnel=hev routes=$ROUTES "
PID=$(said pid)
expect "the helper's own copy of hev is the process" "$(ps -o command= -p "$PID" 2>&1)" "^$STATE/bin/hev-socks5-tunnel $STATE/hev\.yml\$"
expect "as root, in root's group" "$(ps -o uid=,gid= -p "$PID" | tr -s ' ' | sed 's/^ //')" '^0 0$'
expect "the copy is root's and nobody else's" "$(stat -f '%Su %Sp' "$STATE/bin/hev-socks5-tunnel" 2>&1)" '^root -rwx------$'
expect "and so is its config, which holds the login" "$(stat -f '%Su %Sp' "$STATE/hev.yml" 2>&1)" '^root -rw-------$'
expect "which names no script" "$(grep -c script "$STATE/hev.yml")" '^0$'
expect "the utun is the one named, up with both addresses" "$(up_holding utun235)" '^up=yes ipv4=yes ipv6=yes mtu=1500$'
expect "an address goes into it" "$(into utun235 192.0.2.10)" '^into the tun$'
expect "an address of the other half too" "$(into utun235 1.1.1.1)" '^into the tun$'
expect "and an IPv6 one" "$(into utun235 -inet6 2001:db8::10)" '^into the tun$'
expect "the server kept out goes where it went" "$(via "$SERVER")" "^$IF\$"
expect "and so does the IPv6 one" "$(into utun235 -inet6 2001:db8::7)" '^not into the tun'
expect "the local network stays where it is" "$(via "$GATEWAY")" "^$IF\$"
expect "what goes in reaches the SOCKS port, with the login" "$(answered -4 http://192.0.2.10/)" '^stand-in saw 192\.0\.2\.10:80$'
expect "and IPv6 too" "$(answered -6 'http://[2001:db8::10]/')" '^stand-in saw \[2001:db8::10\]:80$'
expect "the server kept out answers by itself" "$(answered -4 "http://$SERVER/")" '^a server, [1-5][0-9][0-9]$'
expect "and was never asked of the stand-in" "$(grep -c "^connect $SERVER:" "$work/stand-in.log")" '^0$'
expect "the stand-in was asked with the login" "$(grep -c "^connect 192\.0\.2\.10:80 as $LOGIN_USER\$" "$work/stand-in.log")" '^[1-9]'
expect "and turned nobody away" "$(grep -c '^refused' "$work/stand-in.log")" '^0$'
expect "a script asked for while it runs is refused" "$(ask "$(request tun utun236 "set:tun.pre-down-script=\"$work/up.sh\"")")" '^ok=False .*pre-down-script is refused'
expect "and costs the tunnel nothing" "$(ask "$(request status)")" "^ok=True state=running hold=False protocol=5 tunnel=hev routes=$ROUTES "
expect "the same hev" "$(said pid)" "^$PID\$"

say "another tunnel in its place, which the system asks for names"
expect "it runs, keeping nothing out" "$(ask "$(request tun utun236 names)")" "^ok=True state=running hold=False protocol=5 tunnel=hev routes=$(plan_count) "
expect "in place of the first one, and none of its routes is left" "$(gone utun235) $(alive "$PID") $(routes_through utun235)" '^gone gone 0$'
PID=$(said pid)
expect "up as it was asked" "$(up_holding utun236)" '^up=yes ipv4=yes ipv6=yes mtu=1500$'
expect "everything goes into it" "$(into utun236 "$SERVER")" '^into the tun$'
expect "the system asks it for names" "$(scutil --dns | awk '/nameserver\[0\]/{print $3; exit}')" '^172\.19\.0\.2$'
expect "hev answers a name with an address of its own" "$(as_user dig +short +time=2 +tries=2 "$NAME" A @172.19.0.2 2>&1 | head -1)" '^198\.1[89]\.[0-9]+\.[0-9]+$'
expect "and hands the name to the SOCKS port" "$(answered -4 "http://$NAME/")" "^stand-in saw $NAME:80\$"
expect "stopped" "$(ask "$(request stop)")" '^ok=True state=idle hold=False protocol=5 tunnel=none routes=0 '
expect "hev is gone" "$(alive "$PID") $(hev_running)" '^gone none$'
expect "and its utun, with every route through it" "$(gone utun236) $(routes_through utun236)" '^gone 0$'
expect "the machine sends where it sent before" "$(via 192.0.2.10)" "^$IF\$"
expect "the key is gone" "$(key_gone)" '^0$'

say "hev killed outright"
expect "a tunnel runs, which the system asks for names" "$(ask "$(request tun utun235 names "exclude:$SERVER/32")")" "^ok=True state=running hold=False protocol=5 tunnel=hev routes=$(plan_count "$SERVER/32") "
PID=$(said pid)
kill -9 "$PID"
for _ in $(seq 1 30); do [ "$(alive "$PID")" = gone ] && ! ifconfig utun235 >/dev/null 2>&1 && break; sleep 0.1; done
expect "hev and its utun are gone, and every route through it" "$(alive "$PID") $(gone utun235) $(routes_through utun235)" '^gone gone 0$'
settled=""
for _ in $(seq 1 30); do settled=$(ask "$(request status)"); case "$settled" in *"state=idle"*) break ;; esac; sleep 0.1; done
expect "the helper says so, and keeps no routes" "$settled" '^ok=True state=idle hold=False protocol=5 tunnel=none routes=0 '
expect "the machine sends where it sent before" "$(via 192.0.2.10)" "^$IF\$"
expect "the key is gone" "$(key_gone)" '^0$'
expect "and the server answers as before" "$(answered -4 "http://$SERVER/")" '^a server, [1-5][0-9][0-9]$'

say "a tunnel under hev behind the block"
expect "it runs, and the block stands" "$(ask "$(request tun utun237 hold)")" '^ok=True state=running hold=True protocol=5 tunnel=hev '
PID=$(said pid)
expect "hev runs in the group the block lets out" "$(ps -o gid= -p "$PID" | tr -d ' ')" "^$GROUP\$"
expect "the block is written for its utun" "$(pfctl -a "$ANCHOR" -s rules 2>/dev/null | grep -c 'pass quick on utun237 all')" '^1$'
expect "a block for another utun is refused" "$(ask "$(request tun utun236 'set:hold={"interface":"utun235","pass":[]}')")" '^ok=False .*own interface'
expect "stopped, and the block with it" "$(ask "$(request stop)")" '^ok=True state=idle hold=False protocol=5 tunnel=none routes=0 '
expect "its utun is gone, and every route through it" "$(gone utun237) $(routes_through utun237)" '^gone 0$'

say "the helper stopped as launchd stops it"
expect "a tunnel runs behind the block, which the system asks for names" "$(ask "$(request tun utun235 names hold "exclude:$SERVER/32")")" '^ok=True state=running hold=True protocol=5 tunnel=hev routes=[1-9]'
PID=$(said pid)
kill -TERM "$DAEMON"
# Reaped by the wait, which a guard ends if the helper does not.
( sleep 15; kill -9 "$DAEMON" 2>/dev/null ) &
GUARD=$!
wait "$DAEMON"
ended=$?
kill "$GUARD" 2>/dev/null
expect "the helper ends, as asked" "$ended" '^0$'
DAEMON=""
exec 3>&-
expect "and leaves no hev" "$(alive "$PID") $(hev_running)" '^gone none$'
expect "no utun, no route through it" "$(gone utun235) $(routes_through utun235)" '^gone 0$'
expect "the machine sends where it sent before" "$(via 192.0.2.10)" "^$IF\$"
expect "no key" "$(key_gone)" '^0$'
expect "and no block" "$(pfctl -a "$ANCHOR" -s rules 2>/dev/null | grep -c .)" '^0$'

say "what the helper's error stream says"
if [ -s "$work/helper.err" ]; then tail -5 "$work/helper.err" | cut -c1-200; else echo "(nothing)"; fi
echo
[ "$failed" -eq 0 ] && echo "the helper runs hev as the document says" || echo "the helper does not run hev as the document says"
exit "$failed"
