$adb = "$env:USERPROFILE\android-sdk\platform-tools\adb.exe"
$s = $env:TV_ADB
& $adb -s $s shell dumpsys package com.android.systemui | Select-String -Pattern "systemui/' | ForEach-Object { $_.Line.Trim() } | Select-Object -Unique | Sort-Object
