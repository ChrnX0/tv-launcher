$adb = "$env:USERPROFILE\android-sdk\platform-tools\adb.exe"
$s = "192.168.1.153:40507"
& $adb -s $s shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
