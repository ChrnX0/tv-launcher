$adb = "$env:USERPROFILE\android-sdk\platform-tools\adb.exe"
$s = $env:TV_ADB
& $adb -s $s shell dumpsys package com.google.android.apps.mediashell | Select-String -Pattern "mediashell/\.[A-Za-z]' | ForEach-Object { $_.Line.Trim() } | Select-Object -Unique | Sort-Object | Select-String -Pattern 'home|device|control|panel|quick' -CaseSensitive:$false
