# tv-launcher

Launcher próprio, leve e escuro para **Chromecast com Google TV** (Android TV 14). Mostra só os apps que você instalou
e a Play Store, com hora, clima, cotação USD/BRL, RAM/disco livres e qualidade do Wi-Fi.

- ~800 KB de APK, sem bibliotecas externas (só o framework do Android).
- ~53 MB de RAM em uso (PSS) contra ~157 MB do Google TV Home (`launcherx`), medidos na mesma TV.

## Estrutura

| Arquivo | Para que serve |
|---|---|
| `app/src/main/kotlin/br/paulo/tvlauncher/Config.kt` | **Tudo que você quer mudar**: cidade, apps fixos, ordem, app aberto ao ligar |
| `MainActivity.kt` | A tela inteira (montada por código, sem XML) |
| `Rede.kt` | Sinal do Wi-Fi, velocidade do enlace e latência |
| `Uso.kt` | Quando cada app foi usado pela última vez (ordem por uso recente) |
| `Memoria.kt` | Botão "Liberar RAM" |
| `BootReceiver.kt` | Abre um app quando a TV **reinicia** (não quando acorda do standby) |
| `restaurar-tv.ps1` | Refaz o setup numa TV nova ou depois de factory reset |
| `dist/tv-launcher.apk` | Build de debug pronto para instalar |

## Mudar para os EUA

Em `Config.kt`: `CIDADE`, `LATITUDE`, `LONGITUDE` e `USAR_FAHRENHEIT = true`. Depois recompile e reinstale.

## Compilar

Precisa de JDK 17, Android SDK (platform 34 + build-tools 34.0.0) e Gradle 8.9.

```powershell
$env:ANDROID_HOME = "$env:USERPROFILE\android-sdk"
gradle assembleDebug
adb -s IP:PORTA install -r app\build\outputs\apk\debug\app-debug.apk
adb -s IP:PORTA shell appops set br.paulo.tvlauncher GET_USAGE_STATS allow
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

## O que NÃO foi testado

- **Reboot da TV** com o Home trocado. Antes de reiniciar, confira que as Configurações abrem pela engrenagem do
  launcher (é por lá que se religa a Depuração sem fio). Pior caso: factory reset.
- O `BootReceiver` foi testado com uma ação de teste (o shell não pode enviar `BOOT_COMPLETED`), não com um reboot real.
- O `restaurar-tv.ps1` foi testado em `-DryRun` e nos comandos equivalentes feitos à mão, não numa TV zerada.
