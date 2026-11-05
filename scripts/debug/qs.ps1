$adb = "$env:USERPROFILE\android-sdk\platform-tools\adb.exe"
$s = $env:TV_ADB
& $adb -s $s shell input keyevent --longpress KEYCODE_HOME
Start-Sleep 1
& $adb -s $s shell "dumpsys activity activities | grep topResumedActivity"
& $adb -s $s shell screencap -p /sdcard/qs.png
& $adb -s $s pull /sdcard/qs.png $env:USERPROFILE\tv-launcher\.shots\qs.png
& $adb -s $s shell rm /sdcard/qs.png
