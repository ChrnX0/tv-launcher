# tv-launcher

Launcher próprio, leve e escuro para **Chromecast com Google TV** (Android TV 14). Mostra só os apps que você instalou
e a Play Store, com hora, data, clima, fase da lua e, no canto superior direito, a bolinha do perfil (troca de perfil, Configurações, DNS e Wi-Fi).

- ~800 KB de APK, sem bibliotecas externas (só o framework do Android).
- ~53 MB de RAM em uso (PSS) contra ~157 MB do Google TV Home (`launcherx`), medidos na mesma TV.

## Estrutura

| Arquivo | Para que serve |
|---|---|
| `app/src/main/kotlin/br/paulo/tvlauncher/Config.kt` | **Tudo que você quer mudar**: cidade, apps fixos, ordem, app aberto ao ligar |
| `MainActivity.kt` | A tela inteira (montada por código, sem XML) |
| `Rede.kt` | Nome da rede (SSID), sinal, velocidade do enlace e latência; decide a cor do ícone de Wi-Fi |
| `PopupPerfil.kt` | O menu da bolinha do perfil: trocar perfil, adicionar, Configurações, DNS, Wi-Fi |
| `Perfil.kt` | Contas Google da TV, perfil ativo do launcher e a imagem de cada perfil (foto ou inicial) |
| `SinalWifi.kt`, `IconeEclipse.kt` | Ícones desenhados em código: barras de Wi-Fi e sol coberto pela lua |
| `Lua.kt` | Fase da lua calculada no aparelho (sem internet) |
| `Uso.kt` | Quando cada app foi usado pela última vez (ordem por uso recente) |
| `BootReceiver.kt` | Abre um app quando a TV **reinicia** (não quando acorda do standby) |
| `restaurar-tv.ps1` | Refaz o setup numa TV nova ou depois de factory reset |
| `dist/tv-launcher.apk` | Build de debug pronto para instalar |

## Reorganizar os apps

Segure **OK** num app solto: ele entra direto no modo de mover (borda verde-agua, guia de teclas numa faixa na parte de baixo).

| Tecla | O que faz |
|---|---|
| ◀ ▶ | anda uma posicao (para passar por um card sao 2 toques: "em cima" dele e "do outro lado") |
| OK | confirma (se estiver "em cima" de outro card, forma a pilha) |
| Voltar | cancela, nada e gravado |
| ▲ | oculta o app (so card solto) |
| ▼ | apaga a ordem salva e volta ao automatico (fixos + uso recente) |

A ordem salva vence os `FIXOS` e o uso recente do `Config.kt`. Apps novos entram no fim.

## Topo da tela e menu do perfil

Topo, da esquerda para a direita: **hora e data** · **temperatura com o ícone do clima** (embaixo: descrição, cidade, mínima/máxima, chuva e fase da lua) · **bolinha do perfil**. Não há mais linha de baixo; no modo de mover aparece uma faixa de teclas na parte de baixo.

**Ícone do clima:** ☀️ céu limpo · sol coberto pela lua (desenhado em código, `IconeEclipse.kt`) para poucas nuvens e parcialmente nublado · ☁️ nublado · 🌫️ neblina · 🌧️ garoa e chuva · ⛈️ tempestade. De noite, céu limpo, poucas nuvens e parcialmente nublado mostram a lua na fase do dia; chuva, tempestade e neblina ficam iguais ao dia. Se a Open-Meteo informa precipitação agora e o código diz "limpo/nublado", vale o ícone de chuva.

**Bolinha do perfil** (OK abre o menu; ▲ a partir de um card solto chega nela):

- uma bolinha por conta Google da TV (anel verde-água = perfil ativo; a legenda mostra o nome); OK troca. O perfil ativo é **só do launcher**: muda a bolinha, não troca a conta do YouTube nem a do Gemini (isso é do Google TV);
- **+** abre a tela de adicionar conta Google (`Settings.ACTION_ADD_ACCOUNT`; se a TV não tiver essa tela, cai nas Configurações);
- **Configurações do sistema** e **DNS** (abre a tela de DNS);
- **Wi-Fi:** nome da rede e ícone de sinal (4 barras pelo sinal em dBm). A **cor** é a pior de três medidas: sinal, latência e velocidade do enlace. Os limites ficam em `Config.kt` (`WIFI_DBM_*`, `LATENCIA_*`, `LINK_*`) e o log `TvLauncher.Rede` mostra qual medida definiu a cor.

**Imagem de cada perfil:** a foto `perfil-<usuario>.jpg` (ou `.jpeg`/`.png`; `<usuario>` = parte do e-mail antes do `@`, em minúsculas) na pasta de arquivos do app; senão, a inicial da conta num círculo colorido (precisa de `GET_ACCOUNTS`). A primeira conta também aceita o nome antigo `perfil.jpg`.

```powershell
adb -s IP:PORTA push foto.jpg /sdcard/Android/data/br.paulo.tvlauncher/files/perfil-fulano.jpg
adb -s IP:PORTA shell pm grant br.paulo.tvlauncher android.permission.GET_ACCOUNTS   # so para a inicial
```

A foto nao vem sozinha da conta: le-la exige OAuth numa API da Google. As fotos (`perfil.*` e `perfil-*`) estao no `.gitignore`; o `.gitignore` antigo tinha `perfil.*`, que no Windows também ignorava o `Perfil.kt` (o arquivo nunca foi commitado até 31/10/2026).

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

## Gemini (assistente) com o launcher próprio

O botão de microfone do controle dispara a intent `ASSIST`. Com o `launcherx` desativado e o nosso launcher como Home, o Gemini abre como sobreposição por cima do launcher (testado na Sala em 31/10/2026 com `adb shell input keyevent KEYCODE_ASSIST`; **não testado** com o botão do controle de verdade nem depois de um reboot).

- A aba "Gemini" da tela inicial do Google TV vive no `launcherx`; o launcher próprio não tem essa aba.
- Na Sala o assistente antigo (em vez do Gemini) apareceu uma vez, até o `launcherx` ser reativado e desativado de novo. Não se sabe se o gatilho foi o `launcherx` ter rodado uma vez ou o usuário 11 (`new_user`) que o sistema recriou nessa hora. **Não remova o usuário 11** até isso ser esclarecido.
- Não funciona: fixar o launcher como Home pelo Device Owner (`addPersistentPreferredActivity`). O Android escolhe o Home pela maior `priority` antes de qualquer preferência: `launcherx/.home.HomeActivity` tem priority 2, o nosso 0, e um app comum não passa de 0 (verificado com `dumpsys package`). O jeito que funciona continua sendo desativar o pacote `launcherx`.

## Testado e não testado

- **Reboot com o Home trocado: testado** (30/10/2026). A TV voltou em ~146 s; a Depuração sem fio persistiu sem novo pareamento (a porta muda a cada boot: ache por `adb mdns services`); o Home abriu o launcher; `setupwraith` e `launcherx` seguiram desativados; 0 lançamentos do `RecoveryActivity`; 0 crashes.
- **BootReceiver:** depois do reboot o OneStream abriu sozinho, mas o próprio OneStream também declara um receiver de `BOOT_COMPLETED`, então não dá para atribuir com certeza. O `ABRIR_AO_LIGAR` do `Config.kt` agora vem vazio, para não haver dois lugares controlando isso; se o OneStream parar de abrir sozinho no próximo reboot, a opção dele não estava ativa.
- **Segurar OK para mover apps:** testado com `input keyevent --longpress`, não com o controle de verdade.
- **`restaurar-tv.ps1`:** testado em `-DryRun` e nos comandos equivalentes feitos à mão, não numa TV zerada.
- **Topo novo e menu do perfil: testados** na Sala (31/10/2026) por screenshot e `input keyevent`: layout, ▲ do card até a bolinha, abrir o menu, trocar de perfil e voltar, linhas de Configurações/DNS, ícone de Wi-Fi verde (sinal -60 dBm, enlace 292 Mbps, latência 14 ms), faixa de teclas do modo mover.
- **Não testado:** o ícone da lua à noite e o de chuva (só o sol coberto pela lua apareceu, pelo clima do momento); o **+** de adicionar conta; o menu com o controle de verdade.

## Shizuku, aprovação automática e DNS

- **Shizuku:** o launcher é cliente dele (`Privilegio.kt`). Com o app Shizuku rodando e este app autorizado, o launcher executa comandos como `shell` (uid 2000), sem o computador. O servidor do Shizuku morre ao reiniciar a TV (iniciar de novo; "Iniciar na inicialização" não foi testado).
- **Aprovação automática:** `AutorizaShizukuService` (serviço de acessibilidade) aperta "Permitir o tempo todo" no pedido de QUALQUER app ao Shizuku, e cada app aprovado ganha shell. Desligue com `Config.SHIZUKU_AUTORIZAR_TUDO = false`. Detalhes que custaram caro: os botões usam os ids do Android (`android:id/button1`), e no Android 14 o XML precisa de `isAccessibilityTool="true"`, senão o diálogo chega vazio ao serviço.
- **Religar o serviço:** o Android o desliga a cada `force-stop` do app (inclusive o que o Shizuku faz ao revogar um app). O próprio launcher o religa ao abrir (`Privilegio.religarAcessibilidade`), porque tem `WRITE_SECURE_SETTINGS`. Basta apertar HOME.
- **DNS (linha "DNS: …" no menu do perfil):** escolhe o DNS privado (DoT) da TV. Precisa uma vez de `adb shell pm grant br.paulo.tvlauncher android.permission.WRITE_SECURE_SETTINGS`, que continua valendo após reinstalar. O Android só aceita **nome** de servidor, nunca IP. Servidor fixo que não responde deixa a TV sem resolver nomes (não volta ao normal sozinho): na tela, escolha "Automático", ou `adb shell settings put global private_dns_mode opportunistic`.

## Pilhas, ocultar e mover cards

**Formar uma pilha:** segure OK num app e ande com ◀ ▶. Ao passar "em cima" de outro card os dois viram uma pilha de previa ("A B+C D E") e a faixa de baixo diz "OK forma a pilha". OK confirma; se continuar andando, o card segue ("A C B D E"). Voltar cancela sem gravar. O app que chegou fica na frente. Uma pilha arrastada so ocupa posicoes ao lado (nao da para empilhar uma pilha dentro de outra); um app solto pode entrar numa pilha que ja existe.

**Usar uma pilha:**

- ▲ ▼ no card da pilha trocam o app da frente e dao a volta (depois do ultimo vem o primeiro); **OK abre o app da frente**. A escolha fica salva. Um passo por toque (segurar a tecla nao gira). Por isso, numa pilha, ▲ ▼ nunca saem para o topo/rodape: para chegar la, va para um card solto.
- Em app solto, ▲ ▼ vao para os outros elementos da tela (a bolinha do perfil, no topo).
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
- Verificado: criar outro usuário Android (o 11, `new_user`) depois de ativar o owner não o desfez (`dpm list-owners` seguiu igual). Não verificado: se o owner impede desinstalar o app.
