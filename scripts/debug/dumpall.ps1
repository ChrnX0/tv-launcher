$adb = "$env:USERPROFILE\android-sdk\platform-tools\adb.exe"
$s = $env:TV_ADB
& $adb -s $s shell dumpsys package com.android.tv.settings | Select-String -Pattern "com\.android\.tv\.settings/' | ForEach-Object { $_.Line.Trim() } | Select-Object -Unique | Sort-Object
