#!/usr/bin/env bash
# test-ios-app-room-check.sh: the app's own olcRTC (the room check and the
# probe) in an iPhone simulator, and no Go in the app.
#
#   test-ios-app-room-check.sh plan
#       what the simulator compiles and runs. Any host.
#   test-ios-app-room-check.sh selftest
#       this script's own parts that need no Mac: the plan, the packages it
#       writes, the scope and the workflow's paths. Any host; seconds.
#   test-ios-app-room-check.sh scope <changed-files>
#       which Mac jobs of rust-ios.yml a change reaches, as `engines=` and
#       `app=` lines (true or false) for $GITHUB_OUTPUT.
#   test-ios-app-room-check.sh run <core-dir> <shared-ui-dir> <work-dir>
#       both packages, tested on an iPhone simulator.
#   test-ios-app-room-check.sh scan <app-bundle>
#       no Go in any Mach-O file of the app, and the core in its binary.
#
# <core-dir> is scripts/build-ghostlane-core-ios.sh's output: the UniFFI
# bindings in gen/ and GhostlaneCore.xcframework, whose simulator slice
# (ios-arm64-simulator) is linked. <shared-ui-dir> holds the app's Kotlin
# for the simulator, SharedUI.framework (Gradle's
# :sharedUI:linkDebugFrameworkIosSimulatorArm64).
#
# run writes two Swift packages into <work-dir> (Swift 6, as the app target
# is) and runs each with `xcodebuild test` on the newest iPhone simulator,
# XCTest in the simulator:
#   harness  what any app Swift needs, whatever the app's Swift is: SharedUI
#            and the core link into one test bundle with the link flags the
#            app uses, values cross from the Kotlin, and the core itself,
#            through the bindings, joins a stand-in hub's room and ends there
#            (iosApp/Tests/AppRoomCheck/HarnessTests.swift)
#   app      iosApp/iosApp/SwiftOlcRtcManager.swift, copied byte for byte,
#            with the bindings the app compiles; the room check and the probe
#            through it, as IosVpnManager asks for them
#            (iosApp/Tests/AppRoomCheck/RoomCheckTests.swift)
# Every room is PipeHub.swift's: the carrier `none`, an interop pipe hub on
# loopback. No olcRTC server is in a room, so the tests show the room
# reached and the call ending there, a request's timeout kept, a refusal
# before any room and the engine's lines on the app's writer; a check that
# succeeds is not something this harness can show.
#
# scan reads every Mach-O file of the bundle (the app's binary and the
# extension inside it) with nm for the symbols PR Checks looks for in the
# extension, and with K1 (scripts/check-no-go.sh) for everything else Go
# leaves; the app's binary must hold the core's UniFFI exports.
#
# Each check prints a line starting `ok   ` or `FAIL `; the run leaves with 1
# after a failure. Needs Xcode on an Apple silicon Mac for run and scan, and
# python3. bash 3.2 safe.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TESTS="$ROOT/iosApp/Tests/AppRoomCheck"
APP_SWIFT="$ROOT/iosApp/iosApp/SwiftOlcRtcManager.swift"
WORKFLOW="$ROOT/.github/workflows/rust-ios.yml"
SLICE=ios-arm64-simulator
PY="${PYTHON:-python3}"
# What the app target links beside SharedUI, as its OTHER_LDFLAGS say, and
# the system frameworks its own Swift imports (and so links), which the
# Kotlin may lean on.
LINK_FLAGS="-lghostlane_core -lresolv -lc++ -lz -framework Security -framework SystemConfiguration
-framework UIKit -framework SwiftUI -framework AVFoundation -framework NetworkExtension -framework Combine
-framework UniformTypeIdentifiers -framework CoreGraphics -framework CoreText -framework QuartzCore -framework Metal"
# The symbols PR Checks' iPhone tunnel extension job refuses in the extension.
GO_SYMBOLS=' _(runtime\.|go:buildid|_cgo_|x_cgo_|Mobile|Libbox|LibXray)'
# What each Mac job of rust-ios.yml reads, as the workflow's `paths` write
# them; the workflow's lists are their union (selftest holds them to it).
ENGINE_PATHS=".github/workflows/rust-ios.yml
scripts/test-ios-engine-on-mac.sh
iosApp/Tests/EngineHost/**
iosApp/PacketTunnel/**
scripts/ghostlane-core-pin.sh
scripts/ghostlane-core-resolve.sh
scripts/build-ghostlane-core-host.sh
sharedUI/src/jvmTest/resources/reference-configs/*core-*.json
sharedUI/src/jvmTest/resources/reference-configs/ios-tun-socks-lossy.json
scripts/xray-servers.txt"
APP_PATHS=".github/workflows/rust-ios.yml
scripts/test-ios-app-room-check.sh
iosApp/Tests/AppRoomCheck/**
iosApp/iosApp/**
iosApp/iosApp.xcodeproj/project.pbxproj
sharedUI/src/iosMain/kotlin/org/olcbox/app/ios/IosBridge.kt
scripts/ghostlane-core-pin.sh
scripts/build-ghostlane-core-ios.sh"

die() {
  echo "FAIL $*" >&2
  exit 1
}

usage() {
  sed -n '2,17p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2
  exit 2
}

plan() {
  echo "harness package (RoomCheckHarness):"
  echo "  RoomCheckCore: <core-dir>/gen/OlcFfi.swift, <core-dir>/gen/OlcrtcError+Localized.swift"
  echo "  HarnessTests:  iosApp/Tests/AppRoomCheck/HarnessTests.swift, PipeHub.swift"
  echo "app package (AppRoomCheck):"
  echo "  AppRoomCheckBridge: iosApp/iosApp/SwiftOlcRtcManager.swift (byte for byte), the same two bindings"
  echo "  AppRoomCheckTests:  iosApp/Tests/AppRoomCheck/RoomCheckTests.swift, PipeHub.swift"
  echo "Both in Swift 6 for the simulator (arm64), linked with SharedUI.framework, -L <the $SLICE slice> and"
  echo "  $(echo "$LINK_FLAGS" | tr '\n' ' ')"
  echo "Tests: $(tests_of "$TESTS/HarnessTests.swift" | wc -l | tr -d ' ') in the harness, $(tests_of "$TESTS/RoomCheckTests.swift" | wc -l | tr -d ' ') through the app's Swift."
}

# The test methods a file declares, one per line.
tests_of() {
  sed -n -E 's/^[[:space:]]*func (test[A-Za-z0-9_]*)\(.*/\1/p' "$1"
}

# manifest <dir> <package> <library> <tests> <slice> <shared-ui-dir>
manifest() {
  local dir="$1" name="$2" library="$3" tests="$4" slice="$5" shared="$6" flag flags=""
  for flag in $LINK_FLAGS; do flags="$flags\"$flag\", "; done
  cat > "$dir/Package.swift" << EOF
// swift-tools-version:6.0
// Written by scripts/test-ios-app-room-check.sh for one run; not committed.
import PackageDescription

let sharedUI = "$shared"
let core = "$slice"
let compile: [SwiftSetting] = [.unsafeFlags(["-F", sharedUI, "-I", core + "/Headers/OlcFfiFFI"])]
let link: [LinkerSetting] = [
    .unsafeFlags(["-ObjC", "-F", sharedUI, "-framework", "SharedUI", "-L", core, $flags]),
]

let package = Package(
    name: "$name",
    platforms: [.iOS(.v15)],
    products: [.library(name: "$name", targets: ["$library"])],
    targets: [
        .target(name: "$library", path: "$library", swiftSettings: compile, linkerSettings: link),
        .testTarget(
            name: "$tests", dependencies: ["$library"], path: "$tests",
            swiftSettings: compile, linkerSettings: link
        ),
    ]
)
EOF
}

# write_packages <core-dir> <slice-dir> <shared-ui-dir> <work-dir>
write_packages() {
  local core="$1" slice="$2" shared="$3" work="$4" h="$4/harness" a="$4/app"
  rm -rf "$h" "$a"
  mkdir -p "$h/RoomCheckCore" "$h/HarnessTests" "$a/AppRoomCheckBridge" "$a/AppRoomCheckTests"
  cp "$core/gen/OlcFfi.swift" "$core/gen/OlcrtcError+Localized.swift" "$h/RoomCheckCore/"
  cp "$TESTS/HarnessTests.swift" "$TESTS/PipeHub.swift" "$h/HarnessTests/"
  manifest "$h" RoomCheckHarness RoomCheckCore HarnessTests "$slice" "$shared"
  cp "$APP_SWIFT" "$core/gen/OlcFfi.swift" "$core/gen/OlcrtcError+Localized.swift" "$a/AppRoomCheckBridge/"
  cmp -s "$APP_SWIFT" "$a/AppRoomCheckBridge/SwiftOlcRtcManager.swift" || die "the copy of SwiftOlcRtcManager.swift differs"
  cp "$TESTS/RoomCheckTests.swift" "$TESTS/PipeHub.swift" "$a/AppRoomCheckTests/"
  manifest "$a" AppRoomCheck AppRoomCheckBridge AppRoomCheckTests "$slice" "$shared"
}

# The newest iOS runtime's first available iPhone: "<udid> <name> (iOS <v>)".
simulator() {
  xcrun simctl list devices available -j | "$PY" -I -c '
import json, re, sys
devices = json.load(sys.stdin)["devices"]
best = None
for runtime, entries in devices.items():
    m = re.search(r"SimRuntime\.iOS-(\d+)-(\d+)(?:-(\d+))?$", runtime)
    if not m:
        continue
    version = tuple(int(x or 0) for x in m.groups())
    phones = [d for d in entries if d.get("isAvailable", True) and d["name"].startswith("iPhone")]
    if phones and (best is None or version > best[0]):
        best = (version, phones[0]["udid"], phones[0]["name"])
if best is None:
    sys.exit("no available iPhone simulator")
print(best[1], "%s (iOS %s)" % (best[2], ".".join(map(str, best[0]))))
'
}

# scheme_of <package-dir> <package>: the scheme that tests the package.
scheme_of() {
  (cd "$1" && xcodebuild -list -json 2> /dev/null) | "$PY" -I -c '
import json, sys
name = sys.argv[1]
text = sys.stdin.read()
data, _ = json.JSONDecoder().raw_decode(text[text.index("{"):])
schemes = (data.get("workspace") or data.get("project") or {}).get("schemes", [])
for want in (name + "-Package", name):
    if want in schemes:
        print(want)
        sys.exit(0)
sys.exit("no scheme %s or %s-Package among %s" % (name, name, schemes))
' "$2"
}

# report <log> <package> <test-source> <xcodebuild-status>: a line per test.
report() {
  "$PY" -I - "$@" << 'PY'
import re, sys
log, package, source, status = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])
text = open(log, errors="replace").read()
want = re.findall(r"^\s*func (test\w*)\(", open(source).read(), re.M)
seen = {}
# Both of Xcode's forms: "Test Case '-[Module.Class testX]' passed (0.1 seconds)."
# and "Test case 'Class.testX()' passed on 'iPhone ...' (0.1 seconds)".
for m in re.finditer(r"Test [Cc]ase '(?:-\[\S+ (test\w*)\]|\S*?\.?(test\w*)\(\))' (passed|failed)", text):
    seen[m.group(1) or m.group(2)] = m.group(3)
for line in text.splitlines():
    if line.startswith("note "):
        print("     " + line)
failed = 0
for test in want:
    state = seen.get(test)
    if state == "passed":
        print("ok   %s: %s" % (package, test))
    else:
        failed = 1
        print("FAIL %s: %s %s" % (package, test, state or "did not run"))
if status != 0 and not failed:
    failed = 1
    print("FAIL %s: xcodebuild left with %d" % (package, status))
if failed:
    # What the compiler, the linker and XCTest said: a test that failed, or
    # one that never ran because the package did not build.
    errors = [l.strip() for l in text.splitlines() if re.search(r"(error|fatal error): |Undefined symbols", l)]
    print("     %d error lines; the first ones:" % len(errors))
    for l in errors[:12]:
        print("       " + l[-400:])
sys.exit(failed)
PY
}

# test_package <package-dir> <package> <udid> <test-source>
test_package() {
  local dir="$1" name="$2" udid="$3" source="$4" scheme rc
  if ! scheme="$(scheme_of "$dir" "$name")"; then
    echo "FAIL $name: no scheme to test it with"
    return 1
  fi
  echo "     $name: xcodebuild test -scheme $scheme"
  set +e
  (cd "$dir" && xcodebuild test -scheme "$scheme" -destination "id=$udid" -derivedDataPath "$dir.derived" \
    -parallel-testing-enabled NO ARCHS=arm64 ONLY_ACTIVE_ARCH=YES) > "$dir.log" 2>&1
  rc=$?
  set -e
  if ! report "$dir.log" "$name" "$source" "$rc"; then
    echo "     the end of $name's log:"
    tail -n 40 "$dir.log" | sed 's/^/       /'
    return 1
  fi
}

# run <core-dir> <shared-ui-dir> <work-dir>
run() {
  local core shared work slice udid what failed=0
  [ "$(uname -s) $(uname -m)" = "Darwin arm64" ] || die "the simulator slice runs on an Apple silicon Mac, not $(uname -s) $(uname -m)"
  core="$(cd "$1" && pwd)"
  shared="$(cd "$2" && pwd)"
  mkdir -p "$3"
  work="$(cd "$3" && pwd)"
  slice="$core/GhostlaneCore.xcframework/$SLICE"
  [ -s "$slice/libghostlane_core.a" ] || die "no $slice/libghostlane_core.a: build it with scripts/build-ghostlane-core-ios.sh $core"
  [ -s "$slice/Headers/OlcFfiFFI/module.modulemap" ] || die "no OlcFfiFFI module in $slice/Headers"
  if [ ! -s "$core/gen/OlcFfi.swift" ] || [ ! -s "$core/gen/OlcrtcError+Localized.swift" ]; then
    die "no bindings in $core/gen"
  fi
  [ -d "$shared/SharedUI.framework" ] || die "no SharedUI.framework in $shared: run ./gradlew :sharedUI:linkDebugFrameworkIosSimulatorArm64"
  echo "core: $(cat "$core/GhostlaneCore.xcframework.commit" 2> /dev/null || echo unknown)"
  echo "xcode: $(xcodebuild -version | tr '\n' ' ')"
  read -r udid what < <(simulator) || die "no iPhone simulator: $(xcrun simctl list runtimes | tr '\n' ' ')"
  echo "simulator: $what"
  xcrun simctl bootstatus "$udid" -b > "$work/boot.log" 2>&1 || die "the simulator did not boot: $(tail -n 5 "$work/boot.log")"
  write_packages "$core" "$slice" "$shared" "$work"
  test_package "$work/harness" RoomCheckHarness "$udid" "$TESTS/HarnessTests.swift" || failed=1
  test_package "$work/app" AppRoomCheck "$udid" "$TESTS/RoomCheckTests.swift" || failed=1
  return "$failed"
}

# scan <app-bundle>
scan() {
  local app bin f n=0 failed=0 hits exports
  local files=()
  [ -d "$1" ] || die "no app at $1: the build did not make one"
  app="$(cd "$1" && pwd)"
  bin="$app/$(basename "$app" .app)"
  [ -f "$bin" ] || die "no binary $bin"
  while IFS= read -r f; do
    case "$(file -b "$f")" in *Mach-O*) ;; *) continue ;; esac
    n=$((n + 1))
    files+=("$f")
    hits="$(nm "$f" 2> /dev/null | grep -E "$GO_SYMBOLS" | head -n 5 || true)"
    if [ -n "$hits" ]; then
      echo "FAIL Go symbols in ${f#"$app"/}:"
      echo "$hits" | sed 's/^/       /'
      failed=1
    else
      echo "ok   no Go symbols in ${f#"$app"/}, $(wc -c < "$f" | tr -d ' ') bytes"
    fi
  done < <(find "$app" -type f | sort)
  [ "$n" -gt 0 ] || die "no Mach-O file in $app"
  if bash "$ROOT/scripts/check-no-go.sh" --no-allow "${files[@]}" > "$app.k1.txt" 2>&1; then
    echo "ok   K1 finds no Go in the $n Mach-O files"
  else
    echo "FAIL K1 on the $n Mach-O files:"
    sed 's/^/       /' "$app.k1.txt"
    failed=1
  fi
  exports="$(nm "$bin" 2> /dev/null | grep -c ' _uniffi_olc_ffi_fn_' || true)"
  if [ "${exports:-0}" -gt 0 ]; then
    echo "ok   the app's binary holds the Rust core: $exports of olc-ffi's UniFFI functions"
  else
    echo "FAIL the app's binary holds none of olc-ffi's UniFFI functions: the core is not linked"
    failed=1
  fi
  return "$failed"
}

# scope <changed-files>
scope() {
  local f pat engines=false app=false
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    while IFS= read -r pat; do
      # shellcheck disable=SC2254 # the pattern is a glob on purpose
      case "$f" in $pat) engines=true ;; esac
    done <<< "$ENGINE_PATHS"
    while IFS= read -r pat; do
      # shellcheck disable=SC2254
      case "$f" in $pat) app=true ;; esac
    done <<< "$APP_PATHS"
  done < "$1"
  echo "engines=$engines"
  echo "app=$app"
}

selftest() {
  local work out f file want
  work="$(mktemp -d)"
  # shellcheck disable=SC2064
  trap "rm -rf '$work'" EXIT
  plan > "$work/plan.txt"
  [ "$(tests_of "$TESTS/HarnessTests.swift" | wc -l)" -ge 2 ] || die "HarnessTests.swift has fewer than two tests"
  [ "$(tests_of "$TESTS/RoomCheckTests.swift" | wc -l)" -ge 5 ] || die "RoomCheckTests.swift has fewer than five tests"
  echo "ok   the plan: $(tail -n 1 "$work/plan.txt")"

  mkdir -p "$work/core/gen" "$work/core/GhostlaneCore.xcframework/$SLICE" "$work/shared/SharedUI.framework"
  echo "// bindings" > "$work/core/gen/OlcFfi.swift"
  echo "// shim" > "$work/core/gen/OlcrtcError+Localized.swift"
  write_packages "$work/core" "$work/core/GhostlaneCore.xcframework/$SLICE" "$work/shared" "$work/out"
  for f in harness/RoomCheckCore/OlcFfi.swift harness/HarnessTests/PipeHub.swift harness/HarnessTests/HarnessTests.swift \
    app/AppRoomCheckBridge/SwiftOlcRtcManager.swift app/AppRoomCheckBridge/OlcFfi.swift \
    "app/AppRoomCheckBridge/OlcrtcError+Localized.swift" app/AppRoomCheckTests/RoomCheckTests.swift app/AppRoomCheckTests/PipeHub.swift; do
    [ -s "$work/out/$f" ] || die "the packages lack $f"
  done
  cmp -s "$APP_SWIFT" "$work/out/app/AppRoomCheckBridge/SwiftOlcRtcManager.swift" || die "the app's Swift is not copied byte for byte"
  grep -q '^// swift-tools-version:6.0$' "$work/out/app/Package.swift" || die "the app package is not Swift 6"
  grep -q '"-lghostlane_core", ' "$work/out/app/Package.swift" || die "the app package does not link the core"
  grep -q "\"-framework\", \"SharedUI\"" "$work/out/harness/Package.swift" || die "the harness does not link SharedUI"
  grep -q "products: \[.library(name: \"AppRoomCheck\", targets: \[\"AppRoomCheckBridge\"\])\]" "$work/out/app/Package.swift" ||
    die "the app package's product"
  echo "ok   the packages: the app's Swift byte for byte, the bindings, the tests, Swift 6, the link flags"

  # The scope: which Mac job a change reaches.
  while read -r file want; do
    echo "$file" > "$work/changed"
    out="$(scope "$work/changed" | tr '\n' ' ')"
    [ "$out" = "$want " ] || die "scope of $file: $out, not $want"
  done << 'CASES'
iosApp/PacketTunnel/OlcrtcEngine.swift engines=true app=false
iosApp/iosApp/SwiftOlcRtcManager.swift engines=false app=true
iosApp/iosApp.xcodeproj/project.pbxproj engines=false app=true
iosApp/Tests/AppRoomCheck/PipeHub.swift engines=false app=true
iosApp/Tests/EngineHost/HostHub.swift engines=true app=false
sharedUI/src/iosMain/kotlin/org/olcbox/app/ios/IosBridge.kt engines=false app=true
sharedUI/src/iosMain/kotlin/org/olcbox/app/vpn/IosVpnManager.kt engines=false app=false
sharedUI/src/jvmTest/resources/reference-configs/ios-core-reality.json engines=true app=false
scripts/ghostlane-core-pin.sh engines=true app=true
.github/workflows/rust-ios.yml engines=true app=true
README.md engines=false app=false
CASES
  printf 'README.md\niosApp/iosApp/Info.plist\n' > "$work/changed"
  [ "$(scope "$work/changed" | tr '\n' ' ')" = "engines=false app=true " ] || die "scope of two files"
  : > "$work/changed"
  [ "$(scope "$work/changed" | tr '\n' ' ')" = "engines=false app=false " ] || die "scope of no change"
  echo "ok   the scope: each Mac job for what it reads, and nothing else"

  # Every `paths` list of the workflow is the union of the two, so nothing
  # starts the workflow that no job reads, and nothing a job reads misses it.
  printf '%s\n%s\n' "$ENGINE_PATHS" "$APP_PATHS" | sort -u > "$work/union"
  "$PY" -I - "$WORKFLOW" "$work/union" << 'PY' || die "the workflow's paths are not the union of the scope's"
import sys
workflow, union = sys.argv[1], sys.argv[2]
want = {l.strip() for l in open(union) if l.strip()}
lists, current = [], None
for line in open(workflow):
    s = line.strip()
    if s == "paths:":
        current = set()
        lists.append(current)
    elif current is not None and s.startswith("- "):
        current.add(s[2:].strip().strip("'\""))
    elif current is not None and s:
        current = None
if len(lists) != 2:
    sys.exit("expected two paths lists (push, pull_request), found %d" % len(lists))
for n, got in enumerate(lists):
    if got != want:
        print("paths list %d: missing %s, extra %s" % (n + 1, sorted(want - got), sorted(got - want)))
        sys.exit(1)
PY
  echo "ok   the workflow's two paths lists are the union of what the Mac jobs read"
}

[ $# -ge 1 ] || usage
cmd="$1"
shift
case "$cmd" in
  plan) [ $# -eq 0 ] || usage; plan ;;
  selftest) [ $# -eq 0 ] || usage; selftest ;;
  scope) [ $# -eq 1 ] || usage; scope "$1" ;;
  run) [ $# -eq 3 ] || usage; run "$@" ;;
  scan) [ $# -eq 1 ] || usage; scan "$1" ;;
  *) usage ;;
esac
