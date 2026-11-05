$adb = "$env:USERPROFILE\android-sdk\platform-tools\adb.exe"
$s = $env:TV_ADB
& $adb -s $s shell screencap -p /sdcard/bt.png
& $adb -s $s pull /sdcard/bt.png $env:USERPROFILE\tv-launcher\.shots\bt.png
& $adb -s $s shell rm /sdcard/bt.png
& $adb -s $s shell input keyevent HOME
