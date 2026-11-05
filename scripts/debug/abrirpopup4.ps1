$adb=$env:USERPROFILE+'\android-sdk\platform-tools\adb.exe'
$s=$env:TV_ADB
& $adb -s $s shell "input keyevent DPAD_CENTER"
Start-Sleep 1
& $adb -s $s shell screencap -p /sdcard/popf.png
& $adb -s $s pull /sdcard/popf.png $env:USERPROFILE\tv-launcher\.shots\popf.png | Out-Null
& $adb -s $s shell rm /sdcard/popf.png
