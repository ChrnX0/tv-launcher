$adb = "$env:USERPROFILE\android-sdk\platform-tools\adb.exe"
& $adb connect 192.168.1.153:40507
Set-Location C:\Users\rockx\tv-launcher
.\gradlew.bat assembleDebug 2>&1 | Select-Object -Last 80
