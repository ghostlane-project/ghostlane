#!/bin/bash
# The config the app's own builder writes for a Mac's tunnel under the kill
# switch (SingBoxConfigDumpTest, desktop-tun-macos-names; copied here as
# lab/app-mac-names.json), handed to the tunnel helper's own code on a Mac,
# and the question the app asks of that tunnel, asked by a JVM as the app asks
# it. Run as root on a throwaway machine (a CI runner).
#
# The block is the helper's and is meant to outlive it, so this script takes it
# away itself three minutes in, whatever happened before: a run that hung under
# a block left its runner with no network and nothing to say.
#
#   sudo bash lab/macos-app-config.sh /path/to/the-lab-daemon /path/to/sing-box <the unprivileged user>
LABD="$1"
SB="$2"
WHO="$3"
WHO_UID="$(id -u "$WHO")"
WHO_GID="$(id -g "$WHO")"
ANCHOR='com.apple/100.org.olcbox.app.hold'
KEY='State:/Network/Service/org.olcbox.app.tunnel/DNS'
PROBE='names-its-programs.ghostlane.invalid'
work="$(cd /tmp && pwd -P)/ghostlane-daemon.$$"
mkdir -p "$work/line"
chmod 755 "$work"
( sleep 180; pfctl -a "$ANCHOR" -F all; printf 'remove %s\n' "$KEY" | scutil ) >/dev/null 2>&1 &

say() { printf '\n=== %s\n' "$*"; }
quiet() { grep -v 'ALTQ\|pfctl: Use of -f option\|present in the main ruleset\|could result in flushing\|See /etc/pf.conf\|^$' || true; }
within() { local s="$1"; shift; perl -e 'alarm shift; exec @ARGV' "$s" "$@"; }
as_user() { python3 -c 'import os,sys; os.setgid(int(sys.argv[2])); os.setuid(int(sys.argv[1])); os.execvp(sys.argv[3], sys.argv[3:])' "$WHO_UID" "$WHO_GID" "$@"; }
fetch() {
  local url="$1" out; shift
  out=$(as_user curl -4 -s -o /dev/null -w '%{http_code} in %{time_total}s' --max-time 5 "$@" "$url" 2>/dev/null)
  printf '%s' "${out:-nothing}"
}
by_address() { fetch "http://$SITE/"; }
elsewhere() { fetch "http://$OTHER/"; }
name_by_system() { within 4 dscacheutil -q host -a name "$1" 2>&1 | awk '/ip_address/{print $2}' | head -1 | grep . || echo none; }
rules_standing() { pfctl -a "$ANCHOR" -s rules 2>/dev/null | grep -c .; }
key_standing() { printf 'show %s\n' "$KEY" | scutil 2>/dev/null | grep -c ServerAddresses; }
# What is true of the machine now, in one line.
look() {
  printf '   %-32s rules %s, key %s, first resolver %s; the server %s, elsewhere %s; a name by the system %s\n' "$1:" \
    "$(rules_standing)" "$(key_standing)" "$(scutil --dns | awk '/nameserver\[0\]/{print $3; exit}')" \
    "$(by_address)" "$(elsewhere)" "$(name_by_system "$2")"
}

say "the machine"
sw_vers | tr '\n' ' '; echo
IF=$(route -n get default | awk '/interface:/{print $2}')
GW=$(route -n get default | awk '/gateway:/{print $2}')
SITE=$(dig +short +time=3 +tries=2 example.com A | grep -E '^[0-9.]+$' | head -1)
RESOLVER=$(scutil --dns | awk '/nameserver\[0\]/{print $3; exit}')
OTHER=$(dig +short +time=3 +tries=2 one.one.one.one A | grep -E '^[0-9.]+$' | head -1)
echo "way out: $IF by $GW; the server, which a block is told to let out, is $SITE; elsewhere is $OTHER; the system asks $RESOLVER"
echo "asking as: $(command -v dig) $(command -v host)"

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

JAVA="$(/usr/libexec/java_home 2>/dev/null)/bin/java"
[ -x "$JAVA" ] || JAVA="$(command -v java)"
JAVA_REAL="$(python3 -c 'import os,sys; print(os.path.realpath(sys.argv[1]))' "$JAVA")"
echo "the app stands in as: $JAVA_REAL ($("$JAVA" -version 2>&1 | head -1))"

# The builder's own output, with what is this Mac's put in: the port the line
# listens on, the interface that carries the internet, the binaries that stand
# in for a line's and for the app, and the address the tun keeps out of itself.
python3 - "$work" "$IF" "$JAVA_REAL" "$SITE" <<'PY2'
import json, sys
work, way_out, app, server = sys.argv[1:5]
config = json.load(open("lab/app-mac-names.json"))
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
rules[3]["domain"] = ["www.kernel.org"]
route = config["route"]["rules"]
assert [r.get("action", "out") for r in route] == ["sniff", "hijack-dns", "out", "reject"], route
route[2]["process_path"] = lines
json.dump(config, open(work + "/utun225.json", "w"))
print("   the tun is named", tun["interface_name"], "and keeps", tun["route_exclude_address"], "out of itself; its API is at",
      config["experimental"]["clash_api"]["external_controller"])
PY2
request() {
  python3 - "$work" "$@" <<'PY2'
import json, sys
work, verb, rest = sys.argv[1], sys.argv[2], sys.argv[3:]
r = {"verb": verb}
if verb == "start":
    r["config"] = open(work + "/utun225.json").read()
    r["names"] = "172.19.0.2"
    r["hold"] = {"interface": "utun225", "pass": rest}
elif rest:
    r["hold"] = rest[0]
print(json.dumps(r))
PY2
}

mkfifo "$work/ask"
"$LABD" "$SB" <"$work/ask" >"$work/said" 2>"$work/labd.err" &
DAEMON=$!
exec 3>"$work/ask"
ask() {
  local before; before=$(wc -l <"$work/said")
  printf '%s\n' "$1" >&3
  for _ in $(seq 1 120); do [ "$(wc -l <"$work/said")" -gt "$before" ] && break; sleep 0.1; done
  tail -1 "$work/said" | python3 -c '
import json,sys
try: r=json.loads(sys.stdin.readline())
except Exception as e: print("   no reply:", e); sys.exit(0)
tail=r.pop("logTail","")
print("   →", json.dumps(r, sort_keys=True))
if not r.get("ok") and tail: print("     ", tail[-600:].replace("\n", "\n      "))'
}
tunnel_pid() { tail -1 "$work/said" | python3 -c 'import json,sys; print(json.loads(sys.stdin.readline()).get("pid",0))'; }
flows() {
  curl -s --max-time 3 -H 'Authorization: Bearer 0f1e2d3c4b5a69788796a5b4c3d2e1f0' http://127.0.0.1:18813/connections | python3 -c '
import json,sys,collections
try: d=json.load(sys.stdin)
except Exception as e: print("   the API did not answer:", e); sys.exit(0)
c=d.get("connections") or []
ways=collections.Counter("/".join(x["chains"]) for x in c)
print("   flows open:", len(c), dict(ways), "with no program named:", sum(1 for x in c if not x["metadata"].get("processPath")), "totals down", d["downloadTotal"], "up", d["uploadTotal"])'
}

say "E1: the app's config, started behind the block with names"
ask "$(request start "$SITE")"
PID=$(tunnel_pid)
echo "   the tunnel: pid $PID, group $(ps -o gid= -p "$PID" | tr -d ' '), interface $(ifconfig utun225 2>/dev/null | grep -c 'inet 172.19.0.1 ') of utun225"
echo "   the app's question, asked by a JVM: $(cd "$(dirname "$0")" && as_user "$JAVA" AskTheTun.java "$PROBE" 2>&1 | tail -1)"
echo "   the same question from a program that is not the app: $(as_user dig +time=2 +tries=1 "$PROBE" @172.19.0.2 2>&1 | awk '/status:/{print $6}' | tr -d ',')"
within 8 tcpdump -l -i "$IF" -n -c 8 port 53 2>/dev/null | cut -c1-140 | sed "s/^/   seen on $IF: /" &
sleep 0.7
look "up behind the block" www.debian.org
echo "   through the inbound the app checks a tunnel by: $(as_user curl -s -o /dev/null -w '%{http_code} in %{time_total}s' --max-time 6 --socks5-hostname 127.0.0.1:10811 https://www.debian.org/ 2>/dev/null)"
echo "   by name through the tunnel: $(fetch https://www.mozilla.org/)"
echo "   the first server's own name, asked by the system: $(name_by_system www.kernel.org)"
echo "   a name of the local network, asked by the system: $(name_by_system printer-$RANDOM.lan) (the question is the router's to answer: look for it above)"
echo "   a name asked of the tun by a line's binary: $(as_user host -W 3 -t A www.python.org 172.19.0.2 2>&1 | awk '/has address/{print $4; exit}')"
sleep 1
flows
sleep 3

say "E2: its tunnel killed, then a start under the block, then released"
kill -9 "$PID"; sleep 1.5
ask "$(request status)"
look "the tunnel dead" www.apache.org
ask "$(request start)"
PID=$(tunnel_pid)
echo "   the app's question, asked by a JVM: $(cd "$(dirname "$0")" && as_user "$JAVA" AskTheTun.java "$PROBE" 2>&1 | tail -1)"
look "another behind the same block" www.gnu.org
ask "$(request stop)"
look "a plain stop" www.gnu.org

say "the end"
exec 3>&-
kill "$LINE" "$DAEMON" 2>/dev/null
pfctl -a "$ANCHOR" -F all >/dev/null 2>&1
printf 'remove %s\n' "$KEY" | scutil >/dev/null 2>&1
echo "--- the helper said on its error stream"; tail -5 "$work/labd.err" | cut -c1-200
rm -rf "$work"
exit 0
