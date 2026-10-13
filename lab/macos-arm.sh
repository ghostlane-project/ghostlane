#!/bin/bash
# What putting the kill switch's block in place does to a connection that was
# open before it.
#
# Why it is asked. Build 1.0.464 on a Mac in the field: a first connect to a
# room, the switch on and no block standing. The helper ran the engine, the
# room was joined, the tunnel was started, which is when the block is put in
# place, and within six seconds the engine's connection to the room was
# closed, three times of three; the app's check, made into that, found nothing
# twice. After the tunnel was killed and the block stood, the same connect
# held. What differs is whether the engine's connections were made before the
# block or under it. The block lets the helper's group out with a rule that
# says nothing of a connection's flags, and pf reads such a rule as one about
# the packet that opens a connection.
#
# So: one connection, opened first and asked over once a second
# (lab/held-open.py), and then the block as the helper writes it, and as it
# could be written; and a tunnel brought up over such a connection with no
# block at all.
#
#   sudo bash lab/macos-arm.sh <sing-box> <the unprivileged user>
set -u
SB="$1"; WHO="$2"
WHO_UID="$(id -u "$WHO")"; WHO_GID="$(id -g "$WHO")"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REFERENCE="$root/sharedUI/src/jvmTest/resources/reference-configs/desktop-tun-macos-names.json"
ANCHOR='com.apple/100.org.olcbox.app.hold'
GROUP=7302243
work="$(cd /tmp && pwd -P)/ghostlane-arm.$$"
mkdir -p "$work"; chmod 755 "$work"
# Whatever happens to this script, the block goes.
python3 -c 'import os,sys,time,subprocess
os.setsid(); time.sleep(600); subprocess.run(["/sbin/pfctl","-a",sys.argv[1],"-F","all"])' "$ANCHOR" >/dev/null 2>&1 &

say() { printf '\n== %s\n' "$*"; }
note() { printf '   %-44s %s\n' "$1" "$2"; }
quiet() { grep -v 'ALTQ\|pfctl: Use of -f option\|present in the main ruleset\|could result in flushing\|See /etc/pf.conf\|rules cleared\|nat cleared\|dummynet cleared\|tables deleted\|^$' || true; }
as_user() { python3 -c 'import os,sys; os.setgid(int(sys.argv[2])); os.setuid(int(sys.argv[1])); os.execvp(sys.argv[3], sys.argv[3:])' "$WHO_UID" "$WHO_GID" "$@"; }
in_group() { python3 -c 'import os,sys; os.setgid(int(sys.argv[2])); os.setuid(int(sys.argv[1])); os.execvp(sys.argv[3], sys.argv[3:])' "$WHO_UID" "$GROUP" "$@"; }
mark() {
  local text; text=$(printf '%s' "$2" | tr '\n"\\' '  /' | cut -c1-138)
  [ -n "${GITHUB_TOKEN:-}" ] || return 0
  curl -4 -s -o /dev/null --max-time 8 -X POST \
    -H "Authorization: Bearer $GITHUB_TOKEN" -H "Accept: application/vnd.github+json" \
    "https://api.github.com/repos/$GITHUB_REPOSITORY/statuses/$GITHUB_SHA" \
    -d "{\"state\":\"success\",\"context\":\"arm/${LABEL:-mac}/$1\",\"description\":\"$text\"}" || true
}
# Waits until <file> has <count> lines that match, for <seconds> at most.
wait_lines() {
  for _ in $(seq 1 $(( $4 * 4 ))); do
    [ "$(grep -cE "$2" "$1" 2>/dev/null)" -ge "$3" ] && return 0
    sleep 0.25
  done
  return 1
}
# What a holder wrote after its first <n> lines: the asks that were answered, and how it ended if it did.
since() {
  local rest ok end
  rest=$(tail -n +$(( $2 + 1 )) "$1")
  ok=$(grep -c '^answered' <<<"$rest")
  end=$(grep -v '^answered' <<<"$rest" | grep . | head -1 | cut -c1-80)
  echo "answered $ok time(s)${end:+, then $end}"
}
# Three million bytes over the held connection: what came of it, in half a minute at most.
big() {
  local before line
  before=$(wc -l < "$1")
  touch "$1.big"
  for _ in $(seq 1 60); do
    line=$(tail -n +$(( before + 1 )) "$1" | grep -E '^(answered [0-9]+ 3000000|stalled|broken)' | head -1)
    [ -n "$line" ] && { cut -c1-80 <<<"$line"; return; }
    sleep 0.5
  done
  echo "no word in thirty seconds"
}
# The block as the helper writes it for utun225, with the lines that let its group out as given.
rules() {
  printf '%s\n' \
    "pass out quick on lo0 all flags any no state" \
    "pass quick on utun225 all flags any no state" \
    "$@" \
    "pass out quick inet proto udp from any port 68 to any port 67" \
    "pass out quick inet6 proto udp from any port 546 to any port 547" \
    "block return out quick proto { tcp, udp } from any to any port 53" \
    "pass out quick inet from any to { 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 169.254.0.0/16, 224.0.0.0/4, 255.255.255.255 }" \
    "pass out quick inet6 from any to { fe80::/10, fc00::/7, ff02::/16 }" \
    "block return out quick all"
}

say "the machine"
sw_vers | tr '\n' ' '; echo
IF=$(route -n get default | awk '/interface:/{print $2}')
echo "way out: $IF; pf: $(pfctl -s info 2>/dev/null | head -1 | cut -c1-60)"
pfctl -E >/dev/null 2>&1
echo "the main rules hand over to: $(pfctl -s rules 2>/dev/null | grep -c 'anchor "com.apple/\*"') anchor(s) of com.apple; states standing: $(pfctl -s states 2>/dev/null | grep -c .)"

# Somewhere that answers so many bytes over one connection, again and again.
HOST=""; RANGED=""
for candidate in "speed.cloudflare.com|" "fsn1-speed.hetzner.com|/100MB.bin" "ash-speed.hetzner.com|/100MB.bin"; do
  host="${candidate%%|*}"; path="${candidate#*|}"
  try="$work/try"; : > "$try"; chmod 666 "$try"; rm -f "$try.big"
  python3 "$root/lab/held-open.py" "$WHO_UID" "$WHO_GID" "$host" "$try" $path &
  T=$!
  if wait_lines "$try" '^answered (200|206) 1000 ' 2 12; then
    touch "$try.big"
    if wait_lines "$try" '^answered (200|206) 3000000 ' 1 20; then HOST="$host"; RANGED="$path"; fi
  fi
  kill "$T" 2>/dev/null
  echo "   $host: $(tr '\n' ';' < "$try" | cut -c1-150)"
  [ -n "$HOST" ] && break
done
[ -n "$HOST" ] || { mark 0 "nothing answers over one connection from here: nothing to ask"; echo "nothing to ask"; exit 1; }

python3 - "$work" "$IF" "$REFERENCE" <<'PY'
import json, sys
work, way_out, reference = sys.argv[1:4]
config = json.load(open(reference))
config.pop("experimental", None)
tun = config["inbounds"][0]
tun.pop("route_exclude_address", None)
tun["interface_name"] = "utun225"
out, direct = config["outbounds"]
out["server_port"] = 18821
direct["bind_interface"] = way_out
config["dns"]["rules"][2]["process_path"] = ["/nowhere/a-line"]
config["route"]["rules"][2]["process_path"] = ["/nowhere/a-line"]
json.dump(config, open(work + "/tun.json", "w"))
PY

# One connection held, and what is done over it.
#   <number> <words> <the holder's group> <the tunnel's group, or none> <the lines that let the group out, or none>
experiment() {
  local n="$1" words="$2" gid="$3" tunnel="$4"; shift 4
  say "$n. $words"
  local out="$work/held-$n" H T="" peer before held bigword fresh stranger count
  : > "$out"; chmod 666 "$out"
  python3 "$root/lab/held-open.py" "$WHO_UID" "$gid" "$HOST" "$out" $RANGED &
  H=$!
  if ! wait_lines "$out" '^answered' 2 15; then
    note "the connection" "was never open and answered twice: $(tail -1 "$out" | cut -c1-80)"
    mark "$n" "nothing to compare: no connection to begin with"
    kill "$H" 2>/dev/null; return
  fi
  peer=$(awk '/^opened to/{print $3; exit}' "$out")
  before=$(wc -l < "$out")
  if [ "$1" != none ]; then
    rules "$@" | pfctl -a "$ANCHOR" -f - 2>&1 | quiet | cut -c1-150
    count=$(pfctl -a "$ANCHOR" -s rules 2>/dev/null | grep -c .)
    if [ "$count" -eq 0 ]; then
      note "the block" "pf took none of it"
      mark "$n" "$words: pf refuses the rule"
      kill "$H" 2>/dev/null; return
    fi
    pfctl -a "$ANCHOR" -s rules 2>/dev/null | grep 'group' | sed 's/  */ /g; s/^/      pf reads: /' | cut -c1-150
  fi
  if [ "$tunnel" != none ]; then
    python3 -c 'import os,sys; os.setgid(int(sys.argv[1])); os.execv(sys.argv[2], [sys.argv[2], "run", "-c", sys.argv[3]])' \
      "$tunnel" "$SB" "$work/tun.json" >"$work/tun-$n.log" 2>&1 &
    T=$!
    for _ in $(seq 1 40); do ifconfig utun225 2>/dev/null | grep -q 'inet 172.19.0.1 ' && break; sleep 0.25; done
    note "the tunnel" "$(ifconfig utun225 2>/dev/null | grep -c 'inet 172.19.0.1 ') interface up; the table sends the far end by $(route -n get "$peer" 2>/dev/null | awk '/interface:/{print $2}')"
  fi
  sleep 6
  held=$(since "$out" "$before")
  note "the connection that was open" "$held"
  if kill -0 "$H" 2>/dev/null; then bigword=$(big "$out"); else bigword="its connection is gone"; fi
  note "three million bytes over it" "$bigword"
  if [ "$1" != none ] && [ "$tunnel" = none ]; then
    fresh=$(in_group curl -4 -s -o /dev/null -w '%{http_code}' --max-time 5 http://1.1.1.1/ 2>/dev/null)
    stranger=$(as_user curl -4 -s -o /dev/null -w '%{http_code}' --max-time 5 http://1.1.1.1/ 2>/dev/null)
    note "a new connection of the group" "${fresh:-000}"
    note "a new connection of anybody else" "${stranger:-000} (000 is refused)"
  fi
  if [ -n "$T" ]; then
    grep -E 'ERROR|WARN|FATAL' "$work/tun-$n.log" 2>/dev/null | perl -pe 's/\e\[[0-9;]*m//g' | cut -c1-150 | tail -3 | sed 's/^/      the tunnel said: /'
    kill -9 "$T" 2>/dev/null; sleep 1
  fi
  pfctl -a "$ANCHOR" -F all >/dev/null 2>&1
  kill "$H" 2>/dev/null
  mark "$n" "$held; 3 MB: $bigword${fresh:+; new in group $fresh, stranger ${stranger:-000}}"
  sleep 2
}

LET="pass out quick proto { tcp, udp } from any to any group $GROUP"
experiment 1 "the block as the helper writes it, over a connection of its group" "$GROUP" none "$LET"
experiment 2 "the same block, over a connection of anybody else" "$WHO_GID" none "$LET"
experiment 3 "with a second line for what belongs to an older connection, kept without state" "$GROUP" none \
  "$LET" "pass out quick proto tcp from any to any group $GROUP flags any no state"
experiment 4 "one line, any flags, state kept" "$GROUP" none "$LET flags any"
experiment 5 "one line, any flags, state kept loosely" "$GROUP" none "$LET flags any keep state (sloppy)"
experiment 6 "no block: a tunnel comes up over a connection of anybody's" "$WHO_GID" 0 none
experiment 7 "the whole order: the connection, the block with the second line, the tunnel in the group" "$GROUP" "$GROUP" \
  "$LET" "pass out quick proto tcp from any to any group $GROUP flags any no state"
experiment 8 "the whole order with the block as the helper writes it" "$GROUP" "$GROUP" "$LET"

pfctl -a "$ANCHOR" -F all >/dev/null 2>&1
rm -rf "$work"
echo
echo "asked"
