$adb = "$env:USERPROFILE\android-sdk\platform-tools\adb.exe"
$s = "192.168.1.153:40507"
"-- AccessoriesActivity --"
& $adb -s $s shell am start -n com.android.tv.settings/.accessories.AccessoriesActivity
Start-Sleep 2
& $adb -s $s shell "dumpsys activity activities | grep topResumedActivity"
Start-Sleep 1
& $adb -s $s shell input keyevent HOME
