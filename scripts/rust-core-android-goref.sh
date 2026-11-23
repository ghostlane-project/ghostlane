#!/usr/bin/env bash
# rust-core-android-goref.sh <out dir>
#
# A reference run on the booted emulator, no verdict: the app's own check of a
# tunnel (TunnelVerifier, through RustCoreVpnE2ETest's
# theTunnelCheckThroughAPortTheScriptNames) through the SOCKS port of a Go core
# on this device. The core is the official Xray server binary ($XRAY_SERVER,
# linux amd64, static Go), with the inbound the Go path writes for a tun
# session (socks, auth password, the app's login) on one port and the same
# without a login on another, and a freedom outbound: the probe goes to its own
# servers on the internet, as in the app. Each check is a RESULT line in
# <out dir>/results.txt. Needs the app and its test APK installed (the kill
# run leaves them). Always exits 0.
set -uo pipefail

out=${1:?usage: rust-core-android-goref.sh <out dir>}
mkdir -p "$out"
out=$(cd "$out" && pwd)
: > "$out/results.txt"
runner=org.proofkit.app.test/androidx.test.runner.AndroidJUnitRunner
say() { echo "RESULT $*" | tee -a "$out/results.txt"; }

[ -n "${XRAY_SERVER:-}" ] && [ -x "$XRAY_SERVER" ] || { say "no Go core: XRAY_SERVER names no binary"; exit 0; }
user="g$RANDOM$RANDOM"
pass="p$RANDOM$RANDOM"
cat > "$out/go.json" << EOF
{"log": {"loglevel": "warning"},
 "inbounds": [
  {"tag": "login", "listen": "127.0.0.1", "port": 10991, "protocol": "socks",
   "settings": {"auth": "password", "accounts": [{"user": "$user", "pass": "$pass"}], "udp": false}},
  {"tag": "open", "listen": "127.0.0.1", "port": 10992, "protocol": "socks",
   "settings": {"auth": "noauth", "udp": false}}],
 "outbounds": [{"tag": "direct", "protocol": "freedom"}]}
EOF
if ! adb push "$XRAY_SERVER" /data/local/tmp/pk-goref-xray > /dev/null 2>&1 \
  || ! adb push "$out/go.json" /data/local/tmp/pk-goref.json > /dev/null 2>&1; then
  say "no Go core: the push to the device failed"
  exit 0
fi
adb shell "chmod 755 /data/local/tmp/pk-goref-xray; cd /data/local/tmp && (./pk-goref-xray run -config pk-goref.json > pk-goref.log 2>&1 &)" > /dev/null 2>&1
sleep 4
adb shell "cat /data/local/tmp/pk-goref.log" 2> /dev/null | tr -d '\r' | head -n 8 | sed 's/^/xray: /'

check() {
  local label=$1
  shift
  adb logcat -c > /dev/null 2>&1 || true
  adb shell "am instrument -w -r -e class org.olcbox.app.RustCoreVpnE2ETest#theTunnelCheckThroughAPortTheScriptNames $* $runner" > "$out/am-$label.txt" 2>&1
  line=$(adb logcat -d -s RustCoreE2E:I 2> /dev/null | tr -d '\r' | grep -o 'RESULT .*' | tail -n 1)
  say "Go core (Xray $(basename "$(dirname "$XRAY_SERVER")")), $label: ${line#RESULT }"
}
check "socks with the app's login" -e tunnelCheckPort 10991 -e tunnelCheckUser "$user" -e tunnelCheckPass "$pass"
check "socks with no login" -e tunnelCheckPort 10992
adb shell "rm -f /data/local/tmp/pk-goref.json" > /dev/null 2>&1
exit 0
