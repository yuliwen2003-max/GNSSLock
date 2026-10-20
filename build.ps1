param(
    [string]$Sdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$JavaHome = 'C:\Program Files\Android\Android Studio\jbr',
    [switch]$Release
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
if ($Release) { $build = Join-Path $build 'release' }
$manifest = Join-Path $PSScriptRoot 'app\src\main\AndroidManifest.xml'
[xml]$manifestXml = Get-Content -LiteralPath $manifest -Raw -Encoding UTF8
$androidNamespace = 'http://schemas.android.com/apk/res/android'
$version = $manifestXml.manifest.GetAttribute('versionName', $androidNamespace)
if ($Release -and $version -notmatch '^[0-9A-Za-z][0-9A-Za-z._+-]*$') { throw 'Manifest versionName is not safe for a release filename.' }
if ($Release -and $manifestXml.manifest.application.GetAttribute('debuggable', $androidNamespace) -eq 'true') { throw 'Release builds must not enable android:debuggable.' }
$apk = if ($Release) { Join-Path $build "GNSSLock-v$version-release.apk" } else { Join-Path $build 'GNSSLock.apk' }
$classes = Join-Path $build ('classes-' + [Guid]::NewGuid().ToString('N'))
$dex = Join-Path $build ('dex-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $classes, $dex | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'app\src\main\java') -Recurse -Filter '*.java' | ForEach-Object FullName)
if ($sources.Count -eq 0) { throw 'No Java sources found.' }
Run "$JavaHome\bin\javac.exe" (@('--release', '8', '-encoding', 'UTF-8', '-classpath', $androidJar, '-d', $classes) + $sources)
Run "$JavaHome\bin\jar.exe" @('cf', "$build\classes.jar", '-C', $classes, '.')
$dexArguments = @('--lib', $androidJar, '--min-api', '30', '--output', $dex)
if ($Release) { $dexArguments += '--release' }
Run "$bt\d8.bat" ($dexArguments + "$build\classes.jar")
# Keep the source manifest's testOnly flag: GNSS settings access requires it.
Run "$bt\aapt2.exe" @('link', '-I', $androidJar, '--manifest', $manifest, '--min-sdk-version', '30', '--target-sdk-version', '35', '-o', "$build\unsigned.apk")
Run "$JavaHome\bin\jar.exe" @('uf', "$build\unsigned.apk", '-C', $dex, 'classes.dex')
Run "$bt\zipalign.exe" @('-f', '4', "$build\unsigned.apk", "$build\aligned.apk")
$localState = Join-Path $PSScriptRoot '.local'
New-Item -ItemType Directory -Force -Path $localState | Out-Null
if ($Release) {
    $key = Join-Path $localState 'release-signing.keystore'
    $credentialsPath = Join-Path $localState 'release-signing.json'
    $hasKey = Test-Path -LiteralPath $key
    $hasCredentials = Test-Path -LiteralPath $credentialsPath
    if ($hasKey -ne $hasCredentials) { throw 'Release signing files are incomplete. Restore the matching keystore and credentials; existing signing material will not be replaced.' }
    $storePasswordEnvironment = 'GNSSLOCK_RELEASE_STORE_PASSWORD'
    $keyPasswordEnvironment = 'GNSSLOCK_RELEASE_KEY_PASSWORD'
    $previousStorePassword = [Environment]::GetEnvironmentVariable($storePasswordEnvironment, 'Process')
    $previousKeyPassword = [Environment]::GetEnvironmentVariable($keyPasswordEnvironment, 'Process')
    try {
        if ($hasKey) {
            $credentials = Get-Content -LiteralPath $credentialsPath -Raw -Encoding UTF8 | ConvertFrom-Json
            if ([string]::IsNullOrWhiteSpace($credentials.alias) -or [string]::IsNullOrWhiteSpace($credentials.storePassword) -or [string]::IsNullOrWhiteSpace($credentials.keyPassword)) { throw 'Release signing credentials are invalid.' }
        } else {
            $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
            try {
                $passwordBytes = New-Object byte[] 32
                $random.GetBytes($passwordBytes)
                $storePassword = [Convert]::ToBase64String($passwordBytes)
                $random.GetBytes($passwordBytes)
                $keyPassword = [Convert]::ToBase64String($passwordBytes)
            } finally { $random.Dispose() }
            $credentials = [pscustomobject]@{ alias = 'gnsslockrelease'; storePassword = $storePassword; keyPassword = $keyPassword }
        }
        [Environment]::SetEnvironmentVariable($storePasswordEnvironment, $credentials.storePassword, 'Process')
        [Environment]::SetEnvironmentVariable($keyPasswordEnvironment, $credentials.keyPassword, 'Process')
        if (-not $hasKey) {
            $temporaryKey = Join-Path $localState ('release-signing-' + [Guid]::NewGuid().ToString('N') + '.tmp.keystore')
            Run "$JavaHome\bin\keytool.exe" @('-genkeypair', '-keystore', $temporaryKey, '-storetype', 'JKS', '-storepass:env', $storePasswordEnvironment, '-keypass:env', $keyPasswordEnvironment, '-alias', $credentials.alias, '-dname', 'CN=GNSS LOCK,O=GNSS LOCK', '-keyalg', 'RSA', '-keysize', '3072', '-validity', '25000')
            $credentials | ConvertTo-Json | Set-Content -LiteralPath $credentialsPath -Encoding UTF8 -NoNewline
            Move-Item -LiteralPath $temporaryKey -Destination $key
        }
        Run "$bt\apksigner.bat" @('sign', '--ks', $key, '--ks-key-alias', $credentials.alias, '--ks-pass', "env:$storePasswordEnvironment", '--key-pass', "env:$keyPasswordEnvironment", '--v4-signing-enabled', 'false', '--out', $apk, "$build\aligned.apk")
    } finally {
        [Environment]::SetEnvironmentVariable($storePasswordEnvironment, $previousStorePassword, 'Process')
        [Environment]::SetEnvironmentVariable($keyPasswordEnvironment, $previousKeyPassword, 'Process')
    }
    Run "$bt\apksigner.bat" @('verify', '--verbose', '--print-certs', $apk)
} else {
    $key = Join-Path $localState 'debug.keystore'
    if (-not (Test-Path -LiteralPath $key)) {
        Run "$JavaHome\bin\keytool.exe" @('-genkeypair', '-keystore', $key, '-storepass', 'android', '-keypass', 'android', '-alias', 'androiddebugkey', '-dname', 'CN=GNSS LOCK Debug', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000')
    }
    Run "$bt\apksigner.bat" @('sign', '--ks', $key, '--ks-key-alias', 'androiddebugkey', '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', $apk, "$build\aligned.apk")
    Run "$bt\apksigner.bat" @('verify', '--verbose', $apk)
}
Write-Host "Built $apk"
