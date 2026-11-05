$adb=$env:USERPROFILE+'\android-sdk\platform-tools\adb.exe'
$s=$env:TV_ADB
& $adb -s $s shell "input keyevent WAKEUP"
Start-Sleep 1
& $adb -s $s shell "input keyevent HOME"
Start-Sleep 8
& $adb -s $s shell "run-as br.paulo.tvlauncher cat /data/data/br.paulo.tvlauncher/shared_prefs/clima.xml" 2>&1
