$adb=$env:USERPROFILE+'\android-sdk\platform-tools\adb.exe'
$s=$env:TV_ADB
& $adb -s $s shell "settings put secure screensaver_components br.paulo.tvlauncher/.ProtetorTela"
& $adb -s $s shell "settings put secure screensaver_enabled 1"
& $adb -s $s shell "dumpsys deviceidle force-idle" | Out-Null
& $adb -s $s shell "cmd power stayon false" | Out-Null
& $adb -s $s shell "am start -n com.android.systemui/.Somnambulator" 2>&1 | Out-Null
Start-Sleep -Milliseconds 800
& $adb -s $s shell "dumpsys activity activities" | Select-String "topResumedActivity"
Start-Sleep 1
& $adb -s $s shell screencap -p /sdcard/sonho2.png
& $adb -s $s pull /sdcard/sonho2.png $env:USERPROFILE\tv-launcher\.shots\sonho2.png | Out-Null
& $adb -s $s shell rm /sdcard/sonho2.png
