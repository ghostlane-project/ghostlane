#!/usr/bin/env bash
# A7: the shipped Android app has no Go path. Fails while
#   - a Go core controller or the Go session is still in the app's sources;
#   - the gomobile olcRTC AAR module is still a Gradle project or a dependency;
#   - shipped Android Kotlin imports the gomobile `mobile` package, or the
#     Android Gradle files name a Go build, a Go library or a Go toolchain;
#   - the app still reaches a Go engine through the old engine switch.
#
#   scripts/p6-tests/no-go-android.sh [root]     root defaults to the repository
# Needs bash and the usual text tools.
set -euo pipefail
root="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
fail=0
bad() { echo "FAIL $*"; fail=1; }

go_files=(
  sharedUI/src/androidMain/kotlin/org/olcbox/app/net/AndroidCoreProcess.kt
  sharedUI/src/androidMain/kotlin/org/olcbox/app/net/AndroidSingBoxController.kt
  sharedUI/src/androidMain/kotlin/org/olcbox/app/net/AndroidXrayController.kt
  sharedUI/src/androidMain/kotlin/org/olcbox/app/vpn/service/engine/GoEngineSession.kt
)
for f in "${go_files[@]}"; do
  [ ! -e "$root/$f" ] || bad "${f#"$root"/} is still shipped; the Android app has no Go engine"
done

if [ -d "$root/sharedUI/olcrtc-bin" ]; then
  bad "sharedUI/olcrtc-bin still builds a Go AAR with gomobile"
fi

# sharedUI also builds the iOS XCFramework with gomobile; that is another
# platform's flip. On Android no Go build, library or tool may remain.
go_gradle='olcrtc-bin|GOMOBILE_PATH|libgojni\.so|libsingboxcore\.so|libxraycore\.so'
for f in settings.gradle.kts sharedUI/build.gradle.kts androidApp/build.gradle.kts; do
  [ -f "$root/$f" ] || { bad "no Gradle file at $f"; continue; }
  if grep -nE "$go_gradle" "$root/$f"; then bad "${f#"$root"/} still builds or packs Go"; fi
done
if grep -nE 'gomobile' "$root/androidApp/build.gradle.kts"; then
  bad "the Android Gradle file still calls gomobile"
fi

# Imports only: a comment that remembers the Go cores is prose, not a code path.
go_import='^[[:space:]]*import[[:space:]]+mobile\.'
switch_names='EngineSwitch|GoEngineSession|AndroidSingBoxController|AndroidXrayController|AndroidCoreProcess'
src_dirs=(
  sharedUI/src/androidMain
  androidApp/src/main
  androidApp/src/rustcore
)
while IFS= read -r f; do
  if grep -nE "$go_import" "$f"; then bad "${f#"$root"/} imports the gomobile package"; fi
  if grep -nE "$switch_names" "$f"; then bad "${f#"$root"/} names a Go engine"; fi
done < <(find "${src_dirs[@]/#/"$root"/}" -type f -name '*.kt' 2>/dev/null | sort)

[ "$fail" -eq 0 ] || exit 1
echo "no Go on Android: ok"
