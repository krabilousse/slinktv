$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
Set-Location $repoRoot
if (Test-Path "$repoRoot/.tools/jdk") {
    $env:JAVA_HOME = (Get-ChildItem "$repoRoot/.tools/jdk" -Directory | Select-Object -First 1).FullName
}
$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$repoRoot/.tools/android-sdk" }
$apk = "$repoRoot/app/build/outputs/apk/debug/app-debug.apk"
& "$sdkRoot/build-tools/35.0.0/apksigner.bat" verify --verbose $apk
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& "$sdkRoot/build-tools/35.0.0/zipalign.exe" -c -P 16 4 $apk
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
New-Item -ItemType Directory -Force "$repoRoot/dist" | Out-Null
$metadata = Get-Content "$repoRoot/app/build/outputs/apk/debug/output-metadata.json" -Raw | ConvertFrom-Json
$version = $metadata.elements[0].versionName
$apkName = "SlinkTV-$version-debug.apk"
Copy-Item -LiteralPath $apk -Destination "$repoRoot/dist/$apkName"
Copy-Item -LiteralPath "$repoRoot/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" -Destination "$repoRoot/dist/SlinkTV-$version-tests.apk"
$digest = (Get-FileHash -Algorithm SHA256 "$repoRoot/dist/$apkName").Hash.ToLowerInvariant()
"$digest  $apkName" | Set-Content "$repoRoot/dist/SHA256SUMS.txt" -Encoding ascii
Copy-Item -LiteralPath $apk -Destination "$repoRoot/dist/app.apk"
Write-Output "Packaged dist/$apkName and dist/app.apk"
