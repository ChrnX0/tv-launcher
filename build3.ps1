# 04/11/2026: com o Shizuku embutido (shizuku/) o projeto usa AGP 8.11 + Gradle 8.14 (wrapper do repo, gradlew.bat)
# e JDK 21. O gradle-8.9 de C:\Users\rockx\tools nao roda mais este projeto.
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot'
$env:ANDROID_HOME=$env:USERPROFILE+'\android-sdk'
$adb=$env:USERPROFILE+'\android-sdk\platform-tools\adb.exe'
# A porta da depuracao sem fio muda a cada boot: pega a atual pelo mDNS (_adb-tls-connect) da TV 192.168.1.153.
$porta = (& $adb mdns services | Select-String '192\.168\.1\.153:(\d+)' | ? { $_ -match 'tls-connect' } | Select-Object -First 1).Matches.Groups[1].Value
$s="192.168.1.153:$porta"
cd C:\Users\rockx\tv-launcher
& $adb connect $s
.\gradlew.bat assembleDebug -q 2>&1 | ? { $_ -notmatch 'SDK XML|NativeCommandError|RemoteException|SDK processing|CategoryInfo|FullyQualified|^\s*$|gradle :|\+ ' } | Select-Object -Last 40
"BUILD exit=$LASTEXITCODE"
if($LASTEXITCODE -ne 0){ exit }
& $adb -s $s install -r -t app\build\outputs\apk\debug\app-debug.apk
Start-Sleep 3
& $adb -s $s shell "input keyevent HOME"
Start-Sleep 2
1..5 | % { & $adb -s $s shell "input keyevent DPAD_RIGHT"; Start-Sleep -Milliseconds 300 }
& $adb -s $s shell "input keyevent DPAD_UP"
Start-Sleep -Milliseconds 500
& $adb -s $s shell "input keyevent DPAD_RIGHT"
Start-Sleep -Milliseconds 500
& $adb -s $s shell "input keyevent DPAD_CENTER"
Start-Sleep 1
& $adb -s $s shell screencap -p /sdcard/pop5.png
& $adb -s $s pull /sdcard/pop5.png C:\Users\rockx\tv-launcher\.shots\pop5.png | Out-Null
& $adb -s $s shell rm /sdcard/pop5.png
