$adb = "$env:USERPROFILE\android-sdk\platform-tools\adb.exe"
$s = "192.168.1.153:40507"
"-- BLUETOOTH_SETTINGS --"
& $adb -s $s shell am start -a android.settings.BLUETOOTH_SETTINGS
Start-Sleep 2
& $adb -s $s shell "dumpsys activity activities | grep mResumedActivity"
Start-Sleep 1
& $adb -s $s shell input keyevent BACK
Start-Sleep 1
"-- SOUND_SETTINGS --"
& $adb -s $s shell am start -a android.settings.SOUND_SETTINGS
Start-Sleep 2
& $adb -s $s shell "dumpsys activity activities | grep mResumedActivity"
Start-Sleep 1
& $adb -s $s shell input keyevent BACK
Start-Sleep 1
"-- CAST_SETTINGS (tentativa) --"
& $adb -s $s shell am start -a android.settings.CAST_SETTINGS
Start-Sleep 2
& $adb -s $s shell "dumpsys activity activities | grep mResumedActivity"
Start-Sleep 1
& $adb -s $s shell input keyevent HOME
