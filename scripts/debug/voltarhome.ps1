$adb = "$env:USERPROFILE\android-sdk\platform-tools\adb.exe"
$s = $env:TV_ADB
& $adb -s $s shell input keyevent BACK
Start-Sleep 1
& $adb -s $s shell input keyevent HOME
Start-Sleep 1
& $adb -s $s shell screencap -p /sdcard/h.png
& $adb -s $s pull /sdcard/h.png $env:USERPROFILE\tv-launcher\.shots\h.png
& $adb -s $s shell rm /sdcard/h.png
