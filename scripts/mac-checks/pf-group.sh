#!/usr/bin/env bash
# K8's check of the engine in the pf group, on a Mac: with the block armed the
# real ghostlane-engine fetches a page from a local Xray server through the
# utun, and the same program started by its user outside the group is refused.
#
# The helper is built by pr-checks.yml from every file of
# desktopApp/tunneldaemon (its own main replaced by scripts/macos-helper-check/
# main.swift), and this script is run as root with four arguments:
#
#   sudo bash scripts/mac-checks/pf-group.sh <helper-check> <hev> <user> <stand-in-line>
#
# The fifth is the engine this release carries, built and cached by the Core
# build at the pin (core-build.yml, its Apple job). Until pr-checks.yml hands
# it over, the check says so and fails: a check that skips itself would leave
# the pf group unproven. GHOSTLANE_ENGINE names it for a hand run.
#
# Run as root on a Mac with a moment of network downtime: the helper loads a
# pf block and points the system's resolver at a tun. A trap takes all of it
# away, whatever happens.
set -uo pipefail
HELPER="${1:?the helper built as the app builds it}"
HEV="${2:?hev-socks5-tunnel built from the app's tree}"
WHO="${3:?the user who asks}"
STAND_IN_BUILT="${4:?a stand-in for a line's program}"
ENGINE="${5:-${GHOSTLANE_ENGINE:-}}"
WHO_UID="$(id -u "$WHO")"
WHO_GID="$(id -g "$WHO")"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
ANCHOR='com.apple/100.org.olcbox.app.hold'
KEY='State:/Network/Service/org.olcbox.app.tunnel/DNS'
LINES='/Library/Application Support/org.olcbox.app.lines'
GROUP=7302243
PORT=18947
UUID='00010203-0405-0607-0809-0a0b0c0d0e0f'
FIXTURE="$root/sharedUI/src/jvmTest/resources/macos-helper/tun-request.json"
work="$(cd /tmp && pwd -P)/ghostlane-pf-group.$$"
mkdir -p "$work/line"
chmod 755 "$work"

failed=0
say() { printf '\n== %s\n' "$*"; }
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
alive() { kill -0 "$1" 2>/dev/null && echo there || echo gone; }
leave() {
  exec 3>&- 2>/dev/null
  [ -n "${DAEMON:-}" ] && kill -9 "$DAEMON" 2>/dev/null
  [ -n "${ENGINE_PID:-}" ] && kill -9 "$ENGINE_PID" 2>/dev/null
  [ -n "${LINE:-}" ] && kill "$LINE" 2>/dev/null
  [ -n "${XRAY_PID:-}" ] && kill "$XRAY_PID" 2>/dev/null
  printf 'remove %s\n' "$KEY" | scutil >/dev/null 2>&1
  pfctl -a "$ANCHOR" -F all >/dev/null 2>&1
  dscacheutil -flushcache 2>/dev/null
  killall -HUP mDNSResponder 2>/dev/null
  rm -rf "$work" "$LINES/ghostlane-engine"
  rmdir "$LINES" 2>/dev/null
}
trap leave EXIT

say "the machine"
sw_vers | tr '\n' ' '; echo
IF=$(route -n get default | awk '/interface:/{print $2}')
MINE=$(ipconfig getifaddr "$IF")
SERVER=$(dig +short +time=3 +tries=2 example.com A | grep -E '^[0-9.]+$' | head -1)
expect "there is a way out, $IF at $MINE" "$MINE" '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$'
expect "the Core build's engine is handed to this check" "${ENGINE:-nothing}" '^/.+/ghostlane-engine$'
expect "that is a program" "$([ -x "$ENGINE" ] && echo yes || echo no)" '^yes$'
expect "hev is a program for this Mac" "$("$HEV" --version 2>&1 | grep -c '^Version: ')" '^1$'
"$ENGINE" version > "$work/version.out" 2> "$work/version.log" || true
expect "it speaks the app's protocol" "$(python3 -I - "$work/version.out" <<'PY'
import json, sys
lines = [l for l in open(sys.argv[1], encoding="utf-8").read().splitlines() if l.strip()]
try: v = json.loads(lines[0]) if lines else {}
except Exception: v = {}
print("protocol=%s" % v.get("protocol"))
PY
)" '^protocol=1$'

# The page is on this Mac's loopback; only Xray's freedom redirect reaches it.
# The line's own address is a TEST-NET-3 one nobody routes, so a request that
# did not go through the line cannot reach the page.
cat > "$work/page.py" <<'PY'
import http.server, sys
class Page(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        body = b"the engine in the pf group fetched this page\n"
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def log_message(self, *args): pass
server = http.server.HTTPServer(("127.0.0.1", 0), Page)
with open(sys.argv[1] + ".port", "w") as f:
    f.write(str(server.server_address[1]))
with open(sys.argv[1] + ".ready", "w") as f:
    f.write("ready")
server.serve_forever()
PY
python3 -I "$work/page.py" > "$work/page.log" 2>&1 &
PAGE=$!
for _ in $(seq 1 50); do [ -e "$work/page.py.ready" ] && break; sleep 0.1; done
PAGE_PORT="$(cat "$work/page.py.port" 2>/dev/null)"
expect "the page listens" "$PAGE_PORT" '^[0-9]+$'

# Xray 26.4.25 as XTLS publishes it, checked against the pinned sha256, as the
# other desktop checks do. It is the line's far end and runs as the user.
xray_version=26.4.25
case "$(uname -m)" in
  arm64) asset=Xray-macos-arm64-v8a; sum=b9d5df9f37e482f17289b105341218aaa614363a29ebb2b6054805e5396d50fe ;;
  x86_64) asset=Xray-macos-64; sum=fde231da0e7edd29d7a7b51fee13cae2fa05dfc6583f31a15945d5c3302aedf5 ;;
  *) echo "FAIL  no Xray build pinned for $(uname -m)"; exit 1 ;;
esac
mkdir -p "$work/xray"
curl -fsSL --retry 3 -o "$work/xray/$asset.zip" \
  "https://github.com/XTLS/Xray-core/releases/download/v$xray_version/$asset.zip" || { echo "FAIL  Xray $xray_version could not be fetched"; exit 1; }
XRAY="$(python3 -I - "$work/xray/$asset.zip" "$sum" "$work/xray" <<'PY'
import hashlib, os, stat, sys, zipfile
archive, want, out = sys.argv[1:4]
got = hashlib.sha256(open(archive, "rb").read()).hexdigest()
if got != want: sys.exit("Xray hashes to %s, the pin is %s" % (got, want))
with zipfile.ZipFile(archive) as z: z.extract("xray", out)
path = os.path.join(out, "xray")
os.chmod(path, os.stat(path).st_mode | stat.S_IXUSR)
print(path)
PY
)" || { echo "FAIL  Xray: $XRAY"; exit 1; }
expect "Xray says what it is" "$("$XRAY" version | sed -n 1p)" '^Xray [0-9]+\.[0-9]+\.[0-9]+'
keys="$("$XRAY" x25519)"
private="$(printf '%s\n' "$keys" | sed -n '/:/p' | sed -n '1s/^[^:]*:[[:space:]]*//p')"
public="$(printf '%s\n' "$keys" | sed -n '/:/p' | sed -n '2s/^[^:]*:[[:space:]]*//p')"
expect "Xray made a reality key" "$public" '^[A-Za-z0-9_-]{43}$'
python3 -I - "$work/xray.json" "$PORT" "$private" "$PAGE_PORT" "$MINE" <<'PY'
import json, sys
path, port, key, page, listen = sys.argv[1:6]
json.dump({
    "log": {"loglevel": "warning"},
    "inbounds": [{"tag": "in", "listen": listen, "port": int(port), "protocol": "vless",
        "settings": {"clients": [{"id": "00010203-0405-0607-0809-0a0b0c0d0e0f", "flow": "xtls-rprx-vision"}], "decryption": "none"},
        "streamSettings": {"network": "raw", "security": "reality", "realitySettings": {
            "dest": "127.0.0.1:9", "serverNames": ["reality.test"], "privateKey": key, "shortIds": ["ab12cd34"]}}}],
    "outbounds": [{"tag": "direct", "protocol": "freedom", "settings": {
        "redirect": "127.0.0.1:%s" % page, "ipsBlocked": [],
        "finalRules": [{"action": "allow", "ip": ["127.0.0.0/8"]}]}}],
}, open(path, "w"), indent=2)
PY
as_user "$XRAY" run -c "$work/xray.json" > "$work/xray.log" 2>&1 &
XRAY_PID=$!
for _ in $(seq 1 100); do python3 -I -c 'import socket,sys;socket.create_connection((sys.argv[2],int(sys.argv[1])),0.3).close()' "$PORT" "$MINE" 2>/dev/null && break; sleep 0.2; done
expect "Xray listens for the line" "$(python3 -I -c 'import socket,sys;socket.create_connection((sys.argv[2],int(sys.argv[1])),0.5).close();print("listening")' "$PORT" "$MINE" 2>/dev/null)" '^listening$'

# A minimal session of the app's protocol: a SOCKS inbound on a free port and
# one VLESS REALITY outbound to the local Xray. Port 0 asks the core for one.
python3 -I - "$work/session.json" "$PORT" "$MINE" "$public" <<'PY'
import json, os, sys
path, port, address, public = sys.argv[1:5]
session = {
    "version": 1,
    "core": {
        "log": {"loglevel": "warning", "access": "none"},
        "inbounds": [{"tag": "socks-in", "protocol": "socks", "listen": "127.0.0.1", "port": 0}],
        "outbounds": [{
            "tag": "proxy", "protocol": "vless",
            "settings": {"vnext": [{"address": address, "port": int(port), "users": [
                {"id": "00010203-0405-0607-0809-0a0b0c0d0e0f", "encryption": "none", "flow": "xtls-rprx-vision"}]}]},
            "streamSettings": {"network": "raw", "security": "reality", "realitySettings": {
                "serverName": "reality.test", "fingerprint": "chrome", "publicKey": public,
                "shortId": "ab12cd34"}},
        }],
    },
}
with open(path, "w") as f: json.dump(session, f)
os.chmod(path, 0o600)
PY
chown "$WHO" "$work/session.json"

# The helper, asked over a pipe as in the other Mac checks. The engine is a
# real binary from the Core build; the stand-in is for the earlier checks.
cp "$STAND_IN_BUILT" "$work/line/stand-in-line"
chown -R "$WHO" "$work/line"
mkfifo "$work/ask"
"$HELPER" /nonexistent/sing-box "$WHO_UID" "$HEV" <"$work/ask" >"$work/said" 2>"$work/helper.err" &
DAEMON=$!
exec 3>"$work/ask"
ask() {
  local before; before=$(wc -l < "$work/said")
  printf '%s\n' "$1" >&3
  for _ in $(seq 1 300); do [ "$(wc -l < "$work/said")" -gt "$before" ] && break; sleep 0.1; done
  tail -1 "$work/said" | python3 -c '
import json,sys
try: r=json.loads(sys.stdin.readline())
except Exception: print("no reply"); sys.exit(0)
print("ok=%s state=%s hold=%s protocol=%s line=%s error=%s" % (r.get("ok"), r.get("state"), r.get("hold"), r.get("protocol"), r.get("line",0), r.get("error")))'
}
line_pid() { tail -1 "$work/said" | python3 -c 'import json,sys; print(json.loads(sys.stdin.readline()).get("line",0))'; }
tunnel_pid() { tail -1 "$work/said" | python3 -c 'import json,sys; print(json.loads(sys.stdin.readline()).get("pid",0))'; }
request_line() {
  python3 -I - "$1" "$2" "${@:3}" <<'PY'
import json, sys
program, output = sys.argv[1:3]
print(json.dumps({"verb": "line", "program": program, "output": output, "arguments": sys.argv[3:]}))
PY
}
request_tun() {
  python3 -I - "$FIXTURE" "$1" "$2" <<'PY'
import json, sys
request = json.load(open(sys.argv[1]))
name, away = sys.argv[2:4]
request["tun"]["name"] = name
# The engine's SOCKS client asks for a TEST-NET-3 page: that request enters
# the utun. The VLESS server is kept out of the tun so the engine's own dial
# reaches it on the physical interface, where only the pf group lets it out.
request["exclude"] = [away + "/32"]
request["hold"] = {"interface": name, "pass": [away]}
print(json.dumps(request))
PY
}

say "a tunnel under hev behind the block"
expect "the helper speaks the fifth protocol" "$(ask '{"verb":"status"}')" '^ok=True state=idle hold=False protocol=5 '
expect "the tunnel runs and the block stands" "$(ask "$(request_tun utun239 "$MINE")")" '^ok=True state=running hold=True protocol=5 '
expect "hev runs in the group the block lets out" "$(ps -o gid= -p "$(tunnel_pid)" 2>/dev/null | tr -d ' ')" "^$GROUP\$"

say "the real engine, started by the helper"
: > "$work/engine.log"
chown "$WHO" "$work/engine.log"
chmod 600 "$work/engine.log"
# The engine's own file: the daemon copies the program as root and runs it as
# the user, who must own the session file for the engine to read it.
cp "$ENGINE" "$work/line/ghostlane-engine"
chown "$WHO" "$work/line/ghostlane-engine"
expect "the helper starts the engine" "$(ask "$(request_line "$work/line/ghostlane-engine" "$work/engine.log" run --session "$work/session.json")")" '^ok=True state=running hold=True protocol=5 line=[1-9][0-9]* '
LPID=$(line_pid)
expect "it runs as the user who asked" "$(ps -o uid= -p "$LPID" | tr -d ' ')" "^$WHO_UID\$"
expect "in the group the block lets out" "$(ps -o gid= -p "$LPID" | tr -d ' ')" "^$GROUP\$"
expect "and stays there: stdin is held for the engine's lifetime" "$(sleep 2; alive "$LPID")" '^there$'
expect "it says it is ready" "$(grep -c '"event":"ready"' "$work/engine.log" 2>/dev/null)" '^[1-9][0-9]*$'
PROXY="$(python3 -I - "$work/engine.log" <<'PY'
import json, sys
for line in open(sys.argv[1], encoding="utf-8"):
    try: event = json.loads(line)
    except Exception: continue
    if event.get("event") == "ready":
        for inbound in event.get("inbounds", []):
            if inbound.get("tag") == "socks-in":
                print(inbound["address"]); break
        break
PY
)"
expect "the engine's SOCKS port is on loopback" "$PROXY" '^127\.0\.0\.1:[0-9]+$'
expect "the page is not reached without the line" "$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "http://203.0.113.10:$PAGE_PORT/" 2>/dev/null)" '^000$'
expect "the engine fetches the page through the line and the utun" "$(as_user curl -s --max-time 8 --socks5 "$PROXY" "http://203.0.113.10:$PAGE_PORT/" 2>&1 | head -1)" '^the engine in the pf group fetched this page$'
expect "the fetch went to Xray" "$(grep -c 'accepted' "$work/xray.log" 2>/dev/null)" '^[1-9][0-9]*$'

say "a stand-in outside the group is refused"
: > "$work/outside.log"
chown "$WHO" "$work/outside.log"
expect "started by its user, it cannot reach the page" "$(as_user "$STAND_IN_BUILT" "$MINE" 127.0.0.1 443 once | tail -1)" '^bound no answer$'

say "nothing left behind"
expect "the helper ends the engine" "$(ask '{"verb":"line-stop"}')" '^ok=True state=running hold=True protocol=5 line=0 '
expect "it is gone" "$(alive "$LPID")" '^gone$'
expect "the tunnel is stopped" "$(ask '{"verb":"stop"}')" '^ok=True state=idle hold=False protocol=5 line=0 '
expect "no rule stands" "$(pfctl -a "$ANCHOR" -s rules 2>/dev/null | grep -c .)" '^0$'

kill -9 "$XRAY_PID" "$PAGE" 2>/dev/null
if [ -s "$work/helper.err" ]; then echo "--- the helper said on its error stream"; tail -5 "$work/helper.err" | cut -c1-200; fi
echo
[ "$failed" -eq 0 ] && echo "the engine runs in the pf group" || echo "the engine does not run in the pf group"
exit "$failed"
