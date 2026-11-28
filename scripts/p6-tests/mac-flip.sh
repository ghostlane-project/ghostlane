#!/usr/bin/env bash
# M4, the Mac's flip: the DMG carries ghostlane-engine at the pin and hev for the
# helper, and no Go core; the helper has no sing-box verb and sweeps the Go
# copies an earlier version left in root's own directory. Fails while
#   - native-macos.gradle.kts builds an olcRTC core, bundles sing-box or Xray,
#     or the helper's embed step copies a sing-box into the app;
#   - native-macos.resources.txt lists a Go core, or lacks ghostlane-engine;
#   - the helper still takes the `start` verb, still has a sing-box child, or has
#     no Sweep.swift that the daemon runs;
#   - release.yml's build-macos sets up or builds Go, downloads Xray, or lacks
#     the engine at the pin, the hev, K1 on the DMG and on the app's own
#     directories, or the strict signature check;
#   - an allow-list file of a DMG is still in scripts/no-go-allow.d;
#   - the sing-box check of the helper is still run or still in the tree.
#
#   scripts/p6-tests/mac-flip.sh [root]     root defaults to the repository
# Needs bash and the usual text tools.
set -euo pipefail
root="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
fail=0
bad() { echo "FAIL $*"; fail=1; }

gradle="$root/desktopApp/gradle/native-macos.gradle.kts"
if grep -nE 'registerOlcRtc|buildOlcRtc|native/sing-box|native/xray|src/main/resources/native/sing-box' "$gradle"; then
  bad "native-macos.gradle.kts still builds or bundles a Go core"
fi
grep -q 'native/ghostlane-engine' "$gradle" || bad "native-macos.gradle.kts does not bundle ghostlane-engine"
grep -q 'hev-socks5-tunnel' "$gradle" || bad "native-macos.gradle.kts does not embed hev for the helper"

list="$root/desktopApp/gradle/native-macos.resources.txt"
if grep -nE 'sing-box|xray|olcrtc' "$list"; then
  bad "native-macos.resources.txt lists a Go core"
fi
for arch in amd64 arm64; do
  grep -qx "$arch bundled native/ghostlane-engine" "$list" || bad "native-macos.resources.txt: no '$arch bundled native/ghostlane-engine'"
done

daemon="$root/desktopApp/tunneldaemon"
[ -f "$daemon/Sweep.swift" ] || bad "desktopApp/tunneldaemon/Sweep.swift is missing"
grep -q 'Sweep\.' "$daemon/Daemon.swift" || bad "Daemon.swift does not run the sweep"
if grep -nE 'case "start"|func start\(|singBox|bundled\("sing-box"\)|coreSource' "$daemon/Daemon.swift" "$daemon/TunnelChild.swift"; then
  bad "the helper still has a sing-box verb or child"
fi

release="$root/.github/workflows/release.yml"
job=$(awk '/^  build-macos:/{p=1;print;next} /^  [a-z][a-z0-9_-]*:/{if(p)exit} p' "$release")
[ -n "$job" ] || bad "release.yml has no build-macos job"
if grep -nE 'setup-go|GO_VERSION|build-singbox\.sh|Xray-macos|XRAY_VERSION|OLCRTC_REPO' <<<"$job"; then
  bad "release.yml's build-macos still sets up, builds or downloads Go"
fi
for need in 'build-ghostlane-core-host.sh' 'build-hev-macos.sh' 'ghostlane-engine" version' 'check-no-go.sh' 'codesign --verify --deep --strict'; do
  grep -qF "$need" <<<"$job" || bad "release.yml's build-macos lacks: $need"
done

for f in "$root"/scripts/no-go-allow.d/Ghostlane-VERSION-macos-*.dmg.allow; do
  [ ! -e "$f" ] || bad "${f#"$root"/} is on the allow list; the DMG is clean"
done

[ ! -e "$root/scripts/check-macos-helper.sh" ] || bad "scripts/check-macos-helper.sh asks the sing-box tunnel and is still here"
checks="$root/.github/workflows/pr-checks.yml"
job=$(awk '/^  mac-helper:/{p=1;print;next} /^  [a-z][a-z0-9_-]*:/{if(p)exit} p' "$checks")
[ -n "$job" ] || bad "pr-checks.yml has no mac-helper job"
if grep -nE 'check-macos-helper|build-singbox|setup-go|cores-pins' <<<"$job"; then
  bad "PR Checks' mac-helper job still builds sing-box or asks its tunnel"
fi

exit "$fail"
