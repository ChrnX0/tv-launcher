$adb = "$env:USERPROFILE\android-sdk\platform-tools\adb.exe"
$s = $env:TV_ADB
& $adb -s $s shell dumpsys package com.android.tv.settings | Select-String -Pattern "accessories|remote|AccessoriesActivity|cast|Cast'
