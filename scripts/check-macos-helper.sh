#!/bin/bash
# Asks the Mac's tunnel helper what it does, on a Mac: its own code
# (desktopApp/tunneldaemon), built with a main that takes requests on standard
# input (scripts/macos-helper-check), handed the config the app's builder
# writes for a tunnel under the kill switch and a stand-in for a line, and
# held to what docs/macos-tunnel-daemon.md says of it.
#
# Run as root, on a machine that can lose its network for a moment: it loads a
# block into the packet filter, brings a tun up over the default route, points
# the system's resolver at it, and kills it. A CI runner, or a Mac of your own
# with nothing else going on. Whatever happens, the block and the resolver's
# key are taken away again three minutes in.
#
#   swiftc … -o helper-check desktopApp/tunneldaemon/{PeerAuthority,GroupExec,TunnelChild,TrafficHold,SystemNames,LineChild,Daemon}.swift \
#       scripts/macos-helper-check/main.swift
#   cc -O2 -o stand-in-line scripts/macos-helper-check/stand-in-line.c
#   sudo scripts/check-macos-helper.sh ./helper-check /path/to/sing-box "$USER" ./stand-in-line
set -u
HELPER="$1"
SB="$2"
WHO="$3"
STAND_IN_BUILT="$4"
WHO_UID="$(id -u "$WHO")"
WHO_GID="$(id -g "$WHO")"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REFERENCE="$root/sharedUI/src/jvmTest/resources/reference-configs/desktop-tun-macos-names.json"
ANCHOR='com.apple/100.org.olcbox.app.hold'
KEY='State:/Network/Service/org.olcbox.app.tunnel/DNS'
LINES='/Library/Application Support/org.olcbox.app.lines'
PROBE='names-its-programs.ghostlane.invalid'
GROUP=7302243
work="$(cd /tmp && pwd -P)/ghostlane-helper-check.$$"
mkdir -p "$work/line"
chmod 755 "$work"
( sleep 180; pfctl -a "$ANCHOR" -F all; printf 'remove %s\n' "$KEY" | scutil ) >/dev/null 2>&1 &

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
within() { local s="$1"; shift; perl -e 'alarm shift; exec @ARGV' "$s" "$@"; }
# A command as the unprivileged user, without sudo: sudo looks its host's name up first.
as_user() { python3 -c 'import os,sys; os.setgid(int(sys.argv[2])); os.setuid(int(sys.argv[1])); os.execvp(sys.argv[3], sys.argv[3:])' "$WHO_UID" "$WHO_GID" "$@"; }
# The answer's code, by address; 000 is no answer. Up to three tries for a yes, one for a no.
code_of() {
  local url="$1" tries="${2:-1}" out=000 n
  for n in $(seq 1 "$tries"); do
    out=$(as_user curl -4 -s -o /dev/null -w '%{http_code}' --max-time 5 "$url" 2>/dev/null)
    [ "${out:-000}" != 000 ] && break
    sleep 1
  done
  printf '%s' "${out:-000}"
}
name_by_system() { within 4 dscacheutil -q host -a name "$1" 2>&1 | awk '/ip_address/{print $2}' | head -1 | grep . || echo none; }
rules_standing() { pfctl -a "$ANCHOR" -s rules 2>/dev/null | grep -c .; }
key_standing() { printf 'show %s\n' "$KEY" | scutil 2>/dev/null | grep -c ServerAddresses; }
first_resolver() { scutil --dns | awk '/nameserver\[0\]/{print $3; exit}'; }
# What a line's binary is told when it asks the tun for a name: a real address,
# or one of the tunnel's own, which is what a program the tun did not name
# gets. The tun misses a flow now and then (docs/macos-tunnel-daemon.md), so
# up to three asks.
real_name() {
  local a="" n
  for n in 1 2 3; do
    a=$(as_user host -W 3 -t A "$1" 172.19.0.2 2>&1 | awk '/has address/{print $4; exit}')
    case "$a" in ""|198.1[89].*) sleep 0.5 ;; *) printf 'real %s' "$a"; return ;; esac
  done
  if [ -n "$a" ]; then printf 'an address of the tunnel, %s' "$a"; else printf 'no answer'; fi
}
# The last line of a file that matches, waited for up to six seconds.
wait_for() {
  local found=""
  for _ in $(seq 1 60); do found=$(grep -E "$1" "$2" 2>/dev/null | tail -1); [ -n "$found" ] && break; sleep 0.1; done
  printf '%s' "${found:-nothing}"
}
# How many lines of a file match, once so many do or six seconds have passed.
count_of() {
  local found=0
  for _ in $(seq 1 60); do found=$(grep -cE "$1" "$2" 2>/dev/null); [ "${found:-0}" -ge "$3" ] && break; sleep 0.1; done
  printf '%s' "${found:-0}"
}

say "the machine"
sw_vers | tr '\n' ' '; echo
IF=$(route -n get default | awk '/interface:/{print $2}')
SERVER=$(dig +short +time=3 +tries=2 example.com A | grep -E '^[0-9.]+$' | head -1)
ELSEWHERE=$(dig +short +time=3 +tries=2 one.one.one.one A | grep -E '^[0-9.]+$' | head -1)
RESOLVER=$(first_resolver)
JAVA="$(/usr/libexec/java_home 2>/dev/null)/bin/java"
[ -x "$JAVA" ] || JAVA="$(command -v java)"
JAVA_REAL="$(python3 -c 'import os,sys; print(os.path.realpath(sys.argv[1]))' "$JAVA")"
MINE=$(ipconfig getifaddr "$IF")
# A stand-in for a line's engine, where a line's binaries lie: the user's own.
STAND_IN="$work/line/stand-in-line"
cp "$STAND_IN_BUILT" "$STAND_IN"
STUN_IP=""; STUN_PORT=""
for candidate in "stun.cloudflare.com 3478" "stun.l.google.com 19302" "stun1.l.google.com 19302"; do
  ip=$(dig +short +time=3 +tries=2 "${candidate% *}" A | grep -E '^[0-9.]+$' | head -1)
  [ -n "$ip" ] || continue
  if "$STAND_IN" "$MINE" "$ip" "${candidate#* }" once | grep -q 'bound answered'; then STUN_IP="$ip"; STUN_PORT="${candidate#* }"; break; fi
done
echo "way out: $IF, this Mac's address on it: $MINE; the server a block is told to let out: $SERVER; elsewhere: $ELSEWHERE; the system asks $RESOLVER"
expect "a socket bound to the way out's address is answered to begin with" "${STUN_IP:-nobody answers}" '^[0-9.]+$'
echo "the app stands in as $JAVA_REAL"
expect "the machine has a network to begin with" "$(code_of "http://$ELSEWHERE/" 3)" '^[1-5][0-9][0-9]$'

# A stand-in for a line: a sing-box of the user's that dials for itself, as a
# line's core dials its server. It is let out of the tun by the rule on its
# binary, which is what a line that comes later in a session relies on.
cp "$SB" "$work/line/sing-box"
chown -R "$WHO" "$work/line"
cat > "$work/line/line.json" <<JSON
{"log":{"level":"warn"},
 "inbounds":[{"type":"socks","tag":"in","listen":"127.0.0.1","listen_port":18821}],
 "outbounds":[{"type":"direct","tag":"direct"}]}
JSON
as_user "$work/line/sing-box" run -c "$work/line/line.json" >"$work/line/log" 2>&1 &
LINE=$!
sleep 1

# The builder's own output with what is this Mac's put in: the port the line
# listens on, the interface that carries the internet, the binaries that stand
# in for a line's and for the app, and the address the tun keeps out of itself.
python3 - "$work" "$IF" "$JAVA_REAL" "$SERVER" "$REFERENCE" <<'PY'
import json, sys
work, way_out, app, server, reference = sys.argv[1:6]
config = json.load(open(reference))
tun = config["inbounds"][0]
tun["route_exclude_address"] = [server + "/32"]
out, direct = config["outbounds"]
out["server_port"] = 18821
direct["bind_interface"] = way_out
lines = [work + "/line/sing-box", "/usr/bin/host"]
rules = config["dns"]["rules"]
assert rules[0]["action"] == "predefined" and rules[2]["server"] == "dns-direct", rules[:3]
rules[0]["process_path"] = [app]
rules[2]["process_path"] = lines
route = config["route"]["rules"]
assert [r.get("action", "out") for r in route] == ["sniff", "hijack-dns", "out", "reject"], route
route[2]["process_path"] = lines
for name in ("utun225", "utun226", "utun227"):
    tun["interface_name"] = name
    json.dump(config, open(work + "/" + name + ".json", "w"))
PY
# request <verb> [<the tun's name> [names] [hold] [addresses to let out…]] | [keep]
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
elif verb == "line":
    r["program"], r["output"], r["arguments"] = rest[0], rest[1], rest[2:]
elif rest:
    r["hold"] = rest[0]
print(json.dumps(r))
PY
}
mkfifo "$work/ask"
"$HELPER" "$SB" "$WHO_UID" <"$work/ask" >"$work/said" 2>"$work/helper.err" &
DAEMON=$!
exec 3>"$work/ask"
# ask <a request's line>: sends it and prints the reply's facts on one line: ok state hold protocol error
ask() {
  local before; before=$(wc -l <"$work/said")
  printf '%s\n' "$1" >&3
  for _ in $(seq 1 150); do [ "$(wc -l <"$work/said")" -gt "$before" ] && break; sleep 0.1; done
  tail -1 "$work/said" | python3 -c '
import json,sys
try: r=json.loads(sys.stdin.readline())
except Exception as e: print("no reply"); sys.exit(0)
print("ok=%s state=%s hold=%s protocol=%s line=%s error=%s" % (r.get("ok"), r.get("state"), r.get("hold"), r.get("protocol"), "yes" if r.get("line") else "no", r.get("error")))'
}
line_pid() { tail -1 "$work/said" | python3 -c 'import json,sys; print(json.loads(sys.stdin.readline()).get("line",0))'; }
tunnel_pid() { tail -1 "$work/said" | python3 -c 'import json,sys; print(json.loads(sys.stdin.readline()).get("pid",0))'; }
the_app_asks() { ( cd "$root/scripts/macos-helper-check" && as_user "$JAVA" AskTheTun.java "$PROBE" 2>&1 | tail -1 ); }

say "at rest"
expect "the helper speaks the fourth protocol, holds nothing and runs no line" "$(ask "$(request status)")" '^ok=True state=idle hold=False protocol=4 line=no '
expect "no rule stands" "$(rules_standing)" '^0$'

say "a line that came before the block"
# The order of a first connect to a room: its engine is run when there is no
# tunnel and no block, joins its room and holds that connection, and the start
# that follows puts the block in place over it.
HELD="$work/line/held"
: > "$HELD"; chown "$WHO" "$HELD"; chmod 600 "$HELD"
expect "the helper runs a line with no tunnel and no block" "$(ask "$(request line "$STAND_IN" "$HELD" "$MINE" "$STUN_IP" "$STUN_PORT" held "$ELSEWHERE" 80 one.one.one.one)")" '^ok=True state=idle hold=False protocol=4 line=yes '
expect "it holds one connection open and is answered over it, time after time" "$(count_of '^held answered' "$HELD" 3) answers, $(grep -c '^held broken' "$HELD") broken" '^[3-9] answers, 0 broken$'
expect "a tunnel is started after it, behind a block" "$(ask "$(request start utun225 names hold "$SERVER")")" '^ok=True state=running hold=True protocol=4 line=yes '
: > "$HELD"
expect "the connection that is older than the block is answered still" "$(wait_for '^held' "$HELD")" '^held answered$'
expect "and goes on being answered" "$(count_of '^held answered' "$HELD" 4) answers, $(grep -c '^held broken' "$HELD") broken" '^[4-9] answers, 0 broken$'
expect "what it binds to the way out is answered under the block" "$(wait_for '^bound' "$HELD")" '^bound answered$'
expect "stopped, and the block with it" "$(ask "$(request stop)")" '^ok=True state=idle hold=False '
expect "the line stopped by the helper" "$(ask "$(request line-stop)")" '^ok=True state=idle hold=False protocol=4 line=no '
expect "no rule stands" "$(rules_standing)" '^0$'

say "a start behind the block, with names"
expect "the tunnel is running and the block stands" "$(ask "$(request start utun225 names hold "$SERVER")")" '^ok=True state=running hold=True '
PID=$(tunnel_pid)
expect "the tunnel runs in the group the block lets out" "$(ps -o gid= -p "$PID" | tr -d ' ')" "^$GROUP\$"
expect "it took the name the block was written for" "$(ifconfig utun225 2>/dev/null | grep -c 'inet 172.19.0.1 ')" '^1$'
RULES="$(pfctl -a "$ANCHOR" -s rules 2>/dev/null)"
expect "the block lets the tunnel's interface out" "$(grep -c 'pass quick on utun225 all' <<<"$RULES")" '^1$'
expect "and the tunnel's own process, by its group" "$(grep -c "group = $GROUP" <<<"$RULES")" '^3$'
expect "one of the three for what its group had open before the block" "$(grep "group = $GROUP" <<<"$RULES" | grep -c 'proto tcp .* no state *$')" '^1$'
expect "and the server the tun keeps out of itself, as new and as open before" "$(grep -c "to $SERVER" <<<"$RULES") rules, $(grep "to $SERVER" <<<"$RULES" | grep -c 'no state *$') without state" '^2 rules, 1 without state$'
expect "and refuses port 53 before it lets the local network out" "$(awk '/port = 53/{refused=NR} /__automatic/ && !local{local=NR} END{print (refused && local && refused < local) ? "before" : "not before"}' <<<"$RULES")" '^before$'
expect "and ends in a refusal of everything else" "$(tail -1 <<<"$RULES")" '^block return out quick all$'
expect "the system asks the tunnel for names" "$(first_resolver)" '^172\.19\.0\.2$'
expect "the tun names the app's own program, asked as the app asks" "$(the_app_asks)" '^answered 127\.0\.0\.53 '
expect "and gives nobody else that answer" "$(as_user dig +time=2 +tries=1 "$PROBE" @172.19.0.2 2>&1 | awk '/status:/{print $6}' | tr -d ',')" '^NXDOMAIN$'
expect "a request elsewhere is carried by the tunnel" "$(code_of "http://$ELSEWHERE/" 3)" '^[1-5][0-9][0-9]$'
expect "a request to the server leaves beside it" "$(code_of "http://$SERVER/" 3)" '^[1-5][0-9][0-9]$'
expect "a name asked by the system gets an address of the tunnel's" "$(name_by_system www.debian.org)" '^198\.1[89]\.'
expect "a name asked of the tun by a line's binary gets a real one, in three asks at most" "$(real_name www.python.org)" '^real [0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$'
expect "the router is not asked beside the tunnel" "$(as_user dig +time=1 +tries=1 +short example.org "@$RESOLVER" 2>&1 | grep -cE '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$')" '^0$'
expect "through the inbound the app checks a tunnel by" "$(as_user curl -s -o /dev/null -w '%{http_code}' --max-time 6 --socks5 127.0.0.1:10811 "http://$ELSEWHERE/" 2>/dev/null)" '^[1-5][0-9][0-9]$'

say "a line's program behind the block"
SAID="$work/line/said"
: > "$SAID"; chown "$WHO" "$SAID"; chmod 600 "$SAID"
expect "started by its user, what it binds to the way out is refused" "$(as_user "$STAND_IN" "$MINE" "$STUN_IP" "$STUN_PORT" once | tail -1)" '^bound no answer$'
expect "the helper runs it" "$(ask "$(request line "$STAND_IN" "$SAID" "$MINE" "$STUN_IP" "$STUN_PORT")")" '^ok=True state=running hold=True protocol=4 line=yes '
LPID=$(line_pid)
expect "as the user who asked, in the group the block lets out" "$(ps -o uid=,gid= -p "$LPID" | tr -s ' ' | sed 's/^ //')" "^$WHO_UID $GROUP\$"
expect "from a copy that is root's and nobody else's to change" "$(stat -f '%Su %Sp' "$LINES/stand-in-line" 2>&1)" '^root -rwxr-xr-x$'
expect "which is the program that runs" "$(ps -o command= -p "$LPID" | cut -c1-70)" "^$LINES/stand-in-line "
expect "it says who it is where the app reads" "$(wait_for '^runs as' "$SAID")" "^runs as uid=$WHO_UID gid=$GROUP\$"
expect "and what it binds to the way out is answered" "$(wait_for '^bound answered' "$SAID")" '^bound answered$'
expect "the helper says a line runs" "$(ask "$(request status)")" '^ok=True state=running hold=True protocol=4 line=yes '

say "the tunnel's process killed"
kill -9 "$PID"; sleep 1.5
expect "the helper says no tunnel and the block standing" "$(ask "$(request status)")" '^ok=True state=idle hold=True '
expect "the rules are where they were" "$([ "$(pfctl -a "$ANCHOR" -s rules 2>/dev/null)" = "$RULES" ] && echo "the same $(rules_standing)" || echo changed)" '^the same [12][0-9]$'
expect "the system asks whom it asked before" "$(first_resolver)" "^$RESOLVER\$"
expect "the key is gone" "$(key_standing)" '^0$'
expect "a request elsewhere is refused" "$(code_of "http://$ELSEWHERE/")" '^000$'
expect "a name asked by the system is not answered" "$(name_by_system www.apache.org)" '^none$'
expect "the server the block lets out still answers" "$(code_of "http://$SERVER/" 3)" '^[1-5][0-9][0-9]$'
: > "$SAID"
expect "the line outlives its tunnel, and is answered still" "$(wait_for '^bound answered' "$SAID")" '^bound answered$'
expect "ended by its user, as any process of theirs" "$(as_user kill "$LPID" 2>&1; sleep 0.6; kill -0 "$LPID" 2>/dev/null && echo there || echo gone)" '^gone$'
expect "and the helper says none runs" "$(ask "$(request status)")" '^ok=True state=idle hold=True protocol=4 line=no '
expect "a line can be started under a block with no tunnel" "$(ask "$(request line "$STAND_IN" "$SAID" "$MINE" "$STUN_IP" "$STUN_PORT")")" '^ok=True state=idle hold=True protocol=4 line=yes '
LPID=$(line_pid)
expect "and is answered" "$(wait_for '^bound answered' "$SAID")" '^bound answered$'
expect "stopped by the helper when asked" "$(ask "$(request line-stop)")" '^ok=True state=idle hold=True protocol=4 line=no '
expect "it is gone" "$(kill -0 "$LPID" 2>/dev/null && echo there || echo gone)" '^gone$'

say "a start under the block that stands"
expect "another tunnel is running behind it" "$(ask "$(request start utun226 names hold)")" '^ok=True state=running hold=True '
PID=$(tunnel_pid)
expect "the tun names the app's own program" "$(the_app_asks)" '^answered 127\.0\.0\.53 '
expect "a request elsewhere is carried again" "$(code_of "http://$ELSEWHERE/" 3)" '^[1-5][0-9][0-9]$'
expect "the block now names the new tunnel" "$(pfctl -a "$ANCHOR" -s rules 2>/dev/null | grep -c 'pass quick on utun226 all')" '^1$'

say "a stop that keeps the block, and the block released"
expect "stopped, the block standing" "$(ask "$(request stop keep)")" '^ok=True state=idle hold=True '
expect "a request elsewhere is refused" "$(code_of "http://$ELSEWHERE/")" '^000$'
expect "released" "$(ask "$(request release)")" '^ok=True state=idle hold=False '
expect "no rule stands" "$(rules_standing)" '^0$'
expect "a request elsewhere goes out directly" "$(code_of "http://$ELSEWHERE/" 3)" '^[1-5][0-9][0-9]$'

say "a start with names and no block, and a plain stop"
expect "the tunnel is running and nothing is held" "$(ask "$(request start utun227 names)")" '^ok=True state=running hold=False '
PID=$(tunnel_pid)
expect "the tunnel runs in root's own group" "$(ps -o gid= -p "$PID" | tr -d ' ')" '^0$'
expect "the system asks the tunnel for names" "$(first_resolver)" '^172\.19\.0\.2$'
expect "no rule stands" "$(rules_standing)" '^0$'
expect "stopped" "$(ask "$(request stop)")" '^ok=True state=idle hold=False '
expect "the key is gone" "$(key_standing)" '^0$'
expect "the system asks whom it asked before" "$(first_resolver)" "^$RESOLVER\$"

say "what is refused, and what it leaves"
expect "an interface that is not a utun" "$(ask "$(request start utun227 names hold | sed 's/"interface": "utun227"/"interface": "en0"/')")" '^ok=False .*not a utun'
expect "an address that is more than an address" "$(ask "$(request start utun227 names hold | sed 's/"pass": \[\]/"pass": ["1.2.3.4 } pass out all"]/')")" '^ok=False .*written as addresses'
expect "a resolver that is not an address" "$(ask "$(request start utun227 | sed 's/^{/{"names": "the router", /')")" '^ok=False .*asked of an address'
ln -s "$STAND_IN" "$work/line/a-link"
expect "a line's program that is a link" "$(ask "$(request line "$work/line/a-link" "$SAID")")" '^ok=False .*no line.s program at '
expect "one that is not its asker's own" "$(ask "$(request line /bin/ls "$SAID")")" '^ok=False .*not a file of the user who asks'
expect "one named from where the helper stands" "$(ask "$(request line stand-in-line "$SAID")")" '^ok=False .*full paths'
expect "one that writes where its user cannot" "$(ask "$(request line "$STAND_IN" /var/root/said "$MINE" "$STUN_IP" "$STUN_PORT")")" '^ok=False .*did not start: open /var/root/said'
expect "no rule stands" "$(rules_standing)" '^0$'
expect "no key stands" "$(key_standing)" '^0$'

say "the helper told to end with a tunnel up behind the block"
expect "the tunnel is running and the block stands" "$(ask "$(request start utun227 names hold)")" '^ok=True state=running hold=True '
PID=$(tunnel_pid)
: > "$SAID"
expect "and a line with it" "$(ask "$(request line "$STAND_IN" "$SAID" "$MINE" "$STUN_IP" "$STUN_PORT")")" '^ok=True state=running hold=True protocol=4 line=yes '
LPID=$(line_pid)
kill -TERM "$DAEMON"
for _ in $(seq 1 40); do kill -0 "$DAEMON" 2>/dev/null || break; sleep 0.25; done
sleep 0.5
expect "the helper is gone" "$(kill -0 "$DAEMON" 2>/dev/null && echo there || echo gone)" '^gone$'
expect "and its tunnel" "$(kill -0 "$PID" 2>/dev/null && echo there || echo gone)" '^gone$'
expect "and its line" "$(kill -0 "$LPID" 2>/dev/null && echo there || echo gone)" '^gone$'
expect "and the block" "$(rules_standing)" '^0$'
expect "and the key" "$(key_standing)" '^0$'
expect "a request elsewhere goes out directly" "$(code_of "http://$ELSEWHERE/" 3)" '^[1-5][0-9][0-9]$'

exec 3>&-
kill "$LINE" 2>/dev/null
pfctl -a "$ANCHOR" -F all >/dev/null 2>&1
printf 'remove %s\n' "$KEY" | scutil >/dev/null 2>&1
if [ -s "$work/helper.err" ]; then echo "--- the helper said on its error stream"; tail -5 "$work/helper.err" | cut -c1-200; fi
rm -rf "$work" "$LINES/stand-in-line"
rmdir "$LINES" 2>/dev/null
echo
[ "$failed" -eq 0 ] && echo "the helper does what the document says" || echo "the helper does not do what the document says"
exit "$failed"
