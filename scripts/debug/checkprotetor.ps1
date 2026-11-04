$adb=$env:USERPROFILE+'\android-sdk\platform-tools\adb.exe'
$s='192.168.1.153:40507'
& $adb -s $s shell settings get secure screensaver_components
& $adb -s $s shell settings get secure screensaver_enabled
& $adb -s $s shell am start -n com.android.systemui/.Somnambulator
Start-Sleep 2
& $adb -s $s shell screencap -p /sdcard/sonho1.png
& $adb -s $s pull /sdcard/sonho1.png C:\Users\rockx\tv-launcher\.shots\sonho1.png | Out-Null
& $adb -s $s shell rm /sdcard/sonho1.png
& $adb -s $s shell "dumpsys activity activities | grep topResumedActivity"
