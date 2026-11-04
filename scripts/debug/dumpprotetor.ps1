$adb=$env:USERPROFILE+'\android-sdk\platform-tools\adb.exe'
$s='192.168.1.153:40507'
& $adb -s $s shell uiautomator dump /sdcard/ui.xml
& $adb -s $s pull /sdcard/ui.xml C:\Users\rockx\tv-launcher\.shots\ui.xml | Out-Null
& $adb -s $s shell rm /sdcard/ui.xml
