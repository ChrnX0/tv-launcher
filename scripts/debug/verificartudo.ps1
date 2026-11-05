$adb=$env:USERPROFILE+'\android-sdk\platform-tools\adb.exe'
$s=$env:TV_ADB
# 1) tela de previsão (fundo novo): HOME -> 5x direita (Play Store) -> cima (clima) -> OK
& $adb -s $s shell "input keyevent WAKEUP"
Start-Sleep 1
& $adb -s $s shell "input keyevent HOME"
Start-Sleep 3
1..5 | % { & $adb -s $s shell "input keyevent DPAD_RIGHT"; Start-Sleep -Milliseconds 300 }
& $adb -s $s shell "input keyevent DPAD_UP"
Start-Sleep -Milliseconds 500
& $adb -s $s shell "input keyevent DPAD_CENTER"
Start-Sleep 7
& $adb -s $s shell "dumpsys activity activities" | Select-String "topResumedActivity"
& $adb -s $s shell screencap -p /sdcard/prev4.png
& $adb -s $s pull /sdcard/prev4.png $env:USERPROFILE\tv-launcher\.shots\prev4.png | Out-Null
& $adb -s $s shell rm /sdcard/prev4.png
# 2) protetor: volta à home e deixa a TV ociosa até o sonho entrar
& $adb -s $s shell "input keyevent HOME"
& $adb -s $s shell "settings put system screen_off_timeout 15000"
Start-Sleep 25
& $adb -s $s shell "dumpsys power" | Select-String "mWakefulness="
& $adb -s $s shell screencap -p /sdcard/sonho7.png
& $adb -s $s pull /sdcard/sonho7.png $env:USERPROFILE\tv-launcher\.shots\sonho7.png | Out-Null
& $adb -s $s shell rm /sdcard/sonho7.png
& $adb -s $s shell "settings put system screen_off_timeout 600000"
& $adb -s $s shell "settings get system screen_off_timeout"
