$adb=$env:USERPROFILE+'\android-sdk\platform-tools\adb.exe'
$s='192.168.1.153:40507'
function shot($n) { & $adb -s $s shell screencap -p /sdcard/$n.png; & $adb -s $s pull /sdcard/$n.png C:\Users\rockx\tv-launcher\.shots\$n.png | Out-Null; & $adb -s $s shell rm /sdcard/$n.png }
& $adb -s $s shell "input keyevent DPAD_CENTER"; Start-Sleep 1
& $adb -s $s shell "input keyevent DPAD_DOWN"; Start-Sleep -Milliseconds 500
& $adb -s $s shell "input keyevent DPAD_CENTER"; Start-Sleep 1
shot "cfg4"
"depois 1 OK: " + (& $adb -s $s shell "settings get system screen_off_timeout")
