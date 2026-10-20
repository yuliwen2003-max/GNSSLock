param(
    [ValidateSet('status', 'enable', 'disable')][string]$Action = 'status',
    [string]$Serial
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (-not $Serial) {
    $devices = @(adb devices | Select-String '^([^\s]+)\s+device$' | ForEach-Object { $_.Matches[0].Groups[1].Value })
    if ($devices.Count -ne 1) { throw 'Connect one ADB device or specify -Serial.' }
    $Serial = $devices[0]
}
& adb.exe -s $Serial shell "su -c '/data/adb/gpslock/gpslock-root.sh $Action'"
if ($LASTEXITCODE -ne 0) { throw "HAL bypass $Action failed ($LASTEXITCODE)." }
