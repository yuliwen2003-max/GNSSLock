param([string]$Serial)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (-not $Serial) {
    $devices = @(adb devices | Select-String '^([^\s]+)\s+device$' | ForEach-Object { $_.Matches[0].Groups[1].Value })
    if ($devices.Count -ne 1) { throw 'Connect one ADB device or specify -Serial.' }
    $Serial = $devices[0]
}
function DeviceAdb([string[]]$Arguments) {
    $output = & adb.exe -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB failed ($LASTEXITCODE): $output" }
    return $output
}
$components = [ordered]@{
    'gpslock-hal' = 'build\gpslock-hal'
    'gpslock-qmi-sv-append' = 'build\gpslock-qmi-sv-append'
    'gpslock-qmi-sv-split' = 'build\gpslock-qmi-sv-split'
    'gpslock-root.sh' = 'native\gpslock-root.sh'
}
foreach ($name in $components.Keys) {
    $source = Join-Path $PSScriptRoot $components[$name]
    if (-not (Test-Path -LiteralPath $source)) { throw "Missing $source. Run build-native.ps1 and build-probes.ps1 first." }
}
foreach ($name in $components.Keys) {
    $source = Join-Path $PSScriptRoot $components[$name]
    $stage = "/data/local/tmp/gpslock-install-$name"
    $destination = "/data/adb/gpslock/$name"
    $null = DeviceAdb @('push', $source, $stage)
    $command = "mkdir -p /data/adb/gpslock && chmod 700 /data/adb/gpslock && cp $stage $destination.new && chown 0:0 $destination.new && chmod 700 $destination.new && mv -f $destination.new $destination"
    $null = DeviceAdb @('shell', "su -c '$command'")
    $remoteHash = ((DeviceAdb @('shell', "su -c 'sha256sum $destination'")) -join '').Split(' ')[0]
    $localHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $source).Hash
    if ($remoteHash -ine $localHash) { throw "Installed component hash mismatch: $name" }
    $null = DeviceAdb @('shell', 'rm', '-f', $stage)
}
DeviceAdb @('shell', "su -c '/data/adb/gpslock/gpslock-root.sh status'")
Write-Host 'Root components installed. Installation does not apply a constellation policy.'
