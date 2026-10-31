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

Segure **OK** num app solto: ele entra direto no modo de mover (borda verde-agua, guia no rodape).

| Tecla | O que faz |
|---|---|
| ◀ ▶ | anda uma posicao (para passar por um card sao 2 toques: "em cima" dele e "do outro lado") |
| OK | confirma (se estiver "em cima" de outro card, forma a pilha) |
| Voltar | cancela, nada e gravado |
| ▲ | oculta o app (so card solto) |
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

## Shizuku, aprovação automática e DNS

- **Shizuku:** o launcher é cliente dele (`Privilegio.kt`). Com o app Shizuku rodando e este app autorizado, o launcher executa comandos como `shell` (uid 2000), sem o computador. O servidor do Shizuku morre ao reiniciar a TV (iniciar de novo; "Iniciar na inicialização" não foi testado).
- **Aprovação automática:** `AutorizaShizukuService` (serviço de acessibilidade) aperta "Permitir o tempo todo" no pedido de QUALQUER app ao Shizuku, e cada app aprovado ganha shell. Desligue com `Config.SHIZUKU_AUTORIZAR_TUDO = false`. Detalhes que custaram caro: os botões usam os ids do Android (`android:id/button1`), e no Android 14 o XML precisa de `isAccessibilityTool="true"`, senão o diálogo chega vazio ao serviço.
- **Religar o serviço:** o Android o desliga a cada `force-stop` do app (inclusive o que o Shizuku faz ao revogar um app). O próprio launcher o religa ao abrir (`Privilegio.religarAcessibilidade`), porque tem `WRITE_SECURE_SETTINGS`. Basta apertar HOME.
- **DNS (chip "DNS: …" no rodapé):** escolhe o DNS privado (DoT) da TV. Precisa uma vez de `adb shell pm grant br.paulo.tvlauncher android.permission.WRITE_SECURE_SETTINGS`, que continua valendo após reinstalar. O Android só aceita **nome** de servidor, nunca IP. Servidor fixo que não responde deixa a TV sem resolver nomes (não volta ao normal sozinho): na tela, escolha "Automático", ou `adb shell settings put global private_dns_mode opportunistic`.

## Pilhas, ocultar e mover cards

**Formar uma pilha:** segure OK num app e ande com ◀ ▶. Ao passar "em cima" de outro card os dois viram uma pilha de previa ("A B+C D E") e o rodape diz "OK forma a pilha". OK confirma; se continuar andando, o card segue ("A C B D E"). Voltar cancela sem gravar. O app que chegou fica na frente. Uma pilha arrastada so ocupa posicoes ao lado (nao da para empilhar uma pilha dentro de outra); um app solto pode entrar numa pilha que ja existe.

**Usar uma pilha:**

- ▲ ▼ no card da pilha trocam o app da frente (etiqueta "▲ 2/3 ▼"); **OK abre o app da frente**. A escolha fica salva. No primeiro/ultimo app a tecla segue para fora da pilha (▲ para o topo, ▼ para o rodape); segurar a tecla nao "foge".
- Em app solto, ▲ ▼ vao para os outros elementos da tela (foto de perfil, chip de DNS).
- Segure OK na pilha: **Mover** · **Remover cards do grupo**. O segundo abre uma pasta com os apps; OK num app pede confirmacao ("Remover X do grupo?", foco em Cancelar). Uma pilha que fica com 1 app se desfaz.
- **Ocultar** (▲ no modo mover) tira o app da fila e o poe no card "Ocultos (n)", sempre o ultimo. La dentro, segure OK → "Mostrar na tela inicial".
- Empilhar congela a ordem manual (como mover). "Ordem automatica" (▼) so apaga a ordem manual (volta a ordenar por uso); pilhas e ocultos continuam como estao.

Onde fica: `Organizacao.kt` guarda tudo nas SharedPreferences `launcher`: `ordem` (lista de tokens: o pacote do app ou `pilha:<id>`), `pilhas` (JSON: `id`, `apps`, `frente`) e `ocultos` (conjunto de pacotes). `MainActivity` so desenha.

Testado no Chromecast simulando o "segurar" com `adb shell input keyevent --longpress KEYCODE_DPAD_CENTER` (mover, pre-pilha sobre app e sobre pilha, confirmar pilha, trocar a frente, remover do grupo com cancelar e confirmar). Nao testado com o controle remoto de verdade: o "segurar" depende do `repeatCount` que o controle envia.

## Device owner (ativo desde 31/10/2026)

O launcher é o **Device Owner** do aparelho (`adb shell dpm list-owners` mostra `br.paulo.tvlauncher/.AdminReceiver`). O receptor `AdminReceiver` está vazio por enquanto: ser owner já dá a base para políticas futuras (ex.: `DevicePolicyManager.setPermissionGrantState`, `setApplicationHidden`, `setLockTaskPackages`), que ainda não usamos.

Como foi ativado: o Android só aceita `set-device-owner` com **um único usuário** e **zero contas**. Foi preciso remover o usuário 10 (`new_user`, vazio) e as duas contas Google (Configurações > Contas e perfis > Remover conta). Depois: `adb shell dpm set-device-owner br.paulo.tvlauncher/.AdminReceiver`.

Consequências:
- As contas Google precisam ser adicionadas de novo para YouTube e Play Store. Apps com login próprio (Prime, 1-Stream) não são afetados.
- Para desfazer: `adb shell dpm remove-active-admin br.paulo.tvlauncher/.AdminReceiver` (só funciona porque o APK é `testOnly`; por isso o APK se instala com `adb install -r -t`).
- Verificado: reinstalar com `install -r -t` mantém o owner.
- Não verificado: se o owner impede criar outro usuário/perfil ou desinstalar o app.
