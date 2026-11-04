$adb=$env:USERPROFILE+'\android-sdk\platform-tools\adb.exe'
$s='192.168.1.153:40507'
& $adb -s $s shell "input keyevent DPAD_DOWN"
Start-Sleep -Milliseconds 300
& $adb -s $s shell "input keyevent DPAD_DOWN"
Start-Sleep -Milliseconds 300
& $adb -s $s shell screencap -p /sdcard/foco3.png
& $adb -s $s pull /sdcard/foco3.png C:\Users\rockx\tv-launcher\.shots\foco3.png | Out-Null
& $adb -s $s shell rm /sdcard/foco3.png
