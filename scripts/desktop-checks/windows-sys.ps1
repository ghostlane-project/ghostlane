# desktop-check-os: windows
#
# W3 on a real Windows: the adapter counters, the registry values of
# system-proxy mode, and the inbound firewall rule of LAN sharing, run as
# WindowsSystemRealTest by Gradle in an administrator session and held to:
# every test of it run, none skipped, none failed.
#   - the counters of an adapter that is up are read, and grow with a fetch;
#     an adapter that is not there gives no reading;
#   - ProxyEnable and AutoConfigURL are set, and put back as they were, both
#     with nothing set and with the user's own values;
#   - the firewall rule names the program, is gone after the stop, and a sweep
#     removes the rule of a run that was killed;
#   - a listener on a private address of this machine answers on its port with
#     the rule in place, and again 15 seconds later.
#
# Run by rust-desktop.yml's Windows leg (once it has an engine to start from)
# and by rust-windows-sys.yml, which needs none:
#   pwsh -NoProfile -File scripts/desktop-checks/windows-sys.ps1
# Needs Java 21 with the Android SDK for the Gradle build, and administrator.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$summary = $env:GITHUB_STEP_SUMMARY
function Say($m) { Write-Host "windows-sys: $m" }
function Die($m) {
    Write-Host "windows-sys: $m"
    if ($summary) { Add-Content -Path $summary -Value "- windows-sys: $m" }
    exit 1
}

$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { Die 'this check needs an administrator session' }

$class = 'org.olcbox.app.vpn.desktop.WindowsSystemRealTest'
$results = Join-Path $root 'sharedUI\build\test-results\jvmTest'
$xml = Join-Path $results "TEST-$class.xml"
Remove-Item -Force -ErrorAction SilentlyContinue $xml

$env:WINDOWS_SYS_CHECK = '1'
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
$expected = (Select-String -Path (Join-Path $root 'sharedUI\src\jvmTest\kotlin\org\olcbox\app\vpn\desktop\WindowsSystemRealTest.kt') -Pattern '^\s*@Test\b' | Measure-Object).Count
Say "tests=$($suite.tests) skipped=$($suite.skipped) failures=$($suite.failures) errors=$($suite.errors) expected=$expected"
if ([int]$suite.tests -ne $expected) { Die "ran $($suite.tests) tests, the file has $expected" }
if ([int]$suite.skipped -ne 0) { Die "$($suite.skipped) test(s) were skipped" }
foreach ($case in $suite.testcase) {
    if ($case.failure) { Write-Host "windows-sys: $($case.name): $($case.failure.message)" }
}
if ([int]$suite.failures -ne 0 -or [int]$suite.errors -ne 0 -or $rc -ne 0) { Die "the tests failed (exit $rc)" }

# Nothing of ours may be left on the machine.
$left = @(Get-NetFirewallRule -Name 'Ghostlane-LAN-*' -ErrorAction SilentlyContinue).Count
if ($left -ne 0) { Die "$left firewall rule(s) of ours are left" }
Say "passed: $expected tests, no rule left"
if ($summary) { Add-Content -Path $summary -Value "- windows-sys: $expected tests passed, no rule left" }
