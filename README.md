# tv-launcher

Launcher próprio, leve e escuro para **Chromecast com Google TV** (Android TV 14). Mostra só os apps que você instalou
e a Play Store, com hora, clima, fase da lua e a rede Wi-Fi (nome, sinal, velocidade e latência).

- ~800 KB de APK, sem bibliotecas externas (só o framework do Android).
- ~53 MB de RAM em uso (PSS) contra ~157 MB do Google TV Home (`launcherx`), medidos na mesma TV.

## Estrutura

| Arquivo | Para que serve |
|---|---|
| `app/src/main/kotlin/br/paulo/tvlauncher/Config.kt` | **Tudo que você quer mudar**: cidade, apps fixos, ordem, app aberto ao ligar |
| `MainActivity.kt` | A tela inteira (montada por código, sem XML) |
| `Rede.kt` | Nome da rede (SSID), sinal, velocidade do enlace e latência |
| `Lua.kt` | Fase da lua calculada no aparelho (sem internet) |
| `Uso.kt` | Quando cada app foi usado pela última vez (ordem por uso recente) |
| `BootReceiver.kt` | Abre um app quando a TV **reinicia** (não quando acorda do standby) |
| `restaurar-tv.ps1` | Refaz o setup numa TV nova ou depois de factory reset |
| `dist/tv-launcher.apk` | Build de debug pronto para instalar |

## Reorganizar os apps

Segure **OK** num app: ele entra no modo de mover (borda verde-agua, guia no rodape).

| Tecla | O que faz |
|---|---|
| ◀ ▶ | troca de posicao |
| OK | salva a ordem |
| Voltar | cancela |
| ▼ | apaga a ordem salva e volta ao automatico (fixos + uso recente) |

A ordem salva vence os `FIXOS` e o uso recente do `Config.kt`. Apps novos entram no fim.

## Foto no botao de Configuracoes

O botao redondo no canto de baixo abre as Configuracoes. Ele mostra, nesta ordem: a foto `perfil.jpg` (ou `.png`) da pasta de arquivos do app, a inicial da conta Google da TV, ou um icone de pessoa.

```powershell
adb -s IP:PORTA push perfil.jpg /sdcard/Android/data/br.paulo.tvlauncher/files/perfil.jpg
adb -s IP:PORTA shell pm grant br.paulo.tvlauncher android.permission.GET_ACCOUNTS   # so para a inicial
```

A foto nao vem sozinha da conta: le-la exige OAuth numa API da Google. O arquivo `perfil.*` esta no `.gitignore`.

## Mudar para os EUA

Em `Config.kt`: `CIDADE`, `LATITUDE`, `LONGITUDE` e `USAR_FAHRENHEIT = true`. Depois recompile e reinstale.

## Compilar

Precisa de JDK 17, Android SDK (platform 34 + build-tools 34.0.0) e Gradle 8.9.

```powershell
$env:ANDROID_HOME = "$env:USERPROFILE\android-sdk"
gradle assembleDebug
adb -s IP:PORTA install -r app\build\outputs\apk\debug\app-debug.apk
adb -s IP:PORTA shell appops set br.paulo.tvlauncher GET_USAGE_STATS allow
adb -s IP:PORTA shell pm grant br.paulo.tvlauncher android.permission.ACCESS_FINE_LOCATION
```

Crie `local.properties` com `sdk.dir=C:/caminho/do/android-sdk`.

## Restaurar a TV

```powershell
# 1ª vez: abra "Depuração sem fio > Parear dispositivo com código" na TV e deixe a janela aberta
.\restaurar-tv.ps1 -Ip 192.168.1.153 -CodigoPareamento 123456 -PularHome

# Trocar o Home (com rollback automático se o foco não cair no launcher novo)
.\restaurar-tv.ps1 -Ip 192.168.1.153

# Voltar ao Google TV Home original
.\restaurar-tv.ps1 -Ip 192.168.1.153 -Desfazer

# Só mostrar os comandos
.\restaurar-tv.ps1 -Ip 192.168.1.153 -DryRun
```

## Por que o Home exige desativar o `setupwraith`

No Google TV, só definir o papel de Home não basta: o `launcherx` continua vencendo. Desativando só o `launcherx`, o
sistema abre `setupwraith/.RecoveryActivity` como Home, em loop. O shell não pode desativar esse componente isolado
(`SecurityException`), então o script desativa o pacote `setupwraith` inteiro e o `launcherx`, para o usuário 0.
Tudo reversível com `pm enable` (ou `-Desfazer`).

## Testado e não testado

- **Reboot com o Home trocado: testado** (30/10/2026). A TV voltou em ~146 s; a Depuração sem fio persistiu sem novo pareamento (a porta muda a cada boot: ache por `adb mdns services`); o Home abriu o launcher; `setupwraith` e `launcherx` seguiram desativados; 0 lançamentos do `RecoveryActivity`; 0 crashes.
- **BootReceiver:** depois do reboot o OneStream abriu sozinho, mas o próprio OneStream também declara um receiver de `BOOT_COMPLETED`, então não dá para atribuir com certeza. O `ABRIR_AO_LIGAR` do `Config.kt` agora vem vazio, para não haver dois lugares controlando isso; se o OneStream parar de abrir sozinho no próximo reboot, a opção dele não estava ativa.
- **Segurar OK para mover apps:** testado com `input keyevent --longpress`, não com o controle de verdade.
- **`restaurar-tv.ps1`:** testado em `-DryRun` e nos comandos equivalentes feitos à mão, não numa TV zerada.