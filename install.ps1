param([string]$Serial, [switch]$NoLaunch)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (-not $Serial) {
    $devices = @(adb devices | Select-String '^([^\s]+)\s+device$' | ForEach-Object { $_.Matches[0].Groups[1].Value })
    if ($devices.Count -ne 1) { throw 'Connect exactly one authorized ADB device, or specify -Serial.' }
    $Serial = $devices[0]
}
function Adb([string[]]$Arguments) {
    & adb.exe -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB failed ($LASTEXITCODE)" }
}
$apk = Join-Path $PSScriptRoot 'build\GNSSLock.apk'
if (-not (Test-Path -LiteralPath $apk)) { throw 'Run .\build.ps1 first.' }
Adb @('install', '-r', '-t', $apk)
Adb @('shell', 'pm', 'grant', 'com.yuliwen.gnsslock', 'android.permission.WRITE_SECURE_SETTINGS')
Adb @('shell', 'pm', 'grant', 'com.yuliwen.gnsslock', 'android.permission.ACCESS_COARSE_LOCATION')
Adb @('shell', 'pm', 'grant', 'com.yuliwen.gnsslock', 'android.permission.ACCESS_FINE_LOCATION')
if (-not $NoLaunch) { Adb @('shell', 'am', 'start', '-n', 'com.yuliwen.gnsslock/.MainActivity', '--ez', 'monitor', 'true') }
Write-Host 'GNSS LOCK installed. System settings and location permissions granted.'
