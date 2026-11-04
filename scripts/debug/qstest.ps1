$adb = "$env:USERPROFILE\android-sdk\platform-tools\adb.exe"
$s = "192.168.1.153:40507"
Write-Output "--- tentativa 1: por action ---"
& $adb -s $s shell am start -a com.android.tv.QUICK_SETTINGS
Start-Sleep 1
& $adb -s $s shell "dumpsys activity activities | grep topResumedActivity"
Write-Output "--- tentativa 2: componente explicito ---"
& $adb -s $s shell am start -n com.android.tv.quicksettings/.QuickSettings
Start-Sleep 1
& $adb -s $s shell "dumpsys activity activities | grep topResumedActivity"
& $adb -s $s shell screencap -p /sdcard/qs2.png
& $adb -s $s pull /sdcard/qs2.png C:\Users\rockx\tv-launcher\.shots\qs2.png
& $adb -s $s shell rm /sdcard/qs2.png
