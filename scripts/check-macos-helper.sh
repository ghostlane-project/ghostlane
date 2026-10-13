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
#   swiftc … -o helper-check desktopApp/tunneldaemon/{PeerAuthority,GroupExec,TunnelChild,TrafficHold,SystemNames,Daemon}.swift \
#       scripts/macos-helper-check/main.swift
#   sudo scripts/check-macos-helper.sh ./helper-check /path/to/sing-box "$USER"
set -u
HELPER="$1"
SB="$2"
WHO="$3"
WHO_UID="$(id -u "$WHO")"
WHO_GID="$(id -g "$WHO")"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REFERENCE="$root/sharedUI/src/jvmTest/resources/reference-configs/desktop-tun-macos-names.json"
ANCHOR='com.apple/100.org.olcbox.app.hold'
KEY='State:/Network/Service/org.olcbox.app.tunnel/DNS'
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

say "the machine"
sw_vers | tr '\n' ' '; echo
IF=$(route -n get default | awk '/interface:/{print $2}')
SERVER=$(dig +short +time=3 +tries=2 example.com A | grep -E '^[0-9.]+$' | head -1)
ELSEWHERE=$(dig +short +time=3 +tries=2 one.one.one.one A | grep -E '^[0-9.]+$' | head -1)
RESOLVER=$(first_resolver)
JAVA="$(/usr/libexec/java_home 2>/dev/null)/bin/java"
[ -x "$JAVA" ] || JAVA="$(command -v java)"
JAVA_REAL="$(python3 -c 'import os,sys; print(os.path.realpath(sys.argv[1]))' "$JAVA")"
echo "way out: $IF; the server a block is told to let out: $SERVER; elsewhere: $ELSEWHERE; the system asks $RESOLVER"
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
elif rest:
    r["hold"] = rest[0]
print(json.dumps(r))
PY
}
mkfifo "$work/ask"
"$HELPER" "$SB" <"$work/ask" >"$work/said" 2>"$work/helper.err" &
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
print("ok=%s state=%s hold=%s protocol=%s error=%s" % (r.get("ok"), r.get("state"), r.get("hold"), r.get("protocol"), r.get("error")))'
}
tunnel_pid() { tail -1 "$work/said" | python3 -c 'import json,sys; print(json.loads(sys.stdin.readline()).get("pid",0))'; }
the_app_asks() { ( cd "$root/scripts/macos-helper-check" && as_user "$JAVA" AskTheTun.java "$PROBE" 2>&1 | tail -1 ); }

say "at rest"
expect "the helper speaks the third protocol and holds nothing" "$(ask "$(request status)")" '^ok=True state=idle hold=False protocol=3 '
expect "no rule stands" "$(rules_standing)" '^0$'

say "a start behind the block, with names"
expect "the tunnel is running and the block stands" "$(ask "$(request start utun225 names hold "$SERVER")")" '^ok=True state=running hold=True '
PID=$(tunnel_pid)
expect "the tunnel runs in the group the block lets out" "$(ps -o gid= -p "$PID" | tr -d ' ')" "^$GROUP\$"
expect "it took the name the block was written for" "$(ifconfig utun225 2>/dev/null | grep -c 'inet 172.19.0.1 ')" '^1$'
RULES="$(pfctl -a "$ANCHOR" -s rules 2>/dev/null)"
expect "the block lets the tunnel's interface out" "$(grep -c 'pass quick on utun225 all' <<<"$RULES")" '^1$'
expect "and the tunnel's own process, by its group" "$(grep -c "group = $GROUP" <<<"$RULES")" '^2$'
expect "and the server the tun keeps out of itself" "$(grep -c "to $SERVER" <<<"$RULES")" '^1$'
expect "and refuses port 53 before it lets the local network out" "$(awk '/port = 53/{refused=NR} /__automatic/{local=NR} END{print (refused && local && refused < local) ? "before" : "not before"}' <<<"$RULES")" '^before$'
expect "and ends in a refusal of everything else" "$(tail -1 <<<"$RULES")" '^block return out quick all$'
expect "the system asks the tunnel for names" "$(first_resolver)" '^172\.19\.0\.2$'
expect "the tun names the app's own program, asked as the app asks" "$(the_app_asks)" '^answered 127\.0\.0\.53 '
expect "and gives nobody else that answer" "$(as_user dig +time=2 +tries=1 "$PROBE" @172.19.0.2 2>&1 | awk '/status:/{print $6}' | tr -d ',')" '^NXDOMAIN$'
expect "a request elsewhere is carried by the tunnel" "$(code_of "http://$ELSEWHERE/" 3)" '^[1-5][0-9][0-9]$'
expect "a request to the server leaves beside it" "$(code_of "http://$SERVER/" 3)" '^[1-5][0-9][0-9]$'
expect "a name asked by the system gets an address of the tunnel's" "$(name_by_system www.debian.org)" '^198\.1[89]\.'
expect "a name asked of the tun by a line's binary gets a real one" "$(as_user host -W 3 -t A www.python.org 172.19.0.2 2>&1 | awk '/has address/{print $4; exit}')" '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$'
expect "and not one of the tunnel's" "$(as_user host -W 3 -t A www.python.org 172.19.0.2 2>&1 | awk '/has address/{print $4; exit}' | grep -c '^198\.1[89]\.')" '^0$'
expect "the router is not asked beside the tunnel" "$(as_user dig +time=1 +tries=1 +short example.org "@$RESOLVER" 2>&1 | grep -cE '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$')" '^0$'
expect "through the inbound the app checks a tunnel by" "$(as_user curl -s -o /dev/null -w '%{http_code}' --max-time 6 --socks5 127.0.0.1:10811 "http://$ELSEWHERE/" 2>/dev/null)" '^[1-5][0-9][0-9]$'

say "the tunnel's process killed"
kill -9 "$PID"; sleep 1.5
expect "the helper says no tunnel and the block standing" "$(ask "$(request status)")" '^ok=True state=idle hold=True '
expect "the rules are where they were" "$(rules_standing)" '^1[0-9]$'
expect "the system asks whom it asked before" "$(first_resolver)" "^$RESOLVER\$"
expect "the key is gone" "$(key_standing)" '^0$'
expect "a request elsewhere is refused" "$(code_of "http://$ELSEWHERE/")" '^000$'
expect "a name asked by the system is not answered" "$(name_by_system www.apache.org)" '^none$'
expect "the server the block lets out still answers" "$(code_of "http://$SERVER/" 3)" '^[1-5][0-9][0-9]$'

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
expect "no rule stands" "$(rules_standing)" '^0$'
expect "no key stands" "$(key_standing)" '^0$'

say "the helper told to end with a tunnel up behind the block"
expect "the tunnel is running and the block stands" "$(ask "$(request start utun227 names hold)")" '^ok=True state=running hold=True '
PID=$(tunnel_pid)
kill -TERM "$DAEMON"
for _ in $(seq 1 40); do kill -0 "$DAEMON" 2>/dev/null || break; sleep 0.25; done
sleep 0.5
expect "the helper is gone" "$(kill -0 "$DAEMON" 2>/dev/null && echo there || echo gone)" '^gone$'
expect "and its tunnel" "$(kill -0 "$PID" 2>/dev/null && echo there || echo gone)" '^gone$'
expect "and the block" "$(rules_standing)" '^0$'
expect "and the key" "$(key_standing)" '^0$'
expect "a request elsewhere goes out directly" "$(code_of "http://$ELSEWHERE/" 3)" '^[1-5][0-9][0-9]$'

exec 3>&-
kill "$LINE" 2>/dev/null
pfctl -a "$ANCHOR" -F all >/dev/null 2>&1
printf 'remove %s\n' "$KEY" | scutil >/dev/null 2>&1
if [ -s "$work/helper.err" ]; then echo "--- the helper said on its error stream"; tail -5 "$work/helper.err" | cut -c1-200; fi
rm -rf "$work"
echo
[ "$failed" -eq 0 ] && echo "the helper does what the document says" || echo "the helper does not do what the document says"
exit "$failed"
