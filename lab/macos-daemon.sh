#!/bin/bash
# The tunnel helper's own code, asked what it does on a Mac: its requests
# (Daemon.swift) driven over a pipe instead of its socket, with the config the
# app would hand it. Run as root on a throwaway machine (a CI runner).
#
# The block is the helper's and is meant to outlive it, so this script takes it
# away itself three minutes in, whatever happened before: a run that hung under
# a block left its runner with no network and nothing to say.
#
#   sudo bash lab/macos-daemon.sh /path/to/the-lab-daemon /path/to/sing-box <the unprivileged user>
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

# The app's own config for a room's session (SingBoxConfigDumpTest,
# desktop-tun-counted) as it is to be written for this helper: the tun under a
# name of the app's choosing; every question that enters it answered there,
# before the rule that lets the lines' binaries out; what those binaries ask
# answered with real addresses, and so are the local network's own names; and
# one name that is answered only to a program the tun can name.
config() {
  python3 - "$work" "$IF" "$1" "$PROBE" <<'PY'
import json, sys
work, way_out, name, probe = sys.argv[1:5]
config = json.load(open("lab/app-room-tun.json"))
tun = config["inbounds"][0]
tun["interface_name"] = name
config["inbounds"] = [tun]
out, direct = config["outbounds"]
out["server_port"] = 18821
out.pop("username", None); out.pop("password", None)
direct["bind_interface"] = way_out
lines = [work + "/line/sing-box", "/usr/bin/host"]
config["dns"]["rules"] = [
    {"domain": [probe], "process_path": ["/usr/bin/dig"], "action": "predefined",
     "answer": [probe + ". IN A 127.0.0.53"]},
    {"domain": [probe], "action": "predefined", "rcode": "NXDOMAIN"},
    {"process_path": lines, "server": "dns-direct"},
    {"domain_suffix": ["lan", "home", "local", "internal", "home.arpa", "localdomain"], "server": "dns-direct"},
] + config["dns"]["rules"]
process, sniff, hijack, no_v6 = config["route"]["rules"]
process["process_path"] = lines
config["route"]["rules"] = [sniff, hijack, process, no_v6]
config["experimental"]["cache_file"]["path"] = "/Library/Application Support/org.olcbox.app/cache.db"
config["experimental"].pop("clash_api", None)
json.dump(config, open(work + "/" + name + ".json", "w"))
PY
}
# request <verb> [<config's name> [names] [hold] [an address to let out]]: one line of JSON.
request() {
  python3 - "$work" "$@" <<'PY'
import json, sys
work, verb, rest = sys.argv[1], sys.argv[2], sys.argv[3:]
r = {"verb": verb}
if verb == "start":
    name = rest[0]
    r["config"] = open(work + "/" + name + ".json").read()
    if "names" in rest: r["names"] = "172.19.0.2"
    if "hold" in rest:
        r["hold"] = {"interface": name, "pass": [a for a in rest[1:] if a[0].isdigit()]}
elif rest:
    r["hold"] = rest[0]
print(json.dumps(r))
PY
}

mkfifo "$work/ask"
"$LABD" "$SB" <"$work/ask" >"$work/said" 2>"$work/labd.err" &
DAEMON=$!
exec 3>"$work/ask"
# ask <a request's line>: sends it and prints what came back, without the log.
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
if not r.get("ok") and tail: print("     ", tail[-400:].replace("\n", "\n      "))'
}
tunnel_pid() { tail -1 "$work/said" | python3 -c 'import json,sys; print(json.loads(sys.stdin.readline()).get("pid",0))'; }
# How long until the tun names the program behind a connection: the one name it answers only then.
names_programs() {
  local n
  for n in $(seq 1 40); do
    if as_user dig +time=1 +tries=1 +short "$PROBE" @172.19.0.2 2>/dev/null | grep -q 127.0.0.53; then
      echo "   the tun names programs after $(( (n - 1) * 250 )) ms of asking"; return
    fi
    sleep 0.25
  done
  echo "   the tun did not name a program in ten seconds"
}

say "S1: at rest"
ask "$(request status)"
look "nothing asked yet" www.gnu.org

say "S2: a start with the block and with names"
config utun161
ask "$(request start utun161 names hold "$SITE")"
PID=$(tunnel_pid)
echo "   the tunnel: pid $PID, group $(ps -o gid= -p "$PID" | tr -d ' '), run as: $(ps -o command= -p "$PID" | cut -c1-110)"
pfctl -a "$ANCHOR" -s rules 2>&1 | quiet | sed 's/^/   /'
names_programs
look "the tunnel up behind the block" www.debian.org
echo "   by name through the tunnel: $(fetch https://www.debian.org/)"
echo "   www.kernel.org asked of the tun by a line's binary: $(as_user host -W 3 -t A www.kernel.org 172.19.0.2 2>&1 | awk '/has address/{print $4; exit}')"
echo "   the gateway $(ping -c 1 -t 2 "$GW" >/dev/null 2>&1 && echo answers a ping || echo does not answer a ping); a name asked of it by a user: $(as_user dig +time=1 +tries=1 +short example.org "@$RESOLVER" 2>&1 | tail -1 | cut -c1-44)"
within 7 route -n monitor >"$work/routes" 2>/dev/null &
sleep 0.5
for host in www.wikipedia.org www.mozilla.org www.python.org www.apache.org www.gnu.org; do fetch "https://$host/" >/dev/null; done
sleep 1.5
echo "   five sites opened by name: $(grep -c '^RTM_' "$work/routes") messages on the routing socket ($(grep '^RTM_' "$work/routes" | cut -d: -f1 | sort | uniq -c | tr -s ' ' | tr '\n' ';'))"

say "S3: the tunnel's process killed"
kill -9 "$PID"; sleep 1.5
ask "$(request status)"
look "the tunnel dead" www.apache.org

say "S4: a start under the block that stands"
config utun162
ask "$(request start utun162 names hold)"
PID=$(tunnel_pid)
names_programs
look "a tunnel behind the old block" www.kernel.org
echo "   by name through the tunnel: $(fetch https://www.mozilla.org/)"

say "S5: a stop that keeps the block, then the block released"
ask "$(request stop keep)"
look "stopped, the block kept" www.python.org
ask "$(request release)"
look "released" www.python.org

say "S6: a start with names and no block, then a plain stop"
config utun163
ask "$(request start utun163 names)"
PID=$(tunnel_pid)
echo "   the tunnel: pid $PID, group $(ps -o gid= -p "$PID" | tr -d ' ')"
names_programs
look "the tunnel up, nothing held" www.debian.org
echo "   by name through the tunnel: $(fetch https://www.debian.org/)"
ask "$(request stop)"
look "stopped" www.gnu.org

say "S7: what is refused, and what it leaves"
config utun164
ask "$(request start utun164 names hold | sed 's/"interface": "utun164"/"interface": "en0"/')"
ask "$(request start utun164 names hold | sed 's/"pass": \[\]/"pass": ["1.2.3.4 } pass out all"]/')"
ask "$(request start utun164 | sed 's/^{/{"names": "the router", /')"
look "after three refusals" www.gnu.org

say "S8: the helper told to end with a tunnel up behind the block"
ask "$(request start utun164 names hold)"
PID=$(tunnel_pid)
look "before" www.debian.org
kill -TERM "$DAEMON"; sleep 2.5
echo "   the helper: $(kill -0 "$DAEMON" 2>/dev/null && echo still there || echo gone); the tunnel: $(kill -0 "$PID" 2>/dev/null && echo still there || echo gone)"
look "after" www.kernel.org

say "the end"
exec 3>&-
kill "$LINE" 2>/dev/null
pfctl -a "$ANCHOR" -F all >/dev/null 2>&1
printf 'remove %s\n' "$KEY" | scutil >/dev/null 2>&1
echo "--- the helper said on its error stream"; tail -5 "$work/labd.err" | cut -c1-200
rm -rf "$work"
exit 0
