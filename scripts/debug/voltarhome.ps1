$adb = "$env:USERPROFILE\android-sdk\platform-tools\adb.exe"
$s = "192.168.1.153:40507"
& $adb -s $s shell input keyevent BACK
Start-Sleep 1
& $adb -s $s shell input keyevent HOME
Start-Sleep 1
& $adb -s $s shell screencap -p /sdcard/h.png
& $adb -s $s pull /sdcard/h.png C:\Users\rockx\tv-launcher\.shots\h.png
& $adb -s $s shell rm /sdcard/h.png
