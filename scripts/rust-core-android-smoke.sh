#!/usr/bin/env bash
# rust-core-android-smoke.sh <out dir>
#
# Runs lab/rust-core-android's instrumented smoke test (CoreSmokeTest) on the
# booted emulator: installs the lab app and its test APK (built by
# rust-core-android.yml), runs the test, keeps the am output and logcat in
# <out dir>, the RESULT lines in <out dir>/results.txt. Non-zero when the test
# fails.
set -uo pipefail

out=${1:?usage: rust-core-android-smoke.sh <out dir>}
mkdir -p "$out"
apks=lab/rust-core-android/build/outputs/apk
app=$(find "$apks/debug" -name '*-debug.apk' | head -1)
test_apk=$(find "$apks/androidTest" -name '*-androidTest.apk' | head -1)
[ -f "$app" ] && [ -f "$test_apk" ] || { echo "no APKs under $apks" >&2; exit 1; }
runner=org.ghostlane.lab.rustcore.test/androidx.test.runner.AndroidJUnitRunner

{
  echo "device: API $(adb shell getprop ro.build.version.sdk | tr -d '\r'), $(adb shell getprop ro.product.cpu.abi | tr -d '\r'), page size $(adb shell getconf PAGE_SIZE | tr -d '\r') B"
} | tee "$out/device.txt"

# The package service can still be starting right after boot: try five times.
install() {
  local apk=$1 try
  for try in 1 2 3 4 5; do
    adb wait-for-device
    timeout 120 adb shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2; done'
    if adb install -r -t "$apk" >> "$out/install.txt" 2>&1; then return 0; fi
    sleep 15
  done
  cat "$out/install.txt"
  return 1
}
: > "$out/install.txt"
install "$app" || exit 1
install "$test_apk" || exit 1

adb logcat -G 16M > /dev/null 2>&1 || true
adb logcat -c || true
timeout 600 adb shell am instrument -w -r "$runner" > "$out/am.txt" 2>&1
adb logcat -d -v time > "$out/logcat.txt" 2>&1 || true
grep -h 'RustCoreSmoke' "$out/logcat.txt" | sed -E 's/^.*RustCoreSmoke\( *[0-9]+\): //' > "$out/results.txt" || true
cat "$out/results.txt"
tail -15 "$out/am.txt"
if ! grep -q '^OK (2 tests)' "$out/am.txt"; then
  grep -E ' (E|F)/(AndroidRuntime|DEBUG|libc|TestRunner)' "$out/logcat.txt" | tail -40
  exit 1
fi
