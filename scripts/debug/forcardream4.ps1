$adb=$env:USERPROFILE+'\android-sdk\platform-tools\adb.exe'
$s='192.168.1.153:40507'
& $adb -s $s shell "settings put system screen_off_timeout 10000"
Start-Sleep 45
& $adb -s $s shell "dumpsys power" | Select-String "mWakefulness="
& $adb -s $s shell "dumpsys activity activities" | Select-String "topResumedActivity"
& $adb -s $s shell screencap -p /sdcard/sonho5.png
& $adb -s $s pull /sdcard/sonho5.png C:\Users\rockx\tv-launcher\.shots\sonho5.png | Out-Null
& $adb -s $s shell rm /sdcard/sonho5.png
& $adb -s $s shell "settings put system screen_off_timeout 600000"
