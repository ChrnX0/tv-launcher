$adb = "$env:USERPROFILE\android-sdk\platform-tools\adb.exe"
$s = $env:TV_ADB
& $adb -s $s shell am start -n com.android.tv.settings/.accessories.AddAccessoryActivity
Start-Sleep 2
& $adb -s $s shell "dumpsys activity activities | grep topResumedActivity"
