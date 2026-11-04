$adb = "$env:USERPROFILE\android-sdk\platform-tools\adb.exe"
$s = "192.168.1.153:40507"
Write-Output "--- pacotes candidatos systemui/quick/overlay/panel ---"
& $adb -s $s shell pm list packages | Select-String -Pattern 'systemui|quick|overlay|panel|tvlauncher|katniss' -CaseSensitive:$false
