# desktop-check-os: windows
#
# The Windows tun's files, as the package carries them, and that each one
# starts:
#   hev.exe          hev-socks5-tunnel at upstream's tag, built with clang for
#                    MSVC (scripts/build-hev-windows.sh): it imports no
#                    msys-2.0.dll, and, with wintun.dll beside it and this
#                    runner's administrator rights, brings an adapter up with
#                    its address;
#   wintun.dll       the signed release (the script checked its sha256); its
#                    Authenticode signature is valid and LoadLibrary takes it;
#   ghostlane-engine the Core build's program, when one exists for Windows at
#                    the pin: `version` prints a line that names this commit.
#                    While the core does not build for Windows there is none:
#                    the check says so, and the other files are still held.
#   msys-2.0.dll     is not in the bundle, and nothing in it names one.
#
# Run by rust-desktop.yml on its Windows leg:
#   pwsh -NoProfile -NonInteractive -File scripts/desktop-checks/windows-bundle.ps1
# with HEV_OUT (the directory scripts/build-hev-windows.sh wrote hev.exe and
# wintun.dll into), GHOSTLANE_ENGINE (the engine's path, which may not exist),
# GHOSTLANE_CORE_COMMIT and DESKTOP_CHECK_WORK (an empty directory of its own).
$ErrorActionPreference = 'Stop'

function Need([string]$name) {
    $v = [Environment]::GetEnvironmentVariable($name)
    if ([string]::IsNullOrWhiteSpace($v)) { throw "$name is not set" }
    return $v
}
$hevOut = Need 'HEV_OUT'
$work = Need 'DESKTOP_CHECK_WORK'
$engineSrc = [Environment]::GetEnvironmentVariable('GHOSTLANE_ENGINE')
$commit = [Environment]::GetEnvironmentVariable('GHOSTLANE_CORE_COMMIT')

$failed = @()
function Fail([string]$why) { Write-Host "FAIL $why"; $script:failed += $why }

# The bundle: the files the package carries, in one directory.
$bundle = Join-Path $work 'bundle'
New-Item -ItemType Directory -Force $bundle | Out-Null
foreach ($f in 'hev.exe', 'wintun.dll') {
    $p = Join-Path $hevOut $f
    if (Test-Path $p) { Copy-Item $p $bundle } else { Fail "$f is not in $hevOut" }
}
$engineHere = $false
if ($engineSrc -and (Test-Path $engineSrc)) {
    Copy-Item $engineSrc (Join-Path $bundle 'ghostlane-engine.exe')
    $engineHere = $true
}
Write-Host '--- the bundle'
Get-ChildItem $bundle | Format-Table Name, Length

# No Cygwin runtime, by name and by import.
if (Test-Path (Join-Path $bundle 'msys-2.0.dll')) { Fail 'msys-2.0.dll is in the bundle' }
foreach ($f in Get-ChildItem $bundle -Filter *.exe) {
    $bytes = [IO.File]::ReadAllBytes($f.FullName)
    $text = [Text.Encoding]::ASCII.GetString($bytes)
    if ($text -match '(?i)msys-2\.0\.dll') { Fail "$($f.Name) names msys-2.0.dll" }
}

# wintun.dll: signed, and the loader takes it.
$wintun = Join-Path $bundle 'wintun.dll'
if (Test-Path $wintun) {
    $sig = Get-AuthenticodeSignature $wintun
    Write-Host "wintun.dll signature: $($sig.Status) $($sig.SignerCertificate.Subject)"
    if ($sig.Status -ne 'Valid') { Fail "wintun.dll signature is $($sig.Status)" }
    Add-Type -Namespace Win -Name Native -MemberDefinition '[DllImport("kernel32.dll", SetLastError=true, CharSet=CharSet.Unicode)] public static extern IntPtr LoadLibraryW(string path);'
    if ([Win.Native]::LoadLibraryW($wintun) -eq [IntPtr]::Zero) { Fail "LoadLibrary refused wintun.dll (error $([Runtime.InteropServices.Marshal]::GetLastWin32Error()))" }
}

# hev.exe starts and says its version.
$hev = Join-Path $bundle 'hev.exe'
if (Test-Path $hev) {
    $said = (& $hev --version 2>&1 | Out-String)
    Write-Host "hev.exe --version: $said"
    if ($said -notmatch 'Version:') { Fail 'hev.exe --version printed no Version line' }
    $global:LASTEXITCODE = 0

    # ... and, with wintun.dll beside it, brings the adapter up.
    @"
tunnel:
  name: hevbundle
  mtu: 1500
  ipv4: 198.18.7.1
socks5:
  port: 1080
  address: 127.0.0.1
  udp: 'udp'
misc:
  log-file: stderr
  log-level: debug
"@ | Set-Content -Encoding ascii (Join-Path $bundle 'config.yml')
    $out = Join-Path $work 'hev.out'; $err = Join-Path $work 'hev.err'
    $p = Start-Process -FilePath $hev -ArgumentList 'config.yml' -WorkingDirectory $bundle `
        -RedirectStandardOutput $out -RedirectStandardError $err -PassThru -NoNewWindow
    $up = $false
    try {
        for ($i = 0; $i -lt 40; $i++) {
            if ($p.HasExited) { break }
            if ((Get-NetAdapter -Name hevbundle -ErrorAction SilentlyContinue) -and
                (Get-NetIPAddress -InterfaceAlias hevbundle -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object IPAddress -eq '198.18.7.1')) { $up = $true; break }
            Start-Sleep -Milliseconds 500
        }
        Write-Host "hev.exe exited: $($p.HasExited)"
        Get-Content $out, $err -ErrorAction SilentlyContinue
        if (-not $up) { Fail 'hev.exe did not bring the hevbundle adapter up with 198.18.7.1' }
        else { Get-NetAdapter -Name hevbundle | Format-List Name, InterfaceDescription, Status, DriverFileName }
    } finally {
        if (-not $p.HasExited) { & taskkill /F /PID $p.Id | Out-Null; $global:LASTEXITCODE = 0 }
    }
}

# The engine, when there is one.
if ($engineHere) {
    $exe = Join-Path $bundle 'ghostlane-engine.exe'
    $line = (& $exe version 2>$null | Select-Object -First 1)
    Write-Host "ghostlane-engine version: $line"
    $global:LASTEXITCODE = 0
    if (-not $line) { Fail 'ghostlane-engine version printed nothing' }
    else {
        $v = $line | ConvertFrom-Json
        if ($v.event -ne 'version') { Fail 'ghostlane-engine version is not a version event' }
        if ($commit -and $v.pin -ne $commit) { Fail "ghostlane-engine says pin $($v.pin), wanted $commit" }
    }
} else {
    Write-Host 'NOTE ghostlane-engine.exe: no program for Windows at this pin yet (the core half does not build for Windows); only hev.exe and wintun.dll were held'
    if ($env:WINDOWS_BUNDLE_ENGINE_REQUIRED -eq '1') { Fail 'ghostlane-engine.exe is not in the bundle' }
}

if ($failed.Count) { Write-Host "$($failed.Count) problem(s)"; exit 1 }
Write-Host 'OK: the bundle holds what it should and each file starts'
