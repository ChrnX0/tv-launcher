$adb = "$env:USERPROFILE\android-sdk\platform-tools\adb.exe"
$s = "192.168.1.212:38429"
& $adb connect $s
Write-Output "--- pacotes candidatos home/cast ---"
& $adb -s $s shell pm list packages -f | Select-String -Pattern 'home|cast|tungsten|mediashell' -CaseSensitive:$false
Write-Output "--- MainSettings: abrindo e tirando print ---"
& $adb -s $s shell am start -n com.android.tv.settings/.MainSettings
Start-Sleep 1
& $adb -s $s shell screencap -p /sdcard/p_ms.png
& $adb -s $s pull /sdcard/p_ms.png C:\Users\rockx\tv-launcher\.shots\p_ms.png
& $adb -s $s shell rm /sdcard/p_ms.png
