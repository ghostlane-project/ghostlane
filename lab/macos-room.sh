#!/bin/bash
# A real room behind the Mac's kill switch: the engine the app ships, a call of
# the gate's pool, and an exit that is another machine (the workflow's other
# job).
#
# What is asked here is what a Mac in the field did with build 1.0.463: a
# session whose tunnel was killed, and then a connect to a room, which the app
# makes by having the helper run the engine first and starting the tunnel after
# it. There the room was joined, the tunnel came up, and the app's check
# ("did a request reach the internet through the tunnel?") failed every time,
# with not one request arriving at the engine. So: the same steps, the check
# made as the app makes it (through the tunnel's own listener, at once, eight
# seconds a try), and what the tunnel and the engine say.
#
#   sudo --preserve-env=… bash lab/macos-room.sh <helper-check> <sing-box> <user> <engine>
# Environment: ROOM, ROOM_KEY, ROOM_CHANNEL, PROVIDER, TRANSPORT; GITHUB_* for the marks.
set -u
HELPER="$1"; SB="$2"; WHO="$3"; ENGINE_BUILT="$4"
WHO_UID="$(id -u "$WHO")"; WHO_GID="$(id -g "$WHO")"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REFERENCE="$root/sharedUI/src/jvmTest/resources/reference-configs/desktop-tun-macos-names.json"
ANCHOR='com.apple/100.org.olcbox.app.hold'
KEY='State:/Network/Service/org.olcbox.app.tunnel/DNS'
LINES='/Library/Application Support/org.olcbox.app.lines'
GROUP=7302243
PORT=10810
LOGIN_USER=lab
LOGIN_PASS=lab-pass-0123456789
work="$(cd /tmp && pwd -P)/ghostlane-room.$$"
mkdir -p "$work/line"; chmod 755 "$work"
python3 -c 'import os,sys,time,subprocess
os.setsid(); time.sleep(660); subprocess.run(["/sbin/pfctl","-a",sys.argv[1],"-F","all"])' "$ANCHOR" >/dev/null 2>&1 &

say() { printf '\n== %s\n' "$*"; }
note() { printf '   %-52s %s\n' "$1" "$2"; }
now() { perl -MTime::HiRes=time -e 'printf "%d\n", time*1000'; }
as_user() { python3 -c 'import os,sys; os.setgid(int(sys.argv[2])); os.setuid(int(sys.argv[1])); os.execvp(sys.argv[3], sys.argv[3:])' "$WHO_UID" "$WHO_GID" "$@"; }
mark() {
  local text; text=$(printf '%s' "$2" | tr '\n"\\' '  /' | cut -c1-138)
  [ -n "${GITHUB_TOKEN:-}" ] || return 0
  curl -4 -s -o /dev/null --max-time 8 -X POST \
    -H "Authorization: Bearer $GITHUB_TOKEN" -H "Accept: application/vnd.github+json" \
    "https://api.github.com/repos/$GITHUB_REPOSITORY/statuses/$GITHUB_SHA" \
    -d "{\"state\":\"success\",\"context\":\"room/$PROVIDER/$1\",\"description\":\"$text\"}" || true
}
# Who the internet sees: this Mac, or the exit a room leads to. Empty for no answer.
seen_as() { as_user curl -4 -s --max-time "${2:-8}" ${1:+--socks5-hostname "$1"} https://api.ipify.org 2>/dev/null | grep -E '^[0-9.]+$' | head -1; }
# What an engine's log says, with nothing in it that names a room.
told() {
  local ice udp ready
  ice=$(grep -c "ICE connection state changed: connected" "$1" 2>/dev/null)
  udp=$(grep -cE "failed to get server reflexive|failed to allocate on TURN" "$1" 2>/dev/null)
  ready=$(grep -c "SOCKS5 server listening" "$1" 2>/dev/null)
  echo "ice connected $ice, udp ways that failed $udp, socks $ready"
}
last_words() {
  grep -E "ICE connection state|failed to get server reflexive|failed to allocate|SOCKS5 server|authenticated peer|client: " "$1" 2>/dev/null |
    sed -E 's#https?://[^ ]+#<url>#g; s/[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+/<ip>/g; s/[0-9A-Za-z+\/=_-]{16,}/<id>/g' | cut -c1-150 | tail -"${2:-6}" | sed 's/^/        /'
}
yaml() { # <dns> <port> <login: yes|no>
  cat <<Y
mode: cnc
auth:
  provider: '$PROVIDER'
room:
  id: '$ROOM'
  channel: '$ROOM_CHANNEL'
crypto:
  key: '$ROOM_KEY'
net:
  transport: '$TRANSPORT'
  dns: '$1'
socks:
  host: '127.0.0.1'
  port: $2
Y
  if [ "$3" = yes ]; then printf "  user: '%s'\n  pass: '%s'\n" "$LOGIN_USER" "$LOGIN_PASS"; fi
  cat <<Y
udp:
  enabled: true
vp8:
  fps: 60
  batch_size: 64
Y
}
room_yaml() { yaml "$@" > "$work/line/room.yaml"; chown "$WHO" "$work/line/room.yaml"; chmod 600 "$work/line/room.yaml"; }
# The engine as its user starts it, in the background: E is the engine's own process.
engine() { # <log>
  python3 -c 'import os,sys; os.setgid(int(sys.argv[2])); os.setuid(int(sys.argv[1])); os.execvp(sys.argv[3], sys.argv[3:])' \
    "$WHO_UID" "$WHO_GID" "$ENGINE" "$work/line/room.yaml" >"$1" 2>&1 &
  E=$!
}
# No engine of this script's is left: by its process, and by its name for one that got away.
no_engine() {
  kill "${E:-0}" 2>/dev/null; sleep 2; kill -9 "${E:-0}" 2>/dev/null
  pkill -9 -f "$work/line/olcrtc-darwin-lab" 2>/dev/null
  pkill -9 -f "$LINES/olcrtc-darwin-lab" 2>/dev/null
  sleep 0.5
}
# Waits until the engine says its SOCKS port listens, or it is gone, or the seconds are up.
ready_within() { # <log> <seconds> <pid>
  local t0; t0=$(now)
  for _ in $(seq 1 $(( $2 * 5 ))); do
    grep -q "SOCKS5 server listening" "$1" 2>/dev/null && { echo "ready in $(( $(now) - t0 ))ms"; return 0; }
    kill -0 "$3" 2>/dev/null || { echo "gone after $(( $(now) - t0 ))ms"; return 1; }
    sleep 0.2
  done
  echo "not ready in $2 s"; return 1
}

# Whom the system asks for names, in its own order: the first resolvers of `scutil --dns`.
resolvers() {
  scutil --dns | awk '
    function out() { if (r != "") print "        " r ns " | " f (o != "" ? " | order " o : ""); ns=""; f=""; o="" }
    /^DNS configuration \(for scoped/ { exit }
    /^resolver #/ { out(); r=$2 }
    /nameserver\[[0-9]+\]/ { ns=ns" "$3 }
    /^ +flags/ { f=$0; sub(/^ +flags +: +/,"",f) }
    /^ +order/ { o=$3 }
    END { out() }' | head -"${1:-4}"
}

say "the machine"
sw_vers | tr '\n' ' '; echo
echo "whom the system asks, before anything:"; resolvers
IF=$(route -n get default | awk '/interface:/{print $2}')
RESOLVER=$(scutil --dns | awk '/nameserver\[0\]/{print $3; exit}')
ENGINE="$work/line/olcrtc-darwin-lab"
cp "$ENGINE_BUILT" "$ENGINE"
chown -R "$WHO" "$work/line"
MAC=$(seen_as "")
echo "way out: $IF; the system asks $RESOLVER; the internet sees this Mac as ${MAC:+an address of its own}"
[ -n "$MAC" ] || { mark 0 "this Mac has no network to begin with"; exit 1; }

# A tunnel's config as the app writes one for a room, from the app's own
# reference: <name> <utun> <the line's port> <the tunnel's own listener> <login: yes|no>.
config() {
  python3 "$root/lab/room-config.py" "$work" "$IF" "$REFERENCE" "$ENGINE" "$LINES/olcrtc-darwin-lab" "$LOGIN_USER" "$LOGIN_PASS" "$@"
}
request() { python3 "$root/lab/room-request.py" "$work" "$@"; }
mkfifo "$work/ask"
"$HELPER" "$SB" "$WHO_UID" <"$work/ask" >"$work/said" 2>"$work/helper.err" &
DAEMON=$!
exec 3>"$work/ask"
ask() {
  local before; before=$(wc -l <"$work/said")
  printf '%s\n' "$1" >&3
  for _ in $(seq 1 200); do [ "$(wc -l <"$work/said")" -gt "$before" ] && break; sleep 0.1; done
  tail -1 "$work/said" | python3 "$root/lab/room-reply.py" summary
}
field() { tail -1 "$work/said" | python3 "$root/lab/room-reply.py" field "$1"; }
# What the tunnel's sing-box has said, as the helper keeps it: the lines that
# are about a connection it was asked for, or about something going wrong.
tunnel_said() { # <how many>
  ask "$(request status)" >/dev/null
  tail -1 "$work/said" | python3 "$root/lab/room-reply.py" tail "$1"
}
# What an engine said after its port was open, with nothing in it that names a room.
engine_said_after_ready() { # <log> <how many>
  awk 'seen{print} /SOCKS5 server listening/{seen=1}' "$1" 2>/dev/null |
    sed -E 's#https?://[^ ]+#<url>#g; s/[0-9A-Za-z+\/=_-]{16,}/<id>/g' | cut -c1-150 | tail -"${2:-6}" | sed 's/^/        /'
  echo "        (requests it was handed: $(awk 'seen{print} /SOCKS5 server listening/{seen=1}' "$1" 2>/dev/null | grep -c 'tunnel to'))"
}
# The address a request is seen from, asked as the app asks: through a SOCKS
# listener, of Cloudflare's trace. <port> <url> <seconds>
traced() {
  as_user curl -4 -s --max-time "$3" --socks5-hostname "127.0.0.1:$1" "$2" 2>/dev/null | awk -F= '$1=="ip"{print $2; exit}'
}
who() { if [ -z "$1" ]; then echo "no answer"; elif [ "$1" = "$EXIT" ]; then echo "the exit"; elif [ "$1" = "$MAC" ]; then echo "THIS MAC"; else echo "somebody else"; fi; }
# The helper runs the engine: LPID is its process, SAID where it writes.
by_helper() { # <log name>
  SAID="$work/line/$1"; : > "$SAID"; chown "$WHO" "$SAID"; chmod 600 "$SAID"
  BY_HELPER=$(ask "$(request line "$ENGINE" "$SAID" "$work/line/room.yaml")")
  LPID=$(field line)
}
# Ends the helper's engine as the app ends it: a signal from its own user.
end_line() {
  if [ "${LPID:-0}" -gt 1 ]; then
    as_user kill -TERM "$LPID" 2>/dev/null
    for _ in 1 2 3 4 5 6 7 8 9 10; do kill -0 "$LPID" 2>/dev/null || break; sleep 0.3; done
    kill -9 "$LPID" 2>/dev/null
  fi
  LPID=0
}
# The line's own port, asked directly. <port> <login: yes|no>
by_its_port() {
  if [ "$2" = yes ]; then
    as_user curl -4 -s --max-time 10 --socks5-hostname "$LOGIN_USER:$LOGIN_PASS@127.0.0.1:$1" https://api.ipify.org 2>/dev/null | grep -E '^[0-9.]+$' | head -1
  else
    seen_as "127.0.0.1:$1" 10
  fi
}

say "1. no switch: the engine the app's own child, no tunnel"
EXIT=""
for attempt in 1 2 3 4 5 6; do
  room_yaml "$RESOLVER:53" "$PORT" no
  engine "$work/line/p1.log"
  r=$(ready_within "$work/line/p1.log" 45 "$E")
  [[ "$r" == ready* ]] && EXIT=$(seen_as "127.0.0.1:$PORT" 15)
  note "try $attempt" "$r; $(told "$work/line/p1.log"); through the room the internet sees $([ -n "$EXIT" ] && echo another machine || echo nobody)"
  no_engine
  [ -n "$EXIT" ] && [ "$EXIT" != "$MAC" ] && break
  last_words "$work/line/p1.log" 4
  EXIT=""; sleep 15
done
mark 1-no-switch "$r; $(told "$work/line/p1.log"); exit $([ -n "$EXIT" ] && echo is another machine || echo not reached)"
[ -n "$EXIT" ] || { echo "the room cannot be joined from here at all: nothing to compare"; kill -TERM "$DAEMON"; exit 1; }

say "2. the session that was running: a tunnel under the block for a core's line, then a room behind it"
config first utun225 10810 10811 no
note "the tunnel" "$(ask "$(request start first utun225)")"
PID=$(field pid)
sleep 2
room_yaml "172.19.0.2,1.1.1.1:53" 10810 no
by_helper p2.log
note "the helper runs the engine behind it" "$BY_HELPER"
r=$(ready_within "$SAID" 45 "$LPID")
T=$(traced 10811 https://1.1.1.1/cdn-cgi/trace 8)
note "the engine" "$r; $(told "$SAID")"
note "the app's check, through the tunnel's listener" "$(who "$T")"
mark 2-session "$r; the check: $(who "$T")"

say "3. the tunnel is killed, and the app answers as it does: a stop that keeps the block, and the engine ended"
if [ "${PID:-0}" -gt 1 ]; then kill -9 "$PID"; fi
sleep 1.5
note "stopped, the block kept" "$(ask "$(request stop keep)")"
end_line
D=$(seen_as "" 5)
note "any program's request" "${D:+ANSWERED}${D:-refused}"
note "the block's first rules" "$(pfctl -a "$ANCHOR" -s rules 2>/dev/null | sed -n 2,3p | tr '\n' ';' | cut -c1-110)"
mark 3-killed "any program: ${D:+ANSWERED}${D:-refused}"
sleep 4

# A connect to a room behind the block that stands, as the app makes it: the
# helper runs the engine, and once its port is open the tunnel is started, and
# the check is made at once.  <mark> <words> <utun> <port> <login>
reconnect() {
  local name="$1" words="$2" utun="$3" port="$4" login="$5" verify=$(( $4 + 1 ))
  say "4.$name $words"
  room_yaml "1.1.1.1,1.1.1.1:53" "$port" "$login"
  by_helper "p4-$name.log"
  note "the helper runs the engine" "$BY_HELPER; as $(ps -o uid=,gid= -p "$LPID" 2>/dev/null | tr -s ' ')"
  local r; r=$(ready_within "$SAID" 45 "$LPID")
  note "the engine" "$r; $(told "$SAID")"
  if [[ "$r" != ready* ]]; then
    last_words "$SAID" 5
    mark "4-$name" "$words: the room was not joined ($r)"
    end_line; return
  fi
  # The app waits a moment for an engine that says it is ready and then is not.
  sleep 1.5
  config "again-$name" "$utun" "$port" "$verify" "$login"
  local started; started=$(ask "$(request start "again-$name" "$utun")")
  local t0; t0=$(now)
  note "the tunnel, started after it" "$started"
  local first second="" took1 took2="" verdict
  first=$(traced "$verify" https://1.1.1.1/cdn-cgi/trace 8); took1=$(( $(now) - t0 ))
  if [ -z "$first" ]; then second=$(traced "$verify" https://cloudflare.com/cdn-cgi/trace 8); took2=$(( $(now) - t0 )); fi
  if [ -n "$first$second" ]; then verdict="CONNECTED"; else verdict="FAILED, as on that Mac"; fi
  note "the app's check, by address" "$(who "$first") after ${took1}ms"
  [ -n "$took2" ] && note "the app's check, by name" "$(who "$second") after ${took2}ms"
  note "what the app would say" "$verdict"
  local later="" at=""
  if [ -z "$first$second" ]; then
    for _ in 1 2 3 4 5 6 7 8 9 10 11 12; do
      later=$(traced "$verify" https://1.1.1.1/cdn-cgi/trace 5)
      [ -n "$later" ] && { at=$(( $(now) - t0 )); break; }
      sleep 1
    done
    note "asked again for a minute" "${at:+$(who "$later") after ${at}ms}${at:-never an answer}"
  fi
  local through; through=$(seen_as "" 8)
  note "any program's request, through the tun" "$(who "$through")"
  note "the line itself, asked of its port" "$(who "$(by_its_port "$port" "$login")")"
  if [ "$name" = d ]; then
    # A resolver the system finds for itself through the tunnel (RFC 9462): does it list one, and keep it?
    sleep 12
    echo "      whom the system asks, the tunnel up for a quarter of a minute:"; resolvers 5
    note "what it does with it" "$(scutil --dns | grep -c 'one.one.one.one\|1\.1\.1\.1') lines of its list name 1.1.1.1"
  fi
  echo "      the tunnel said:"; tunnel_said 14
  echo "      the engine said, after its port was open:"; engine_said_after_ready "$SAID" 6
  mark "4-$name" "$verdict; address: $(who "$first") ${took1}ms${took2:+; name: $(who "$second") ${took2}ms}${at:+; later ${at}ms}; tun: $(who "$through")"
  ask "$(request stop keep)" >/dev/null
  end_line
  sleep 2
}
reconnect a "as the app: the line asks a login, port 10808, the tunnel's name again" utun225 10808 yes
reconnect b "the same with another name for the tunnel" utun226 10808 yes
reconnect c "the same with no login" utun225 10808 no
reconnect d "no login, the port the first session had" utun227 10810 no

ask "$(request line-stop)" >/dev/null
no_engine
ask "$(request stop)" >/dev/null
note "released" "$(ask "$(request release)")"
sleep 3
echo "whom the system asks, the tunnel gone and the block released:"; resolvers 5

exec 3>&-
kill -TERM "$DAEMON" 2>/dev/null
sleep 1
pfctl -a "$ANCHOR" -F all >/dev/null 2>&1
printf 'remove %s\n' "$KEY" | scutil >/dev/null 2>&1
if [ -s "$work/helper.err" ]; then echo "--- the helper said on its error stream"; tail -5 "$work/helper.err" | cut -c1-200; fi
rm -rf "$work" "$LINES/olcrtc-darwin-lab"
echo
echo "asked"
