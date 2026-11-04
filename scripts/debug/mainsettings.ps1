$adb = "$env:USERPROFILE\android-sdk\platform-tools\adb.exe"
$s = "192.168.1.153:40507"
& $adb -s $s shell am start -n com.android.tv.settings/.MainSettings
Start-Sleep 2
& $adb -s $s shell screencap -p /sdcard/ms.png
& $adb -s $s pull /sdcard/ms.png C:\Users\rockx\tv-launcher\.shots\ms.png
& $adb -s $s shell rm /sdcard/ms.png
