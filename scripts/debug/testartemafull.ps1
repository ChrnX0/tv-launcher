$adb=$env:USERPROFILE+'\android-sdk\platform-tools\adb.exe'
$s=$env:TV_ADB
& $adb -s $s shell "input keyevent DPAD_CENTER"
Start-Sleep 1
& $adb -s $s shell "input keyevent DPAD_DOWN"
Start-Sleep -Milliseconds 400
& $adb -s $s shell "input keyevent DPAD_CENTER"
Start-Sleep -Milliseconds 700
& $adb -s $s shell screencap -p /sdcard/temadepois.png
& $adb -s $s pull /sdcard/temadepois.png $env:USERPROFILE\tv-launcher\.shots\temadepois.png | Out-Null
& $adb -s $s shell rm /sdcard/temadepois.png
& $adb -s $s shell "input keyevent BACK"
Start-Sleep 2
& $adb -s $s shell screencap -p /sdcard/voltouok.png
& $adb -s $s pull /sdcard/voltouok.png $env:USERPROFILE\tv-launcher\.shots\voltouok.png | Out-Null
& $adb -s $s shell rm /sdcard/voltouok.png
& $adb -s $s shell "dumpsys activity activities | grep topResumedActivity"
