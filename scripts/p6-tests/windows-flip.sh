#!/usr/bin/env bash
# W4, the Windows flip: the shipped Windows app carries ghostlane-engine, hev
# and Wintun and no Go core, every PE we build is signed before it is packed,
# the desktop manager reaches the Windows pieces, and the Windows legs of CI
# are required. Fails while
#   - release.yml's build-windows job builds or downloads sing-box, Xray or the
#     olcRTC engine, sets up Go, or checks out the olcRTC tree;
#   - that job does not build the engine at the pin, hev and Wintun, does not
#     read the engine's version, does not name the PEs we build in a signing
#     list, does not check what the finished jar holds against that list, or
#     does not run K1 on the exe, the msi and the portable zip;
#   - the Windows resource list stages a Go core, or lacks the engine, hev.exe
#     or wintun.dll;
#   - an allow-list file of a Windows artefact is still in scripts/no-go-allow.d;
#   - the sweep of earlier builds' files does not know sing-box.exe, xray.exe and
#     the olcRTC engine of Windows;
#   - rust-desktop.yml's Windows leg is allowed to fail, or its bundle check
#     accepts a bundle with no engine;
#   - DesktopVpnManager still starts sing-box's tun on Windows, or does not use
#     WindowsTunController / WindowsAdapterTraffic; DesktopLanProxy does not
#     use WindowsLanFirewall; the system proxy on Windows is not
#     WindowsSystemProxy with a journal that is read at the next start.
#
#   scripts/p6-tests/windows-flip.sh [root]     root defaults to the repository
# Needs bash and the usual text tools.
set -euo pipefail
root="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
fail=0
bad() { echo "FAIL $*"; fail=1; }

release="$root/.github/workflows/release.yml"
job=$(awk '/^  build-windows:/{p=1;print;next} /^  [a-z][a-z0-9_-]*:/{if(p)exit} p' "$release")
[ -n "$job" ] || bad "release.yml has no build-windows job"

if grep -nE 'build-singbox\.sh|Xray-windows|setup-go|GO_VERSION|go build|olcrtc_ref|repository: ghostlane-project/olcrtc|OLCRTC_REPO' <<<"$job"; then
  bad "release.yml's build-windows still builds, downloads or checks out a Go core"
fi
grep -q 'build-ghostlane-core-host.sh' <<<"$job" || bad "build-windows does not build ghostlane-engine at the pin"
grep -q 'x86_64-pc-windows-msvc' <<<"$job" || bad "build-windows names no msvc target"
grep -q 'build-hev-windows.sh' <<<"$job" || bad "build-windows does not build hev and fetch Wintun"
grep -q 'ghostlane-engine.exe" version' <<<"$job" || bad "build-windows does not read the engine's version event"
grep -q 'check-no-go.sh' <<<"$job" || bad "build-windows does not run K1 on the assets"
for asset in windows-amd64.exe windows-amd64.msi windows-amd64-portable.zip; do
  grep -q "$asset" <<<"$(grep -n 'check-no-go.sh' -A6 <<<"$job")" || bad "build-windows's K1 step does not name $asset"
done
grep -q 'WINDOWS_PES_WE_BUILD' <<<"$job" || bad "build-windows has no signing list of the PEs we build (WINDOWS_PES_WE_BUILD)"
for pe in ghostlane-engine.exe hev.exe; do
  grep -q "WINDOWS_PES_WE_BUILD:.*$pe" <<<"$job" || bad "$pe is not on the signing list"
done
grep -q 'Every PE we build is on the signing list' <<<"$job" || bad "build-windows has no step that holds the finished jar to the signing list"
grep -q 'Sign the PEs we build' <<<"$job" || bad "build-windows does not sign the PEs before they are packed"

res="$root/desktopApp/gradle/native-windows.resources.txt"
grep -qx 'amd64 bundled native/ghostlane-engine.exe' "$res" || bad "native-windows.resources.txt does not bundle the engine"
grep -qx 'amd64 generated native/hev.exe' "$res" || bad "native-windows.resources.txt does not stage hev.exe"
grep -qx 'amd64 generated native/wintun.dll' "$res" || bad "native-windows.resources.txt does not stage wintun.dll"
if grep -nE 'sing-box|xray|olcrtc' "$res" | grep -v '^[0-9]*:#'; then
  bad "native-windows.resources.txt still stages a Go core"
fi
gradle="$root/desktopApp/gradle/native-windows.gradle.kts"
if grep -nE 'registerOlcRtc|buildOlcRtcWindows|native/sing-box|native/xray|downloadWintun|msys-2\.0' "$gradle" | grep -v '^[0-9]*:\s*//'; then
  bad "native-windows.gradle.kts still builds or stages a Go core or downloads Wintun on its own"
fi

for f in "$root"/scripts/no-go-allow.d/*windows*; do
  [ ! -e "$f" ] || bad "${f#"$root"/} is on the allow list; the Windows assets are clean"
done

sweep="$root/sharedUI/src/jvmMain/kotlin/org/olcbox/app/vpn/desktop/StaleBinarySweep.kt"
for name in 'sing-box.exe' 'xray.exe' 'olcrtc-windows-'; do
  grep -q "\"$name\"" "$sweep" || bad "StaleBinarySweep does not know $name"
done
grep -q 'StaleBinarySweep' "$root/sharedUI/src/jvmMain/kotlin/org/olcbox/app/vpn/DesktopVpnManager.kt" \
  || bad "the desktop manager never runs StaleBinarySweep (Windows has no launcher script to do it)"

rd="$root/.github/workflows/rust-desktop.yml"
if grep -nE "continue-on-error: .*windows" "$rd"; then
  bad "rust-desktop.yml's Windows leg is still allowed to fail"
fi
if grep -nE 'No program for Windows|WINDOWS_BUNDLE_ENGINE_REQUIRED|no program for Windows' "$rd" "$root/scripts/desktop-checks/windows-bundle.ps1"; then
  bad "the Windows leg or its bundle check still takes a bundle with no engine"
fi

mgr="$root/sharedUI/src/jvmMain/kotlin/org/olcbox/app/vpn/DesktopVpnManager.kt"
if grep -nE 'engine\.windowsTun\.|SingBoxConfig\.buildDesktopTun' "$mgr"; then
  bad "DesktopVpnManager still starts sing-box's tun on Windows"
fi
grep -q 'windowsTunController.startTunnel' "$mgr" || bad "DesktopVpnManager does not start the Windows tunnel through WindowsTunController"
grep -q 'WindowsAdapterTraffic' "$mgr" || bad "DesktopVpnManager does not count the adapter's traffic (WindowsAdapterTraffic)"
lan="$root/sharedUI/src/jvmMain/kotlin/org/olcbox/app/vpn/DesktopLanProxy.kt"
grep -q 'WindowsLanFirewall' "$lan" || bad "DesktopLanProxy does not use WindowsLanFirewall"
if grep -n 'New-NetFirewallRule' "$lan"; then
  bad "DesktopLanProxy still writes its own firewall script"
fi
pc="$root/sharedUI/src/jvmMain/kotlin/org/olcbox/app/vpn/desktop/DesktopProxyController.kt"
grep -q 'WindowsSystemProxy' "$pc" || bad "the Windows system proxy is not WindowsSystemProxy"
sp="$root/sharedUI/src/jvmMain/kotlin/org/olcbox/app/vpn/desktop/WindowsSystemProxy.kt"
grep -q 'journal' "$sp" || bad "WindowsSystemProxy keeps no journal of the values it changed"
grep -q 'recover' "$sp" || bad "WindowsSystemProxy does not restore a journal left by a killed run"

docs="$root/docs/rust-parity/rows"
for r in dist.windows.exe dist.windows.msi dist.windows.portable; do
  grep -q '^windows | proven |' "$docs/$r.txt" || bad "parity row $r is not proven"
done

exit "$fail"
