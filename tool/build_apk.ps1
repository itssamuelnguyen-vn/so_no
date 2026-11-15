# Builds the release APK and copies it to build\apk\sono-<version>-<yyyyMMdd-HHmm>.apk, so several builds of the
# same version stay apart. Gradle only: Android Studio and the emulator are never started.
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$env:JAVA_HOME = 'D:\dev\android-sdk\jbr'

$version = (Select-String -Path app\build.gradle.kts -Pattern 'versionName\s*=\s*"([^"]+)"').Matches[0].Groups[1].Value
$stamp = Get-Date -Format 'yyyyMMdd-HHmm'

.\gradlew.bat assembleRelease
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$out = 'build\apk'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$apk = Join-Path $out "sono-$version-$stamp.apk"
Copy-Item 'app\build\outputs\apk\release\app-release.apk' $apk -Force
Write-Host ""
Write-Host "APK: $(Resolve-Path $apk)"
