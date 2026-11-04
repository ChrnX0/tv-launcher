$adb=$env:USERPROFILE+'\android-sdk\platform-tools\adb.exe'
$s='192.168.1.153:40507'
& $adb -s $s shell "input keyevent WAKEUP"
Start-Sleep 1
& $adb -s $s shell "input keyevent HOME"
Start-Sleep 8
& $adb -s $s shell "run-as br.paulo.tvlauncher cat /data/data/br.paulo.tvlauncher/shared_prefs/clima.xml" 2>&1
