#!/usr/bin/env bash
# rust-core-android-e2e.sh <apk dir> <out dir>
#
# Runs the app's RustCoreVpnE2ETest (androidApp/src/androidTestRustcore) on the
# booted emulator: installs the debug app and its test APK (built by the `app`
# job of rust-core-android.yml), runs the six tests, keeps the am output and
# logcat in <out dir>, the RESULT lines in <out dir>/results.txt. Non-zero when
# a test fails. No carrier room, no secret.
set -uo pipefail

apks=${1:?usage: rust-core-android-e2e.sh <apk dir> <out dir>}
out=${2:?usage: rust-core-android-e2e.sh <apk dir> <out dir>}
mkdir -p "$out"
test_apk=$(find "$apks" -name '*androidTest*.apk' | head -1)
app=$(find "$apks" -name '*.apk' ! -name '*androidTest*' | head -1)
[ -f "$app" ] && [ -f "$test_apk" ] || { echo "no APKs under $apks" >&2; ls -R "$apks" >&2; exit 1; }
runner=org.proofkit.app.test/androidx.test.runner.AndroidJUnitRunner

echo "device: API $(adb shell getprop ro.build.version.sdk | tr -d '\r'), $(adb shell getprop ro.product.cpu.abi | tr -d '\r'), page size $(adb shell getconf PAGE_SIZE | tr -d '\r') B" | tee "$out/device.txt"

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

# What the tun test needs of the image: toybox's nc (the shell's fetch through the tun).
adb shell 'toybox nc --help 2>&1 | head -2; id' | tee "$out/image.txt"

adb logcat -G 16M > /dev/null 2>&1 || true
adb logcat -c || true
timeout 900 adb shell am instrument -w -r -e class org.olcbox.app.RustCoreVpnE2ETest "$runner" > "$out/am.txt" 2>&1
adb logcat -d -v time > "$out/logcat.txt" 2>&1 || true
grep -h 'RustCoreE2E' "$out/logcat.txt" | sed -E 's/^.*RustCoreE2E *\( *[0-9]+\): //' > "$out/results.txt" || true
cat "$out/results.txt"
tail -30 "$out/am.txt"
if ! grep -q '^OK (7 tests)' "$out/am.txt"; then
  grep -E ' (E|F)/(AndroidRuntime|DEBUG|libc|TestRunner|ghostlane-core)' "$out/logcat.txt" | tail -60
  exit 1
fi
