$adb=$env:USERPROFILE+'\android-sdk\platform-tools\adb.exe'
$s=$env:TV_ADB
& $adb -s $s shell "input keyevent DPAD_CENTER"
Start-Sleep 1
& $adb -s $s shell screencap -p /sdcard/cfg.png
& $adb -s $s pull /sdcard/cfg.png $env:USERPROFILE\tv-launcher\.shots\cfg.png | Out-Null
& $adb -s $s shell rm /sdcard/cfg.png
& $adb -s $s shell "dumpsys activity activities | grep topResumedActivity"
