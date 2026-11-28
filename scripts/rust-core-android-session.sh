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
# With SESSION_DIAG=1 (the e2e legs) a red run says more: every start samples
# the app's process for 24 s (each thread's name, the system call it sits in
# and where in the kernel, its descriptors by kind, its sockets to the line's
# port with the bytes the kernel queues, the kernel's entropy), and the host's
# loopback is captured on the line's port (tcpdump, where sudo runs it), where
# the emulator hands Xray what the device sent; both are printed when it fails.
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
capture_pid=
line_port=

say() { echo "RESULT $*" | tee -a "$out/results.txt"; }

finish() {
  adb logcat -d -v time > "$out/logcat.txt" 2>&1 || true
  if [ -n "$host_pid" ]; then
    kill "$host_pid" 2> /dev/null
    wait "$host_pid" 2> /dev/null
  fi
  if [ -n "$capture_pid" ]; then
    sudo -n kill "$capture_pid" 2> /dev/null
    wait "$capture_pid" 2> /dev/null
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
    if [ -s "$out/start-samples.txt" ]; then
      echo "--- the app's process while the last session came up (threads: name | system call | kernel wait)"
      tail -n 150 "$out/start-samples.txt" | cut -c1-200
    fi
    if [ -s "$out/line.pcap" ]; then
      echo "--- the line's port on the host's loopback (what reached Xray), $line_port"
      { tcpdump -nn -tt -r "$out/line.pcap" 2> /dev/null || sudo -n tcpdump -nn -tt -r "$out/line.pcap" 2> /dev/null; } | head -n 80 | cut -c1-200
    fi
  fi
  exit "$1"
}

fail() {
  say "FAIL $*"
  finish 1
}

# Every device command is bounded: an API 24 image once hung in one for 44 minutes.
dev() { timeout 600 adb shell "$@" 2> /dev/null | tr -d '\r'; }
hits() { cat "$out/host/hits" 2> /dev/null || echo 0; }
installed() { dev pm path "$1" | grep -q '^package:'; }
code_path() { dev pm path "$1" | sed -n 's/^package://p' | grep -m1 'base.apk$'; }

# The app's main process: a candidate of pidof or ps whose command line is the package's
# own (on Android 6 one of them named process 1, and the sampler read init).
app_pid() {
  local p
  for p in $(dev pidof "$pkg") $({ dev ps -A; dev ps; } | awk -v n="$pkg" '$NF == n { print $2 }'); do
    case "$p" in '' | *[!0-9]*) continue ;; esac
    if [ "$(timeout 60 adb shell cat "/proc/$p/cmdline" 2> /dev/null | tr -d '\r\000')" = "$pkg" ]; then
      echo "$p"
      return
    fi
  done
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

# One sample of the app's process [pid] as root, by the device script session_open pushed.
sample_once() {
  dev su 0 sh /data/local/tmp/pk-sample.sh "$1" "$(printf '%04X' "${line_port:-0}")"
}

# Every 2 s for 24 s from a start: what the app's process does while the session comes up.
sample_start() {
  local i pid
  echo "=== a start at $(date +%T)" >> "$out/start-samples.txt"
  for i in $(seq 1 12); do
    sleep 2
    pid=$(app_pid)
    if [ -z "$pid" ]; then
      echo "--- $(date +%T), +$((i * 2)) s, no process of $pkg (pidof: '$(dev pidof "$pkg")')" >> "$out/start-samples.txt"
      continue
    fi
    { echo "--- $(date +%T), +$((i * 2)) s, process $pid"; sample_once "$pid"; } >> "$out/start-samples.txt" 2>&1
  done
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
  line_port=$(cat "$out/host/ready")
  if [ "${SESSION_DIAG:-}" = 1 ]; then
    if command -v tcpdump > /dev/null 2>&1 && sudo -n true 2> /dev/null; then
      sudo -n tcpdump -i lo -nn -U -s 160 -w "$out/line.pcap" "tcp port $line_port" > "$out/tcpdump.log" 2>&1 &
      capture_pid=$!
    fi
    cat > "$out/pk-sample.sh" << 'EOF'
# pk-sample.sh <pid> <port, 4 hex digits>: one sample of a process, run as root on the device.
p=$1
echo "entropy $(cat /proc/sys/kernel/random/entropy_avail)"
for t in /proc/$p/task/*; do
  echo "$(cat $t/comm 2> /dev/null) | $(cat $t/syscall 2> /dev/null) | $(cat $t/wchan 2> /dev/null)"
done
ls -l /proc/$p/fd 2> /dev/null | sed -n 's/.*-> //p' | sed 's/[[][0-9]*[]]//' | sort | uniq -c
grep -i ":$2 " /proc/net/tcp /proc/net/tcp6 2> /dev/null
EOF
    adb push "$out/pk-sample.sh" /data/local/tmp/pk-sample.sh > /dev/null 2>&1 || true
  fi

  echo "device: API $(dev getprop ro.build.version.sdk), $(dev getprop ro.product.cpu.abi), $(dev getprop ro.build.type), kernel $(dev uname -r), entropy $(dev cat /proc/sys/kernel/random/entropy_avail), the shell as $(dev id | cut -d" " -f1)" | tee "$out/device.txt"
  app=$(find "$apks" -name '*.apk' ! -name '*androidTest*' | head -1)
  test_apk=$(find "$apks" -name '*androidTest*.apk' | head -1)
  [ -f "$app" ] && [ -f "$test_apk" ] || fail "no APKs under $apks"
  for a in "$app" "$test_apk"; do
    for try in 1 2 3; do
      timeout 300 adb install -r -t "$a" >> "$out/install.txt" 2>&1 && break
      [ "$try" -eq 3 ] && fail "install of $(basename "$a")"
      sleep 10
    done
  done
  installed "$pkg" && installed "$pkg.test" || fail "the app or its test APK is not installed"
  shellnet_cp="$(code_path "$pkg.test"):$(code_path "$pkg")"
  case "$(dev id)" in 'uid=0('*) shell_as="su 2000" ;; esac

  adb logcat -G 16M > /dev/null 2>&1 || true
  timeout 300 adb shell "am instrument -w -r -e class org.olcbox.app.RustKillE2ETest#armTheSessionForTheShell -e rustKill arm -e rustKillLink '$link' $runner" > "$out/arm.txt" 2>&1
  tr -d '\r' < "$out/arm.txt" | grep -q '^INSTRUMENTATION_STATUS_CODE: 0$' || { tail -30 "$out/arm.txt"; fail "the arm did not pass"; }
  say "armed: the Rust core, tun mode, the shell alone in the tun, the host's REALITY line"
}

session_start() {
  local established verb sampler=
  adb logcat -c || true
  established=$(log_count 'VPN tunnel established')
  dev am broadcast -f 0x20 -n "$receiver" -a org.proofkit.app.DEBUG_START_VPN > "$out/start.txt"
  if [ "${SESSION_DIAG:-}" = 1 ]; then
    sample_start &
    sampler=$!
  fi
  if ! wait_session "start by the debug receiver" 60 "$established"; then
    [ -n "$sampler" ] && wait "$sampler" 2> /dev/null
    [ -z "$(app_pid)" ] || fail "the session did not come up (start: $(tr '\n' ' ' < "$out/start.txt"))"
    verb=startservice
    [ "$(dev getprop ro.build.version.sdk)" -ge 26 ] && verb=start-foreground-service
    dev su 0 am "$verb" -n "$pkg/$service" -a "$service.START" >> "$out/start.txt"
    say "the debug receiver started no session: started by su am $verb instead"
    wait_session "start by su am $verb" 120 "$established" || fail "the session did not come up (start: $(tr '\n' ' ' < "$out/start.txt"))"
  fi
  if [ -n "$sampler" ]; then
    kill "$sampler" 2> /dev/null
    wait "$sampler" 2> /dev/null
  fi
  [ "$(log_count 'Rust core ready on')" -ge 1 ] || fail "the session is not on the Rust core"
}

session_stop() {
  dev am broadcast -f 0x20 -n "$receiver" -a org.proofkit.app.DEBUG_STOP_VPN > /dev/null
  for _ in $(seq 1 60); do tun_up || break; sleep 1; done
  tun_up && fail "tun0 is still up after the stop"
  for _ in $(seq 1 30); do service_listed || break; sleep 1; done
  service_listed && fail "the service is still there after the stop"
  say "stopped: tun0 gone, the service gone"
}
