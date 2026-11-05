$adb=$env:USERPROFILE+'\android-sdk\platform-tools\adb.exe'
$s=$env:TV_ADB
& $adb -s $s shell "input keyevent DPAD_DOWN"
Start-Sleep -Milliseconds 400
& $adb -s $s shell screencap -p /sdcard/cfgfoco.png
& $adb -s $s pull /sdcard/cfgfoco.png $env:USERPROFILE\tv-launcher\.shots\cfgfoco.png | Out-Null
& $adb -s $s shell rm /sdcard/cfgfoco.png
