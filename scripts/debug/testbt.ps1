$adb = "$env:USERPROFILE\android-sdk\platform-tools\adb.exe"
$s = $env:TV_ADB
"-- AccessoriesActivity --"
& $adb -s $s shell am start -n com.android.tv.settings/.accessories.AccessoriesActivity
Start-Sleep 2
& $adb -s $s shell "dumpsys activity activities | grep topResumedActivity"
Start-Sleep 1
& $adb -s $s shell input keyevent HOME
