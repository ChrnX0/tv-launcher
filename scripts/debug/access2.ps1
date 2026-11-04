$adb = "$env:USERPROFILE\android-sdk\platform-tools\adb.exe"
$s = "192.168.1.153:40507"
& $adb -s $s shell am start -n com.android.tv.settings/.MainSettings
Start-Sleep 1
for ($i=0; $i -lt 6; $i++) { & $adb -s $s shell input keyevent DPAD_DOWN; Start-Sleep -Milliseconds 250 }
& $adb -s $s shell input keyevent DPAD_DOWN; Start-Sleep -Milliseconds 250
& $adb -s $s shell input keyevent DPAD_CENTER
Start-Sleep 1
& $adb -s $s shell "dumpsys activity activities | grep topResumedActivity"
& $adb -s $s shell screencap -p /sdcard/acc2.png
& $adb -s $s pull /sdcard/acc2.png C:\Users\rockx\tv-launcher\.shots\acc2.png
& $adb -s $s shell rm /sdcard/acc2.png
& $adb -s $s shell input keyevent HOME
