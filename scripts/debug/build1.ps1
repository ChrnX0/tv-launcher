$adb = "$env:USERPROFILE\android-sdk\platform-tools\adb.exe"
& $adb connect $env:TV_ADB
Set-Location $env:USERPROFILE\tv-launcher
.\gradlew.bat assembleDebug 2>&1 | Select-Object -Last 80
