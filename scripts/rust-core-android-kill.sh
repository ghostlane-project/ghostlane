#!/usr/bin/env bash
# rust-core-android-kill.sh <apk dir> <out dir>
#
# kill -9 of the VPN service's process on the Rust core, on the booted emulator
# (a leg of K3 in rust-core-android.yml). The session is
# rust-core-android-session.sh's: armed by RustKillE2ETest, started by the app's
# debug receiver outside any instrumentation, on a VLESS REALITY line to the
# official Xray server $XRAY_SERVER on the host.
#
# The core is a library in the app's process (D16), so kill -9 ends the service
# with it. Android's own restart of the killed service is not asked: the cell
# is that the line goes down and is back by the app's next start. Whether
# Android restarted it is reported as a fact, never a verdict.
#
#   1. the shell's GET of 192.0.2.1 comes back with the host page's nonce;
#   2. SIGKILL to the app's process (su on the emulator image, else adb root);
#      tun0 goes with it and the shell's GET is no longer answered;
#   3. Android's restart within 90 s, if it happens: the RESULT line says
#      "android-restart: yes" in a new process on the Rust core, or
#      "android-restart: none". On API 29 to 35 Android has not restarted it:
#      the system's VPN unbinds the service when tun0 goes, before the process's
#      death is handled, and the service is left started with no process
#      ("Exception when unbinding service", then no "Scheduling restart");
#      what Android said is printed then. Either result passes this check;
#   4. down and back: the session comes back in a new process on the Rust core
#      (by Android if it restarted, else by the next start, the one the app's
#      Connect sends), tun0 comes back and the shell's GET is answered again;
#      the RESULT line "down-and-back: ok", the cell that is judged;
#   5. SIGABRT to that process, as a crash of the core inside it would be, and
#      whether Android starts the service again within 90 s: said in the RESULT
#      line "android-restart-after-abort", no verdict;
#   6. the debug receiver's stop takes the tun and the service down.
#
# Every step is a RESULT line in <out dir>/results.txt; logcat, the host's
# logs and the instrumentation's output stay in <out dir>. Non-zero when a
# judged step failed (the process did not die, the line did not go down or did
# not come back); Android's restart is only reported. No carrier room, no secret.
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
on_rust=$(log_count 'Rust core ready on')
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

# ---- Android's restart -------------------------------------------------------------
# D16 does not ask for this: the service answers START_REDELIVER_INTENT, and
# whether Android acts on it is reported, not judged.
if wait_session "Android's restart after the kill" 90 "$established"; then
  android=yes
  pid2=$(app_pid)
  [ "$(log_count 'Rust core ready on')" -gt "$on_rust" ] || fail "the restarted session is not on the Rust core"
  adb logcat -d -v time ActivityManager:I '*:S' 2> /dev/null | grep -i -E 'restart.*OlcboxVpnService' | tail -5 | tee -a "$out/results.txt"
  say "android-restart: yes, in process $pid2, $(($(date +%s) - killed_at)) s after the kill"
else
  android=none
  dev dumpsys activity services "$pkg" > "$out/services-after-kill.txt"
  # What Android said of the process and its service, in the job's log.
  echo "--- Android on the process and the service after the kill"
  adb logcat -d -v time 2> /dev/null | grep -E '(ActivityManager|ActiveServices|Vpn)' \
    | grep -i -E 'proofkit|olcbox|restart|died' | grep -v -E 'Force stopping|Start proc|Broadcasting|Enqueued' | tail -n 20 | cut -c1-300
  grep -E 'ServiceRecord|app=|startRequested' "$out/services-after-kill.txt" | head -n 6 | cut -c1-200
  say "android-restart: none within 90 s of the kill (START_REDELIVER_INTENT did not bring the service back; D16 does not ask it to)"
  # The line comes back by the app's next start, the one its Connect sends.
  session_start
  pid2=$(app_pid)
fi

# ---- down and back -----------------------------------------------------------------
[ -n "$pid2" ] && [ "$pid2" != "$pid1" ] || fail "no new process after the kill ($pid1, then ${pid2:-none})"
by="by Android"
[ "$android" = yes ] || by="by the next start"
say "back $by, in process $pid2, $(($(date +%s) - killed_at)) s after the kill, on the Rust core"
shell_fetch after-restart || fail "no GET through the restarted session"
say "down-and-back: ok, back $by (Android's restart was $android, not a requirement)"

# ---- a native abort ------------------------------------------------------------------
# What a crash of the core inside the app's process is to Android: SIGABRT, which the
# process's crash handler reports to the system before the process dies. Said, not a
# verdict: whether Android starts the service again after it.
established=$(log_count 'VPN tunnel established')
dev su 0 kill -6 "$pid2" > /dev/null
for _ in $(seq 1 20); do alive "$pid2" || break; sleep 1; done
if alive "$pid2"; then
  say "android-restart-after-abort: not looked at (SIGABRT did not end $pid2)"
else
  aborted_at=$(date +%s)
  if wait_session "Android's restart after the abort" 90 "$established"; then
    say "android-restart-after-abort: yes, in process $(app_pid), $(($(date +%s) - aborted_at)) s after the abort"
  else
    echo "--- Android on the process and the service after the abort"
    adb logcat -d -v time 2> /dev/null | grep -E '(ActivityManager|ActiveServices|Vpn|DEBUG)' \
      | grep -i -E 'proofkit|olcbox|restart|died|crash' | grep -v -E 'Start proc|Broadcasting|Enqueued' | tail -n 20 | cut -c1-300
    say "android-restart-after-abort: none within 90 s of the abort"
    session_start
  fi
fi
session_stop

finish 0
