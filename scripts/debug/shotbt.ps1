$adb = "$env:USERPROFILE\android-sdk\platform-tools\adb.exe"
$s = "192.168.1.153:40507"
& $adb -s $s shell screencap -p /sdcard/bt.png
& $adb -s $s pull /sdcard/bt.png C:\Users\rockx\tv-launcher\.shots\bt.png
& $adb -s $s shell rm /sdcard/bt.png
& $adb -s $s shell input keyevent HOME
