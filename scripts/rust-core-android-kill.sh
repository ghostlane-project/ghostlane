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
#      tun0 goes with it and the shell's GET is no longer answered;
#   3. the restart: Android's own (the service answers START_REDELIVER_INTENT)
#      within 90 s, or else the next start, the one the app's Connect sends,
#      in a new process, on the Rust core; the tunnel and tun0 come back and
#      the shell's GET is answered again. Which of the two it was is a RESULT
#      line ("restart: ...") and a word in K3's table: on API 29 to 35 Android
#      has not restarted it, because the system's VPN unbinds the service when
#      tun0 goes, before the process's death is handled, and the service is
#      left started with no process ("Exception when unbinding service", then
#      no "Scheduling restart"); what Android said is printed then;
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
# The service as Android holds it before the kill: started, foreground, its starts.
dev dumpsys activity services "$pkg" > "$out/services-before-kill.txt"
grep -E 'ServiceRecord|startRequested|isForeground|deliveredStarts|restart' "$out/services-before-kill.txt" | head -n 12 | cut -c1-200
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

# ---- what the kill took down ------------------------------------------------------
for _ in $(seq 1 30); do tun_up || break; sleep 1; done
tun_up && ! alive "$(app_pid)" && fail "tun0 outlived the process"
if [ -z "$(app_pid)" ]; then
  hits_before=$(hits)
  reply=$(shellnet fetch 192.0.2.1 80 /bytes/1024/after-kill 5000 1 "$nonce")
  echo "$reply" > "$out/fetch-after-kill.txt"
  echo "$reply" | grep -q '^RESULT found' && fail "the shell's GET was answered with no session"
  [ "$(hits)" = "$hits_before" ] || fail "the page served a GET with no session"
  say "the kill took the session down: no tun0, the shell's GET unanswered"
else
  say "a new process ($(app_pid)) was already up when tun0 was looked at"
fi

# ---- the restart -----------------------------------------------------------------
if wait_session "Android's restart after the kill" 90 "$established"; then
  restart="by Android"
  pid2=$(app_pid)
  [ "$(log_count 'Transport engine: Rust core')" -gt "$on_rust" ] || fail "the restarted session is not on the Rust core"
  adb logcat -d -v time ActivityManager:I '*:S' 2> /dev/null | grep -i -E 'restart.*OlcboxVpnService' | tail -5 | tee -a "$out/results.txt"
else
  dev dumpsys activity services "$pkg" > "$out/services-after-kill.txt"
  # What Android said of the process and its service, in the job's log.
  echo "--- Android on the process and the service after the kill"
  adb logcat -d -v time 2> /dev/null | grep -E '(ActivityManager|ActiveServices|Vpn)' \
    | grep -i -E 'proofkit|olcbox|restart|died' | grep -v -E 'Force stopping|Start proc|Broadcasting|Enqueued' | tail -n 20 | cut -c1-300
  grep -E 'ServiceRecord|app=|startRequested' "$out/services-after-kill.txt" | head -n 6 | cut -c1-200
  say "Android did not restart the service within 90 s of the kill: the next start brings it back"
  restart="by the next start (Android did not restart it)"
  session_start
  pid2=$(app_pid)
fi
[ -n "$pid2" ] && [ "$pid2" != "$pid1" ] || fail "no new process after the kill ($pid1, then ${pid2:-none})"
say "restart: $restart, in process $pid2, $(($(date +%s) - killed_at)) s after the kill, on the Rust core"
shell_fetch after-restart || fail "no GET through the restarted session"

session_stop
finish 0
