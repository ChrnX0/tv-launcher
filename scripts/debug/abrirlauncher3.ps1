$adb=$env:USERPROFILE+'\android-sdk\platform-tools\adb.exe'
$s='192.168.1.153:40507'
& $adb -s $s shell "input keyevent DPAD_CENTER"
Start-Sleep 1
& $adb -s $s shell screencap -p /sdcard/cfg.png
& $adb -s $s pull /sdcard/cfg.png C:\Users\rockx\tv-launcher\.shots\cfg.png | Out-Null
& $adb -s $s shell rm /sdcard/cfg.png
& $adb -s $s shell "dumpsys activity activities | grep topResumedActivity"
