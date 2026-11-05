$adb = "$env:USERPROFILE\android-sdk\platform-tools\adb.exe"
$s = $env:TV_ADB
Write-Output "--- pacotes candidatos systemui/quick/overlay/panel ---"
& $adb -s $s shell pm list packages | Select-String -Pattern "systemui|quick|overlay|panel|tvlauncher|katniss' -CaseSensitive:$false
