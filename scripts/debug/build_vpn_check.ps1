$env:Path="$env:USERPROFILE\tools\gradle-8.9\bin;"+$env:Path
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot'
$env:ANDROID_HOME=$env:USERPROFILE+'\android-sdk'
$adb=$env:USERPROFILE+'\android-sdk\platform-tools\adb.exe'
$s=$env:TV_ADB
cd $env:USERPROFILE\tv-launcher
gradle assembleDebug -q 2>&1 | Select-Object -Last 40
"BUILD exit=$LASTEXITCODE"
if($LASTEXITCODE -ne 0){ exit }
& $adb -s $s install -r -t app\build\outputs\apk\debug\app-debug.apk
