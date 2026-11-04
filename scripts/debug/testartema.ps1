$adb=$env:USERPROFILE+'\android-sdk\platform-tools\adb.exe'
$s='192.168.1.153:40507'
& $adb -s $s shell "input keyevent DPAD_DOWN"
Start-Sleep -Milliseconds 400
& $adb -s $s shell screencap -p /sdcard/cfgfoco.png
& $adb -s $s pull /sdcard/cfgfoco.png C:\Users\rockx\tv-launcher\.shots\cfgfoco.png | Out-Null
& $adb -s $s shell rm /sdcard/cfgfoco.png
