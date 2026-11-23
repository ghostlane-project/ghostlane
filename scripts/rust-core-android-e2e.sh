#!/usr/bin/env bash
# rust-core-android-e2e.sh <apk dir> <out dir>
#
# Runs every test class of the app's androidTestRustcore sources on the booted
# emulator: installs the debug app and its test APK (built by the `app` job of
# rust-core-android.yml), asks the runner for each class by name, keeps the am
# output and logcat in <out dir>, the RESULT lines in <out dir>/results.txt and
# what each class did in <out dir>/classes.txt. Non-zero when a test fails, when
# a class it asked for had no test that passed (it did not run), or when there
# is no class at all. No carrier room, no secret.
#
# A test class is a .kt file under the sources that holds an `@Test`, named as
# its file is, in the file's package: a class added there runs with no edit here.
# RUST_CORE_E2E_SRC names another source directory (the script's own test).
set -uo pipefail

apks=${1:?usage: rust-core-android-e2e.sh <apk dir> <out dir>}
out=${2:?usage: rust-core-android-e2e.sh <apk dir> <out dir>}
mkdir -p "$out"
src=${RUST_CORE_E2E_SRC:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/androidApp/src/androidTestRustcore}

classes=()
while IFS= read -r f; do
  grep -q '@Test' "$f" || continue
  package=$(sed -n 's/^package[[:space:]]\{1,\}\([A-Za-z0-9_.]*\).*/\1/p' "$f" | head -1)
  classes+=("${package:+$package.}$(basename "$f" .kt)")
done < <(find "$src" -name '*.kt' 2>/dev/null | sort)
[ "${#classes[@]}" -gt 0 ] || { echo "no test class under $src" >&2; exit 1; }
asked=$(IFS=,; echo "${classes[*]}")
echo "classes: ${classes[*]}"
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
# Ten minutes for the runner, five more for each class.
timeout $((600 + 300 * ${#classes[@]})) adb shell am instrument -w -r -e class "$asked" "$runner" > "$out/am.txt" 2>&1
adb logcat -d -v time > "$out/logcat.txt" 2>&1 || true
grep -h 'RustCoreE2E' "$out/logcat.txt" | sed -E 's/^.*RustCoreE2E *\( *[0-9]+\): //' > "$out/results.txt" || true
cat "$out/results.txt"
tail -30 "$out/am.txt"

# A class ran when one of its tests passed: the runner reports every test with
# its class and a status code, 0 for a pass.
failed=0
: > "$out/classes.txt"
for c in "${classes[@]}"; do
  passed=$(awk -v c="$c" '
    /^INSTRUMENTATION_STATUS: class=/ { cur = substr($0, index($0, "=") + 1); sub(/\r$/, "", cur) }
    /^INSTRUMENTATION_STATUS_CODE: 0\r?$/ { if (cur == c) n++ }
    END { print n + 0 }' "$out/am.txt")
  if [ "$passed" -gt 0 ]; then
    echo "CLASS $c: $passed passed" | tee -a "$out/classes.txt"
  else
    echo "CLASS $c did not run: no test of it passed" | tee -a "$out/classes.txt"
    failed=1
  fi
done
grep -q '^OK (' "$out/am.txt" || failed=1
if [ "$failed" -ne 0 ]; then
  grep -E ' (E|F)/(AndroidRuntime|DEBUG|libc|TestRunner|ghostlane-core)' "$out/logcat.txt" | tail -60
  exit 1
fi
