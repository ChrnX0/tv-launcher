$adb=$env:USERPROFILE+'\android-sdk\platform-tools\adb.exe'
$s=$env:TV_ADB
& $adb -s $s shell "input keyevent DPAD_CENTER"
Start-Sleep -Milliseconds 500
& $adb -s $s shell screencap -p /sdcard/temanovo.png
& $adb -s $s pull /sdcard/temanovo.png $env:USERPROFILE\tv-launcher\.shots\temanovo.png | Out-Null
& $adb -s $s shell rm /sdcard/temanovo.png
& $adb -s $s shell "input keyevent BACK"
Start-Sleep 2
& $adb -s $s shell screencap -p /sdcard/voltou.png
& $adb -s $s pull /sdcard/voltou.png $env:USERPROFILE\tv-launcher\.shots\voltou.png | Out-Null
& $adb -s $s shell rm /sdcard/voltou.png
& $adb -s $s shell "dumpsys activity activities | grep topResumedActivity"
