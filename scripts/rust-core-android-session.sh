#!/usr/bin/env bash
# rust-core-android-session.sh: sourced by rust-core-android-kill.sh and
# rust-core-android-soak.sh, never run. A session of the app on the Rust core
# that the booted emulator runs outside any instrumentation:
#
#   session_open <apk dir> <out dir> <name>
#       the host's line (rust-core-android-host.py serve: a VLESS REALITY line
#       on the official Xray server $XRAY_SERVER, reached from the emulator at
#       10.0.2.2, whose page alone answers 192.0.2.1); both APKs installed;
#       RustKillE2ETest#armTheSessionForTheShell run, which saves the Rust core
#       switch, tun mode, a login, the shell (uid 2000) as the one app in the
#       tun, and that line as the profile
#   session_start
#       the app's debug receiver starts the session (root's `am` with the same
#       bare intent where the image keeps a background app from starting a
#       foreground service on a broadcast), up to the tunnel and tun0
#   session_stop
#       the debug receiver's stop: tun0 and the service gone
#   shell_fetch <label>  /  shellnet <args>
#       the shell's GET of 192.0.2.1, through the tun, the line and the page
#   say, fail, finish, app_pid, alive, tun_up, log_count, wait_session, dev
#
# A session under instrumentation could not be killed and watched: when the
# instrumented process dies, Android ends the instrumentation and force-stops
# the package, and the service's restart with it.

pkg=org.proofkit.app
runner=org.proofkit.app.test/androidx.test.runner.AndroidJUnitRunner
receiver=$pkg/org.olcbox.app.DebugVpnControlReceiver
service=org.olcbox.app.vpn.service.OlcboxVpnService
session_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
host_pid=
out=
session_name=session

say() { echo "RESULT $*" | tee -a "$out/results.txt"; }

finish() {
  adb logcat -d -v time > "$out/logcat.txt" 2>&1 || true
  if [ -n "$host_pid" ]; then
    kill "$host_pid" 2> /dev/null
    wait "$host_pid" 2> /dev/null
  fi
  # The run's keys and credential stay out of the artifact even if the host's helper could not remove them.
  rm -f "$out/host/server.json" "$out/host/target.key" "$out/host/target.crt" "$out/host/link"
  if [ "$1" -eq 0 ]; then
    say "$session_name ok"
  else
    say "$session_name failed"
    # What a red run is read by, in the job's log: the artifact is a download away.
    echo "--- the host's line: $(hits) GET(s) served by the page"
    tail -n 5 "$out/host.log" 2> /dev/null
    tail -n 40 "$out/host/xray.log" 2> /dev/null | cut -c1-300
    echo "--- the install"
    tail -n 10 "$out/install.txt" 2> /dev/null
    echo "--- the service's log"
    grep -E '/OlcboxVpnService' "$out/logcat.txt" | tail -n 40 | cut -c1-300
    echo "--- errors and aborts"
    grep -E ' (E|F|A)/(AndroidRuntime|DEBUG|libc|art|ActivityManager|PackageManager|ghostlane-core)' "$out/logcat.txt" | tail -n 40 | cut -c1-300
  fi
  exit "$1"
}

fail() {
  say "FAIL $*"
  finish 1
}

dev() { adb shell "$@" 2> /dev/null | tr -d '\r'; }
hits() { cat "$out/host/hits" 2> /dev/null || echo 0; }
installed() { dev pm path "$1" | grep -q '^package:'; }
code_path() { dev pm path "$1" | sed -n 's/^package://p' | grep -m1 'base.apk$'; }

app_pid() {
  local p
  p=$(dev pidof "$pkg" | awk '{print $1}')
  case "$p" in '' | *[!0-9]*) p=$({ dev ps -A; dev ps; } | awk -v n="$pkg" '$NF == n { print $2; exit }') ;; esac
  echo "$p"
}
alive() { [ -n "$1" ] && dev ls -d "/proc/$1" | grep -q "^/proc/$1\$"; }
tun_up() { dev cat /proc/net/dev | grep -q 'tun0:'; }
log_count() { adb logcat -d -s OlcboxVpnService:D 2> /dev/null | grep -c "$1"; }
service_listed() { dev dumpsys activity services "$pkg" | grep -q "$service"; }

# ShellNet (the test APK's) as a process of the shell's (uid 2000: the package the app
# lists name), the app's APK for the Kotlin library; by `su 2000` where adbd is root.
# Quoted for the device's shell: Android 11's paths hold `~~` and `==`.
shell_as=
shellnet() { dev $shell_as app_process "'-Djava.class.path=$shellnet_cp'" /system/bin org.olcbox.app.ShellNet "$@"; }

# A GET of 192.0.2.1 by the shell, three tries: through the tun, the line and the page, or nowhere.
shell_fetch() {
  local label=$1 before after reply
  before=$(hits)
  reply=$(shellnet fetch 192.0.2.1 80 "/bytes/65536/$label" 15000 3 "$nonce")
  after=$(hits)
  echo "$reply" > "$out/fetch-$label.txt"
  if echo "$reply" | grep -q '^RESULT found'; then
    say "$label: the shell's GET came back through the line (the page served $before, then $after)"
    [ "$after" -gt "$before" ] || fail "$label: an answer the page did not serve"
    return 0
  fi
  say "$label: the shell's GET was not answered: $(echo "$reply" | tr '\n' ' ' | cut -c1-300)"
  return 1
}

# Up when the service logged one more tunnel than <established> and tun0 exists.
wait_session() {
  local what=$1 seconds=$2 established=$3 i
  for i in $(seq 1 "$seconds"); do
    if [ "$(log_count 'VPN tunnel established')" -gt "$established" ] && tun_up; then
      say "$what: the tunnel is up after ${i}s"
      return 0
    fi
    sleep 1
  done
  adb logcat -d -s OlcboxVpnService:D | tail -40
  return 1
}

session_open() {
  local apks=$1 app test_apk a try
  out=$2
  session_name=$3
  mkdir -p "$out"
  out=$(cd "$out" && pwd)
  : > "$out/results.txt"
  [ -n "${XRAY_SERVER:-}" ] && [ -x "$XRAY_SERVER" ] || fail "XRAY_SERVER names no Xray server binary"

  python3 "$session_dir/rust-core-android-host.py" serve --xray "$XRAY_SERVER" --work "$out/host" > "$out/host.log" 2>&1 &
  host_pid=$!
  for _ in $(seq 1 60); do
    [ -f "$out/host/ready" ] && break
    kill -0 "$host_pid" 2> /dev/null || break
    sleep 1
  done
  [ -f "$out/host/ready" ] || { cat "$out/host.log"; fail "the host's line did not come up"; }
  link=$(cat "$out/host/link")
  nonce=$(cat "$out/host/nonce")

  echo "device: API $(dev getprop ro.build.version.sdk), $(dev getprop ro.product.cpu.abi), $(dev getprop ro.build.type), the shell as $(dev id | cut -d" " -f1)" | tee "$out/device.txt"
  app=$(find "$apks" -name '*.apk' ! -name '*androidTest*' | head -1)
  test_apk=$(find "$apks" -name '*androidTest*.apk' | head -1)
  [ -f "$app" ] && [ -f "$test_apk" ] || fail "no APKs under $apks"
  for a in "$app" "$test_apk"; do
    for try in 1 2 3; do
      adb install -r -t "$a" >> "$out/install.txt" 2>&1 && break
      [ "$try" -eq 3 ] && fail "install of $(basename "$a")"
      sleep 10
    done
  done
  installed "$pkg" && installed "$pkg.test" || fail "the app or its test APK is not installed"
  shellnet_cp="$(code_path "$pkg.test"):$(code_path "$pkg")"
  case "$(dev id)" in 'uid=0('*) shell_as="su 2000" ;; esac

  adb logcat -G 16M > /dev/null 2>&1 || true
  adb shell "am instrument -w -r -e class org.olcbox.app.RustKillE2ETest#armTheSessionForTheShell -e rustKill arm -e rustKillLink '$link' $runner" > "$out/arm.txt" 2>&1
  tr -d '\r' < "$out/arm.txt" | grep -q '^INSTRUMENTATION_STATUS_CODE: 0$' || { tail -30 "$out/arm.txt"; fail "the arm did not pass"; }
  say "armed: the Rust core, tun mode, the shell alone in the tun, the host's REALITY line"
}

session_start() {
  local established verb
  adb logcat -c || true
  established=$(log_count 'VPN tunnel established')
  dev am broadcast -f 0x20 -n "$receiver" -a org.proofkit.app.DEBUG_START_VPN > "$out/start.txt"
  if ! wait_session "start by the debug receiver" 60 "$established"; then
    [ -z "$(app_pid)" ] || fail "the session did not come up (start: $(tr '\n' ' ' < "$out/start.txt"))"
    verb=startservice
    [ "$(dev getprop ro.build.version.sdk)" -ge 26 ] && verb=start-foreground-service
    dev su 0 am "$verb" -n "$pkg/$service" -a "$service.START" >> "$out/start.txt"
    say "the debug receiver started no session: started by su am $verb instead"
    wait_session "start by su am $verb" 120 "$established" || fail "the session did not come up (start: $(tr '\n' ' ' < "$out/start.txt"))"
  fi
  [ "$(log_count 'Transport engine: Rust core')" -ge 1 ] || fail "the session is not on the Rust core"
}

session_stop() {
  dev am broadcast -f 0x20 -n "$receiver" -a org.proofkit.app.DEBUG_STOP_VPN > /dev/null
  for _ in $(seq 1 60); do tun_up || break; sleep 1; done
  tun_up && fail "tun0 is still up after the stop"
  for _ in $(seq 1 30); do service_listed || break; sleep 1; done
  service_listed && fail "the service is still there after the stop"
  say "stopped: tun0 gone, the service gone"
}
