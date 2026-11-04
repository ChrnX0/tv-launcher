$adb = "$env:USERPROFILE\android-sdk\platform-tools\adb.exe"
$s = "192.168.1.153:40507"
& $adb -s $s shell am start -n com.google.android.apps.mediashell/.settings.CastSettingsActivity
Start-Sleep 2
& $adb -s $s shell "dumpsys activity activities | grep topResumedActivity"
& $adb -s $s shell screencap -p /sdcard/home.png
& $adb -s $s pull /sdcard/home.png C:\Users\rockx\tv-launcher\.shots\home.png
& $adb -s $s shell rm /sdcard/home.png
& $adb -s $s shell input keyevent HOME
