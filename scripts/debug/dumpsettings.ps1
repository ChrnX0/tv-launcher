$adb = "$env:USERPROFILE\android-sdk\platform-tools\adb.exe"
$s = $env:TV_ADB
& $adb -s $s shell dumpsys package com.android.tv.settings | Select-String -Pattern "Activity|bluetooth|sound|cast|home|accessor' -CaseSensitive:$false | Select-Object -First 80
