$adb = "$env:USERPROFILE\android-sdk\platform-tools\adb.exe"
$s = "192.168.1.153:40507"
& $adb -s $s shell input keyevent HOME
Start-Sleep 2
& $adb -s $s shell "dumpsys activity activities | grep topResumedActivity"
# Foco inicial do launcher costuma ser a bolinha de perfil/linha de apps; DPAD_UP + OK para alcançar a bolinha do topo.
& $adb -s $s shell input keyevent DPAD_UP
Start-Sleep -Milliseconds 300
& $adb -s $s shell input keyevent DPAD_UP
Start-Sleep -Milliseconds 300
& $adb -s $s shell input keyevent DPAD_CENTER
Start-Sleep 1
& $adb -s $s shell screencap -p /sdcard/pop.png
& $adb -s $s pull /sdcard/pop.png C:\Users\rockx\tv-launcher\.shots\pop.png
& $adb -s $s shell rm /sdcard/pop.png
