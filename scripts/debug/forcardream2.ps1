$adb=$env:USERPROFILE+'\android-sdk\platform-tools\adb.exe'
$s='192.168.1.153:40507'
& $adb -s $s shell "input keyevent WAKEUP"
Start-Sleep 1
& $adb -s $s shell "dumpsys power" | Select-String "mWakefulness=|mScreenOn="
& $adb -s $s shell "settings put secure screensaver_components br.paulo.tvlauncher/.ProtetorTela"
& $adb -s $s shell "settings put system screen_off_timeout 20000"
Start-Sleep 25
& $adb -s $s shell "dumpsys power" | Select-String "mWakefulness="
& $adb -s $s shell "dumpsys activity activities" | Select-String "topResumedActivity"
& $adb -s $s shell screencap -p /sdcard/sonho3.png
& $adb -s $s pull /sdcard/sonho3.png C:\Users\rockx\tv-launcher\.shots\sonho3.png | Out-Null
& $adb -s $s shell rm /sdcard/sonho3.png
& $adb -s $s shell "settings put system screen_off_timeout 600000"
