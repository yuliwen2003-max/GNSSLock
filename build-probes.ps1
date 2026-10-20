param([string]$Ndk = "$env:LOCALAPPDATA\Android\Sdk\ndk\29.0.14206865")
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$bin = Join-Path $Ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin'
New-Item -ItemType Directory -Force -Path (Join-Path $PSScriptRoot 'build') | Out-Null
foreach ($name in @('qmi_probe', 'qmi_get_control', 'qmi_sv_split', 'qmi_get_legacy', 'qmi_sv_append')) {
    $source = Join-Path $PSScriptRoot "native\$name.cpp"
    if (-not (Test-Path -LiteralPath $source)) { continue }
    $output = Join-Path $PSScriptRoot ('build\gpslock-' + $name.Replace('_', '-'))
    & "$bin\clang++.exe" --target=aarch64-linux-android30 -std=c++17 -O2 -Wall -Wextra -Werror -fPIE -pie -static-libstdc++ -fstack-protector-strong -D_FORTIFY_SOURCE=2 '-Wl,-z,relro,-z,now,-z,noexecstack,-z,max-page-size=16384' $source -ldl -o $output
    if ($LASTEXITCODE -ne 0) { throw "Probe build failed: $name" }
    & "$bin\llvm-strip.exe" --strip-unneeded $output
    if ($LASTEXITCODE -ne 0) { throw "Probe strip failed: $name" }
    Write-Host "Built $output"
}
