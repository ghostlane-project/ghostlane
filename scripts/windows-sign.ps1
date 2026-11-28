# Signs each file named on the command line with the release certificate, as
# release.yml's Windows job does for every PE we build and for the finished
# assets. The caller has put signtool's path in SIGNTOOL, the certificate's in
# SIGN_PFX and its password in WINDOWS_CODESIGN_CERTIFICATE_PASSWORD (the
# "Find signtool and the certificate" step); nothing here reads a secret of its
# own. A file that cannot be signed stops the job.
#
#   pwsh -NoProfile -File scripts/windows-sign.ps1 <file>...
param([Parameter(Mandatory = $true, ValueFromRemainingArguments = $true)][string[]]$Path)
$ErrorActionPreference = 'Stop'
foreach ($name in 'SIGNTOOL', 'SIGN_PFX', 'WINDOWS_CODESIGN_CERTIFICATE_PASSWORD') {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) { throw "$name is not set" }
}
$stamp = $env:WINDOWS_CODESIGN_TIMESTAMP_URL
if ([string]::IsNullOrWhiteSpace($stamp)) { $stamp = 'http://timestamp.digicert.com' }
foreach ($file in $Path) {
    if (-not (Test-Path -LiteralPath $file)) { throw "no file to sign: $file" }
    & $env:SIGNTOOL sign /fd SHA256 /tr $stamp /td SHA256 /f $env:SIGN_PFX /p $env:WINDOWS_CODESIGN_CERTIFICATE_PASSWORD $file
    if ($LASTEXITCODE -ne 0) { throw "Failed to sign $file" }
    Write-Host "signed $file"
}
