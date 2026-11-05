$adb = "$env:USERPROFILE\android-sdk\platform-tools\adb.exe"
$s = $env:TV_ADB
& $adb -s $s shell am start -n com.google.android.apps.mediashell/.settings.CastSettingsActivity
Start-Sleep 2
& $adb -s $s shell "dumpsys activity activities | grep topResumedActivity"
& $adb -s $s shell screencap -p /sdcard/home.png
& $adb -s $s pull /sdcard/home.png $env:USERPROFILE\tv-launcher\.shots\home.png
& $adb -s $s shell rm /sdcard/home.png
& $adb -s $s shell input keyevent HOME
