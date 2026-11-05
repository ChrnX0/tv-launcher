$adb=$env:USERPROFILE+'\android-sdk\platform-tools\adb.exe'
$s=$env:TV_ADB
& $adb -s $s shell uiautomator dump /sdcard/ui.xml
& $adb -s $s pull /sdcard/ui.xml $env:USERPROFILE\tv-launcher\.shots\ui.xml | Out-Null
& $adb -s $s shell rm /sdcard/ui.xml
