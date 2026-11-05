$env:Path="$env:USERPROFILE\tools\gradle-8.9\bin;"+$env:Path
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot'
$env:ANDROID_HOME=$env:USERPROFILE+'\android-sdk'
$adb=$env:USERPROFILE+'\android-sdk\platform-tools\adb.exe'
$s=$env:TV_ADB
cd $env:USERPROFILE\tv-launcher
& $adb connect $s
gradle assembleDebug -q 2>&1 | ? { $_ -notmatch 'SDK XML|NativeCommandError|RemoteException|SDK processing|CategoryInfo|FullyQualified|^\s*$|gradle :|\+ ' } | Select-Object -Last 40
"BUILD exit=$LASTEXITCODE"
if($LASTEXITCODE -ne 0){ exit }
& $adb -s $s install -r -t app\build\outputs\apk\debug\app-debug.apk
Start-Sleep 4
& $adb -s $s shell "input keyevent HOME"; Start-Sleep 30
& $adb -s $s shell screencap -p /sdcard/f.png; & $adb -s $s pull /sdcard/f.png $env:USERPROFILE\tv-launcher\.shots\f_parte1.png | Out-Null; & $adb -s $s shell rm /sdcard/f.png
(Get-Item app\build\outputs\apk\debug\app-debug.apk).Length
& $adb -s $s shell "dumpsys activity activities | grep topResumedActivity"
