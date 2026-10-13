#!/bin/bash
# How long the first run of a program takes on a Mac when the file is new, with
# the network there and with it withheld by the kill switch's block.
#
# Why it is asked. Build 1.0.463 on a Mac in the field: a tunnel started behind
# a block that had stood for a minute was, by the app's own check, not serving
# a second after the helper called it running, and in one start had still not
# answered twelve seconds later. The helper copies sing-box, and the step that
# takes it into its group, anew for every start, so every start is the first
# run of two files the system has not seen, and both are signed with a
# Developer ID: what the system does before it lets such a file run is its own,
# and some of it asks the network. A runner's own builds are signed by nobody,
# so nothing of that was ever in a lab here. This takes the programs out of the
# build that Mac ran.
#
#   sudo bash lab/macos-exec.sh <Ghostlane.app>
set -u
APP="$1"
ANCHOR='com.apple/100.org.olcbox.app.hold'
GROUP=7302243
TEAM='anchor apple generic and certificate leaf[subject.OU] = "3QJG3J7L66"'
work="$(cd /tmp && pwd -P)/ghostlane-exec.$$"
mkdir -p "$work"; chmod 700 "$work"
# Whatever happens to this script, the block goes.
python3 -c 'import os,sys,time,subprocess
os.setsid(); time.sleep(300); subprocess.run(["/sbin/pfctl","-a",sys.argv[1],"-F","all"])' "$ANCHOR" >/dev/null 2>&1 &

say() { printf '\n== %s\n' "$*"; }
note() { printf '   %-58s %s\n' "$1" "$2"; }
now() { perl -MTime::HiRes=time -e 'printf "%d\n", time*1000'; }
mark() {
  local text; text=$(printf '%s' "$2" | tr '\n"\\' '  /' | cut -c1-138)
  [ -n "${GITHUB_TOKEN:-}" ] || return 0
  curl -4 -s -o /dev/null --max-time 8 -X POST \
    -H "Authorization: Bearer $GITHUB_TOKEN" -H "Accept: application/vnd.github+json" \
    "https://api.github.com/repos/$GITHUB_REPOSITORY/statuses/$GITHUB_SHA" \
    -d "{\"state\":\"success\",\"context\":\"exec/${LABEL:-mac}/$1\",\"description\":\"$text\"}" || true
}
# A new copy of <file>, root's own and nobody else's to run: as the helper leaves one.
fresh() {
  local target="$work/$2.$RANDOM$RANDOM"
  cp "$1" "$target"; chown root:wheel "$target"; chmod 700 "$target"
  echo "$target"
}
# Milliseconds a command took, and how it ended.
timed() {
  local t0; t0=$(now)
  "$@" >/dev/null 2>&1
  local status=$?
  echo "$(( $(now) - t0 )) ms$([ $status -eq 0 ] || echo " (ended $status)")"
}
ms() { echo "${1%% ms*}"; }

say "the machine and the programs"
sw_vers | tr '\n' ' '; echo
echo "protection of the system: $(csrutil status 2>&1 | head -1 | cut -c1-80)"
echo "assessments: $(spctl --status 2>&1 | head -1)"
SB=$(find "$APP/Contents" -type f -name sing-box | head -1)
DAEMON=$(find "$APP/Contents" -type f -name '*TunnelDaemon*' | head -1)
[ -n "$SB" ] && [ -n "$DAEMON" ] || { echo "the build has no sing-box or no helper in it"; find "$APP/Contents" -maxdepth 2 | head -30; exit 1; }
for file in "$SB" "$DAEMON"; do
  echo "$(basename "$file"): $(stat -f %z "$file") bytes; $(codesign -dv "$file" 2>&1 | grep -E 'TeamIdentifier|flags=' | tr '\n' ' ' | cut -c1-120)"
done

# One round: the first run of new files, three times over, and the same file again.
round() {
  local words="$1" worst=0 i copy step took
  for i in 1 2 3; do
    copy=$(fresh "$SB" sing-box)
    took=$(timed "$copy" version); note "$words: sing-box, a new copy, its first run" "$took"
    [ "$(ms "$took")" -gt "$worst" ] && worst=$(ms "$took")
    note "$words: the same copy, again" "$(timed "$copy" version)"
    step=$(fresh "$DAEMON" as-group); copy=$(fresh "$SB" sing-box)
    took=$(timed "$step" --as-group "$GROUP" "$copy" version); note "$words: through the group step, both files new" "$took"
    [ "$(ms "$took")" -gt "$worst" ] && worst=$(ms "$took")
    note "$words: the check the helper makes of a new copy" "$(timed codesign --verify -R="$TEAM" "$(fresh "$SB" sing-box)")"
  done
  WORST=$worst
}

say "1. the network there"
round "network"
A=$WORST
mark 1-network "the slowest first run of a new copy: ${A} ms"

say "2. behind the block, the system's own lookups forgotten"
pfctl -E >/dev/null 2>&1
printf '%s\n' \
  "pass out quick on lo0 all flags any no state" \
  "pass out quick proto { tcp, udp } from any to any group $GROUP" \
  "pass out quick inet proto udp from any port 68 to any port 67" \
  "block return out quick proto { tcp, udp } from any to any port 53" \
  "pass out quick inet from any to { 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 169.254.0.0/16, 224.0.0.0/4, 255.255.255.255 }" \
  "block return out quick all" | pfctl -a "$ANCHOR" -f - 2>&1 | grep -v "ALTQ\|pfctl:" | head -3
dscacheutil -flushcache; killall -HUP mDNSResponder 2>/dev/null
sleep 12
note "any program's request" "$(curl -4 -s -o /dev/null -w '%{http_code}' --max-time 5 https://api.ipify.org 2>/dev/null | sed 's/^000$/refused/')"
round "block"
B=$WORST

say "3. what the system's own services said while the block stood"
log show --last 2m --style compact --predicate '(process == "syspolicyd" OR process == "trustd" OR process == "amfid" OR process == "XprotectService") AND (eventMessage CONTAINS[c] "ocsp" OR eventMessage CONTAINS[c] "notar" OR eventMessage CONTAINS[c] "ticket" OR eventMessage CONTAINS[c] "timed out" OR eventMessage CONTAINS[c] "timeout" OR eventMessage CONTAINS[c] "cloudkit" OR eventMessage CONTAINS[c] "revocation")' 2>/dev/null |
  sed -E 's/[0-9a-f]{24,}/<hash>/g' | cut -c1-190 | tail -25
echo "   (lines of those services in the two minutes: $(log show --last 2m --style compact --predicate 'process == "syspolicyd" OR process == "trustd" OR process == "amfid"' 2>/dev/null | wc -l | tr -d ' '))"

pfctl -a "$ANCHOR" -F all >/dev/null 2>&1
sleep 2
mark 2-block "the slowest first run of a new copy: ${B} ms behind the block, ${A} ms with the network"
say "4. the network back"
round "after"
mark 3-after "the slowest first run of a new copy: ${WORST} ms"
rm -rf "$work"
echo
echo "asked: with the network ${A} ms, behind the block ${B} ms, after it ${WORST} ms"
