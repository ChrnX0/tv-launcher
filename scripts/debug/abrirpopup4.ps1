$adb=$env:USERPROFILE+'\android-sdk\platform-tools\adb.exe'
$s='192.168.1.153:40507'
& $adb -s $s shell "input keyevent DPAD_CENTER"
Start-Sleep 1
& $adb -s $s shell screencap -p /sdcard/popf.png
& $adb -s $s pull /sdcard/popf.png C:\Users\rockx\tv-launcher\.shots\popf.png | Out-Null
& $adb -s $s shell rm /sdcard/popf.png
