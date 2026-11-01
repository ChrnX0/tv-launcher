<#
.SYNOPSIS
  Reproduz o setup da TV (Chromecast com Google TV): remove apps, instala o launcher e o define como Home.

.PARAMETER Ip
  IP da TV na rede (Configuracoes > Preferencias do dispositivo > Sobre > Status).

.PARAMETER CodigoPareamento
  Codigo de 6 digitos de "Depuracao sem fio > Parear dispositivo com codigo". So na 1a vez (ou depois de factory reset).
  Deixe a janela de pareamento ABERTA na TV ao rodar.

.PARAMETER Apk
  Caminho do APK do launcher. Padrao: dist\tv-launcher.apk (copia do build, neste repositorio).

.PARAMETER PularHome
  Faz a limpeza e instala o APK, mas NAO troca o Home (mais seguro para a primeira vez).

.PARAMETER Desfazer
  Volta ao Google TV Home original (reativa os pacotes desativados e solta o papel de Home).

.PARAMETER DryRun
  So mostra os comandos que rodaria, sem tocar na TV.

.EXAMPLE
  .\restaurar-tv.ps1 -Ip 192.168.1.153 -CodigoPareamento 123456 -PularHome
  .\restaurar-tv.ps1 -Ip 192.168.1.153            # troca o Home (com rollback automatico)
  .\restaurar-tv.ps1 -Ip 192.168.1.153 -Desfazer
#>
param(
    [Parameter(Mandatory)][string]$Ip,
    [string]$CodigoPareamento,
    [string]$Apk = "$PSScriptRoot\dist\tv-launcher.apk",
    [switch]$PularHome,
    [switch]$Desfazer,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

# ---------------------------------------------------------------- o que este script mexe

$LauncherPkg   = "br.paulo.tvlauncher"
$SetupWizard   = "com.google.android.tungsten.setupwraith"   # sem desativar isto, o Android abre o RecoveryActivity em loop como Home
$GoogleTvHome  = "com.google.android.apps.tv.launcherx"

# Apps de usuario removidos (apaga dados e login de cada um; reinstalam pela Play Store).
$AppsRemover = @(
    "com.wbd.stream", "com.crunchyroll.crunchyroid", "com.netflix.ninja", "com.tcl.browser",
    "com.ted.android.tv", "com.yablio.sendfilestotv", "com.esaba.downloader", "com.purple.dns.safe"
)

# Pacotes de sistema removidos so para o usuario 0 (reversivel: cmd package install-existing <pacote>).
$SistemaRemover = @(
    # Ficam (01/11/2026): com.google.android.videos (app Google TV: alugar/comprar filmes) e
    # com.google.android.syncadapters.calendar (sincroniza a agenda que o launcher mostra).
    "com.google.android.play.games", "com.google.android.tvrecommendations",
    "com.android.printspooler", "com.android.htmlviewer", "com.google.android.feedback",
    "com.android.tv.feedbackconsent", "com.netflix.tokenmanager",
    "com.google.android.ondevicepersonalization.services", "com.google.android.federatedcompute",
    "com.android.bluetoothmidiservice", "com.android.dynsystem",
    "com.google.android.healthconnect.controller", "com.google.android.health.connect.backuprestore"
)

# ---------------------------------------------------------------- utilidades

function Passo($texto) { Write-Host "`n=== $texto" -ForegroundColor Cyan }

# Roda o adb (ou so mostra o comando, no DryRun). Uso: Adb @("-s", $serial, "shell", "comando")
function Adb([string[]]$Argumentos) {
    if ($DryRun) { Write-Host "[dry-run] adb $($Argumentos -join ' ')" -ForegroundColor DarkGray; return @() }
    & adb @Argumentos 2>&1
}

function Foco($serial) {
    if ($DryRun) { return "" }
    (& adb -s $serial shell "dumpsys window | grep mCurrentFocus") -join ""
}

# Liga o servico de acessibilidade do launcher (aprova sozinho os pedidos ao Shizuku; ver AutorizaShizukuService.kt).
# O Android DESLIGA o servico sempre que o app leva "force-stop" (inclusive quando o Shizuku revoga o app): rode de novo.
function LigarAprovacaoShizuku($serial) {
    $servico = "$LauncherPkg/$LauncherPkg.AutorizaShizukuService"
    if ($DryRun) { Write-Host "[dry-run] adb shell settings put secure enabled_accessibility_services (acrescenta $servico)" -ForegroundColor DarkGray; return }
    $atual = ((& adb -s $serial shell settings get secure enabled_accessibility_services) -join "").Trim()
    if ($atual -eq "null" -or $atual -eq "") { $novo = $servico }
    elseif ($atual -notlike "*$servico*") { $novo = "${atual}:$servico" }
    else { $novo = $atual }
    Adb @("-s", $serial, "shell", "settings put secure enabled_accessibility_services $novo") | Out-Null
    Adb @("-s", $serial, "shell", "settings put secure accessibility_enabled 1") | Out-Null
}

# ---------------------------------------------------------------- adb e conexao

function GarantirAdb {
    if (Get-Command adb -ErrorAction SilentlyContinue) { return }
    Passo "adb nao encontrado: instalando Google.PlatformTools via winget"
    if ($DryRun) { Write-Host "[dry-run] winget install --id Google.PlatformTools --exact"; return }
    winget install --id Google.PlatformTools --exact --accept-package-agreements --accept-source-agreements
    $env:Path = [Environment]::GetEnvironmentVariable("Path", "Machine") + ";" + [Environment]::GetEnvironmentVariable("Path", "User")
    if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { throw "adb continua indisponivel. Reabra o terminal e rode de novo." }
}

function Conectar {
    if ($DryRun) { return "${Ip}:PORTA" }
    $ipRegex = [regex]::Escape($Ip)
    $servicos = (& adb mdns services) -join "`n"
    if ($CodigoPareamento) {
        $m = [regex]::Match($servicos, "_adb-tls-pairing\._tcp\s+(${ipRegex}:\d+)")
        if (-not $m.Success) { throw "Janela de pareamento nao encontrada. Na TV: Depuracao sem fio > Parear dispositivo com codigo (deixe aberta)." }
        Write-Host (& adb pair $m.Groups[1].Value $CodigoPareamento 2>&1)
        Start-Sleep 2
        $servicos = (& adb mdns services) -join "`n"
    }
    $m = [regex]::Match($servicos, "_adb-tls-connect\._tcp\s+(${ipRegex}:\d+)")
    if (-not $m.Success) { throw "TV nao encontrada por mDNS em $Ip. Ligue a Depuracao sem fio e, na 1a vez, use -CodigoPareamento." }
    $alvo = $m.Groups[1].Value
    Write-Host (& adb connect $alvo 2>&1)
    return $alvo
}

# ---------------------------------------------------------------- Home

function DesfazerHome($serial) {
    Passo "Voltando ao Google TV Home original"
    Adb @("-s", $serial, "shell", "pm enable $GoogleTvHome") | Out-Host
    Adb @("-s", $serial, "shell", "pm enable $SetupWizard") | Out-Host
    Adb @("-s", $serial, "shell", "cmd role remove-role-holder --user 0 android.app.role.HOME $LauncherPkg") | Out-Host
    Adb @("-s", $serial, "shell", "input keyevent KEYCODE_HOME") | Out-Null
}

function TrocarHome($serial) {
    Passo "Definindo o launcher como Home (com rollback automatico em 12 s)"
    Adb @("-s", $serial, "shell", "am start -n $LauncherPkg/.MainActivity") | Out-Null
    # papel de Home: abaixo
    Adb @("-s", $serial, "shell", "cmd role add-role-holder --user 0 android.app.role.HOME $LauncherPkg") | Out-Host
    Adb @("-s", $serial, "shell", "pm disable-user --user 0 $SetupWizard") | Out-Host
    Adb @("-s", $serial, "shell", "pm disable-user --user 0 $GoogleTvHome") | Out-Host
    Start-Sleep 2

    # Teste honesto: sai do launcher (abre Configuracoes) e aperta HOME. Se cair de volta no launcher novo, funcionou.
    Adb @("-s", $serial, "shell", "monkey -p com.android.tv.settings -c android.intent.category.LEANBACK_LAUNCHER 1") | Out-Null
    Start-Sleep 3
    Adb @("-s", $serial, "shell", "input keyevent KEYCODE_HOME") | Out-Null
    $ok = $DryRun
    if (-not $DryRun) {
        foreach ($i in 1..12) {
            Start-Sleep 1
            if ((Foco $serial) -match [regex]::Escape($LauncherPkg)) { $ok = $true; break }
        }
    }
    if ($ok) { Write-Host "OK: o Home agora e o launcher novo." -ForegroundColor Green }
    else { Write-Host "FALHOU: o foco nao caiu no launcher novo. Revertendo." -ForegroundColor Red; DesfazerHome $serial }
}

# ---------------------------------------------------------------- execucao

GarantirAdb
$serial = Conectar

if ($Desfazer) { DesfazerHome $serial; return }

Passo "Removendo apps de usuario ($($AppsRemover.Count))"
foreach ($pkg in $AppsRemover) {
    $instalado = $DryRun -or ((& adb -s $serial shell "pm list packages $pkg") -match [regex]::Escape($pkg))
    if ($instalado) { Write-Host "$pkg -> $((Adb @('-s', $serial, 'uninstall', $pkg)) -join ' ')" }
    else { Write-Host "$pkg -> nao instalado" }
}

Passo "Removendo pacotes de sistema so para o usuario 0 ($($SistemaRemover.Count))"
foreach ($pkg in $SistemaRemover) {
    Write-Host "$pkg -> $((Adb @('-s', $serial, 'shell', "pm uninstall -k --user 0 $pkg")) -join ' ')"
}
Adb @("-s", $serial, "shell", "pm trim-caches 3G") | Out-Null

Passo "Instalando o launcher"
if (-not $DryRun -and -not (Test-Path $Apk)) { throw "APK nao encontrado: $Apk" }
Adb @("-s", $serial, "install", "-r", "-t", $Apk) | Out-Host   # -t: o APK e testOnly (permite desfazer o device owner por adb)
Adb @("-s", $serial, "shell", "appops set $LauncherPkg GET_USAGE_STATS allow") | Out-Null   # ordem por uso recente
Adb @("-s", $serial, "shell", "pm grant $LauncherPkg android.permission.ACCESS_FINE_LOCATION") | Out-Null   # nome do Wi-Fi
Adb @("-s", $serial, "shell", "pm grant $LauncherPkg android.permission.WRITE_SECURE_SETTINGS") | Out-Null   # tela de DNS (vale apos reinstalar)
LigarAprovacaoShizuku $serial   # precisa do app Shizuku instalado e rodando; sem ele o servico so fica ocioso

if ($PularHome) {
    Write-Host "`n-PularHome: o Home NAO foi trocado. O launcher abre pelo menu de apps da TV." -ForegroundColor Yellow
} else {
    TrocarHome $serial
}
Write-Host "`nPronto." -ForegroundColor Green
