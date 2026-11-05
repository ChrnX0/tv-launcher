$adb=$env:USERPROFILE+'\android-sdk\platform-tools\adb.exe'
$s=$env:TV_ADB
& $adb -s $s shell "input keyevent DPAD_DOWN"
Start-Sleep -Milliseconds 300
& $adb -s $s shell "input keyevent DPAD_DOWN"
Start-Sleep -Milliseconds 300
& $adb -s $s shell screencap -p /sdcard/foco3.png
& $adb -s $s pull /sdcard/foco3.png $env:USERPROFILE\tv-launcher\.shots\foco3.png | Out-Null
& $adb -s $s shell rm /sdcard/foco3.png
