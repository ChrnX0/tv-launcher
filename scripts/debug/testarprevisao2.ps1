$adb=$env:USERPROFILE+'\android-sdk\platform-tools\adb.exe'
$s=$env:TV_ADB
& $adb -s $s shell "input keyevent WAKEUP"
Start-Sleep 1
& $adb -s $s shell "input keyevent HOME"
Start-Sleep 2
1..5 | % { & $adb -s $s shell "input keyevent DPAD_RIGHT"; Start-Sleep -Milliseconds 300 }
& $adb -s $s shell "input keyevent DPAD_UP"
Start-Sleep -Milliseconds 500
& $adb -s $s shell screencap -p /sdcard/foco1.png
& $adb -s $s pull /sdcard/foco1.png $env:USERPROFILE\tv-launcher\.shots\foco1.png | Out-Null
& $adb -s $s shell rm /sdcard/foco1.png
& $adb -s $s shell "input keyevent DPAD_CENTER"
Start-Sleep 2
& $adb -s $s shell "dumpsys activity activities" | Select-String "topResumedActivity"
& $adb -s $s shell screencap -p /sdcard/prev2.png
& $adb -s $s pull /sdcard/prev2.png $env:USERPROFILE\tv-launcher\.shots\prev2.png | Out-Null
& $adb -s $s shell rm /sdcard/prev2.png
