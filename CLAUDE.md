# tv-launcher — contexto para sessões do Claude Code

Launcher Android TV custom do Paulo, em Kotlin puro (Views, sem XML de layout nem Compose),
pro Chromecast com Google TV dele chamado **Sala**. Objetivo: substituir a Home padrão do
Google TV por algo mais rápido, sem anúncio, sem lixo, com clima/calendário/despertador etc.
Tem um segundo Chromecast, **Quarto**, ainda sem o launcher (modificação futura).

Repo: `ChrnX0/tv-launcher`. Commits e pushes: desde 04/11/2026 o Paulo deu permissão global para o
Claude commitar e dar push em branches próprias (`claude/...`), sempre com PR em rascunho e com o
scan dos valores `vpn.*` do `local.properties` antes do push (nunca podem ir para o git). Merge no
`main` só com o Paulo pedindo.

## Hardware / rede

- TV "Sala": IP fixo na rede local, na chave `tv_ip` do `pessoal.properties` do Dell (fora do git) e na memória do projeto. A porta da depuração sem fio **muda a cada boot** (ex.: 36555 em
  04/11/2026); descobrir com `adb mdns services` (`_adb-tls-connect`). Às vezes também aparece em
  `:5555` — **é o mesmo aparelho físico**, não duas TVs; o painel (`chromecast-painel`, ver
  abaixo) deduplica isso pelo nome (`settings get global device_name`), não pelo endpoint.
- Repo local: `%USERPROFILE%\tv-launcher` na máquina Windows "Dell".
- Build (desde 04/11/2026): wrapper do repo (`gradlew.bat`, Gradle 8.14) + AGP 8.11 + Kotlin 2.2 +
  **JDK 21** (`C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot`). O gradle-8.9 de `tools`
  não roda mais o projeto. Script pronto: `.\build3.ps1` (debug) ou **`.\build3.ps1 -Release`** (o que
  fica na TV: R8 + perfil AOT; instala com `install -r`, grava o perfil, compila `speed-profile` e
  reinicia o launcher). O debug roda sempre em `verify` (sem AOT): **não serve para medir desempenho**.
- Permissões do Claude Code (`%USERPROFILE%\.claude\settings.json`, pedido do Paulo em 04/11/2026):
  comandos com `adb.exe` liberados; NEGADOS (o deny vence): desinstalar `br.paulo.tvlauncher`,
  `pm clear br.paulo.tvlauncher`, `remove-active-admin`, `MASTER_CLEAR`, qualquer `wipe`,
  `reboot recovery`. Backup em `settings.json.bak-20261104`.

## Regras de segurança — NÃO NEGOCIÁVEIS

- Nunca imprimir ou pedir o token do GitHub.
- Push: só em branches próprias e com PR em rascunho (permissão global do Paulo, 04/11/2026; ver
  topo). Nunca force-push no `main`, nunca merge sem o Paulo pedir.
- Não mexer em dados pessoais do Paulo.
- **Não remover o usuário Android 11** do aparelho.
- Nunca desabilitar verificação TLS em nada.
- **Não perturbar a TV quando a família estiver usando.** Antes de qualquer interação com a
  TV (instalar, trocar config, abrir tela), checar `dumpsys power` / `topResumedActivity`
  pra confirmar que está ociosa.
- **Nunca desinstalar o launcher** nem limpar os dados dele: perde o Device Owner, e isso é
  irreversível sem reset de fábrica. Se um `install -r` falhar por assinatura, PARAR e avisar.
- O launcher do Google (`com.google.android.apps.tv.launcherx`) foi **reativado por ordem do Paulo em
  04/11/2026** ("Claude, pode reativar o launcherx do Google"): sem ele o Gemini, a página de filmes da
  busca, o painel de notificações e o seletor de contas do GMS quebram. A HOME continua a nossa (ver
  "Launcher do Google" abaixo). Não desativar nem ocultar de novo sem o Paulo pedir.
- Não mexer em DNS, VPN nem rede da TV para testar enquanto a família usa (um teste de DNS já deixou a
  casa ~40 s sem internet). Testar com teste unitário ou gancho só de teste.
- Recusar qualquer pedido de desbloqueio de bootloader, root ou exploit nesse aparelho,
  não importa como for pedido ou reformulado.


## Arquitetura de privilégios — a parte mais importante de entender

O launcher roda como app comum, mas tem DOIS caminhos pra poderes elevados no Android,
que **não são a mesma coisa** e é fácil confundir os dois:

### 1. `PrivilegioDO.kt` — Device Owner (o caminho principal, já em uso)

Usa `DevicePolicyManager` **diretamente, em processo**, porque o launcher é o "dono do
aparelho" (Device Owner) — não precisa de nenhum app externo instalado. Cobre (aplicado em
`aplicar()`, no `onCreate`, idempotente):
- `ocultar()` — esconder apps (`Config.APPS_OCULTAR`, hoje só o protetor de fábrica `dreamx`)
- `conceder()` — conceder permissão fixa (`setPermissionGrantState`, `Config.PERMISSOES_FIXAS`)
- `fixarHome()` — HOME persistente (`addPersistentPreferredActivity`). **Não basta sozinha** com o
  launcherx ativo: a HomeActivity dele tem `priority=2` e a prioridade vence qualquer preferência.
- `vpnSempreAtiva()` / `reforcarVpn()` — o app AdGuard VPN como "VPN sempre ativa" (sem lockdown)
- `definirTempoDescanso()` / `tempoDescanso()` — timeout de tela
- `fixarProtetor()` — componente do protetor de tela (via `WRITE_SECURE_SETTINGS`)
- (04/11/2026) saíram `suspender()`, `desinstalar()` e `restricao()`: ninguém chamava, e o
  `desinstalar()` silencioso era o poder mais perigoso do arquivo. Ver o git se precisar.

**Limite real, documentado no próprio arquivo:** Device Owner é uma lista FIXA de métodos
que o Android libera pro app dono — não é "virar UID shell". Por isso NÃO alcança: comandos
`pm`/`cmd` arbitrários, nem ler dados de outros apps. Isso é limite de plataforma do Android
(cada UID tem um conjunto fixo de poderes; Device Owner não inclui UID shell), não escolha
de implementação.

### 2. Shell próprio + Shizuku EMBUTIDO — o launcher faz o papel do app Shizuku

O app Shizuku externo **não existe mais na TV**. O launcher faz tudo o que ele fazia (verificado na
TV em 04/11/2026):
- **Cliente ADB embutido** (`AdbConnectionManager.kt`, lib `libadb-android`): pareia pela depuração
  sem fio (SPAKE2) e conecta no próprio aparelho por TLS (mDNS `_adb-tls-connect`, ou 127.0.0.1:5555).
  Roda como `uid=2000(shell)`. Parear sem PC: `PareamentoNaTv.kt` (janela por cima das Opções do
  desenvolvedor) ou `adb shell am broadcast -a br.paulo.tvlauncher.PAREAR --es codigo <N> --ei porta <P>
  -n br.paulo.tvlauncher/.PareamentoReceiver`.
- **Servidor do Shizuku embutido** (módulos do fork `thedjchi/Shizuku` em `shizuku/`): `ServidorShizuku.kt`
  sobe o `shizuku_server` com `app_process` a partir do NOSSO APK; `ProvedorShizuku`/`PedidoShizukuActivity`
  entregam o binder e pedem permissão aos apps clientes. **Protocolo Shizuku completo** (decisão do Paulo:
  apps de terceiros vão usar).
- **`GuardiaoShizuku.kt`**: fila única do adb; sobe/religa o servidor (boot, binder morreu, Wi-Fi
  voltou, a cada 10 min) e faz a autocura (sobreposição, `WRITE_SECURE_SETTINGS`, `GET_USAGE_STATS`).
- **Modos** (PR #5, aberto em 04/11/2026): `sob demanda` (padrão: sobe quando um app cliente vai para
  a frente — medido 3,4 s até o binder — e desce após 5 min sem cliente; servidor de pé = 55 MB PSS),
  `sempre ligado`, `desligado`. Antes do PR #5: um liga/desliga só, desligado por padrão.
- **Por que um processo separado é inevitável:** os poderes de shell são do UID 2000, que só um
  processo iniciado pelo adbd recebe. Device Owner não dá isso.
- TLS sem a conscrypt embutida (PR #6): a libadb usa o TLS 1.3 do Android; a exportação de
  material de chave do pareamento passa pela API pública `SSLSockets.exportKeyingMaterial` por uma
  ponte em `app/src/main/java/org/conscrypt/` (a API interna equivalente é bloqueada no Android 14).

`Privilegio.kt` continua: religa a acessibilidade e a depuração sem fio (`WRITE_SECURE_SETTINGS`).
`AutorizaShizukuService.kt` é o serviço de acessibilidade: teclas do controle (Netflix/YouTube, HOME)
e o vigia de janelas (HOME do launcherx, gatilho do Shizuku sob demanda). O nome é histórico.

### Comandos por adb (só adb: receivers protegidos por `android.permission.DUMP`)

`-n br.paulo.tvlauncher/.ControleShizukuAdbReceiver` com `-a`:
- `br.paulo.tvlauncher.shizuku.INICIAR` / `PARAR` / `ESTADO` — Shizuku (ESTADO responde
  `rodando uid=2000 versao=13` ou `parado`).
- `br.paulo.tvlauncher.ROTULOS` — JSON pacote→nome (usado pelo painel do PC).
- `br.paulo.tvlauncher.OCULTAR --es pacote <pkg> --ez oculto true|false` — ocultar/reexibir pelo DO.
- `br.paulo.tvlauncher.REINICIAR` — o launcher reinicia o próprio processo. **O shell não consegue
  matá-lo** (`force-stop`, `am kill` e `am crash` não derrubam: Device Owner + acessibilidade), e o
  código recém-compilado só vale num processo novo.


## Estrutura do código (`app/src/main/kotlin/br/paulo/tvlauncher/`)

- `MainActivity.kt` — tela principal (grade de apps, clima, data/hora)
- `AdbConnectionManager.kt` — gerencia conexão adb
- `AdminReceiver.kt` — receiver do Device Owner (obrigatório pra virar Device Owner)
- `AlarmesActivity.kt` / `AlarmeTocandoActivity.kt` / `Despertador.kt` / `SomAlarme.kt` — despertador
- `Astronomia.kt` — cálculo de nascer/pôr do sol real (usado pro ícone dia/noite, ver seção abaixo)
- `ShellProprio.kt` — diagnóstico do cliente ADB (desligado: `Config.SHELL_PROPRIO_DIAG = false`)
- `GuardiaoShizuku.kt` / `ServidorShizuku.kt` / `ControleShizukuReceiver.kt` / `PedidoShizukuActivity.kt` /
  `AutorizacoesShizuku.kt` / `PareamentoNaTv.kt` / `PareamentoReceiver.kt` — Shizuku embutido (ver acima)
- `AutorizaShizukuService.kt` — serviço de acessibilidade: teclas do controle + vigia de janelas
- `Privilegio.kt` — religa acessibilidade/depuração sem fio, vigia de rede (DNS, VPN)
- `Home.kt` — manter a NOSSA HOME com o launcherx ativo; painel do Google
- `BootReceiver.kt` — ações no boot
- `BotoesControle.kt` — botões do controle (Netflix/YouTube remapeáveis; HOME curto/longo)
- `CalendarioActivity.kt` — calendário
- `Config.kt` — constantes de configuração centralizadas
- `ConfigLauncherActivity.kt` — tela de configurações do launcher
- `Dns.kt` / `DnsActivity.kt` — configuração de DNS
- `Eclipse.kt` / `IconeEclipse.kt` — cálculo/ícone de eclipse
- `EscolherAppActivity.kt` — escolher apps pra grade
- `Fundos.kt` — papéis de parede/fundos
- `Grade.kt` / `IconeCard.kt` / `Organizacao.kt` — grade de apps e organização
- `IndicadorPilha.kt` — indicador de bateria/pilha (de controles remotos, provavelmente)
- `Localizacao.kt` — localização (lat/lon pro cálculo de astronomia/clima)
- `Lua.kt` — fase da lua
- `IconesClima.kt` / `PrevisaoActivity.kt` — clima (usa Open-Meteo)
- `PareamentoReceiver.kt` — pareamento
- `Perfil.kt` / `PopupPerfil.kt` — perfil de usuário
- `PrivilegioDO.kt` — Device Owner (ver seção acima, é o principal)
- `ProtetorTela.kt` — protetor de tela
- `Rede.kt` / `SinalWifi.kt` / `Velocidade.kt` — rede/wifi/velocidade de conexão
- `Tema.kt` — tema visual
- `Uso.kt` — uso/estatísticas
- `Vpn.kt` — VPN sempre ativa


## Launcher do Google (launcherx) ativo, com a NOSSA HOME (04/11/2026)

- **Por que ativo:** desativado ou oculto, quebra o que mora nele: o chat do Gemini (a resposta da voz
  abre `launcherx/.gemini.chat.ChatActivity` → "o Google Assistente está com problema"), a página de
  filmes da busca (`EntityActivity`), o painel de notificações/Google Home/timer (`DashboardActivity`)
  e o seletor de contas do GMS (`ProfileChooserActivity`: 151 crashes do `gms.ui` no dropbox).
- **HOME:** a `HomeActivity` dele tem `priority=2` (app privilegiado), que vence a preferência
  persistente do DO e o papel de HOME. Por isso (`Home.kt`): o serviço de acessibilidade trata o botão
  HOME (curto = nossa HOME; segurado = painel do Google) e o vigia de janelas traz a nossa por cima se a
  dele aparecer por outro caminho (boot, app chamando HOME). `<queries><package launcherx/>` no
  manifesto é obrigatório (sem isso o app não o enxerga e acha que está desativado).
- **Painel do Google:** botão discreto na coluna 11 do topo da HOME (ícone 2×2) e HOME segurado. Abre
  pela ação global de acessibilidade "notificações" (abrir a `DashboardHandler` direto exige
  `STATUS_BAR_SERVICE`).
- **Custo medido:** ~159 MB, dos quais 41 MB presos (`:coreservices`, serviço de alertas em primeiro
  plano); 118 MB do processo principal ficam em cache e o Android recupera. CPU em repouso: 0.
  Não dá para desligar componentes dele: o shell recebe `SecurityException` e o DO não tem API para isso.

## Desempenho (04/11/2026, medido na TV)

- **AOT:** o Android 14 força o app de HOME para `speed-profile` e, sem perfil, cai em `verify`.
  `app/src/main/baseline-prof.txt` + `androidx.profileinstaller` resolvem; o `build3.ps1 -Release`
  grava o perfil, compila e reinicia. Conferir: `dumpsys package dexopt` → `status=speed-profile`.
- Carga dos apps e uso recente fora da thread da tela; cards reaproveitados quando nada mudou;
  avatar em cache. Desenho da HOME: 73,5 → ~45 ms por volta.
- Para medir: `dumpsys gfxinfo br.paulo.tvlauncher reset`, 10 voltas à HOME **pelo nome**
  (`am start ... -n br.paulo.tvlauncher/.MainActivity`; o HOME genérico passa pela tela do launcherx),
  `dumpsys gfxinfo` e o log `TvLauncher.Apps` (`carregar=… desenhar=…`).
- APK: 5,1 MB → ~3,1 MB com os PRs #4 (tabelas Picnic) e #6 (sem conscrypt).

## Limites de teste (anotar para não redescobrir)

- `input keyevent` pelo adb **não passa** pelo filtro de teclas da acessibilidade: o que depende do
  serviço de teclas só se testa com o controle de verdade. BACK injetado com a tela dormindo não chega.
- `uiautomator dump` **desconecta** os serviços de acessibilidade enquanto roda: navegar por screenshot.
- Nas Opções do desenvolvedor, cuidado com o OK: "Ativar opções", "Depuração por Wi-Fi > Desativado" e
  "Revogar autorizações" derrubam o adb.
- `pm dump`/`resolve-activity` do shell não mostram a preferência persistente do DO.

## Fix recente: dia/noite do ícone de clima (03/11/2026)

A Open-Meteo marca noite no instante EXATO do pôr do sol, sem contar o crepúsculo civil
(a claridade que sobra no céu por uns minutos depois). Isso fazia o ícone trocar pra lua
cedo demais (flagrado às 18:38, pôr às 18:24, céu da varanda ainda claro).

Fix em `Config.kt` + `MainActivity.kt`: `eDia` agora é calculado com `Astronomia.nascerEPor()`
(nascer/pôr REAL do local) + margem de `Config.MARGEM_CREPUSCULO_MIN = 20` minutos só no pôr.
Cai no `is_day` da Open-Meteo só se o cálculo falhar (não deve acontecer, exceto perto dos polos).
Verificado contra o `clima.xml` real do aparelho em dois horários (dentro e fora da margem).

---

# Painel no navegador EMBUTIDO no launcher (fase 1, 04/11/2026)

Substitui aos poucos o `chromecast-painel` do PC (decisão do Paulo: "o PC era só um meio"). O próprio launcher
serve o painel em `http://<IP da TV>:8080/` (porta < 1024 é proibida para app, medido). Plano completo e medições
da fase 0: `/mnt/project-files/tv-launcher/painel-no-launcher.md`.

- Liga em Configurações do launcher > Painel no navegador. Definir o PIN (6 a 12 dígitos) já liga. Desligado = sem
  socket nem thread.
- Arquivos: `PainelWeb.kt` (servidor, rotas), `PainelHttp.kt` + `PainelSeguranca.kt` (puros, com testes JVM em
  `app/src/test`), `PainelArmazem.kt` (prefs `painel_seguro` e `painel_log`, FORA do backup de propósito),
  `PainelApps.kt` (apps, ícone, ocultar, desinstalar com receiver de resultado), `BackupConfig.kt` (veio do PR #9),
  frontend em `app/src/main/assets/painel/`.
- Defesas: só aceita conexão pela rede local (não pela VPN), Host na lista (`Config.PAINEL_NOMES` + IP), POST exige
  Origin do próprio painel e `X-Painel: 1`, cookie HttpOnly SameSite=Strict, PIN em PBKDF2, 5 erros = bloqueio
  crescente, 20 erros = painel desliga sozinho (só religa na TV). Desinstalar e restaurar backup pedem o PIN de novo
  (vale 5 min). Lista de apps protegidos em `PainelApps.PROTEGIDOS_FIXOS` (inclui o launcherx: NUNCA ocultar).
- Nome "chromecast": a TV usa IP fixo, então só funciona com um registro de DNS local no roteador UniFi UX7 apontando
  para 192.168.1.153; o navegador precisa de `http://chromecast:8080/` (palavra solta vira busca).
- Testes JVM: `gradlew testReleaseUnitTest` (ou kotlinc + JUnit direto, sem Android).
- Fase 2 (04/11/2026, PR #10): todos os apps por padrão; permissões por app (`PainelPermissoes.kt`: perigosas pelo DO
  `setPermissionGrantState`, acessos especiais por `appops set --uid` no shell próprio via `GuardiaoShizuku.comando`, comando
  montado de tabela fixa); instalar APK em silêncio (`PainelApps.instalar`, PIN de novo; o próprio launcher é recusado);
  aba Arquivos (`PainelArquivos.kt`, memória interna menos `Android/`; corpo do envio vai em fluxo direto ao disco, ver
  `Requisicao.fluxoCorpo`). O acesso a todos os arquivos (MANAGE_EXTERNAL_STORAGE) o launcher se concede pelo shell na 1ª vez.
- Ficha de cada app (`PainelFicha.kt` + `assets/fichas-apps.json`, 94 pacotes desta TV escritos à mão; overlays pela regra
  "/overlay/" no caminho do APK): risco essencial/cuidado/seguro, efeito de ocultar, papéis em uso (teclado, HOME, VPN,
  acessibilidade, protetor, assistente, navegador) e "usado por" calculado na TV (biblioteca compartilhada e permissão que o app
  define). Papel em uso sobe o risco para essencial. Pacote novo na TV sem ficha = "sem informação": acrescentar no JSON.
- Aba Controle (`PainelControle.kt`, 05/11/2026): foto da tela pela acessibilidade (`canTakeScreenshot` no XML; JPEG 1280x720
  a cada 2 s só com a aba visível), teclas (VOLTAR/INÍCIO pela acessibilidade, o resto `input keyevent` no shell, tabela fixa),
  texto (`ChecagensPainel.textoTvValido`, sem aspas nem acento), acordar/dormir, reiniciar a TV (DO `reboot`, PIN). No Detalhes:
  abrir, parar (`am force-stop`) e limpar dados (DO `clearApplicationUserData`, só app instalado, PIN); espaço e uso de hoje.
- Fases seguintes (não feitas): alarmes pelo painel, horário de dormir, ver o Quarto, HTTPS.

# chromecast-painel — painel de administração (subpasta deste mesmo repo)

Painel web simples e não-técnico pra administrar os Chromecasts da casa (Sala e, no futuro,
Quarto) pelo navegador: instalar/congelar/desinstalar apps, alternar permissão por app, enviar/
baixar arquivo, ver a tela ao vivo — tudo multi-aparelho, descoberta automática por nome amigável.

**Local:** `%USERPROFILE%\tv-launcher\chromecast-painel` — subpasta do MESMO repo
`tv-launcher` (decisão do Paulo em 03/11/2026: faz parte do mesmo projeto). `node_modules/`
e `cache/` dessa subpasta estão no `.gitignore` do repo (não versionar dependências nem
dados locais de cache). **Atenção de segurança:** esse painel controla instalar/desinstalar
app e dá acesso à tela da TV, sem senha. Por isso, desde 04/11/2026, **só escuta em 127.0.0.1**
(painel: `server.js`, `PAINEL_HOST`; ws-scrcpy: `node -r ws-scrcpy-extra/so-localhost.js` no
`%USERPROFILE%\ws-scrcpy\start.ps1`, sem mexer no código dele). Antes escutavam em todas as
interfaces, e o firewall do Windows tem uma regra "Node.js" liberando entrada (Privado e Público).
Medido depois: pelo IP do Dell na rede (portas 9000/8000) recusa; por localhost funciona, inclusive a
tela ao vivo. Para usar de outro aparelho: pôr senha ANTES de abrir para a rede.

Motivo de existir: o Paulo rejeitou explicitamente a interface técnica/crua do `ws-scrcpy`
("Que coisa horrorosa é isso daqui?") — então esse painel existe pra embutir a visualização
de tela do `ws-scrcpy` por baixo dos panos, sem nunca mostrar a lista de dispositivos dele.

## Stack

Node.js + Express (backend) servindo HTML/CSS/JS puro (frontend, sem framework). Porta `9000`.
Dependências: `express`, `multer` (upload de arquivo — usar `^2.0.0`, não `1.x`, que tem CVEs
conhecidos), `app-info-parser` (extrai nome real do app do APK, ex. "Netflix" de
`com.netflix.ninja`).


## Arquivos

- `server.js` — todo o backend (~260 linhas): descoberta/dedup de aparelhos, apps
  (listar/instalar/congelar/descongelar/desinstalar/nome real), permissões (listar/
  conceder/revogar), arquivos (listar/enviar/baixar), link pra tela (ver ws-scrcpy abaixo).
- `public/index.html` — SPA: grade de aparelhos → tela do aparelho com 3 abas
  (Tela / Aplicativos / Arquivos) + modal de permissões.
- `public/style.css` — tema escuro. **Atenção a um bug de CSS já corrigido:** uma classe
  com `display` definido (`.modal`, `.botao-secundario`) tem a MESMA especificidade do
  `[hidden] { display: none }` do navegador, e como o CSS do usuário carrega depois do
  user-agent stylesheet, ela ganha — elemento com `hidden` ficava visível mesmo assim.
  Resolvido globalmente com `[hidden] { display: none !important; }` no topo do arquivo.
- `public/app.js` — toda a lógica do frontend (227 linhas).

## Descoberta de aparelhos e deduplicação (mecanismo-chave)

Um Chromecast físico aparece em mais de um endpoint do `adb devices -l` (porta de depuração
sem fio custom, porta padrão `5555`, nome mDNS). `descobrirDispositivos()` resolve isso
consultando `settings get global device_name` em CADA endpoint e agrupando pelo nome
amigável (slugificado), persistindo em `cache/dispositivos.json`. Hoje "Sala" aparece como
uma única entrada mesmo tendo 2 endpoints. **Esse é o mecanismo que também vai suportar o
Quarto no futuro** — quando a segunda TV for modificada, basta estar na mesma rede com
depuração sem fio ativa que ela aparece sozinha, sem código novo.

## Bugs já corrigidos (não repetir)

- `pm path` traz `\r` sobrando no fim da primeira linha (CRLF) — sempre usar
  `.split(/\r?\n/)[0]`, nunca `.trim()` no texto inteiro antes de splitar.
- `dumpsys package <pkg>` repete a seção `runtime permissions:` uma vez por usuário Android
  (User 0 e User 11 nesse aparelho) — sempre deduplicar com `Map`, mantendo a 1ª ocorrência
  (User 0, que é a que importa num contexto de usuário único).
- Nome de arquivo com espaço (`ls -la`) quebrava ao pegar só o último token — `ls -la` tem
  7 campos fixos antes do nome; usar `.split(/\s+/).slice(7).join(' ')`, nunca `.pop()`.


## ws-scrcpy — visualização de tela embutida (funcionando desde 04/11/2026)

O `ws-scrcpy` (`%USERPROFILE%\ws-scrcpy`, clone oficial do GitHub, **não modificado** —
decisão explícita do Paulo de manter "todinho do repositório") roda numa porta separada
(`8000`, escondida do usuário) e faz o trabalho pesado de capturar e decodificar a tela via
scrcpy. O painel nunca mostra a lista de dispositivos dele — só devolve o link direto do
stream pronto, via `GET /api/dispositivos/:id/tela`.

### Fórmula do link (`server.js`, confirmada correta por leitura de `new URLSearchParams`
no console do navegador — NÃO é bug, apesar de parecer):

```
http://localhost:8000/#!action=stream&udid=<endpoint>&player=mse&ws=<ws:// URL, url-encoded>
```
onde o `ws` interno é:
```
ws://localhost:8000/?action=proxy-adb&remote=tcp:8886&udid=<endpoint>
```
(`8886` = porta fixa do scrcpy-server no aparelho, constante `SERVER_PORT` em
`src/common/Constants.ts`). As DUAS camadas usam `URLSearchParams` do início ao fim —
nunca `encodeURIComponent` manual, senão dá encode duplo.

### Tela preta: RESOLVIDA em 04/11/2026 (três causas, todas medidas)

1. **O scrcpy-server só é iniciado quando o aparelho (re)conecta no adb do PC.** Em
   `src/server/goog-device/Device.ts`, `fetchDeviceInfo` (que chama `startServer`) roda na conexão e
   nas novas tentativas depois de falha — nunca mais. Se o servidor morre (ou não subiu naquela hora,
   que foi o caso do diagnóstico anterior), ninguém o religa. Medido: matei o servidor e nem abrir o
   stream nem abrir a lista do ws-scrcpy o religou. **Correção (no painel):** `garantirScrcpyServer()`
   em `server.js` confere com `ps` e, se faltar, envia o jar (o scrcpy-server APAGA o próprio jar ao
   iniciar) e sobe com o mesmo comando que o ws-scrcpy usa (visto no `servidor.log`).
2. **O player `mse` pede 720x720 (padrão do `MsePlayer`) → vídeo 720x400, e o codificador do
   Chromecast embaralha essa largura** (faixas diagonais). Com 1280x720 a imagem sai certa. A escolha
   fica no localStorage da origem do ws-scrcpy (porta 8000), com chave `MseDecoder:<udid>:<LxA da
   janela>`. **Correção:** `ws-scrcpy-extra/painel-tela.html`, que o painel copia para
   `ws-scrcpy/dist/public` ao iniciar (dist é saída de build, fora do git do ws-scrcpy — o código dele
   continua intacto): grava 1280x720 na chave certa e redireciona para o stream. O iframe tem tamanho
   fixo 1328x736 (escalado por CSS) para a chave ser sempre a mesma.
3. **Com a tela da TV parada, o scrcpy não manda quadro novo** e o player fica preto até algo mudar.
   Não é bug; o painel mostra uma dica embaixo da tela.

Atenção: se o servidor já estiver rodando com outro tamanho (aberto por outro cliente, ex. a lista do
ws-scrcpy), um cliente novo ADOTA o tamanho dele. Matar o `app_process ... scrcpy.Server` na TV e
abrir de novo pelo painel resolve.
## Status das tarefas do painel

- **Descoberta/dedup de aparelhos** — feito, testado com a Sala real.
- **Apps** (listar/instalar/congelar/descongelar/desinstalar/nome real) — feito, testado.
- **Permissões** (listar/conceder/revogar) — feito, testado, ciclo completo revertido ao
  estado original.
- **Arquivos** (listar/enviar/baixar) — feito, testado, incluindo nome com espaço e
  verificação byte a byte do download.
- **Tela ao vivo (ws-scrcpy)** — funcionando (04/11/2026), ver seção acima.
- **Visual (CSS/JS) geral** — as 3 abas conferidas no navegador em 04/11/2026: nomes reais dos apps (pedidos
  ao launcher), launcher protegido (sem congelar/remover), pastas sem "Baixar", texto do aparelho escapado.
  Antes: bugs corrigidos (`hidden`, nome de arquivo com espaço).
- **Auto-start do painel** — feito (04/11/2026): `chromecast-painel/start.ps1` + `start.vbs`, cópia em
  `shell:startup\chromecast-painel.vbs`. Não sobe duas vezes (confere a porta 9000). Log em `cache\painel.log`.
- **Acesso por nome** (digitar "chromecast" no navegador em vez de `localhost:9000`) —
  não investigado. Provável caminho: mDNS/hosts local; precisa checar viabilidade.

## Shizuku automático — FEITO (04/11/2026)

Era um objetivo (03/11/2026) e foi além do planejado: em vez de instalar o app Shizuku, o servidor
foi EMBUTIDO no launcher, que pareia, sobe e vigia sozinho (ver "Shell próprio + Shizuku embutido").

## Brainstorm aprovado em princípio, NÃO iniciado (não começar sem o Paulo pedir)

Pilha automática de apps (com toggle on/off nas configs do launcher), modo visita,
histórico de rede (via o painel, web-only), atalho de voz (STT), backup automático das
SharedPreferences do launcher, auto-update do APK do launcher. Aprovados em conversa
anterior, mas sem prioridade reafirmada — perguntar antes de atacar qualquer um.

## Organização do repo (03/11/2026)

- ~57 scripts `.ps1` de depuração avulsos (testes manuais de popup, tema, protetor de tela
  etc.) foram movidos da raiz pra `scripts/debug/`. `build3.ps1` (o build de verdade)
  continua na raiz.
- `.shots/` (102 screenshots de depuração, ~65MB) e
  `app/src/main/res/raw/vpn_cert.pem` entraram no `.gitignore` — **o certificado de VPN
  nunca deve ir pro GitHub**. Se o build de VPN depender desse arquivo, ele precisa existir
  localmente em qualquer máquina que for buildar, mas fora do controle de versão.

## Como trabalhar com o Paulo (resumo das preferências dele)

Responder em português do Brasil. Ser direto e breve — resposta primeiro, sem introdução
nem elogio. Toda afirmação factual etiquetada (verificado / lido em fonte / não
verificado). Discordar uma vez com o motivo e, se ele mantiver a decisão, executar sem
reabrir a discussão. Antes de tarefa grande/cara, dizer escopo e custo em números. Para
código: ele é iniciante aprendendo ao vivo — explicar o que o código faz e por quê, inline
e curto. Commits/push: branch própria + PR em rascunho (permissão global de 04/11/2026; ver topo).

## IA na nuvem: REMOVIDA (05/11/2026); catálogo guardado para o MCP

- O bloco 6 (aba Falar do painel, chave do dono para Gemini/Claude/OpenAI/Perplexity, `IaNuvem.kt`, `IaArmazem.kt`) saiu por
  decisão do Paulo: o Gemini do controle e os atalhos de voz já fazem o mesmo. Estudo e decisão (A + E):
  https://claude.ai/artifact/XYGCQ9U4YC8Paoz1iGSDbS. Ver o git (commit f7f80c5) se precisar do código antigo.
- Ficaram, como base do futuro servidor MCP (Claude de fora chamando ferramentas da TV): `ComandosIa` (catálogo FECHADO e
  conferência: abrir app da HOME, abrir tela, despertador, volume, mídia, modo visita), `PedidoIa.propor` (sem limite de pedidos desde 06/11/2026) e `ConfirmarIaActivity` (foco em Cancelar, 60 s e cancela). Desde 06/11/2026 (Paulo: "tire o ok") as ações executam na hora, sem OK no controle (`Config.IA_CONFIRMAR_NO_CONTROLE = false`); a TV mostra um aviso do que fez. O catálogo continua fechado.
- `PedidoIa.apagarRestosDaNuvem` (chamado no `LauncherApp.onCreate`) apaga na TV as prefs `ia_seguro` e a chave `ia_chave_api` do
  Keystore. A do UniFi (`unifi_chave_api`) é outra e fica.
- Teste só pelo adb: `am broadcast -a br.paulo.tvlauncher.IA_TESTE --es frase "teste" --es resposta '{"acoes":[{"tipo":"abrir_tela","tela":"previsao"}],"resposta":"ok"}' -n br.paulo.tvlauncher/.ControleShizukuAdbReceiver`
  e `-a br.paulo.tvlauncher.IA_ESTADO` (último pedido).
- MCP pela internet (05/11/2026, branch `claude/mcp-tv`, ainda não testado na TV): `McpServidor.kt` (só 127.0.0.1:`Config.MCP_PORTA`
  = 8091; OAuth 2.1 com DCR + PKCE em `McpAutorizacao.kt`, login = PIN do painel; JSON-RPC em `McpProtocolo.kt`; ferramentas em
  `McpFerramentas.kt`: leitura + `pedir_acoes`, que só roda com OK no controle). De fora só pelo túnel: `tunel/main.go` (Tailscale
  `tsnet` em userspace, SEM usar a vaga de VPN do AdGuard; Funnel :443 -> 127.0.0.1:8091), compilado pela tarefa `compilarTunel`
  (precisa do Go; sem Go o APK sai sem o túnel) como `libtsnet.so` e rodado por `TunelTailscale.kt` (religa com espera crescente).
  O painel :8080 NÃO passa pelo túnel. Chave do Tailscale: painel > aba Claude (PIN de novo), cifrada no Keystore e apagada
  depois do 1º login. APK: 3,1 -> 11,8 MB por causa do binário (21 MB descompactado).
  Login sem chave (05/11 noite): o túnel imprime `LOGIN <url>` / `ATIVAR <url>` (HTTPS+Funnel em conta nova) e a TV
  (`ConectarClaudeActivity`, também no assistente de boas-vindas) e o painel (aba Claude, `/api/mcp/qr`) mostram como QR code
  (zxing core). Clientes aceitos no OAuth: claude.ai/claude.com, chatgpt.com, vscode.dev e localhost (Codex, Claude Code, Cursor).
  Opção "Rede Tailscale" (DESLIGADA por padrão, escolha do Paulo): painel em http://tv-sala:8080 pelos aparelhos da conta,
  ferramenta MCP `aparelhos_tailscale` (só lista) e atalhos de porta (`AtalhosTunel.kt`: "nas:8096" -> 127.0.0.1:8096 na TV,
  para Jellyfin/Kodi alcançarem um NAS em outro lugar). Não testado na TV.
