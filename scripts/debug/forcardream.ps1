$adb=$env:USERPROFILE+'\android-sdk\platform-tools\adb.exe'
$s=$env:TV_ADB
& $adb -s $s shell "settings put secure screensaver_components br.paulo.tvlauncher/.ProtetorTela"
& $adb -s $s shell "settings put system screen_off_timeout 8000"
Start-Sleep 13
& $adb -s $s shell "dumpsys activity activities" | Select-String "topResumedActivity"
& $adb -s $s shell screencap -p /sdcard/sonho2.png
& $adb -s $s pull /sdcard/sonho2.png $env:USERPROFILE\tv-launcher\.shots\sonho2.png | Out-Null
& $adb -s $s shell rm /sdcard/sonho2.png
& $adb -s $s shell "settings put system screen_off_timeout 600000"
