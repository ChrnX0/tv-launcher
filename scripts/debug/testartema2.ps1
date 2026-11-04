$adb=$env:USERPROFILE+'\android-sdk\platform-tools\adb.exe'
$s='192.168.1.153:40507'
& $adb -s $s shell "input keyevent DPAD_CENTER"
Start-Sleep -Milliseconds 500
& $adb -s $s shell screencap -p /sdcard/temanovo.png
& $adb -s $s pull /sdcard/temanovo.png C:\Users\rockx\tv-launcher\.shots\temanovo.png | Out-Null
& $adb -s $s shell rm /sdcard/temanovo.png
& $adb -s $s shell "input keyevent BACK"
Start-Sleep 2
& $adb -s $s shell screencap -p /sdcard/voltou.png
& $adb -s $s pull /sdcard/voltou.png C:\Users\rockx\tv-launcher\.shots\voltou.png | Out-Null
& $adb -s $s shell rm /sdcard/voltou.png
& $adb -s $s shell "dumpsys activity activities | grep topResumedActivity"
