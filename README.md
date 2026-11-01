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
| `Grade.kt` | A grade da tela: margens de 48 dp, 12 colunas de 50 dp com vãos de 24 dp, alturas fixas. Topo: hora e data (col. 1-4), ícone, temperatura, máxima/mínima e chuva (5-8), lua (9-11), bolinha (12); cada card ocupa 3 colunas |
| `Rede.kt` | Nome da rede (SSID), sinal, velocidade do enlace e latência; decide a cor do ícone de Wi-Fi |
| `PopupPerfil.kt` | O menu da bolinha do perfil: trocar perfil, adicionar, Configurações, DNS, aparência, teste de velocidade, Wi-Fi |
| `Tema.kt`, `Fundos.kt`, `Astronomia.kt` | Aparência: 3 temas (fonte, cor do foco e fundo desenhado em código). `Astronomia.kt` calcula a altura do sol e o nascer/pôr no aparelho, sem internet |
| `Fundos.kt` (desempenho e contraste) | Cada fundo e uma **camada de hardware** (`criarFundo`): a GPU guarda o fundo pronto e so o cola a cada quadro; ele so e redesenhado 1x por minuto ou com clima novo. Medido na TV da Sala (12 teclas de navegacao, mediana por quadro): Ceu 69 ms → 22 ms, Galeria 32 → 22 ms, Escuro 24 → 22 ms. Veu escuro no topo (`veuDoTopo`, ~70% no alto) e texto mais claro nos temas com fundo desenhado garantem o contraste da hora, clima, umidade e chuva |
| `Velocidade.kt` | Teste de velocidade: baixa 25 MB de speed.cloudflare.com e mostra os Mbps |
| `app/src/main/assets/fonts/` | Fontes dos temas (Instrument Serif, Cormorant, Figtree, IBM Plex Mono), baixadas de github.com/google/fonts (licença OFL) |
| `Perfil.kt` | Contas Google da TV, perfil ativo do launcher e a imagem de cada perfil (foto ou inicial) |
| `Eclipse.kt` | Dos horários da tabela de eclipses (`Config.kt`) calcula onde a lua está em relação ao sol e quanto dele ela cobre, no minuto |
| `SinalWifi.kt`, `IconeEclipse.kt` | Ícones desenhados em código: barras de Wi-Fi e sol coberto pela lua (este só aparece durante um eclipse solar real) |
| `Localizacao.kt` | Onde a TV está: Wi-Fi do Android (±56 m na Sala) > IP (ipwho.is) > coordenadas do `Config.kt`. O clima usa: tempo **agora** (temperatura, umidade, chuva/garoa/trovoada/neblina) do METAR do aeroporto mais perto (SBRP, 6 km), e previsão (mínima/máxima, chance de chuva, céu) da Open-Meteo no ponto exato. Atualiza a cada 5 min |
| `Despertador.kt`, `AlarmesActivity.kt`, `AlarmeTocandoActivity.kt` | Despertador: OK no relógio abre a lista/editor; na hora, acorda o Chromecast (testado: em standby acordou e mandou o comando HDMI-CEC de ligar a TV) e toca até Parar/Soneca (Voltar = soneca; para sozinho em 10 min). A TV não tem som de alarme: toca um bipe. É o app de despertador do Android (`SET_ALARM`), então pedidos de voz podem cair nele (não testado por voz) |
| `PrevisaoSemana.kt` | OK no clima: previsão de 7 dias |
| `CalendarioActivity.kt` | OK na data: agenda do perfil ativo (mês com os dias marcados + próximos 30 dias). Lê a agenda do Android, preenchida pelo sincronizador da agenda Google (religado em 31/10/2026 com `cmd package install-existing --user 0 com.google.android.syncadapters.calendar`). Precisa de `pm grant br.paulo.tvlauncher android.permission.READ_CALENDAR` |
| `BotoesControle.kt`, `EscolherAppActivity.kt` | Botões Netflix e YouTube do controle: toque abre o app escolhido (padrão: Netflix → 1-Stream, YouTube → YouTube); segurar 3 s abre a lista para trocar. O serviço de acessibilidade (`AutorizaShizukuService.onKeyEvent`) pega a tecla antes do sistema, então o Netflix não abre mais por esse botão. Códigos medidos no controle da Sala: Netflix = `BUTTON_4`, YouTube = `BUTTON_3` (o YouTube não fazia nada desde que o Home do Google foi desligado). Testado em 01/11/2026 |
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

**Ícone do clima:** ☀️ céu limpo · 🌤️ poucas nuvens · ⛅ parcialmente nublado · ☁️ nublado · 🌫️ neblina · 🌧️ garoa e chuva · ⛈️ tempestade. De noite, céu limpo, poucas nuvens e parcialmente nublado mostram a lua na fase do dia; chuva, tempestade e neblina ficam iguais ao dia. Se a Open-Meteo informa precipitação agora e o código diz "limpo/nublado", vale o ícone de chuva.

**Eclipse solar (só quando existe de verdade):** dentro dos horários da tabela `ECLIPSES_SOLARES` (`Config.kt`) e com o céu visível, o ícone do clima vira o sol coberto pela lua, com a cobertura do minuto (atualiza a cada 60 s), e a linha da fase da lua mostra "Eclipse solar parcial · NN%" (NN = % da **área** do sol coberta). Com nuvem ou chuva (código do clima acima de 2) o ícone continua o do clima e a linha ganha "(encoberto)". Fora desses horários o ícone nunca aparece.

- **Fonte da tabela:** [timeanddate.com](https://www.timeanddate.com/eclipse/in/brazil/ribeirao-preto), lida em 31/10/2026. Os 4 eclipses visíveis de Ribeirão Preto até 2034 são todos parciais: 06/02/2027 (máximo 13:25 BRT, magnitude 0,778), 26/01/2028 (11:32, 0,330), 09/05/2032 (09:18, 0,055) e 12/09/2034 (14:26, 0,694). **A tabela termina em 2034**; depois disso (ou em outra cidade) é preciso trocar/acrescentar linhas em `ECLIPSES_SOLARES`: 1º contato, máximo e último contato em **UTC** (Brasília + 3 h) e a magnitude.
- **A conta** (`Eclipse.kt`): sol e lua são dois discos e a lua passa em linha reta. A magnitude dá a distância mínima entre os centros; os 3 horários dão a velocidade; a cobertura é a área da interseção dos discos. A razão entre os tamanhos aparentes da lua e do sol não vem na tabela: usei 1,0. Para razões de 0,90 a 1,03 o percentual mudaria no máximo 3,1 pontos (calculado em Python).
- **Não calculado:** de que lado a lua entra. O desenho a faz passar da esquerda para a direita, por baixo do sol; o lado real depende de onde o sol está no céu e não foi calculado.

**Bolinha do perfil** (OK abre o menu; ▲ a partir de um card solto chega nela):

- uma bolinha por conta Google da TV (anel verde-água = perfil ativo; a legenda mostra o nome); OK troca. O perfil ativo é **só do launcher**: muda a bolinha, não troca a conta do YouTube nem a do Gemini (isso é do Google TV);
- **+** abre a tela de adicionar conta Google (`Settings.ACTION_ADD_ACCOUNT`; se a TV não tiver essa tela, cai nas Configurações);
- **Configurações do sistema** e **DNS** (abre a tela de DNS);
- **Aparência:** OK passa para o próximo tema e a tela é remontada. Fica salvo (`tema` nas preferências do app):
  - **Céu ao vivo** (padrão): o fundo é o céu de agora, desenhado em código. Cor pela altura do sol, estrelas à noite, nuvens/chuva/neblina pelo clima medido, o arco do sol (nascer → pôr) e a lua na fase e posição aproximadas. Atualiza a cada minuto e a cada leitura do clima;
  - **Galeria:** uma pintura de domínio público do Metropolitan Museum por dia (28 obras, títulos em português), com etiqueta de museu no canto. A imagem é baixada uma vez e guardada no aparelho;
  - **Escuro:** grafite azulado com dois brilhos suaves (frio atrás da hora, quente no canto de baixo), vinheta e véu embaixo, desenhado em código (`FundoEscuro`). (O tema Astrolábio foi removido: grande demais para ler o resto da tela.)
  - **Topo:** hora e data à esquerda; uma barra vertical; à direita dela, na altura da hora, o ícone do tempo + temperatura + máxima/mínima empilhadas, e na altura da data a linha de chuva. Descrição do tempo, umidade e a coluna da lua foram removidas a pedido (a lua só aparece como ícone, de noite). Os ícones do clima são imagens (`IconesClima.kt`, PNG em `assets/clima/`, conjunto Meteocons de Bas Milius, licença MIT): ~45 ícones para todos os códigos de clima da Open-Meteo (céu, garoa, chuva, neve, granizo, trovoada, neblina), dia e noite, e as 8 fases da lua;
- **Testar velocidade da internet:** baixa 25 MB e mostra os Mbps na própria linha (medido na Sala em 01/11/2026: 96 Mbps). Gasta os 25 MB a cada teste;
- **Wi-Fi:** nome da rede, ícone de sinal (4 barras pelo sinal em dBm) e velocidade do enlace em Mbps; sem rede conectada vira o atalho **Conectar ao Wi-Fi**. A **cor** é a pior de três medidas: sinal, latência e velocidade do enlace. Os limites ficam em `Config.kt` (`WIFI_DBM_*`, `LATENCIA_*`, `LINK_*`) e o log `TvLauncher.Rede` mostra qual medida definiu a cor.

**Imagem de cada perfil:** a foto `perfil-<usuario>.jpg` (ou `.jpeg`/`.png`; `<usuario>` = parte do e-mail antes do `@`, em minúsculas) na pasta de arquivos do app; senão, a inicial da conta num círculo colorido (precisa de `GET_ACCOUNTS`). A primeira conta também aceita o nome antigo `perfil.jpg`.

```powershell
adb -s IP:PORTA push foto.jpg /sdcard/Android/data/br.paulo.tvlauncher/files/perfil-fulano.jpg
adb -s IP:PORTA shell pm grant br.paulo.tvlauncher android.permission.GET_ACCOUNTS   # so para a inicial
```

A foto nao vem sozinha da conta: le-la exige OAuth numa API da Google. As fotos (`perfil.*` e `perfil-*`) estao no `.gitignore`; o `.gitignore` antigo tinha `perfil.*`, que no Windows também ignorava o `Perfil.kt` (o arquivo nunca foi commitado até 31/10/2026).

## Mudar para os EUA

Em `Config.kt`: `CIDADE`, `LATITUDE`, `LONGITUDE`, `USAR_FAHRENHEIT = true` e a tabela `ECLIPSES_SOLARES` (os horários são de Ribeirão Preto; apague as linhas se não tiver a tabela da nova cidade, assim nunca aparece eclipse). Depois recompile e reinstale.

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
- **Eclipse solar:** testado no aparelho (31/10/2026) com um relógio falso, depois removido do código: 7 instantes dentro de eclipses (72%, 35%, 34%, 22%, 1,5%, 62%, 0,1%) e 1 fora; a porcentagem do app bateu com a do modelo em Python nos 7, e fora do eclipse o ícone voltou ao do clima. Não testado: um eclipse de verdade (o primeiro é 06/02/2027) e o caso de céu encoberto durante um eclipse (só lido no código, o clima de hoje estava com poucas nuvens).
- **Topo novo e menu do perfil: testados** na Sala (31/10/2026) por screenshot e `input keyevent`: layout, ▲ do card até a bolinha, abrir o menu, trocar de perfil e voltar, linhas de Configurações/DNS, ícone de Wi-Fi verde (sinal -60 dBm, enlace 292 Mbps, latência 14 ms), faixa de teclas do modo mover.
- **Não testado:** o ícone da lua à noite e o de chuva (só apareceu o clima do momento, "poucas nuvens"); o **+** de adicionar conta; o menu com o controle de verdade.

## Shizuku, aprovação automática e DNS

- **Shizuku:** o launcher é cliente dele (`Privilegio.kt`). Com o app Shizuku rodando e este app autorizado, o launcher executa comandos como `shell` (uid 2000), sem o computador. O servidor do Shizuku morre ao reiniciar a TV (iniciar de novo; "Iniciar na inicialização" não foi testado).
- **Aprovação automática:** `AutorizaShizukuService` (serviço de acessibilidade) aperta "Permitir o tempo todo" no pedido de QUALQUER app ao Shizuku, e cada app aprovado ganha shell. Desligue com `Config.SHIZUKU_AUTORIZAR_TUDO = false`. Detalhes que custaram caro: os botões usam os ids do Android (`android:id/button1`), e no Android 14 o XML precisa de `isAccessibilityTool="true"`, senão o diálogo chega vazio ao serviço.
- **Religar o serviço:** o Android o desliga a cada `force-stop` do app (inclusive o que o Shizuku faz ao revogar um app). O próprio launcher o religa ao abrir (`Privilegio.religarAcessibilidade`), porque tem `WRITE_SECURE_SETTINGS`. Basta apertar HOME.
- **Depuração sem fio sempre ligada:** em 31/10/2026 ela voltou **desligada** depois de um reinício (em 30/10 tinha persistido), e sem ela o computador não conecta e o Shizuku não sobe. Com `Config.DEPURACAO_SEM_FIO_SEMPRE_LIGADA = true` o launcher escreve `adb_wifi_enabled=1` no boot (`BootReceiver`) e toda vez que abre, se estiver desligada (precisa de `WRITE_SECURE_SETTINGS`, já dada). Testado sem reiniciar: desliguei por adb, chamei o `BootReceiver` (`am broadcast -n br.paulo.tvlauncher/.BootReceiver -a br.paulo.tvlauncher.TESTE_BOOT`) e o computador reconectou em 8 s (log `TvLauncher.Adb`). Num reinício real (31/10, 15:50) isso **não bastou**: o launcher abriu às 15:51:16.1 com ela ligada e o Android a desligou 0,6 s depois ("Network disconnected. Disabling adbwifi", o Wi-Fi oscila no boot). Por isso agora o launcher também **vigia** o ajuste e o Wi-Fi (`Privilegio.vigiarDepuracaoSemFio`) e religa 3 s depois de qualquer desligamento. Testado sem reiniciar: `settings put global adb_wifi_enabled 0` às 16:05:54, religada às 16:05:57.
- **Shizuku no boot:** a versão instalada (13.7.0, fork thedjchi) tem "Iniciar na inicialização" e "Watchdog" nas configurações dele; precisa de `WRITE_SECURE_SETTINGS` (já dada) e sem otimização de bateria (já está na lista de exceções). No boot de 31/10 ele tentou 3 vezes e falhou no aperto de mão TLS com o adb ("Worker result RETRY"): a chave dele não estava pareada. Depois de parear o próprio Shizuku (no app dele, com o código das Opções do desenvolvedor), o reinício de 31/10 às 16:10 subiu tudo sozinho: o launcher religou a depuração 41 s após o boot e o Shizuku iniciou 2 s depois ("Worker result SUCCESS"), sem ninguém mexer na TV. Iniciar à mão por adb: `adb shell <pasta do app>/lib/arm/libshizuku.so` (a pasta sai de `pm path moe.shizuku.privileged.api`).
- **DNS (linha "DNS: …" no menu do perfil):** escolhe o DNS privado (DoT) da TV. Precisa uma vez de `adb shell pm grant br.paulo.tvlauncher android.permission.WRITE_SECURE_SETTINGS`, que continua valendo após reinstalar. O Android só aceita **nome** de servidor, nunca IP. Servidor fixo que não responde deixa a TV sem resolver nomes (não volta ao normal sozinho): na tela, escolha "Automático", ou `adb shell settings put global private_dns_mode opportunistic`.

## Pilhas, ocultar e mover cards

**Formar uma pilha:** segure OK num app e ande com ◀ ▶. Ao passar "em cima" de outro card os dois viram uma pilha de previa ("A B+C D E") e a faixa de baixo diz "OK forma a pilha". OK confirma; se continuar andando, o card segue ("A C B D E"). Voltar cancela sem gravar. O app que chegou fica na frente. Uma pilha arrastada so ocupa posicoes ao lado (nao da para empilhar uma pilha dentro de outra); um app solto pode entrar numa pilha que ja existe.

**Usar uma pilha:**

- ▲ ▼ no card da pilha trocam o app da frente e dao a volta (depois do ultimo vem o primeiro); **OK abre o app da frente**. A escolha fica salva. Um passo por toque (segurar a tecla nao gira). Um selo no canto da carta da frente (`IndicadorPilha.kt`: "^ v" e "2/3") mostra que ali ha varios apps e qual esta na frente. **Segurar ▲ por 1 s** (`Config.PILHA_SUBIR_MS`) sai da pilha para o topo (hora, clima, bolinha); soltar antes disso so troca o app. E um cronometro de verdade (nao conta repeticoes da tecla), entao nao depende de o controle repetir a tecla.
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
