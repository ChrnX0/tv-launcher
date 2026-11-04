$adb = "$env:USERPROFILE\android-sdk\platform-tools\adb.exe"
$s = "192.168.1.153:40507"
& $adb -s $s shell input keyevent --longpress KEYCODE_HOME
Start-Sleep 1
& $adb -s $s shell "dumpsys activity activities | grep topResumedActivity"
& $adb -s $s shell screencap -p /sdcard/qs.png
& $adb -s $s pull /sdcard/qs.png C:\Users\rockx\tv-launcher\.shots\qs.png
& $adb -s $s shell rm /sdcard/qs.png
