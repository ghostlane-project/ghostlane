#!/usr/bin/env bash
# K3, the Android emulator matrix (rust-core-android.yml), and its scripts. Fails when
#   - the e2e job's matrix lacks a leg of API 23, 24, 29, 34, 35 (4 KiB pages)
#     or 35 (16 KiB pages), x86_64, or a leg does not run both every class of
#     the package (rust-core-android-e2e.sh) and the kill (-kill.sh);
#   - the soak job runs on anything but a workflow_dispatch that asks for it;
#   - RustSplitE2ETest or RustKillE2ETest is missing, or a helper file of the
#     package (ShellNet.kt, RustE2eDevice.kt) holds a test, so that the e2e
#     script would ask for it as a class;
#   - those four files or RustCoreVpnE2ETest call what Android 6 lacks: the
#     instrumentation's two-way shell (Android 12), toybox's nc (Android 8),
#     or startForegroundService (Android 8) anywhere but behind
#     RustE2eDevice.startVpn's version check;
#   - rust-core-android-host.py's `serve` (here against a stand-in xray) does
#     not write a REALITY link to 10.0.2.2 with the run's public key, its
#     server does not reach the page through a SOCKS relay that takes
#     192.0.2.1:80 alone (Xray's freedom refuses reserved addresses), its page
#     does not answer /bytes/<n>/ with <n> bytes that begin with the nonce, it
#     does not count what it served, or the run's keys and link outlive it;
#   - rust-core-android-kill.sh, on a stand-in adb whose Android does not
#     restart the killed service, passes, or K3's table (the k3 job's own
#     script) passes its cell of Android's restart; or, where Android does
#     restart it, either is red; or the table does not keep the session's
#     going down with the process and coming back by the next start as a
#     cell of its own;
#   - its `meminfo` misreads Android 6's or Android 15's dumpsys, or its
#     `soak-summary` calls a growing curve, a restarted process or failed
#     fetches flat, or a flat clean curve not flat;
#   - a script of the session does not parse (bash -n).
#
#   scripts/p6-tests/android-matrix.sh [root]     root defaults to the repository
# Needs bash, python3 and openssl.
set -euo pipefail
root="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
fail=0
bad() { echo "FAIL $*"; fail=1; }
ok() { echo "ok   $*"; }

wf="$root/.github/workflows/rust-core-android.yml"
host="$root/scripts/rust-core-android-host.py"
pkg="$root/androidApp/src/androidTestRustcore/kotlin/org/olcbox/app"

# ---- the workflow --------------------------------------------------------------
for leg in '{ api: 23, target: google_apis, pages: 4k }' '{ api: 24, target: google_apis, pages: 4k }' \
  '{ api: 29, target: google_apis, pages: 4k }' '{ api: 34, target: google_apis, pages: 4k }' \
  '{ api: 35, target: google_apis, pages: 4k }' '{ api: 35, target: google_apis_ps16k, pages: 16k }'; do
  prefix="- ${leg%\}}"
  grep -qF -- "${prefix% }," "$wf" && ok "leg $leg" || bad "rust-core-android.yml has no K3 leg $leg"
done
python3 - "$wf" << 'EOF' || fail=1
import re, sys
text = open(sys.argv[1]).read()
def job(name):
    m = re.search(r"^  %s:\n(.*?)(?=^  [a-z0-9_-]+:\n|\Z)" % re.escape(name), text, re.S | re.M)
    return m.group(1) if m else ""
e2e, soak, k3 = job("e2e"), job("soak"), job("k3")
problems = []
if "arch: x86_64" not in e2e or "target: ${{ matrix.leg.target }}" not in e2e or "api-level: ${{ matrix.leg.api }}" not in e2e:
    problems.append("the e2e job does not run each leg's own image on x86_64")
if "ram-size: ${{ matrix.leg.ram }}" not in e2e or "disk-size: ${{ matrix.leg.disk }}" not in e2e:
    problems.append("the e2e job does not boot each leg with its own RAM and data partition")
if len(re.findall(r"^\s+- \{ api: \d+, target: \w+, pages: \d+k, ram: \d+M, disk: (?:\d+M|'')\s*\}$", e2e, re.M)) != 6:
    problems.append("a K3 leg does not name its api, target, pages, ram and disk")
script = re.search(r"^\s+script: (.*)$", e2e, re.M)
if not script or "rust-core-android-e2e.sh" not in script.group(1) or "rust-core-android-kill.sh" not in script.group(1):
    problems.append("a leg does not run both rust-core-android-e2e.sh and rust-core-android-kill.sh")
elif not re.search(r'\[ "\$e" -eq 0 \] && \[ "\$k" -eq 0 \]\s*$', script.group(1)):
    problems.append("a leg's exit is not both scripts' verdicts")
if "fail-fast: false" not in e2e:
    problems.append("one red leg cancels the others (fail-fast)")
if "if: ${{ github.event_name == 'workflow_dispatch' && inputs.soak }}" not in soak:
    problems.append("the soak job runs on more than a dispatch that asks for it")
if "rust-core-android-soak.sh" not in soak:
    problems.append("the soak job does not run rust-core-android-soak.sh")
if "needs: [app, e2e]" not in k3 or "!cancelled()" not in k3:
    problems.append("the k3 table does not read every leg, red ones included")
for p in problems:
    print("FAIL " + p)
if not problems:
    print("ok   the e2e matrix runs every class and the kill on every leg; the soak only by dispatch; k3 reads every leg")
sys.exit(1 if problems else 0)
EOF

# ---- the package's new files ---------------------------------------------------
for c in RustSplitE2ETest RustKillE2ETest; do
  [ -f "$pkg/$c.kt" ] && grep -q '@Test' "$pkg/$c.kt" && ok "$c is a test class" || bad "$c.kt is missing or holds no test"
done
for h in ShellNet RustE2eDevice; do
  [ -f "$pkg/$h.kt" ] || { bad "$h.kt is missing"; continue; }
  grep -q '@Test' "$pkg/$h.kt" && bad "$h.kt holds '@Test': the e2e script would ask for it as a class" || ok "$h.kt is no class of tests"
done
for f in RustSplitE2ETest RustKillE2ETest ShellNet RustE2eDevice RustCoreVpnE2ETest; do
  [ -f "$pkg/$f.kt" ] || { bad "$f.kt is missing"; continue; }
  code=$(grep -v '^\s*\(\*\|//\|/\*\)' "$pkg/$f.kt")
  echo "$code" | grep -q 'executeShellCommandRw' && bad "$f.kt calls executeShellCommandRw (Android 12)"
  echo "$code" | grep -q 'toybox nc' && bad "$f.kt runs toybox's nc (not on Android 6 and 7)"
  if echo "$code" | grep -q 'startForegroundService'; then
    if [ "$f" != RustE2eDevice ] || ! grep -B3 'context.startForegroundService' "$pkg/$f.kt" | grep -q 'SDK_INT >= Build.VERSION_CODES.O'; then
      bad "$f.kt calls startForegroundService outside RustE2eDevice.startVpn's version check"
    fi
  fi
done
ok "A5's e2e files and RustCoreVpnE2ETest checked for calls Android 6 lacks"

# ---- the host's helper ---------------------------------------------------------
tmp=$(mktemp -d)
cleanup() {
  [ -n "${serve_pid:-}" ] && kill "$serve_pid" 2> /dev/null
  # A kill run that did not reach its finish leaves the host's helper behind.
  pkill -f -- "--work $tmp/" 2> /dev/null || true
  rm -rf "$tmp"
}
trap cleanup EXIT

cat > "$tmp/xray" << 'EOF'
#!/usr/bin/env bash
# A stand-in for Xray: two key lines for `x25519`, a listener on the inbound's port for `run`.
case "$1" in
  x25519) echo "PrivateKey: private-key-of-the-test"; echo "Password: public-key-of-the-test"; echo "Hash32: x" ;;
  run)
    port=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["inbounds"][0]["port"])' "$3")
    exec python3 -c '
import socket, sys
s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.bind(("127.0.0.1", int(sys.argv[1]))); s.listen(8)
while True:
    c, _ = s.accept(); c.close()' "$port" ;;
  *) exit 2 ;;
esac
EOF
chmod +x "$tmp/xray"
python3 "$host" serve --xray "$tmp/xray" --work "$tmp/work" > "$tmp/serve.log" 2>&1 &
serve_pid=$!
for _ in $(seq 1 100); do [ -f "$tmp/work/ready" ] && break; kill -0 "$serve_pid" 2> /dev/null || break; sleep 0.2; done
if [ ! -f "$tmp/work/ready" ]; then
  bad "serve did not get ready: $(tr '\n' ' ' < "$tmp/serve.log")"
else
  link=$(cat "$tmp/work/link")
  nonce=$(cat "$tmp/work/nonce")
  port=$(cat "$tmp/work/ready")
  case "$link" in
    "vless://"*"@10.0.2.2:$port?security=reality&encryption=none&pbk=public-key-of-the-test&sid="*"&fp=chrome&sni=reality.e2e.test&flow=xtls-rprx-vision&type=tcp#"*)
      ok "serve's link: REALITY to 10.0.2.2:$port with the run's public key" ;;
    *) bad "serve's link is not the REALITY line it should be: $link" ;;
  esac
  python3 - "$tmp/work" "$nonce" << 'EOF' || fail=1
import json, socket, sys
work, nonce = sys.argv[1], sys.argv[2].encode()
config = json.load(open(work + "/server.json"))
rules = config["routing"]["rules"]
page_out = [o for o in config["outbounds"] if o["tag"] == "page"][0]
problems = []
# Xray's freedom refuses private and reserved destinations over VLESS: the page is reached
# through a SOCKS relay on loopback instead, which takes 192.0.2.1:80 and nothing else.
servers = page_out.get("settings", {}).get("servers", [])
if page_out["protocol"] != "socks" or len(servers) != 1 or servers[0].get("address") != "127.0.0.1":
    problems.append("the page is not one SOCKS relay on loopback: %r" % page_out)
    relay = None
else:
    relay = int(servers[0]["port"])
if any(o["protocol"] == "freedom" and o.get("settings") for o in config["outbounds"]):
    problems.append("a freedom outbound of the server is set to reach more than its default")
if config["outbounds"][0]["tag"] != "direct" or rules != [{"type": "field", "ip": ["192.0.2.1"], "outboundTag": "page"}]:
    problems.append("the server does not send 192.0.2.1 alone to the page and the rest straight out")
if config["inbounds"][0]["streamSettings"]["realitySettings"]["privateKey"] != "private-key-of-the-test":
    problems.append("the server does not hold the run's private key")
def read_all(s):
    data = b""
    while True:
        chunk = s.recv(65536)
        if not chunk:
            return data
        data += chunk
def through_relay(dest, port, path):
    """What the relay gives for a CONNECT to dest:port and a GET of path: (reply code, answer)."""
    with socket.create_connection(("127.0.0.1", relay), timeout=5) as s:
        s.sendall(b"\x05\x01\x00")
        if s.recv(2) != b"\x05\x00":
            return None, b""
        s.sendall(b"\x05\x01\x00\x01" + socket.inet_aton(dest) + port.to_bytes(2, "big"))
        reply = b""
        while len(reply) < 10:
            chunk = s.recv(10 - len(reply))
            if not chunk:
                break
            reply += chunk
        if len(reply) < 2 or reply[1] != 0:
            return (reply[1] if len(reply) > 1 else None), b""
        s.sendall(b"GET " + path + b" HTTP/1.0\r\n\r\n")
        return 0, read_all(s)
def get(path):
    code, data = through_relay("192.0.2.1", 80, path)
    if code != 0:
        problems.append("the relay refused CONNECT 192.0.2.1:80 (%r)" % code)
    return data
before = int(open(work + "/hits").read())
body = get(b"/bytes/100000/x").split(b"\r\n\r\n", 1)[-1] if relay else b""
if len(body) != 100000 or not body.startswith(nonce + b"\n"):
    problems.append("the page's /bytes/100000/ answer is %d bytes, nonce first: %s" % (len(body), body.startswith(nonce)))
if relay:
    get(b"/plain")
    for dest, port in (("192.0.2.2", 80), ("192.0.2.1", 443), ("127.0.0.1", 80)):
        code, _ = through_relay(dest, port, b"/x")
        if code == 0:
            problems.append("the relay took CONNECT %s:%d: it takes 192.0.2.1:80 alone" % (dest, port))
after = int(open(work + "/hits").read())
if after != before + 2:
    problems.append("the page counted %d requests, not 2" % (after - before))
for p in problems:
    print("FAIL " + p)
if not problems:
    print("ok   serve's server and page: 192.0.2.1:80 alone to the page through the relay, the nonce first, every request counted")
sys.exit(1 if problems else 0)
EOF
  kill "$serve_pid"
  wait "$serve_pid" 2> /dev/null || true
  serve_pid=
  left=$(cd "$tmp/work" && ls server.json target.key target.crt link 2> /dev/null || true)
  [ -z "$left" ] && ok "serve left none of the run's keys, config or link" || bad "serve left behind: $left"
fi

# ---- the kill, and its cells in K3's table ----------------------------------------
# A stand-in for adb and the emulator behind it: one app process, its tun0 and its log.
# SIGKILL (and SIGABRT) takes the process and tun0; with FAKE_RESTART=android, Android
# starts the service again in a new process at once, as START_REDELIVER_INTENT asks.
mkdir -p "$tmp/fake"
cat > "$tmp/fake/adb" << 'EOF'
#!/usr/bin/env bash
st=$FAKE_STATE
pid() { cat "$st/pid" 2> /dev/null; }
up() {
  local n
  n=$(($(cat "$st/next" 2> /dev/null || echo 4000) + 1))
  echo "$n" > "$st/next"; echo "$n" > "$st/pid"; : > "$st/tun"; : > "$st/service"
  printf 'D/OlcboxVpnService(%s): Transport engine: Rust core\nD/OlcboxVpnService(%s): VPN tunnel established\n' "$n" "$n" >> "$st/log"
}
down() { rm -f "$st/pid" "$st/tun"; }
case "$1" in
  install) echo Success ;;
  root | unroot | wait-for-device) ;;
  logcat) case "$2" in -c) : > "$st/log" ;; -G) ;; *) cat "$st/log" 2> /dev/null ;; esac ;;
  shell)
    shift
    cmd="$*"
    case "$cmd" in
      "am instrument "*) echo "INSTRUMENTATION_STATUS_CODE: 0" ;;
      "pm path "*) echo "package:/data/app/${cmd#pm path }-1/base.apk" ;;
      "getprop ro.build.version.sdk") echo 35 ;;
      "getprop "*) echo x ;;
      id) echo "uid=2000(shell) gid=2000(shell)" ;;
      "uname -r") echo 6.6 ;;
      "cat /proc/sys/kernel/random/entropy_avail") echo 256 ;;
      "pidof "*) pid ;;
      "cat /proc/"*"/cmdline") [ -n "$(pid)" ] && [ "$cmd" = "cat /proc/$(pid)/cmdline" ] && printf 'org.proofkit.app\0' ;;
      ps | "ps -A") echo "USER PID PPID NAME" ;;
      "ls -d /proc/"*) if [ -n "$(pid)" ] && [ "${cmd#ls -d /proc/}" = "$(pid)" ]; then echo "/proc/$(pid)"; else echo "no such file" >&2; fi ;;
      "cat /proc/net/dev") echo "lo: 0"; [ -f "$st/tun" ] && echo "tun0: 0" ;;
      "dumpsys activity services "*) [ -f "$st/service" ] && echo "  * ServiceRecord{1 u0 org.proofkit.app/org.olcbox.app.vpn.service.OlcboxVpnService}" ;;
      *DEBUG_START_VPN* | "su 0 am start"*) up; echo "Broadcast completed: result=0" ;;
      *DEBUG_STOP_VPN*) down; rm -f "$st/service" ;;
      "su 0 kill -9 "* | "kill -9 "* | "su 0 kill -6 "*)
        if [ "${cmd##* }" = "$(pid)" ]; then
          down
          [ "${FAKE_RESTART:-}" = android ] && up
        fi ;;
      *"ShellNet fetch"*)
        if [ -f "$st/tun" ]; then
          echo $(($(cat "$FAKE_HITS") + 1)) > "$FAKE_HITS"
          echo "RESULT found"
        else
          echo "RESULT none"
        fi ;;
      *) echo "stand-in adb: no answer for: $cmd" >&2; exit 1 ;;
    esac ;;
  *) echo "stand-in adb: no answer for: $*" >&2; exit 1 ;;
esac
EOF
real_sleep=$(command -v sleep)
printf '#!/bin/sh\nexec %s 0.05\n' "$real_sleep" > "$tmp/fake/sleep"
chmod +x "$tmp/fake/adb" "$tmp/fake/sleep"

# A package of one class, and two legs: one whose Android does not restart the service, one whose does.
k3root="$tmp/k3root"
mkdir -p "$k3root/androidApp/src/androidTestRustcore/kotlin/org/olcbox/app" "$tmp/apks"
printf 'package org.olcbox.app\n\nclass StandInE2ETest {\n    @Test fun x() {}\n}\n' \
  > "$k3root/androidApp/src/androidTestRustcore/kotlin/org/olcbox/app/StandInE2ETest.kt"
: > "$tmp/apks/app.apk"
: > "$tmp/apks/app-androidTest.apk"
kill_leg() {
  local leg=$1 restart=$2 d="$k3root/out/k3/k3-$1" rc
  mkdir -p "$d/e2e" "$tmp/state-$leg"
  printf 'INSTRUMENTATION_STATUS: class=org.olcbox.app.StandInE2ETest\nINSTRUMENTATION_STATUS: test=x\nINSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS: class=org.olcbox.app.StandInE2ETest\nINSTRUMENTATION_STATUS: test=x\nINSTRUMENTATION_STATUS_CODE: 0\n' > "$d/e2e/am.txt"
  rc=0
  PATH="$tmp/fake:$PATH" FAKE_STATE="$tmp/state-$leg" FAKE_RESTART="$restart" FAKE_HITS="$d/kill/host/hits" \
    XRAY_SERVER="$tmp/xray" timeout 150 bash "$root/scripts/rust-core-android-kill.sh" "$tmp/apks" "$d/kill" > "$tmp/kill-$leg.log" 2>&1 || rc=$?
  echo "$rc"
}
rc=$(kill_leg norestart none)
[ "$rc" -ne 0 ] && ok "the kill is red where Android does not restart the service" \
  || bad "the kill passed where Android did not restart the service: $(grep '^RESULT' "$k3root/out/k3/k3-norestart/kill/results.txt" | tail -n 4 | tr '\n' ' ')"
rc=$(kill_leg restart android)
[ "$rc" -eq 0 ] && ok "the kill passes where Android restarts the service" \
  || bad "the kill was red where Android restarted the service: $(grep '^RESULT' "$k3root/out/k3/k3-restart/kill/results.txt" | tail -n 4 | tr '\n' ' ')"

# K3's table, the k3 job's own script, on those two legs.
python3 - "$wf" "$tmp/table.py" << 'EOF' || fail=1
import re, sys, textwrap
text = open(sys.argv[1]).read()
m = re.search(r"^  k3:\n(.*?)(?=^  [a-z0-9_-]+:\n|\Z)", text, re.S | re.M)
script = re.search(r"python3 - <<'EOF'\n(.*?)\n[ \t]*EOF\n", m.group(1) if m else "", re.S)
if not script:
    print("FAIL the k3 job holds no table script")
    sys.exit(1)
open(sys.argv[2], "w").write(textwrap.dedent(script.group(1)) + "\n")
EOF
if [ -f "$tmp/table.py" ]; then
  table_rc=0
  (cd "$k3root" && LEGS="norestart restart" GITHUB_STEP_SUMMARY="$tmp/summary.md" python3 "$tmp/table.py") > "$tmp/table.txt" 2>&1 || table_rc=$?
  python3 - "$tmp/table.txt" "$table_rc" << 'EOF' || fail=1
import sys
lines = open(sys.argv[1]).read().splitlines()
rows = {}
for line in lines:
    cells = [c.strip() for c in line.strip().strip("|").split("|")]
    if line.startswith("| ") and len(cells) == 3:
        rows[cells[0]] = cells[1:]
problems = []
down = rows.get("kill -9, down and back")
android = rows.get("kill -9, Android's restart")
if not down:
    problems.append("the table has no row of the session going down with the process and coming back")
elif down != ["pass, back by the next start", "pass, back by Android"]:
    problems.append("the down-and-back row is %r, not a pass by the next start and a pass by Android" % down)
if not android:
    problems.append("the table has no row of Android's restart of the service")
else:
    if not android[0].startswith("FAIL") or "Android did not restart" not in android[0]:
        problems.append("where Android did not restart the service its cell is %r, not a FAIL that says so" % android[0])
    if android[1] != "pass":
        problems.append("where Android restarted the service its cell is %r, not a pass" % android[1])
if rows.get("StandInE2ETest") != ["pass 1", "pass 1"]:
    problems.append("the class's row is %r" % rows.get("StandInE2ETest"))
if sys.argv[2] == "0" or "1 cell(s) did not pass" not in lines:
    problems.append("the table did not fail on the one cell of Android's restart (exit %s)" % sys.argv[2])
for p in problems:
    print("FAIL " + p)
if problems:
    print("\n".join("     " + l for l in lines))
else:
    print("ok   K3's table: Android's restart is a cell of its own, red where it did not happen; down and back is another")
sys.exit(1 if problems else 0)
EOF
fi

old_meminfo='Applications Memory Usage (in Kilobytes):
Uptime: 123 Realtime: 123

** MEMINFO in pid 2345 [org.proofkit.app] **
                   Pss  Private  Private  Swapped     Heap     Heap     Heap
                 Total    Dirty    Clean    Dirty     Size    Alloc     Free
                ------   ------   ------   ------   ------   ------   ------
  Native Heap    21000    20900        0        0    30000    25000     5000
  Dalvik Heap     9000     8800        0        0    12000    10000     2000
        TOTAL    81234    70000     5000        0    42000    35000     7000

 App Summary
                       Pss(KB)
                        ------
           Java Heap:     9100
         Native Heap:    20900
                Code:    30000
               Stack:      300
            Graphics:        0
       Private Other:     4000
              System:    16934

               TOTAL:    81234       TOTAL SWAP (KB):        0'
new_meminfo='** MEMINFO in pid 4567 [org.proofkit.app] **
                   Pss      Pss  Private  Private  SwapPss      Rss     Heap     Heap     Heap
                 Total    Clean    Dirty    Clean    Dirty    Total     Size    Alloc     Free
                ------   ------   ------   ------   ------   ------   ------   ------   ------
  Native Heap    33000        0    32900        0       12    34000    40000    36000     4000
        TOTAL   120345    40000    70000     9000       76   250000    60000    50000    10000

 App Summary
                       Pss(KB)                        Rss(KB)
                        ------                         ------
           Java Heap:    11000                          30672
         Native Heap:    32900                          34000
                Code:    50000                          90000
           TOTAL PSS:   120345            TOTAL RSS:   250000       TOTAL SWAP PSS:       76'
got=$(printf '%s\n' "$old_meminfo" | python3 "$host" meminfo)
[ "$got" = "81234 - 20900 9100" ] && ok "meminfo reads Android 6's dumpsys (App Summary)" || bad "meminfo of Android 6's dumpsys: '$got', not '81234 - 20900 9100'"
got=$(printf '%s\n' "$new_meminfo" | python3 "$host" meminfo)
[ "$got" = "120345 250000 32900 11000" ] && ok "meminfo reads Android 15's dumpsys (App Summary)" || bad "meminfo of Android 15's dumpsys: '$got', not '120345 250000 32900 11000'"

# A curve of 120 one-minute samples: PSS of <base> KiB plus <growth> KiB a minute.
curve() {
  local growth=$1 pid_change=${2:-} failing=${3:-0} m pid
  echo "minute,epoch,pid,pss_kb,rss_kb,native_kb,java_kb,ok,fail,bytes"
  for m in $(seq 0 119); do
    pid=4567
    [ -n "$pid_change" ] && [ "$m" -ge "$pid_change" ] && pid=4999
    f=0
    [ "$failing" -gt 0 ] && [ $((m % 10)) -eq 0 ] && f=1
    echo "$m,$((1700000000 + m * 60)),$pid,$((100000 + (m * 37) % 900 + m * growth)),200000,30000,10000,$((20 - f)),$f,5242880"
  done
}
verdict() { python3 "$host" soak-summary --csv "$1" > "$1.txt" && echo flat || echo "not flat"; }
curve 0 > "$tmp/flat.csv"
curve 300 > "$tmp/growing.csv"
curve 0 60 > "$tmp/restarted.csv"
curve 0 "" 1 > "$tmp/failing.csv"
[ "$(verdict "$tmp/flat.csv")" = flat ] && grep -q '^VERDICT flat' "$tmp/flat.csv.txt" && ok "soak-summary: a flat clean curve is flat" || bad "soak-summary called a flat clean curve not flat: $(cat "$tmp/flat.csv.txt")"
[ "$(verdict "$tmp/growing.csv")" = "not flat" ] && ok "soak-summary: 300 KiB a minute is not flat" || bad "soak-summary called a growing curve flat"
[ "$(verdict "$tmp/restarted.csv")" = "not flat" ] && ok "soak-summary: a restarted process fails the soak" || bad "soak-summary passed a soak whose process restarted"
[ "$(verdict "$tmp/failing.csv")" = "not flat" ] && ok "soak-summary: a tenth of the rounds failing fails the soak" || bad "soak-summary passed a soak whose fetches failed"

for s in session kill soak e2e; do
  bash -n "$root/scripts/rust-core-android-$s.sh" && ok "rust-core-android-$s.sh parses" || bad "rust-core-android-$s.sh does not parse"
done

exit "$fail"
