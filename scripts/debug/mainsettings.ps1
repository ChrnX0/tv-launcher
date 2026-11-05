$adb = "$env:USERPROFILE\android-sdk\platform-tools\adb.exe"
$s = $env:TV_ADB
& $adb -s $s shell am start -n com.android.tv.settings/.MainSettings
Start-Sleep 2
& $adb -s $s shell screencap -p /sdcard/ms.png
& $adb -s $s pull /sdcard/ms.png $env:USERPROFILE\tv-launcher\.shots\ms.png
& $adb -s $s shell rm /sdcard/ms.png
