$adb = "$env:USERPROFILE\android-sdk\platform-tools\adb.exe"
$s = $env:TV_ADB
for ($i=0; $i -lt 6; $i++) { & $adb -s $s shell input keyevent DPAD_DOWN; Start-Sleep -Milliseconds 300 }
& $adb -s $s shell screencap -p /sdcard/ms2.png
& $adb -s $s pull /sdcard/ms2.png $env:USERPROFILE\tv-launcher\.shots\ms2.png
& $adb -s $s shell rm /sdcard/ms2.png
