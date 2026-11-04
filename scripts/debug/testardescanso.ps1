$adb=$env:USERPROFILE+'\android-sdk\platform-tools\adb.exe'
$s='192.168.1.153:40507'
function shot($n) { & $adb -s $s shell screencap -p /sdcard/$n.png; & $adb -s $s pull /sdcard/$n.png C:\Users\rockx\tv-launcher\.shots\$n.png | Out-Null; & $adb -s $s shell rm /sdcard/$n.png }
"antes: " + (& $adb -s $s shell "settings get system screen_off_timeout")
& $adb -s $s shell "input keyevent WAKEUP"; Start-Sleep 1
& $adb -s $s shell "input keyevent HOME"; Start-Sleep 3
1..5 | % { & $adb -s $s shell "input keyevent DPAD_RIGHT"; Start-Sleep -Milliseconds 300 }
& $adb -s $s shell "input keyevent DPAD_UP"; Start-Sleep -Milliseconds 500
& $adb -s $s shell "input keyevent DPAD_RIGHT"; Start-Sleep -Milliseconds 500
& $adb -s $s shell "input keyevent DPAD_CENTER"; Start-Sleep 1
& $adb -s $s shell "input keyevent DPAD_DOWN"; Start-Sleep -Milliseconds 300
& $adb -s $s shell "input keyevent DPAD_DOWN"; Start-Sleep -Milliseconds 300
shot "cfg1"
& $adb -s $s shell "input keyevent DPAD_CENTER"; Start-Sleep 2
& $adb -s $s shell "dumpsys activity activities" | Select-String "topResumedActivity"
& $adb -s $s shell "input keyevent DPAD_DOWN"; Start-Sleep -Milliseconds 500
shot "cfg2"
& $adb -s $s shell "input keyevent DPAD_CENTER"; Start-Sleep 1
shot "cfg3"
"depois: " + (& $adb -s $s shell "settings get system screen_off_timeout")
