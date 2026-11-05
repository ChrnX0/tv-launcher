$adb=$env:USERPROFILE+'\android-sdk\platform-tools\adb.exe'
$s=$env:TV_ADB
# foco atual esta no card "Bluetooth" (topo direita do grid); DOWN DOWN desce ate "Launcher" (ultima linha)
& $adb -s $s shell "input keyevent DPAD_DOWN"
Start-Sleep -Milliseconds 300
& $adb -s $s shell "input keyevent DPAD_DOWN"
Start-Sleep -Milliseconds 300
& $adb -s $s shell screencap -p /sdcard/foco.png
& $adb -s $s pull /sdcard/foco.png $env:USERPROFILE\tv-launcher\.shots\foco.png | Out-Null
& $adb -s $s shell rm /sdcard/foco.png
