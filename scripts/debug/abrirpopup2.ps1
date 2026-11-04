$env:ANDROID_HOME=$env:USERPROFILE+'\android-sdk'
$adb=$env:USERPROFILE+'\android-sdk\platform-tools\adb.exe'
$s='192.168.1.153:40507'
& $adb connect $s
& $adb -s $s shell "input keyevent HOME"
Start-Sleep 1
# "Play Store" e o ultimo icone da fileira e NAO e pilha (sem indicador ^v) -- DPAD_UP dali sobe
# direto para o topo (bolinha de perfil), sem o desvio de "trocar app da pilha" que pilhas tem.
1..5 | % { & $adb -s $s shell "input keyevent DPAD_RIGHT"; Start-Sleep -Milliseconds 300 }
& $adb -s $s shell "input keyevent DPAD_UP"
Start-Sleep -Milliseconds 500
& $adb -s $s shell "input keyevent DPAD_CENTER"
Start-Sleep 1
& $adb -s $s shell screencap -p /sdcard/pop3.png
& $adb -s $s pull /sdcard/pop3.png C:\Users\rockx\tv-launcher\.shots\pop3.png | Out-Null
& $adb -s $s shell rm /sdcard/pop3.png
& $adb -s $s shell "dumpsys activity activities | grep topResumedActivity"
