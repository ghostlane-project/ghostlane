#!/usr/bin/env bash
# The Android engine seam. Fails when
#   - OlcboxVpnService.kt names a Go core: the gomobile package (`mobile.`,
#     `Mobile.`), `singBoxCore`, `xrayCore`, or a class that runs one
#     (AndroidSingBoxController, AndroidXrayController, AndroidCoreProcess);
#   - OlcboxVpnService.kt reaches for the Rust engine itself (RustCore.backend,
#     RustCoreBackend) instead of asking its session;
#   - the engine package lacks EngineSession.kt, GoEngineSession.kt or
#     RustEngineSession.kt, or a file of the service's package other than
#     engine/GoEngineSession.kt names a Go core;
#   - the Rust core's proxy-mode refusal is written anywhere but in its hook
#     file, engine/RustProxyMode.kt, or the Rust session does not ask the hook;
#   - scripts/rust-core-android-e2e.sh does not ask for every test class under
#     the androidTestRustcore sources, or passes when a class it asked for did
#     not run, when a test failed, or when there is no class at all. This part
#     runs the script against a stand-in `adb`.
#
#   scripts/p6-tests/engine-seam.sh [root]     root defaults to the repository
# Needs bash and the usual text tools.
set -euo pipefail
root="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
fail=0
bad() { echo "FAIL $*"; fail=1; }

# ---- the service's source ---------------------------------------------------
pkg="$root/sharedUI/src/androidMain/kotlin/org/olcbox/app/vpn/service"
svc="$pkg/OlcboxVpnService.kt"
go_names='(^|[^A-Za-z0-9_])[Mm]obile\.|singBoxCore|xrayCore|AndroidSingBoxController|AndroidXrayController|AndroidCoreProcess'

[ -f "$svc" ] || { echo "FAIL no service source at $svc"; exit 1; }
if grep -nE "$go_names" "$svc"; then bad "OlcboxVpnService.kt names a Go core (the lines above)"; fi
if grep -nE 'RustCore\.backend|RustCoreBackend' "$svc"; then bad "OlcboxVpnService.kt reaches for the Rust engine (the lines above)"; fi
for f in EngineSession GoEngineSession RustEngineSession; do
  [ -f "$pkg/engine/$f.kt" ] || bad "engine/$f.kt is missing"
done
while IFS= read -r f; do
  case "$f" in "$pkg/engine/GoEngineSession.kt") continue ;; esac
  if grep -qE "$go_names" "$f"; then bad "${f#"$root"/} names a Go core; only engine/GoEngineSession.kt may"; fi
done < <(find "$pkg" -name '*.kt' | sort)

# ---- the proxy-mode refusal sits behind its hook -----------------------------
refusal='does not run proxy mode'
hook="$pkg/engine/RustProxyMode.kt"
if [ -f "$hook" ] && grep -q "$refusal" "$hook"; then
  while IFS= read -r f; do
    [ "$f" = "$hook" ] && continue
    if grep -q "$refusal" "$f"; then bad "${f#"$root"/} carries the proxy-mode refusal; it belongs in engine/RustProxyMode.kt"; fi
  done < <(find "$pkg" -name '*.kt' | sort)
  if ! grep -q 'RustProxyMode\.' "$pkg/engine/RustEngineSession.kt" 2>/dev/null; then bad "RustEngineSession.kt does not ask RustProxyMode"; fi
else
  bad "engine/RustProxyMode.kt does not hold the proxy-mode refusal"
fi

# ---- the e2e script runs every class and notices one that did not run --------
e2e="$root/scripts/rust-core-android-e2e.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin" "$tmp/apks" "$tmp/src/kotlin/org/example/app"
: > "$tmp/apks/app-debug.apk"
: > "$tmp/apks/app-debug-androidTest.apk"
for c in AlphaE2ETest BetaE2ETest; do
  printf 'package org.example.app\n\nclass %s {\n    @Test\n    fun runs() {}\n}\n' "$c" > "$tmp/src/kotlin/org/example/app/$c.kt"
done
# Not a test class: nothing asks for it.
printf 'package org.example.app\n\nobject Helpers\n' > "$tmp/src/kotlin/org/example/app/Helpers.kt"

# The stand-in adb: quiet for everything but `shell am instrument`, where it
# reports one passed test for each class asked for, except FAKE_SKIP (a class
# the runner did not run) and FAKE_FAIL (a class whose test fails).
cat > "$tmp/bin/adb" <<'ADB'
#!/usr/bin/env bash
[ "${1:-}" = shell ] && [ "${2:-}" = am ] && [ "${3:-}" = instrument ] || exit 0
classes=""
prev=""
for a in "$@"; do
  if [ "$prev" = class ]; then classes="$a"; fi
  prev="$a"
done
printf '%s\n' "$classes" > "$FAKE_ASKED"
n=0
failed=0
for c in ${classes//,/ }; do
  [ "$c" = "${FAKE_SKIP:-}" ] && continue
  n=$((n + 1))
  code=0
  if [ "$c" = "${FAKE_FAIL:-}" ]; then code=-2; failed=$((failed + 1)); fi
  for status in 1 "$code"; do
    printf 'INSTRUMENTATION_STATUS: class=%s\n' "$c"
    printf 'INSTRUMENTATION_STATUS: current=%s\n' "$n"
    printf 'INSTRUMENTATION_STATUS: id=AndroidJUnitRunner\n'
    printf 'INSTRUMENTATION_STATUS: test=runs\n'
    printf 'INSTRUMENTATION_STATUS_CODE: %s\n' "$status"
  done
done
printf 'INSTRUMENTATION_RESULT: stream=\n\n'
if [ "$failed" -gt 0 ]; then
  printf 'FAILURES!!!\nTests run: %s,  Failures: %s\n' "$n" "$failed"
else
  printf 'OK (%s tests)\n' "$n"
fi
printf 'INSTRUMENTATION_CODE: -1\n'
ADB
chmod +x "$tmp/bin/adb"

# run <name> <expected: pass|fail> [VAR=value ...]: the script against the stand-in.
run() {
  local name=$1 expect=$2 rc=0
  shift 2
  env PATH="$tmp/bin:$PATH" FAKE_ASKED="$tmp/asked-$name" RUST_CORE_E2E_SRC="${SRC:-$tmp/src}" "$@" \
    bash "$e2e" "$tmp/apks" "$tmp/out-$name" > "$tmp/log-$name" 2>&1 || rc=$?
  if [ "$expect" = pass ] && [ "$rc" -ne 0 ]; then bad "e2e script, $name: exit $rc, expected a pass"; sed 's/^/    /' "$tmp/log-$name"; fi
  if [ "$expect" = fail ] && [ "$rc" -eq 0 ]; then bad "e2e script, $name: passed, expected a failure"; sed 's/^/    /' "$tmp/log-$name"; fi
  return 0
}

run all pass
asked="$(cat "$tmp/asked-all" 2>/dev/null || true)"
for c in org.example.app.AlphaE2ETest org.example.app.BetaE2ETest; do
  case ",$asked," in *",$c,"*) ;; *) bad "e2e script did not ask for $c (asked: '${asked}')" ;; esac
done
case ",$asked," in *Helpers*) bad "e2e script asked for a file with no test in it" ;; esac

run skipped fail FAKE_SKIP=org.example.app.BetaE2ETest
grep -q 'org.example.app.BetaE2ETest.*did not run' "$tmp/log-skipped" || bad "e2e script does not name the class that did not run"

run failed fail FAKE_FAIL=org.example.app.AlphaE2ETest

mkdir -p "$tmp/empty"
SRC="$tmp/empty" run empty fail

[ "$fail" -eq 0 ] || exit 1
echo "engine seam: ok"
