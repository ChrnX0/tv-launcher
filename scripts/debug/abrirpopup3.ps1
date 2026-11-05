$env:ANDROID_HOME=$env:USERPROFILE+'\android-sdk'
$adb=$env:USERPROFILE+'\android-sdk\platform-tools\adb.exe'
$s=$env:TV_ADB
& $adb connect $s
& $adb -s $s shell "input keyevent BACK"
Start-Sleep -Milliseconds 500
& $adb -s $s shell "input keyevent HOME"
Start-Sleep 1
1..5 | % { & $adb -s $s shell "input keyevent DPAD_RIGHT"; Start-Sleep -Milliseconds 300 }
& $adb -s $s shell "input keyevent DPAD_UP"
Start-Sleep -Milliseconds 500
& $adb -s $s shell "input keyevent DPAD_RIGHT"
Start-Sleep -Milliseconds 500
& $adb -s $s shell screencap -p /sdcard/pop4.png
& $adb -s $s pull /sdcard/pop4.png $env:USERPROFILE\tv-launcher\.shots\pop4.png | Out-Null
& $adb -s $s shell rm /sdcard/pop4.png
& $adb -s $s shell "dumpsys activity activities | grep topResumedActivity"
