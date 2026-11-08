$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$env:JAVA_HOME = (Get-ChildItem "$repoRoot/.tools/jdk" -Directory | Select-Object -First 1).FullName
$sdkRoot = "$repoRoot/.tools/android-sdk"
$sdkManager = "$sdkRoot/cmdline-tools/bootstrap/cmdline-tools/bin/sdkmanager.bat"
1..100 | ForEach-Object { 'y' } | & $sdkManager "--sdk_root=$sdkRoot" --licenses
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $sdkManager "--sdk_root=$sdkRoot" 'platforms;android-36' 'build-tools;35.0.0' 'platform-tools'
exit $LASTEXITCODE
