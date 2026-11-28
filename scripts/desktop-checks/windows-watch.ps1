# desktop-check-os: windows
# desktop-check-paths: sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/desktop/WindowsWatchRealTest.kt
# desktop-check-paths: sharedUI/src/jvmMain/kotlin/org/olcbox/app/vpn/desktop/PhysicalInterfaceWatch.kt
# desktop-check-paths: sharedUI/src/jvmMain/kotlin/org/olcbox/app/vpn/desktop/WindowsTunController.kt
#
# WW, the interface watch on a real Windows as an administrator:
# WindowsWatchRealTest (Gradle, WINDOWS_WATCH_CHECK=1) run with the hev.exe and
# wintun.dll of scripts/build-hev-windows.sh (HEV_OUT), and held to: every test
# of it run, none skipped, none failed.
#   - the reader the watch uses names the same way out as the manager's own
#     read before the tun (`physicalRoute`), never a tunnel adapter, and the
#     resolvers of the machine's adapters can be read;
#   - the machine is moved: hev makes a second Wintun adapter (WatchAlt), a
#     default route with the lowest metric is put on it, and the watch tells
#     the move to WatchAlt within seconds; with the route taken away it tells
#     the move back. The route points nowhere, so it is held for seconds.
#   - WR: with the tun up on a real engine and one spared /32, the move makes
#     the /32's next hop and interface the new route's (one route, none on the
#     old interface), the move back makes them the old one's, and after the
#     stop no route of it is left. The tun carries the traffic meanwhile, so
#     the moved state is not a black hole.
# Not covered: the whole session (the app's restart of the line behind the
# tun) on a real move; the watch's tell and the manager's new alias are the
# jvmTests', and the engine's own binding is windows-tun.ps1's.
#
# Run by rust-desktop.yml's Windows leg:
#   pwsh -NoProfile -File scripts/desktop-checks/windows-watch.ps1
# Needs Java 21 with the Android SDK for the Gradle build, HEV_OUT, administrator.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$summary = $env:GITHUB_STEP_SUMMARY
function Say($m) { Write-Host "windows-watch: $m" }
function Die($m) {
    Write-Host "windows-watch: $m"
    if ($summary) { Add-Content -Path $summary -Value "- windows-watch: $m" }
    exit 1
}

$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { Die 'this check needs an administrator session' }
if (-not $env:HEV_OUT -or -not (Test-Path (Join-Path $env:HEV_OUT 'hev.exe')) -or -not (Test-Path (Join-Path $env:HEV_OUT 'wintun.dll'))) { Die 'HEV_OUT must hold hev.exe and wintun.dll' }
if (-not $env:GHOSTLANE_ENGINE -or -not (Test-Path $env:GHOSTLANE_ENGINE)) { Die 'GHOSTLANE_ENGINE must name the engine for Windows' }

$class = 'org.olcbox.app.vpn.desktop.WindowsWatchRealTest'
$results = Join-Path $root 'sharedUI\build\test-results\jvmTest'
$xml = Join-Path $results "TEST-$class.xml"
Remove-Item -Force -ErrorAction SilentlyContinue $xml

$env:WINDOWS_WATCH_CHECK = '1'
Push-Location $root
try {
    & .\gradlew.bat ':sharedUI:jvmTest' --rerun --tests $class --stacktrace
    $rc = $LASTEXITCODE
} finally {
    Pop-Location
}

if (-not (Test-Path $xml)) { Die "Gradle wrote no result for $class (exit $rc)" }
[xml]$doc = Get-Content -Raw $xml
$suite = $doc.testsuite
$expected = (Select-String -Path (Join-Path $root 'sharedUI\src\jvmTest\kotlin\org\olcbox\app\vpn\desktop\WindowsWatchRealTest.kt') -Pattern '^\s*@Test\b' | Measure-Object).Count
Say "tests=$($suite.tests) skipped=$($suite.skipped) failures=$($suite.failures) errors=$($suite.errors) expected=$expected"
if ([int]$suite.tests -ne $expected) { Die "ran $($suite.tests) tests, the file has $expected" }
if ([int]$suite.skipped -ne 0) { Die "$($suite.skipped) test(s) were skipped" }
foreach ($case in $suite.testcase) {
    if ($case.failure) { Write-Host "windows-watch: $($case.name): $($case.failure.message)" }
}
if ([int]$suite.failures -ne 0 -or [int]$suite.errors -ne 0 -or $rc -ne 0) { Die "the tests failed (exit $rc)" }

$left = @()
if (Get-NetAdapter -Name 'WatchAlt*' -ErrorAction SilentlyContinue) { $left += 'an adapter' }
if (Get-NetRoute -InterfaceAlias 'WatchAlt*' -ErrorAction SilentlyContinue) { $left += 'a route' }
if (Get-Process -Name hev -ErrorAction SilentlyContinue) { $left += 'hev.exe' }
if (Get-NetAdapter -Name 'Ghostlane-Tun*' -ErrorAction SilentlyContinue) { $left += 'the tun adapter' }
if (Get-NetRoute -DestinationPrefix '203.0.113.77/32' -ErrorAction SilentlyContinue) { $left += 'the spared route' }
if ($left.Count) { Die ("left on the machine: " + ($left -join ', ')) }
Say "passed: $expected tests, nothing left"
if ($summary) { Add-Content -Path $summary -Value "- windows-watch: $expected tests passed (a default route moved to a second adapter and back), nothing left" }
