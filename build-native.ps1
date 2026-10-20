param(
    [string]$Ndk = "$env:LOCALAPPDATA\Android\Sdk\ndk\29.0.14206865"
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$clang = Join-Path $Ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin\clang++.exe'
if (-not (Test-Path -LiteralPath $clang)) { throw "Missing NDK compiler: $clang" }
$outputDir = Join-Path $PSScriptRoot 'build'
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
& $clang --target=aarch64-linux-android30 -std=c++17 -O2 -Wall -Wextra -Werror -fPIE -pie -static-libstdc++ -fstack-protector-strong -D_FORTIFY_SOURCE=2 '-Wl,-z,relro,-z,now,-z,noexecstack,-z,max-page-size=16384' (Join-Path $PSScriptRoot 'native\hal_bypass.cpp') -o (Join-Path $outputDir 'gpslock-hal')
if ($LASTEXITCODE -ne 0) { throw "Native build failed ($LASTEXITCODE)" }
$strip = Join-Path $Ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe'
& $strip --strip-unneeded (Join-Path $outputDir 'gpslock-hal')
if ($LASTEXITCODE -ne 0) { throw "Native strip failed ($LASTEXITCODE)" }
Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $outputDir 'gpslock-hal')
