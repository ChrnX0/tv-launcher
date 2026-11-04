$adb = "$env:USERPROFILE\android-sdk\platform-tools\adb.exe"
& $adb -s 192.168.1.153:40507 shell pm list packages | Select-String -Pattern 'home|cast|bluetooth|sound|tvsettings'
