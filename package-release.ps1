param([string]$Apk)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$workspace = [IO.Path]::GetFullPath($PSScriptRoot)
[xml]$manifest = Get-Content -LiteralPath (Join-Path $workspace 'app\src\main\AndroidManifest.xml') -Raw -Encoding UTF8
$version = $manifest.manifest.GetAttribute('versionName', 'http://schemas.android.com/apk/res/android')
if ($version -notmatch '^[0-9A-Za-z][0-9A-Za-z._+-]*$') { throw 'Unsafe versionName for a release filename.' }
$releaseDir = Join-Path $workspace 'build\release'
New-Item -ItemType Directory -Path $releaseDir -Force | Out-Null
if (-not $Apk) { $Apk = Join-Path $releaseDir "GNSSLock-v$version-release.apk" }
$files = [ordered]@{
    'build\GNSSLock.apk' = $Apk
    'build\gpslock-hal' = Join-Path $workspace 'build\gpslock-hal'
    'build\gpslock-qmi-sv-append' = Join-Path $workspace 'build\gpslock-qmi-sv-append'
    'build\gpslock-qmi-sv-split' = Join-Path $workspace 'build\gpslock-qmi-sv-split'
    'native\gpslock-root.sh' = Join-Path $workspace 'native\gpslock-root.sh'
    'install.ps1' = Join-Path $workspace 'install.ps1'
    'install-bypass.ps1' = Join-Path $workspace 'install-bypass.ps1'
    'RELEASE-INSTALL.md' = Join-Path $workspace 'RELEASE-INSTALL.md'
}
foreach ($source in $files.Values) {
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw "Missing release input: $source" }
}
$stage = Join-Path $releaseDir ('bundle-' + [Guid]::NewGuid().ToString('N'))
$archive = Join-Path $releaseDir "GNSSLock-v$version-windows-adb.zip"
$utf8 = New-Object Text.UTF8Encoding($false)
try {
    New-Item -ItemType Directory -Path $stage | Out-Null
    foreach ($relative in $files.Keys) {
        $destination = Join-Path $stage $relative
        New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
        Copy-Item -LiteralPath $files[$relative] -Destination $destination
    }
    $checksums = foreach ($relative in $files.Keys) {
        $hash = (Get-FileHash -LiteralPath (Join-Path $stage $relative) -Algorithm SHA256).Hash.ToLowerInvariant()
        $hash + '  ' + $relative.Replace('\', '/')
    }
    [IO.File]::WriteAllText((Join-Path $stage 'SHA256SUMS.txt'), ($checksums -join "`n") + "`n", $utf8)
    Compress-Archive -Path (Join-Path $stage '*') -DestinationPath $archive -Force
} finally {
    if (Test-Path -LiteralPath $stage) {
        $resolvedStage = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $stage).Path)
        $allowedPrefix = [IO.Path]::GetFullPath($releaseDir).TrimEnd('\') + '\'
        if (-not $resolvedStage.StartsWith($allowedPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Refusing to remove an unexpected staging path.' }
        Remove-Item -LiteralPath $resolvedStage -Recurse -Force
    }
}
$assetChecksums = foreach ($asset in @($Apk, $archive)) {
    (Get-FileHash -LiteralPath $asset -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + [IO.Path]::GetFileName($asset)
}
[IO.File]::WriteAllText((Join-Path $releaseDir 'SHA256SUMS.txt'), ($assetChecksums -join "`n") + "`n", $utf8)
Get-Item -LiteralPath $Apk, $archive, (Join-Path $releaseDir 'SHA256SUMS.txt') | Select-Object Name, Length, FullName
