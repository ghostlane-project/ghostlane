#!/usr/bin/env bash
# run-emulator-tests.sh <out dir>
#
# Runs the lab's instrumented tests on the booted emulator, against the T1
# host on this runner ($T1_CONFIG, written by zz_t1_host_test.go): installs
# the lab app and its test APK, then
#   - ghostlane-runtime's relief unit tests, natively (adb shell; the
#     allocator's Android branch: footprint and trim), and their armeabi-v7a
#     build when the image's ABI list names armeabi-v7a (rust32);
#   - CallSequenceTest once (the API, the callbacks, the SOCKS fetch, UDP
#     ASSOCIATE, ten stop/starts, Go's error texts, memoryStats);
#   - CloseTest once (close() on a running runtime, then the next runtime in
#     the same room);
#   - TrimTest twice, each in a process of its own (the burst, trim() and
#     mallopt's purges, the core inside the app's process);
#   - TrimProcessTest twice (the same, the core in a process of its own:
#     olc-ffi-client in its control mode);
#   - by LAB_RUNS only: CallSequenceTest again (calls1-9) and ControlTest
#     (control1-9).
# A run that fails keeps the system's tombstones (<name>.tombstones.txt).
# The room's 127.0.0.1 becomes 10.0.2.2, the emulator's way to this host's
# loopback; the origin and the echo are dialed by the server, on this host.
# Each run's am output and logcat land in <out dir>; the RESULT and MEM lines
# in <out dir>/results.txt. Exits non-zero when a run fails.
set -uo pipefail

out=${1:?usage: run-emulator-tests.sh <out dir>}
mkdir -p "$out"
cfg=${T1_CONFIG:?the endpoints of the T1 host}
here=$(cd "$(dirname "$0")" && pwd)
apks="$here/build/outputs/apk"
app=$(find "$apks/debug" -name '*-debug.apk' | head -1)
test_apk=$(find "$apks/androidTest" -name '*-androidTest.apk' | head -1)
[ -f "$app" ] && [ -f "$test_apk" ] || { echo "no APKs under $apks" >&2; exit 1; }
pkg=org.ghostlane.lab.olcffi
runner=$pkg.test/androidx.test.runner.AndroidJUnitRunner

j() { jq -r ".$1" "$cfg"; }
room=$(j room | sed 's#127\.0\.0\.1#10.0.2.2#')
args=(room "$room" key "$(j key)" transport "$(j transport)" dns "$(j dns)" origin "$(j origin)"
  big "$(j big)" big_bytes "$(j big_bytes)" echo_port "$(j echo_port)" echo_ip "$(j echo_ip)")
extras=()
for ((i = 0; i < ${#args[@]}; i += 2)); do
  v=${args[i + 1]}
  # adb shell hands the line to the device's sh: plain values only.
  [[ "$v" =~ ^[A-Za-z0-9_.:/=+-]*$ ]] || { echo "argument ${args[i]} has a character the device shell would read: $v" >&2; exit 1; }
  extras+=(-e "${args[i]}" "${v:-_}")
done

{
  echo "device: API $(adb shell getprop ro.build.version.sdk | tr -d '\r'), $(adb shell getprop ro.product.cpu.abi | tr -d '\r'), page size $(adb shell getconf PAGE_SIZE | tr -d '\r') B, $(adb shell nproc | tr -d '\r') CPUs, $(adb shell cat /proc/meminfo | awk '/MemTotal/ {print $2 " kB"}')"
  echo "allocator: $(adb shell getprop ro.malloc.impl | tr -d '\r') (empty = the build's default)"
  echo "ABIs: $(adb shell getprop ro.product.cpu.abilist | tr -d '\r')"
} | tee "$out/device.txt"

# The package service can still be starting when the emulator reports a
# boot (run 43686357448: "Failure calling service package: Broken pipe"):
# each install is tried five times, 15 s apart, after the boot completes.
install() {
  local apk=$1 try
  for try in 1 2 3 4 5; do
    adb wait-for-device
    timeout 120 adb shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2; done'
    if adb install -r -t "$apk" >> "$out/install.txt" 2>&1; then return 0; fi
    echo "install of $(basename "$apk") failed (try $try)" >> "$out/install.txt"
    sleep 15
  done
  cat "$out/install.txt"
  return 1
}
: > "$out/install.txt"
install "$app" || exit 1
install "$test_apk" || exit 1

# The emulator's network can come up well after its boot (run 43687552946's
# 16 KiB image: every CallSequenceTest there failed on "Network is
# unreachable" to 10.0.2.2 for over a minute): wait up to 3 min for a route.
net=down
for _ in $(seq 1 90); do
  if adb shell ip route get 10.0.2.2 > /dev/null 2>&1; then net=up; break; fi
  sleep 2
done
echo "network: a route to 10.0.2.2 $net ($(adb shell ip route get 10.0.2.2 2>&1 | head -1 | tr -d '\r'))" | tee -a "$out/device.txt"
[ "$net" = up ] || { adb shell ip route 2>&1 | head; exit 1; }
adb logcat -G 16M > /dev/null 2>&1 || true

rc=0
run() {
  local name=$1 class=$2
  shift 2
  adb logcat -c || true
  local t0=$(date +%s)
  timeout 1500 adb shell am instrument -w -r -e class "$pkg.$class" "${extras[@]}" "$@" "$runner" > "$out/$name.am.txt" 2>&1
  local code=$?
  adb logcat -d -v time > "$out/$name.logcat.txt" 2>&1 || true
  grep -h 'OlcFfiLab' "$out/$name.logcat.txt" | sed -E 's/^.*OlcFfiLab\( *[0-9]+\): //' > "$out/$name.results.txt" || true
  local verdict
  if [ $code -eq 0 ] && grep -q '^OK (' "$out/$name.am.txt" && ! grep -q 'FAILURES!!!\|INSTRUMENTATION_FAILED\|Process crashed' "$out/$name.am.txt"; then
    verdict=passed
  else
    verdict="FAILED (am exit $code)"
    rc=1
  fi
  {
    echo "== $name ($class): $verdict in $(( $(date +%s) - t0 )) s"
    cat "$out/$name.results.txt"
  } >> "$out/results.txt"
  echo "== $name: $verdict"
  if [ "$verdict" != passed ]; then
    # A native crash's whole tombstone (every thread), as the system kept it.
    adb shell dumpsys dropbox --print SYSTEM_TOMBSTONE > "$out/$name.tombstones.txt" 2>&1 || true
    grep -A12 'INSTRUMENTATION_STATUS: stack=' "$out/$name.am.txt" | head -40
    tail -20 "$out/$name.am.txt"
    grep -E ' (E|F)/(AndroidRuntime|DEBUG|libc|olcrtc|TestRunner)' "$out/$name.logcat.txt" | tail -40
  fi
}

# ghostlane-runtime's relief tests, natively on the emulator (adb's shell
# runs what is pushed to /data/local/tmp): rust <name> <binary> <what>.
rust() {
  local name=$1 bin=$2 what=$3
  [ -f "$bin" ] || { echo "== $name: no $bin" >> "$out/results.txt"; rc=1; return; }
  adb push "$bin" "/data/local/tmp/$name-tests" > /dev/null
  adb shell chmod 755 "/data/local/tmp/$name-tests"
  timeout 300 adb shell "/data/local/tmp/$name-tests" relief --test-threads=1 --nocapture > "$out/$name.txt" 2>&1
  local verdict=passed
  grep -q '^test result: ok' "$out/$name.txt" || { verdict=FAILED; rc=1; }
  {
    echo "== $name (ghostlane-runtime relief, $what): $verdict"
    grep -E '^test |^test result|trim after|relief:|panicked|assert|not executable|No such file' "$out/$name.txt"
  } >> "$out/results.txt"
  echo "== $name: $verdict"
}

# The armeabi-v7a build of the same tests, where the image runs 32-bit ARM
# (the relief branch's 32-bit RTLD_DEFAULT); not run elsewhere.
rust32() {
  local abis
  abis=$(adb shell getprop ro.product.cpu.abilist | tr -d '\r')
  case ",$abis," in
    *,armeabi-v7a,*) rust rust32 "$here/core/ghostlane-runtime-tests-armv7" "armeabi-v7a, translated" ;;
    *)
      echo "== rust32 (ghostlane-runtime relief, armeabi-v7a): not run, the image's ABIs are $abis" >> "$out/results.txt"
      echo "== rust32: not run ($abis)"
      ;;
  esac
}

: > "$out/results.txt"
cat "$out/device.txt" >> "$out/results.txt"
# LAB_RUNS picks the runs. The 16 KiB-page emulator runs rust, then
# CallSequenceTest three times (calls1-3) between three of ControlTest
# (control1-3: the Kotlin side's readings and GC churn without the core), for
# the SIGSEGV in ART's JIT code that its CallSequenceTest process died of once
# (run 43681797712).
for r in ${LAB_RUNS:-rust rust32 calls close trim1 trim2 proc1 proc2}; do
  case "$r" in
    rust) rust rust "$here/core/ghostlane-runtime-tests" native ;;
    rust32) rust32 ;;
    calls) run calls CallSequenceTest ;;
    close) run close CloseTest ;;
    calls[0-9]) run "$r" CallSequenceTest ;;
    control[0-9]) run "$r" ControlTest ;;
    trim1) run trim1 TrimTest -e run 1 ;;
    trim2) run trim2 TrimTest -e run 2 ;;
    proc1) run proc1 TrimProcessTest -e run 1 ;;
    proc2) run proc2 TrimProcessTest -e run 2 ;;
    *) echo "unknown run $r" >&2; rc=1 ;;
  esac
done
cat "$out/results.txt"
exit $rc
