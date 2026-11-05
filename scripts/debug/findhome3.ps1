$adb = "$env:USERPROFILE\android-sdk\platform-tools\adb.exe"
$s = $env:TV_ADB
& $adb -s $s shell dumpsys package com.google.android.apps.mediashell | Select-String -Pattern "mediashell/' | ForEach-Object { $_.Line.Trim() } | Select-Object -Unique | Sort-Object
