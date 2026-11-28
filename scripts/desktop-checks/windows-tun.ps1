# desktop-check-os: windows
#
# K9, the Windows tun, on a real Windows as an administrator: WindowsTunRealTest
# (Gradle, WINDOWS_TUN_CHECK=1) run with the hev.exe and wintun.dll of
# scripts/build-hev-windows.sh (HEV_OUT) and the engine of the Core build for
# Windows (GHOSTLANE_ENGINE), and held to: every test of it run, none skipped,
# none failed.
#   - a fetch crosses the Wintun adapter hev makes, to the engine behind it,
#     whose own sockets leave by the physical interface (IP_UNICAST_IF): the
#     engine's connection to the far side has the physical address, not the
#     tunnel's;
#   - the default route is split (0.0.0.0/1, 128.0.0.0/1) onto the adapter, an
#     address that must stay outside keeps the physical way, and after the stop
#     the adapter, the routes and the record of them are gone;
#   - the DNS guard: a name no resolver knows is answered inside the tunnel,
#     the Dnscache service is blocked from every resolver but the tunnel's, and
#     the rules and the name rule are gone after the stop;
#   - after a kill (hev dead, or the app dead and hev alive) the next start
#     removes the spared routes, the guard, and hev itself.
#
# Run by rust-windows-tun.yml (and by rust-desktop.yml's Windows leg, which has
# the same two environment variables):
#   pwsh -NoProfile -File scripts/desktop-checks/windows-tun.ps1
# Needs Java 21 with the Android SDK for the Gradle build, and administrator.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$summary = $env:GITHUB_STEP_SUMMARY
function Say($m) { Write-Host "windows-tun: $m" }
function Die($m) {
    Write-Host "windows-tun: $m"
    if ($summary) { Add-Content -Path $summary -Value "- windows-tun: $m" }
    exit 1
}

$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { Die 'this check needs an administrator session' }
if (-not $env:HEV_OUT -or -not (Test-Path (Join-Path $env:HEV_OUT 'hev.exe')) -or -not (Test-Path (Join-Path $env:HEV_OUT 'wintun.dll'))) { Die 'HEV_OUT must hold hev.exe and wintun.dll' }
if (-not $env:GHOSTLANE_ENGINE -or -not (Test-Path $env:GHOSTLANE_ENGINE)) { Die 'GHOSTLANE_ENGINE must name the engine for Windows' }

$class = 'org.olcbox.app.vpn.desktop.WindowsTunRealTest'
$results = Join-Path $root 'sharedUI\build\test-results\jvmTest'
$xml = Join-Path $results "TEST-$class.xml"
Remove-Item -Force -ErrorAction SilentlyContinue $xml

$env:WINDOWS_TUN_CHECK = '1'
Push-Location $root
try {
    & .\gradlew.bat ':sharedUI:jvmTest' --rerun --tests $class --tests 'org.olcbox.app.vpn.desktop.WindowsTunRoutesTest' --stacktrace
    $rc = $LASTEXITCODE
} finally {
    Pop-Location
}

if (-not (Test-Path $xml)) { Die "Gradle wrote no result for $class (exit $rc)" }
[xml]$doc = Get-Content -Raw $xml
$suite = $doc.testsuite
$expected = (Select-String -Path (Join-Path $root 'sharedUI\src\jvmTest\kotlin\org\olcbox\app\vpn\desktop\WindowsTunRealTest.kt') -Pattern '^\s*@Test\b' | Measure-Object).Count
Say "tests=$($suite.tests) skipped=$($suite.skipped) failures=$($suite.failures) errors=$($suite.errors) expected=$expected"
if ([int]$suite.tests -ne $expected) { Die "ran $($suite.tests) tests, the file has $expected" }
if ([int]$suite.skipped -ne 0) { Die "$($suite.skipped) test(s) were skipped" }
foreach ($case in $suite.testcase) {
    if ($case.failure) { Write-Host "windows-tun: $($case.name): $($case.failure.message)" }
}
if ([int]$suite.failures -ne 0 -or [int]$suite.errors -ne 0 -or $rc -ne 0) { Die "the tests failed (exit $rc)" }

# Nothing of ours may be left on the machine.
$left = @()
if (Get-NetAdapter -Name 'Ghostlane-Tun*' -ErrorAction SilentlyContinue) { $left += 'an adapter' }
if (Get-NetFirewallRule -Name 'Ghostlane-DNS-*' -ErrorAction SilentlyContinue) { $left += 'a DNS firewall rule' }
if (Get-DnsClientNrptRule -ErrorAction SilentlyContinue | Where-Object { $_.DisplayName -eq 'Ghostlane-DNS' }) { $left += 'a name rule' }
if (Get-Process -Name hev -ErrorAction SilentlyContinue) { $left += 'hev.exe' }
if ($left.Count) { Die ("left on the machine: " + ($left -join ', ')) }
Say "passed: $expected tests, nothing left"
if ($summary) { Add-Content -Path $summary -Value "- windows-tun: $expected tests passed, nothing left" }
