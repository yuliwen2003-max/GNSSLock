param(
    [ValidateSet('status', 'allow', 'block', 'all', 'restore')][string]$Action = 'status',
    [string[]]$Constellations = @(),
    [string]$Serial
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$key = 'gnss_satellite_blocklist'
$names = [ordered]@{ GPS = 1; SBAS = 2; GLONASS = 3; QZSS = 4; BEIDOU = 5; GALILEO = 6; NAVIC = 7 }
if (-not $Serial) {
    $devices = @(adb devices | Select-String '^([^\s]+)\s+device$' | ForEach-Object { $_.Matches[0].Groups[1].Value })
    if ($devices.Count -ne 1) { throw 'Connect exactly one authorized ADB device, or specify -Serial.' }
    $Serial = $devices[0]
}
if ($Serial -notmatch '^[a-zA-Z0-9_.:-]+$') { throw 'Invalid ADB serial.' }
function Adb([string[]]$Arguments) {
    $output = & adb.exe -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB failed ($LASTEXITCODE): $output" }
    return $output
}
function ReadSetting {
    $allSettings = @(Adb @('shell', 'settings', 'list', 'global'))
    $line = @($allSettings | Where-Object { $_.StartsWith($key + '=') })
    if ($line.Count -eq 0) { return @{ present = $false; value = '' } }
    if ($line.Count -ne 1) { throw 'Ambiguous settings output; refusing to change configuration.' }
    $encoded = (Adb @('shell', "settings get global $key | base64")) -join ''
    $raw = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($encoded))
    if (-not $raw.EndsWith("`n")) { throw 'Unexpected settings get output; refusing to change configuration.' }
    $value = $raw.Substring(0, $raw.Length - 1)
    if ($value.Contains([char]0)) { throw 'Unsupported NUL in original setting; refusing to change configuration.' }
    return @{ present = $true; value = $value }
}
function WriteSetting([bool]$Present, [string]$Value) {
    if ($Present) {
        if ($Value.Contains([char]0)) { throw 'Unsupported NUL in settings value.' }
        $encoded = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($Value))
        # Append a sentinel so shell command substitution preserves trailing newlines.
        $remoteCommand = 'gnss_value=$(printf %s ' + $encoded + ' | base64 -d; printf x); settings put global ' + $key + ' "${gnss_value%x}"'
        $null = Adb @('shell', $remoteCommand)
    } else { $null = Adb @('shell', 'settings', 'delete', 'global', $key) }
    $observed = ReadSetting
    if ($observed.present -ne $Present -or ($Present -and $observed.value -cne $Value)) { throw 'Settings readback did not match.' }
}
$stateDir = Join-Path $PSScriptRoot '.local'
$safeSerial = $Serial.Replace(':', '_')
$backupPath = Join-Path $stateDir "$safeSerial-original.json"
$current = ReadSetting
if ($Action -eq 'status') {
    Write-Host "Device: $Serial"
    Write-Host "Blocklist present: $($current.present); value: [$($current.value)]"
    Write-Host 'IDs: GPS=1 SBAS=2 GLONASS=3 QZSS=4 BEIDOU=5 GALILEO=6 NAVIC=7; SVID=0 means entire constellation.'
    Adb @('shell', "dumpsys location | grep -E 'Capabilities:|GNSS Hardware Model' | head -n 2")
    exit 0
}
if ($Action -eq 'restore') {
    if (-not (Test-Path -LiteralPath $backupPath)) { throw "No saved baseline at $backupPath" }
    $saved = Get-Content -LiteralPath $backupPath -Raw | ConvertFrom-Json
    if ($saved.serial -ne $Serial) { throw 'Backup device mismatch.' }
    WriteSetting ([bool]$saved.present) ([string]$saved.value)
    Move-Item -LiteralPath $backupPath -Destination ($backupPath + '.restored-' + [DateTime]::UtcNow.ToString('yyyyMMddHHmmssfff'))
    Write-Host 'Original setting restored and verified.'
    exit 0
}
$selected = @()
foreach ($token in $Constellations) {
    foreach ($part in $token.Split(',')) {
        $name = $part.Trim().ToUpperInvariant()
        if ($name -eq 'BDS') { $name = 'BEIDOU' }
        if ($name -eq 'IRNSS') { $name = 'NAVIC' }
        if (-not $names.Contains($name)) { throw "Unknown constellation: $name" }
        $selected += [int]$names[$name]
    }
}
if ($Action -in @('allow', 'block') -and $selected.Count -eq 0) { throw 'Specify -Constellations GPS,BEIDOU (or another selection).' }
$blocked = @(switch ($Action) {
    'allow' { @($names.Values | Where-Object { $_ -notin $selected }) }
    'block' { @($selected | Sort-Object -Unique) }
    'all' { @() }
})
if (@($blocked).Count -ge 7) { throw 'At least one constellation must remain allowed.' }
$newValue = (@($blocked | Sort-Object -Unique | ForEach-Object { "$_,0" }) -join ',')
if (-not (Test-Path -LiteralPath $backupPath)) {
    New-Item -ItemType Directory -Force -Path $stateDir | Out-Null
    @{ serial = $Serial; present = $current.present; value = $current.value; savedUtc = [DateTime]::UtcNow.ToString('o') } | ConvertTo-Json | Set-Content -LiteralPath $backupPath -Encoding UTF8
}
WriteSetting $true $newValue
Write-Host "Submitted and read back: [$newValue]"
Write-Host 'This confirms the setting only. Check fresh GNSS used-in-fix data to verify the hardware effect.'
