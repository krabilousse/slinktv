param([string[]]$Tasks = @('testDebugUnitTest', 'assembleDebug', 'lintDebug', 'assembleDebugAndroidTest'))
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
Set-Location $repoRoot
if (Test-Path "$repoRoot/.tools/jdk") {
    $env:JAVA_HOME = (Get-ChildItem "$repoRoot/.tools/jdk" -Directory | Select-Object -First 1).FullName
}
if (Test-Path "$repoRoot/.tools/android-sdk") {
    $env:ANDROID_HOME = "$repoRoot/.tools/android-sdk"
}
if (Test-Path "$repoRoot/.tools/python311/tools/python.exe") {
    $env:SLINK_BUILD_PYTHON = "$repoRoot/.tools/python311/tools/python.exe"
}
if (Test-Path "$repoRoot/gradlew.bat") {
    & "$repoRoot/gradlew.bat" @Tasks --console=plain
} else {
    & "$repoRoot/.tools/gradle/gradle-8.13/bin/gradle.bat" @Tasks --console=plain
}
exit $LASTEXITCODE
