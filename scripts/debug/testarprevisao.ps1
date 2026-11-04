$adb=$env:USERPROFILE+'\android-sdk\platform-tools\adb.exe'
$s='192.168.1.153:40507'
& $adb -s $s shell "input keyevent WAKEUP"
Start-Sleep 1
& $adb -s $s shell "am start -n br.paulo.tvlauncher/.PrevisaoActivity"
Start-Sleep 3
& $adb -s $s shell screencap -p /sdcard/prev1.png
& $adb -s $s pull /sdcard/prev1.png C:\Users\rockx\tv-launcher\.shots\prev1.png | Out-Null
& $adb -s $s shell rm /sdcard/prev1.png
