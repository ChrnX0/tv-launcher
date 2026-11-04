# Build + instalação na TV. Uso:
#   .\build3.ps1            -> debug (rápido de compilar; roda só interpretado/JIT, NÃO serve para medir desempenho)
#   .\build3.ps1 -Release   -> release (R8 + perfil de compilação AOT): é o que fica instalado na TV
# Nunca desinstala: só "install -r". Se o install der erro de assinatura, PARE (desinstalar perde o Device Owner).
#
# 04/11/2026: com o Shizuku embutido (shizuku/) o projeto usa AGP 8.11 + Gradle 8.14 (wrapper do repo, gradlew.bat)
# e JDK 21. O gradle-8.9 de C:\Users\rockx\tools nao roda mais este projeto.
param([switch]$Release)
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot'
$env:ANDROID_HOME=$env:USERPROFILE+'\android-sdk'
$adb=$env:USERPROFILE+'\android-sdk\platform-tools\adb.exe'
# A porta da depuracao sem fio muda a cada boot: pega a atual pelo mDNS (_adb-tls-connect) da TV 192.168.1.153.
$porta = (& $adb mdns services | Select-String '192\.168\.1\.153:(\d+)' | ? { $_ -match 'tls-connect' } | Select-Object -First 1).Matches.Groups[1].Value
$s="192.168.1.153:$porta"
cd C:\Users\rockx\tv-launcher
& $adb connect $s
$tarefa = if ($Release) { ':app:assembleRelease' } else { 'assembleDebug' }
.\gradlew.bat $tarefa -q 2>&1 | ? { $_ -notmatch 'SDK XML|NativeCommandError|RemoteException|SDK processing|CategoryInfo|FullyQualified|^\s*$|gradle :|\+ ' } | Select-Object -Last 40
"BUILD ($tarefa) exit=$LASTEXITCODE"
if($LASTEXITCODE -ne 0){ exit }
$apk = if ($Release) { 'app\build\outputs\apk\release\app-release.apk' } else { 'app\build\outputs\apk\debug\app-debug.apk' }
& $adb -s $s install -r -t $apk
if($LASTEXITCODE -ne 0){ "INSTALL FALHOU: pare aqui. NAO desinstale o launcher (perderia o Device Owner)."; exit }
# Compilação AOT (04/11/2026). O Android 14 força o app de HOME para "speed-profile" (ignora o -m speed) e, sem perfil,
# cai em "verify". Por isso: 1) pede ao profileinstaller do app que grave o perfil embutido (baseline-prof.txt);
# 2) compila com ele. Vale para o release. No debug (debuggable) o ART sempre usa verify, e este passo não faz mal.
# Conferir: adb shell "dumpsys package dexopt | grep -A2 br.paulo.tvlauncher"  ->  status=speed-profile
& $adb -s $s shell "am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE -n br.paulo.tvlauncher/androidx.profileinstaller.ProfileInstallReceiver"
& $adb -s $s shell "cmd package compile -m speed-profile -f br.paulo.tvlauncher"
# O código compilado só vale num processo novo, e o shell não consegue matar o launcher (Device Owner + acessibilidade):
# ele mesmo se reinicia por este comando (só adb, DUMP). O sistema sobe a HOME e religa a acessibilidade sozinho.
& $adb -s $s shell "am broadcast -a br.paulo.tvlauncher.REINICIAR -n br.paulo.tvlauncher/.ControleShizukuAdbReceiver"
Start-Sleep 6
& $adb -s $s shell "dumpsys package dexopt | grep -A2 '\[br.paulo.tvlauncher\]' | tail -1"
# Abre a NOSSA HOME pelo nome: com o launcherx do Google ativo, o HOME genérico passa antes pela tela dele (ver Home.kt).
& $adb -s $s shell "am start -a android.intent.action.MAIN -c android.intent.category.HOME -n br.paulo.tvlauncher/.MainActivity"
Start-Sleep 2
1..5 | % { & $adb -s $s shell "input keyevent DPAD_RIGHT"; Start-Sleep -Milliseconds 300 }
& $adb -s $s shell "input keyevent DPAD_UP"
Start-Sleep -Milliseconds 500
& $adb -s $s shell "input keyevent DPAD_RIGHT"
Start-Sleep -Milliseconds 500
& $adb -s $s shell "input keyevent DPAD_CENTER"
Start-Sleep 1
& $adb -s $s shell screencap -p /sdcard/pop5.png
& $adb -s $s pull /sdcard/pop5.png C:\Users\rockx\tv-launcher\.shots\pop5.png | Out-Null
& $adb -s $s shell rm /sdcard/pop5.png
