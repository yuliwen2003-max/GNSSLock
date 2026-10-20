param(
    [string]$Sdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$JavaHome = 'C:\Program Files\Android\Android Studio\jbr'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Set-Location $PSScriptRoot
$env:JAVA_HOME = $JavaHome
$env:PATH = "$JavaHome\bin;$env:PATH"
$androidJar = Join-Path $Sdk 'platforms\android-37.0\android.jar'
$bt = Join-Path $Sdk 'build-tools\36.0.0'
foreach ($tool in @($androidJar, "$JavaHome\bin\javac.exe", "$bt\aapt2.exe", "$bt\d8.bat", "$bt\apksigner.bat", "$bt\zipalign.exe")) {
    if (-not (Test-Path -LiteralPath $tool)) { throw "Missing Android build tool: $tool" }
}
function Run([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Executable failed ($LASTEXITCODE)" }
}
$build = Join-Path $PSScriptRoot 'build'
$classes = Join-Path $build ('classes-' + [Guid]::NewGuid().ToString('N'))
$dex = Join-Path $build ('dex-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $classes, $dex | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'app\src\main\java') -Recurse -Filter '*.java' | ForEach-Object FullName)
if ($sources.Count -eq 0) { throw 'No Java sources found.' }
Run "$JavaHome\bin\javac.exe" (@('--release', '8', '-encoding', 'UTF-8', '-classpath', $androidJar, '-d', $classes) + $sources)
Run "$JavaHome\bin\jar.exe" @('cf', "$build\classes.jar", '-C', $classes, '.')
Run "$bt\d8.bat" @('--lib', $androidJar, '--min-api', '30', '--output', $dex, "$build\classes.jar")
Run "$bt\aapt2.exe" @('link', '-I', $androidJar, '--manifest', "$PSScriptRoot\app\src\main\AndroidManifest.xml", '--min-sdk-version', '30', '--target-sdk-version', '35', '-o', "$build\unsigned.apk")
Run "$JavaHome\bin\jar.exe" @('uf', "$build\unsigned.apk", '-C', $dex, 'classes.dex')
Run "$bt\zipalign.exe" @('-f', '4', "$build\unsigned.apk", "$build\aligned.apk")
$localState = Join-Path $PSScriptRoot '.local'
New-Item -ItemType Directory -Force -Path $localState | Out-Null
$key = Join-Path $localState 'debug.keystore'
if (-not (Test-Path -LiteralPath $key)) {
    Run "$JavaHome\bin\keytool.exe" @('-genkeypair', '-keystore', $key, '-storepass', 'android', '-keypass', 'android', '-alias', 'androiddebugkey', '-dname', 'CN=GNSS LOCK Debug', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000')
}
Run "$bt\apksigner.bat" @('sign', '--ks', $key, '--ks-key-alias', 'androiddebugkey', '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', "$build\GNSSLock.apk", "$build\aligned.apk")
Run "$bt\apksigner.bat" @('verify', '--verbose', "$build\GNSSLock.apk")
Write-Host "Built $build\GNSSLock.apk"
