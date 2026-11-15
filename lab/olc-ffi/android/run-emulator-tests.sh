#!/usr/bin/env bash
# run-emulator-tests.sh <out dir>
#
# Runs the lab's instrumented tests on the booted emulator, against the T1
# host on this runner ($T1_CONFIG, written by zz_t1_host_test.go): installs
# the lab app and its test APK, then
#   - CallSequenceTest once (the API, the callbacks, the SOCKS fetch, UDP
#     ASSOCIATE, ten stop/starts, Go's error texts, memoryStats);
#   - TrimTest twice, each in a process of its own (the burst, trim() and
#     mallopt's purges).
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
} | tee "$out/device.txt"

adb install -r -t "$app" > "$out/install.txt" 2>&1 || { cat "$out/install.txt"; exit 1; }
adb install -r -t "$test_apk" >> "$out/install.txt" 2>&1 || { cat "$out/install.txt"; exit 1; }
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
    grep -A12 'INSTRUMENTATION_STATUS: stack=' "$out/$name.am.txt" | head -40
    tail -20 "$out/$name.am.txt"
    grep -E ' (E|F)/(AndroidRuntime|DEBUG|libc|olcrtc|TestRunner)' "$out/$name.logcat.txt" | tail -40
  fi
}

: > "$out/results.txt"
cat "$out/device.txt" >> "$out/results.txt"
# LAB_RUNS picks the runs (the 16 KiB-page emulator runs the calls alone).
for r in ${LAB_RUNS:-calls trim1 trim2}; do
  case "$r" in
    calls) run calls CallSequenceTest ;;
    trim1) run trim1 TrimTest -e run 1 ;;
    trim2) run trim2 TrimTest -e run 2 ;;
    *) echo "unknown run $r" >&2; rc=1 ;;
  esac
done
cat "$out/results.txt"
exit $rc
