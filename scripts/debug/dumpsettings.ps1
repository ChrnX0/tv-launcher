$adb = "$env:USERPROFILE\android-sdk\platform-tools\adb.exe"
$s = "192.168.1.153:40507"
& $adb -s $s shell dumpsys package com.android.tv.settings | Select-String -Pattern 'Activity|bluetooth|sound|cast|home|accessor' -CaseSensitive:$false | Select-Object -First 80
