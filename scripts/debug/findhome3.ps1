$adb = "$env:USERPROFILE\android-sdk\platform-tools\adb.exe"
$s = "192.168.1.153:40507"
& $adb -s $s shell dumpsys package com.google.android.apps.mediashell | Select-String -Pattern 'mediashell/' | ForEach-Object { $_.Line.Trim() } | Select-Object -Unique | Sort-Object
