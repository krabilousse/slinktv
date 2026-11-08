param([string]$SigningKey = "$env:USERPROFILE/.android/debug.keystore")
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
Set-Location $repoRoot
# Keep the signing identity used by already-installed prototypes. The key stays outside Git.
$env:SLINK_KEYSTORE = (Resolve-Path -LiteralPath $SigningKey).Path
$env:SLINK_STORE_PASSWORD = 'android'
$env:SLINK_KEY_ALIAS = 'androiddebugkey'
$env:SLINK_KEY_PASSWORD = 'android'
& "$PSScriptRoot/build.ps1" -Tasks @('testDebugUnitTest', 'assembleRelease', 'lintRelease', 'assembleDebugAndroidTest')
exit $LASTEXITCODE
