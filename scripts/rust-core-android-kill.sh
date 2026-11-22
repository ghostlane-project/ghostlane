#!/usr/bin/env bash
# rust-core-android-kill.sh <apk dir> <out dir>
#
# kill -9 of the VPN service's process on the Rust core, and Android's restart
# of it, on the booted emulator (a leg of K3 in rust-core-android.yml). The
# session is rust-core-android-session.sh's: armed by RustKillE2ETest, started
# by the app's debug receiver outside any instrumentation, on a VLESS REALITY
# line to the official Xray server $XRAY_SERVER on the host.
#
#   1. the shell's GET of 192.0.2.1 comes back with the host page's nonce;
#   2. SIGKILL to the app's process (su on the emulator image, else adb root);
#   3. Android restarts the service (it answers START_REDELIVER_INTENT) in a
#      new process, on the Rust core, the tunnel and tun0 come back, and the
#      shell's GET is answered again;
#   4. the debug receiver's stop takes the tun and the service down.
#
# Every step is a RESULT line in <out dir>/results.txt; logcat, the host's
# logs and the instrumentation's output stay in <out dir>. Non-zero when a
# step failed. No carrier room, no secret.
set -uo pipefail

apks=${1:?usage: rust-core-android-kill.sh <apk dir> <out dir>}
# shellcheck source=scripts/rust-core-android-session.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/rust-core-android-session.sh"
session_open "$apks" "${2:?usage: rust-core-android-kill.sh <apk dir> <out dir>}" KILL

session_start
shell_fetch before-kill || fail "no GET through the session before the kill"
pid1=$(app_pid)
[ -n "$pid1" ] || fail "no process for $pkg"
say "the session's process: $pid1"

# ---- SIGKILL -------------------------------------------------------------------
established=$(log_count 'VPN tunnel established')
on_rust=$(log_count 'Transport engine: Rust core')
killed_by=
dev su 0 kill -9 "$pid1" > /dev/null
sleep 2
if alive "$pid1"; then
  adb root > /dev/null 2>&1 && adb wait-for-device && sleep 2
  dev kill -9 "$pid1" > /dev/null
  sleep 2
  alive "$pid1" || killed_by="adb root"
  adb unroot > /dev/null 2>&1
  adb wait-for-device
  sleep 2
else
  killed_by=su
fi
[ -n "$killed_by" ] || fail "SIGKILL did not reach $pid1 (no su, no adb root on this image)"
killed_at=$(date +%s)
say "SIGKILL to $pid1 (by $killed_by)"

# ---- Android's restart -----------------------------------------------------------
if ! wait_session "restart after the kill" 240 "$established"; then
  dev dumpsys activity services "$pkg" > "$out/services-after-kill.txt"
  fail "Android did not bring the session back within 240 s"
fi
pid2=$(app_pid)
[ -n "$pid2" ] && [ "$pid2" != "$pid1" ] || fail "no new process after the kill ($pid1, then ${pid2:-none})"
[ "$(log_count 'Transport engine: Rust core')" -gt "$on_rust" ] || fail "the restarted session is not on the Rust core"
say "restarted in process $pid2, $(($(date +%s) - killed_at)) s after the kill, on the Rust core"
adb logcat -d -v time ActivityManager:I '*:S' 2> /dev/null | grep -i -E 'restart.*OlcboxVpnService' | tail -5 | tee -a "$out/results.txt"
shell_fetch after-restart || fail "no GET through the restarted session"

session_stop
finish 0
