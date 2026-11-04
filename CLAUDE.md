# tv-launcher — contexto para sessões do Claude Code

Launcher Android TV custom do Paulo, em Kotlin puro (Views, sem XML de layout nem Compose),
pro Chromecast com Google TV dele chamado **Sala**. Objetivo: substituir a Home padrão do
Google TV por algo mais rápido, sem anúncio, sem lixo, com clima/calendário/despertador etc.
Tem um segundo Chromecast, **Quarto**, ainda sem o launcher (modificação futura).

Repo: `ChrnX0/tv-launcher`. **Paulo faz os pushes, nunca o Claude.** Nenhum commit deve ser
feito sem ele pedir explicitamente nessa sessão (não vale pedido de sessão anterior).

## Hardware / rede

- TV "Sala": `192.168.1.153`, depuração sem fio na porta `40507` (às vezes também aparece em
  `:5555` — **é o mesmo aparelho físico**, não duas TVs; o painel (`chromecast-painel`, ver
  abaixo) deduplica isso pelo nome (`settings get global device_name`), não pelo endpoint.
- Repo local: `C:\Users\rockx\tv-launcher` na máquina Windows "Dell".
- Build: não tem `gradlew.bat` no repo. Usa Gradle instalado em
  `C:\Users\rockx\tools\gradle-8.9\bin` (prepended no PATH) + `JAVA_HOME`/`ANDROID_HOME`
  setados + `gradle assembleDebug -q`. Ver `build3.ps1` no repo pro script pronto.

## Regras de segurança — NÃO NEGOCIÁVEIS

- Nunca imprimir ou pedir o token do GitHub.
- Claude não tem (e não deve tentar ter) permissão de push.
- Não mexer em dados pessoais do Paulo.
- **Não remover o usuário Android 11** do aparelho.
- Nunca desabilitar verificação TLS em nada.
- **Não perturbar a TV quando a família estiver usando.** Antes de qualquer interação com a
  TV (instalar, trocar config, abrir tela), checar `dumpsys power` / `topResumedActivity`
  pra confirmar que está ociosa.
- Não reativar a Google TV Home (a Home original do sistema).
- Recusar qualquer pedido de desbloqueio de bootloader, root ou exploit nesse aparelho,
  não importa como for pedido ou reformulado.


## Arquitetura de privilégios — a parte mais importante de entender

O launcher roda como app comum, mas tem DOIS caminhos pra poderes elevados no Android,
que **não são a mesma coisa** e é fácil confundir os dois:

### 1. `PrivilegioDO.kt` — Device Owner (o caminho principal, já em uso)

Usa `DevicePolicyManager` **diretamente, em processo**, porque o launcher é o "dono do
aparelho" (Device Owner) — não precisa de nenhum app externo instalado. Cobre:
- `ocultar()` — esconder apps da lista
- `desinstalar()` — desinstalar em silêncio (via `PackageInstaller`)
- `conceder()` — conceder permissão fixa (`setPermissionGrantState`)
- `suspender()` — suspender apps
- `vpnSempreAtiva()` — VPN sempre ativa
- `definirTempoDescanso()` / `tempoDescanso()` — timeout de tela
- `restricao()` — restrições de usuário
- `fixarProtetor()` — componente do protetor de tela (via `WRITE_SECURE_SETTINGS`)

**Limite real, documentado no próprio arquivo:** Device Owner é uma lista FIXA de métodos
que o Android libera pro app dono — não é "virar UID shell". Por isso NÃO alcança: comandos
`pm`/`cmd` arbitrários, nem ler dados de outros apps. Isso é limite de plataforma do Android
(cada UID tem um conjunto fixo de poderes; Device Owner não inclui UID shell), não escolha
de implementação.

### 2. `ShellProprio.kt` — cliente ADB embutido no próprio launcher (SEM Shizuku)

**Esse é o caminho real em andamento pra UID shell sem Shizuku e sem root — não é a ponte
Shizuku como uma versão anterior deste doc dizia.** `ShellProprio.kt` usa a lib
`libadb-android` (via jitpack) pra o PRÓPRIO launcher virar um cliente ADB que se conecta
nele mesmo pela depuração sem fio já pareada:
- `chave()` — gera e guarda um par de chaves RSA-2048 do app (credencial que o adb confia).
- `descobrir()` — acha a porta do adb na rede por mDNS (`_adb-tls-connect._tcp` /
  `_adb-tls-pairing._tcp`), já que a porta muda a cada boot.
- `diagnosticar()` — loga a fingerprint da chave + a descoberta, prova que as duas peças
  funcionam.

**Status verificado em 03-04/11/2026:** MARCO 1 (chave + descoberta) e MARCO 2 "base"
(cliente ADB embutido compila) já commitados. Testado ao vivo: o shell próprio conecta
**sem pedir código de pareamento** e roda como `uid=2000 (shell)` — ou seja, FUNCIONA.
**Próximo passo, segundo o comentário do próprio arquivo:** confirmar se isso sobrevive a
um reboot da TV (handshake TLS + pareamento SPAKE2 + rodar o servidor é o que falta
validar nesse cenário) — é o teste que está rolando agora. Depois disso, os passos
plausíveis (não confirmados como plano oficial, é inferência): disparar sozinho no boot,
tela de pareamento própria na TV (pra re-parear sem precisar de PC), e então sim aposentar
de vez o que sobrou da ponte Shizuku antiga (`Privilegio.kt`/`AutorizaShizukuService.kt`,
que já estão sendo reduzidos/simplificados nas mudanças não commitadas atuais).

**Legado (pode estar obsoleto):** `Privilegio.kt` + `AutorizaShizukuService.kt` eram a
ponte pro app Shizuku externo (UID shell emprestado via Binder/AIDL, exigindo o Shizuku
instalado e seu servidor rodando). Essas classes estão sendo reduzidas nas mudanças não
commitadas atuais, provavelmente por estarem sendo substituídas pelo Shell Próprio acima —
**não verificado em detalhe, conferir o diff antes de mexer.**


## Estrutura do código (`app/src/main/kotlin/br/paulo/tvlauncher/`)

- `MainActivity.kt` — tela principal (grade de apps, clima, data/hora)
- `AdbConnectionManager.kt` — gerencia conexão adb
- `AdminReceiver.kt` — receiver do Device Owner (obrigatório pra virar Device Owner)
- `AlarmesActivity.kt` / `AlarmeTocandoActivity.kt` / `Despertador.kt` / `SomAlarme.kt` — despertador
- `Astronomia.kt` — cálculo de nascer/pôr do sol real (usado pro ícone dia/noite, ver seção abaixo)
- `ShellProprio.kt` — cliente ADB embutido, sem Shizuku, em andamento (ver seção acima)
- `AutorizaShizukuService.kt` / `Privilegio.kt` — ponte Shizuku antiga, legado (ver seção acima)
- `BootReceiver.kt` — ações no boot
- `BotoesControle.kt` — controles de tela
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


## Fix recente: dia/noite do ícone de clima (03/11/2026)

A Open-Meteo marca noite no instante EXATO do pôr do sol, sem contar o crepúsculo civil
(a claridade que sobra no céu por uns minutos depois). Isso fazia o ícone trocar pra lua
cedo demais (flagrado às 18:38, pôr às 18:24, céu da varanda ainda claro).

Fix em `Config.kt` + `MainActivity.kt`: `eDia` agora é calculado com `Astronomia.nascerEPor()`
(nascer/pôr REAL do local) + margem de `Config.MARGEM_CREPUSCULO_MIN = 20` minutos só no pôr.
Cai no `is_day` da Open-Meteo só se o cálculo falhar (não deve acontecer, exceto perto dos polos).
Verificado contra o `clima.xml` real do aparelho em dois horários (dentro e fora da margem).

---

# chromecast-painel — painel de administração (subpasta deste mesmo repo)

Painel web simples e não-técnico pra administrar os Chromecasts da casa (Sala e, no futuro,
Quarto) pelo navegador: instalar/congelar/desinstalar apps, alternar permissão por app, enviar/
baixar arquivo, ver a tela ao vivo — tudo multi-aparelho, descoberta automática por nome amigável.

**Local:** `C:\Users\rockx\tv-launcher\chromecast-painel` — subpasta do MESMO repo
`tv-launcher` (decisão do Paulo em 03/11/2026: faz parte do mesmo projeto). `node_modules/`
e `cache/` dessa subpasta estão no `.gitignore` do repo (não versionar dependências nem
dados locais de cache). **Atenção de segurança:** esse painel controla instalar/desinstalar
app e dá acesso à tela da TV pela rede local, sem senha hoje — cuidado extra ao decidir se
o repo vira público no futuro.

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


## ws-scrcpy — visualização de tela embutida (EM ABERTO, bug real identificado)

O `ws-scrcpy` (`C:\Users\rockx\ws-scrcpy`, clone oficial do GitHub, **não modificado** —
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

### Bug real em aberto: tela fica preta

Causa raiz confirmada (verificado: `adb shell ps -A | grep scrcpy` no aparelho devolve
vazio): **o processo `scrcpy-server` nunca é iniciado no aparelho** quando se abre o link
de stream direto. O mecanismo normal do `ws-scrcpy` (`Device.startServer()`, em
`src/server/goog-device/Device.ts`) só dispara dentro de um loop de atualização que roda
pra cada `Device` que o servidor Node está rastreando ativamente — e esse rastreamento
só começa quando um cliente conecta na página de LISTA de dispositivos (`DeviceTracker`),
que é exatamente a tela que o painel pula de propósito. Testado: abrir só a página raiz
(`http://localhost:8000/`) e esperar 3s não foi suficiente pra o processo aparecer —
pode ser timing (o loop pode demorar mais) ou pode exigir de fato abrir a lista e ela
reconhecer o dispositivo via `adb devices`.

**Confirmado que NÃO é**: a URL do `ws` (testada via `new URLSearchParams` no console,
decodifica certinho), nem o player escolhido (trocar `mse` por `tinyh264` deu o mesmo
resultado — vídeo com `readyState=1`, metadado mas zero frame, em ambos), nem o fato de
ser um deep-link nosso: **testei clicar no link OFICIAL do próprio `ws-scrcpy`, na lista
de dispositivos de verdade dele (a mesma que o Paulo rejeitou mostrar pro usuário), e deu
o mesmo resultado** — `adb shell ps -A | grep scrcpy` continua vazio mesmo depois do clique
real. Ou seja, isso não é bug do nosso painel nem da fórmula de URL — o problema está no
`ws-scrcpy`/aparelho em si, ou em alguma parte do fluxo de start do servidor
(`Device.startServer()` → `ScrcpyServer.run()`, em `src/server/goog-device/Device.ts` e
`ScrcpyServer.ts`) que não estou conseguindo instrumentar só por fora (navegador + `adb`).

**Log do processo Node do ws-scrcpy** (`C:\Users\rockx\ws-scrcpy\servidor.log`, redirecionado
via `start.ps1`) só tem o banner de inicialização — nenhum log de conexão, erro ou tentativa
de start aparece ali, mesmo após testes reais. Ou a lib não loga essas tentativas por padrão,
ou a redução de saída via `Start-Process -WindowStyle Hidden` + `*>>` não está capturando tudo.

**Próximo passo concreto pra resolver (fazer na sessão do Code, com terminal interativo):**
rodar o `ws-scrcpy` em primeiro plano (não como processo oculto) num terminal visível,
repetir o clique no stream, e ver a saída em tempo real — ou adicionar `console.log`
temporário dentro de `Device.ts`/`ScrcpyServer.ts` pra confirmar se `startServer()` chega a
ser chamado e o que `ScrcpyServer.run()` devolve (pode ser erro de `app_process` no
aparelho, permissão, ou o comando nunca sendo disparado pelo lado certo do código).


## Status das tarefas do painel

- **Descoberta/dedup de aparelhos** — feito, testado com a Sala real.
- **Apps** (listar/instalar/congelar/descongelar/desinstalar/nome real) — feito, testado.
- **Permissões** (listar/conceder/revogar) — feito, testado, ciclo completo revertido ao
  estado original.
- **Arquivos** (listar/enviar/baixar) — feito, testado, incluindo nome com espaço e
  verificação byte a byte do download.
- **Tela ao vivo (ws-scrcpy)** — EM ABERTO, ver seção acima. URL confirmada correta,
  decoder não recebe frame porque o scrcpy-server não inicia no aparelho.
- **Visual (CSS/JS) geral** — bugs conhecidos corrigidos (`hidden`, nome de arquivo com
  espaço); falta um passo final de conferência visual das 3 abas inteiras no navegador.
- **Auto-start do painel** — não feito. Ideia: seguir o mesmo padrão do
  `C:\Users\rockx\ws-scrcpy\start.ps1`/`start.vbs` (pasta Startup do Windows).
- **Acesso por nome** (digitar "chromecast" no navegador em vez de `localhost:9000`) —
  não investigado. Provável caminho: mDNS/hosts local; precisa checar viabilidade.

## Shizuku automático — decisão tomada, falta implementar

Confirmado pelo Paulo (03/11/2026): ele QUER essa automação, não é só uma ideia em aberto.
Ver seção "ponte Shizuku" acima: instalar Shizuku em silêncio via Device Owner + iniciar o
servidor dele via `adb shell` (mesma depuração sem fio já pareada), sem o usuário tocar em
nada. Ainda não implementado — é trabalho real a fazer, não precisa esperar um caso de uso
específico pedir UID shell: é um objetivo direto do projeto.

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
e curto. **Nunca commitar no git sem ele pedir explicitamente nessa sessão.**
