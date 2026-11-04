$env:Path='C:\Users\rockx\tools\gradle-8.9\bin;'+$env:Path
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot'
$env:ANDROID_HOME=$env:USERPROFILE+'\android-sdk'
$adb=$env:USERPROFILE+'\android-sdk\platform-tools\adb.exe'
$s='192.168.1.153:40507'
cd C:\Users\rockx\tv-launcher
& $adb connect $s
gradle assembleDebug -q 2>&1 | ? { $_ -notmatch 'SDK XML|NativeCommandError|RemoteException|SDK processing|CategoryInfo|FullyQualified|^\s*$|gradle :|\+ ' } | Select-Object -Last 60
"BUILD exit=$LASTEXITCODE"
if($LASTEXITCODE -ne 0){ exit }
& $adb -s $s install -r -t app\build\outputs\apk\debug\app-debug.apk
