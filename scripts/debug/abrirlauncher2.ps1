$adb=$env:USERPROFILE+'\android-sdk\platform-tools\adb.exe'
$s=$env:TV_ADB
& $adb -s $s shell "input keyevent DPAD_DOWN"
Start-Sleep -Milliseconds 400
& $adb -s $s shell screencap -p /sdcard/foco2.png
& $adb -s $s pull /sdcard/foco2.png $env:USERPROFILE\tv-launcher\.shots\foco2.png | Out-Null
& $adb -s $s shell rm /sdcard/foco2.png
