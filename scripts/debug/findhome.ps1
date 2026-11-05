$adb = "$env:USERPROFILE\android-sdk\platform-tools\adb.exe"
$s = $env:TV_ADB
Write-Output "--- pacotes candidatos ---"
& $adb -s $s shell pm list packages -f | Select-String -Pattern "home|cast|tungsten|mediashell|hotwired|matter' -CaseSensitive:$false
Write-Output "--- launcher activities desses pacotes ---"
$pkgs = & $adb -s $s shell pm list packages | Select-String -Pattern 'home|cast|tungsten|mediashell|hotwired|matter' -CaseSensitive:$false | ForEach-Object { ($_ -split ':')[1] }
foreach ($p in $pkgs) {
    Write-Output ">>> $p"
    & $adb -s $s shell dumpsys package $p | Select-String -Pattern 'android.intent.action.MAIN|android.intent.category.LAUNCHER' -Context 2,0
}
