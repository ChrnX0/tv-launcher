$adb = "$env:USERPROFILE\android-sdk\platform-tools\adb.exe"
& $adb -s $env:TV_ADB shell pm list packages | Select-String -Pattern "home|cast|bluetooth|sound|tvsettings'
