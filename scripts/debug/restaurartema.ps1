$adb=$env:USERPROFILE+'\android-sdk\platform-tools\adb.exe'
$s='192.168.1.153:40507'
& $adb -s $s shell "input keyevent HOME"
Start-Sleep 1
1..5 | % { & $adb -s $s shell "input keyevent DPAD_RIGHT"; Start-Sleep -Milliseconds 300 }
& $adb -s $s shell "input keyevent DPAD_UP"
Start-Sleep -Milliseconds 500
& $adb -s $s shell "input keyevent DPAD_RIGHT"
Start-Sleep -Milliseconds 500
& $adb -s $s shell "input keyevent DPAD_CENTER"
Start-Sleep 1
& $adb -s $s shell "input keyevent DPAD_DOWN"
Start-Sleep -Milliseconds 300
& $adb -s $s shell "input keyevent DPAD_DOWN"
Start-Sleep -Milliseconds 400
& $adb -s $s shell "input keyevent DPAD_CENTER"
Start-Sleep 1
& $adb -s $s shell "input keyevent DPAD_DOWN"
Start-Sleep -Milliseconds 400
& $adb -s $s shell "input keyevent DPAD_CENTER"
Start-Sleep -Milliseconds 700
& $adb -s $s shell screencap -p /sdcard/restaurado.png
& $adb -s $s pull /sdcard/restaurado.png C:\Users\rockx\tv-launcher\.shots\restaurado.png | Out-Null
& $adb -s $s shell rm /sdcard/restaurado.png
& $adb -s $s shell "input keyevent BACK"
Start-Sleep 2
& $adb -s $s shell screencap -p /sdcard/final.png
& $adb -s $s pull /sdcard/final.png C:\Users\rockx\tv-launcher\.shots\final.png | Out-Null
& $adb -s $s shell rm /sdcard/final.png
