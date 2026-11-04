$adb=$env:USERPROFILE+'\android-sdk\platform-tools\adb.exe'
$s='192.168.1.153:40507'
# foco atual esta no card "Bluetooth" (topo direita do grid); DOWN DOWN desce ate "Launcher" (ultima linha)
& $adb -s $s shell "input keyevent DPAD_DOWN"
Start-Sleep -Milliseconds 300
& $adb -s $s shell "input keyevent DPAD_DOWN"
Start-Sleep -Milliseconds 300
& $adb -s $s shell screencap -p /sdcard/foco.png
& $adb -s $s pull /sdcard/foco.png C:\Users\rockx\tv-launcher\.shots\foco.png | Out-Null
& $adb -s $s shell rm /sdcard/foco.png
