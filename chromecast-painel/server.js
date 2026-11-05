// Painel simples pra gerenciar os Chromecasts da casa. Fala com cada aparelho via "adb" (a ferramenta de
// linha de comando do Android) - sem nada disso aparecer pra quem usa o painel.
'use strict';

const express = require('express');
const multer = require('multer');
const path = require('path');
const fs = require('fs');
const os = require('os');
const { execFile } = require('child_process');

const PORTA = 9000;
const ADB = 'adb'; // precisa estar no PATH (o start.ps1 do painel cuida disso)
const PASTA_CACHE = path.join(__dirname, 'cache');
const PASTA_UPLOADS = path.join(__dirname, 'cache', 'uploads');
for (const p of [PASTA_CACHE, PASTA_UPLOADS]) if (!fs.existsSync(p)) fs.mkdirSync(p, { recursive: true });

const ARQUIVO_DISPOSITIVOS = path.join(PASTA_CACHE, 'dispositivos.json');

// ---------- adb: roda um comando e devolve a saida (nunca usa shell, pra nao ter injecao) ----------
function adb(args, { timeoutMs = 8000 } = {}) {
    return new Promise((resolve, reject) => {
        execFile(ADB, args, { timeout: timeoutMs, windowsHide: true, maxBuffer: 1024 * 1024 * 32 }, (err, stdout, stderr) => {
            if (err) { err.stderr = stderr; return reject(err); }
            resolve(stdout);
        });
    });
}
function adbShell(endpoint, comando, opts) {
    return adb(['-s', endpoint, 'shell', comando], opts);
}

// ---------- descoberta dos Chromecasts, com nome por aparelho (nao por "endpoint" tecnico) ----------
// Um mesmo Chromecast aparece no "adb devices" varias vezes: depuracao sem fio (porta solta) e porta fixa
// 5555, as vezes tambem um nome mDNS longo. Tudo isso e o MESMO aparelho. Aqui a gente pergunta o nome que
// a pessoa deu na TV (Configuracoes > Sobre > nome do dispositivo) e agrupa por ele.
let dispositivos = {}; // slug -> { nome, endpoint, vistoEm }
carregarDispositivosDoDisco();

function carregarDispositivosDoDisco() {
    try { dispositivos = JSON.parse(fs.readFileSync(ARQUIVO_DISPOSITIVOS, 'utf8')); } catch { dispositivos = {}; }
}
function salvarDispositivosNoDisco() {
    fs.writeFileSync(ARQUIVO_DISPOSITIVOS, JSON.stringify(dispositivos, null, 2));
}
function slugificar(nome) {
    return nome.toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
}

async function listarEndpointsAdb() {
    const saida = await adb(['devices', '-l']);
    return saida.split('\n').slice(1)
        .map((l) => l.trim()).filter(Boolean)
        .map((l) => l.split(/\s+/))
        .filter((partes) => partes[1] === 'device') // so os autorizados e conectados
        .map((partes) => partes[0]);
}

async function descobrirDispositivos() {
    const endpoints = await listarEndpointsAdb();
    await Promise.all(endpoints.map(async (ep) => {
        try {
            const nome = (await adbShell(ep, 'settings get global device_name', { timeoutMs: 3000 })).trim();
            if (!nome || nome === 'null') return;
            const slug = slugificar(nome);
            dispositivos[slug] = { nome, endpoint: ep, vistoEm: Date.now() };
        } catch {
            // endpoint morto/duplicado/sem resposta a tempo: ignora, nao e erro do painel
        }
    }));
    salvarDispositivosNoDisco();
    return listaDispositivosPublica();
}
function listaDispositivosPublica() {
    const agora = Date.now();
    return Object.entries(dispositivos).map(([slug, d]) => ({
        id: slug, nome: d.nome, online: agora - d.vistoEm < 2 * 60 * 1000,
    }));
}
function endpointDe(slug) {
    const d = dispositivos[slug];
    if (!d) throw Object.assign(new Error('Aparelho nao encontrado'), { status: 404 });
    return d.endpoint;
}

// ---------- apps: listar, instalar, congelar, remover ----------
// So mostra apps de TERCEIROS (os que a familia instalou) - nao lista nem deixa tocar nos apps do
// proprio sistema Android, pra nao arriscar travar a TV por engano.
const CACHE_NOMES_ARQ = path.join(PASTA_CACHE, 'nomes-apps.json');
let cacheNomes = {};
try { cacheNomes = JSON.parse(fs.readFileSync(CACHE_NOMES_ARQ, 'utf8')); } catch { cacheNomes = {}; }
function salvarCacheNomes() { fs.writeFileSync(CACHE_NOMES_ARQ, JSON.stringify(cacheNomes, null, 2)); }

// Nome "bonito" so com o que da pra saber sem baixar o APK (rapido). Ex.: com.netflix.mediaclient -> Netflix.
// Se a pessoa clicar em "atualizar nomes" a gente tenta algo melhor (ver atualizarNomeDeVerdade abaixo).
function nomeRapido(pkg) {
    if (cacheNomes[pkg]) return cacheNomes[pkg];
    const partes = pkg.split('.').filter((p) => !['com', 'org', 'net', 'app', 'android', 'tv', 'io'].includes(p));
    const base = partes[partes.length - 1] || pkg;
    return base.charAt(0).toUpperCase() + base.slice(1);
}

async function listarApps(endpoint) {
    const saida = await adbShell(endpoint, 'pm list packages -3 -u'); // -3: so de terceiros; -u: inclui congelados
    const pacotes = saida.split('\n').map((l) => l.trim()).filter((l) => l.startsWith('package:')).map((l) => l.slice(8));
    const congelados = new Set((await adbShell(endpoint, 'pm list packages -3 -d')).split('\n')
        .map((l) => l.trim()).filter((l) => l.startsWith('package:')).map((l) => l.slice(8)));
    const rotulos = await rotulosDoLauncher(endpoint);
    return pacotes.map((pkg) => ({
        pkg, nome: rotulos[pkg] || nomeRapido(pkg), congelado: congelados.has(pkg), protegido: PROTEGIDOS.has(pkg),
    }));
}

// Nome REAL dos apps (o que aparece na TV), perguntado ao próprio launcher, que já sabe (PackageManager): um broadcast
// por adb que devolve JSON {pacote: nome}. Só o adb/sistema podem pedir (DUMP). Se o launcher for antigo e não
// responder, fica o nome derivado do pacote, como antes.
async function rotulosDoLauncher(endpoint) {
    try {
        const saida = await adbShell(endpoint, 'am broadcast -n br.paulo.tvlauncher/.ControleShizukuAdbReceiver -a br.paulo.tvlauncher.ROTULOS');
        const m = saida.match(/data="([\s\S]*)"\s*$/m);
        return m ? JSON.parse(m[1]) : {};
    } catch { return {}; }
}

// Tenta um nome melhor baixando so o cabecalho do APK e procurando a label no AndroidManifest/resources.
// E mais lento (precisa puxar o APK do aparelho), por isso e sob pedido, nao em toda listagem.
async function atualizarNomeDeVerdade(endpoint, pkg) {
    const caminhoRemoto = (await adbShell(endpoint, `pm path ${pkg}`)).split(/\r?\n/)[0].replace(/^package:/, '').trim();
    if (!caminhoRemoto) throw new Error('apk nao encontrado no aparelho');
    const tmp = path.join(os.tmpdir(), `${pkg}.apk`);
    await adb(['-s', endpoint, 'pull', caminhoRemoto, tmp], { timeoutMs: 60000 });
    try {
        const AppInfoParser = require('app-info-parser');
        const info = await new AppInfoParser(tmp).parse();
        const nome = info?.application?.label?.[0] || info?.application?.label || null;
        if (nome && typeof nome === 'string') { cacheNomes[pkg] = nome; salvarCacheNomes(); return nome; }
        throw new Error('apk nao trouxe um nome legivel');
    } finally {
        fs.unlink(tmp, () => {});
    }
}

// Apps que o painel NUNCA congela nem remove (04/11/2026): o launcher é a tela inicial E o Device Owner da TV —
// congelá-lo deixaria a TV sem HOME. Antes a lista oferecia "Remover" e o interruptor para ele também.
const PROTEGIDOS = new Set(['br.paulo.tvlauncher']);
function recusarSeProtegido(pkg) {
    if (PROTEGIDOS.has(pkg)) throw Object.assign(new Error('Este app é a tela inicial da TV e não pode ser congelado nem removido pelo painel'), { status: 403 });
}
async function congelarApp(endpoint, pkg) { recusarSeProtegido(pkg); await adbShell(endpoint, `pm disable-user --user 0 ${pkg}`); }
async function descongelarApp(endpoint, pkg) { await adbShell(endpoint, `pm enable ${pkg}`); }
async function removerApp(endpoint, pkg) { recusarSeProtegido(pkg); await adb(['-s', endpoint, 'uninstall', pkg], { timeoutMs: 30000 }); }
async function instalarApp(endpoint, caminhoApkLocal) {
    await adb(['-s', endpoint, 'install', '-r', '-t', caminhoApkLocal], { timeoutMs: 120000 });
}

// ---------- permissoes ----------
// So as permissoes "perigosas" (camera, microfone, localizacao, armazenamento, etc.) aparecem - sao as
// unicas que fazem sentido a familia decidir; o resto (internet, etc.) o Android ja cuida sozinho.
async function listarPermissoes(endpoint, pkg) {
    const saida = await adbShell(endpoint, `dumpsys package ${pkg}`, { timeoutMs: 10000 });
    const linhas = saida.split('\n');
    // O dumpsys repete "runtime permissions:" uma vez por usuario do Android (aqui so importa o user 0 -
    // o unico "de verdade" numa TV); usa um mapa pra nao duplicar e fica com a PRIMEIRA leitura de cada uma.
    const permissoes = new Map();
    let dentroRuntime = false;
    for (const linhaBruta of linhas) {
        const linha = linhaBruta.trim();
        if (/^runtime permissions:/.test(linha)) { dentroRuntime = true; continue; }
        if (dentroRuntime) {
            if (/^[A-Za-z]+:$/.test(linha) || linha === '') { dentroRuntime = false; continue; }
            const m = linha.match(/^(android\.permission\.[A-Za-z_]+)[,:]?\s*granted=(true|false)/);
            if (m && !permissoes.has(m[1])) permissoes.set(m[1], m[2] === 'true');
        }
    }
    return [...permissoes.entries()].map(([permissao, concedida]) => ({ permissao, concedida }));
}
async function concederPermissao(endpoint, pkg, permissao) { await adbShell(endpoint, `pm grant ${pkg} ${permissao}`); }
async function revogarPermissao(endpoint, pkg, permissao) { await adbShell(endpoint, `pm revoke ${pkg} ${permissao}`); }

// ---------- arquivos ----------
async function listarArquivos(endpoint, pastaRemota) {
    const saida = await adbShell(endpoint, `ls -la ${JSON.stringify(pastaRemota)}`.replace(/"/g, "'"));
    return saida.split('\n').map((l) => l.trim()).filter(Boolean).filter((l) => !l.startsWith('total'));
}
async function enviarArquivo(endpoint, caminhoLocal, pastaRemota, nomeArquivo) {
    const destino = pastaRemota.replace(/\/+$/, '') + '/' + nomeArquivo;
    await adb(['-s', endpoint, 'push', caminhoLocal, destino], { timeoutMs: 120000 });
    return destino;
}
async function baixarArquivo(endpoint, caminhoRemoto, caminhoLocalDestino) {
    await adb(['-s', endpoint, 'pull', caminhoRemoto, caminhoLocalDestino], { timeoutMs: 120000 });
}

// ========================= servidor HTTP =========================
const app = express();
app.use(express.json());
app.use(express.static(path.join(__dirname, 'public')));
const upload = multer({ dest: PASTA_UPLOADS });

function envolver(fn) {
    return (req, res) => fn(req, res).catch((err) => {
        console.error(err);
        res.status(err.status || 500).json({ erro: err.message || 'Erro desconhecido' });
    });
}

app.get('/api/dispositivos', envolver(async (req, res) => {
    res.json(await descobrirDispositivos());
}));

app.get('/api/dispositivos/:id/apps', envolver(async (req, res) => {
    res.json(await listarApps(endpointDe(req.params.id)));
}));
app.post('/api/dispositivos/:id/apps/:pkg/nome', envolver(async (req, res) => {
    res.json({ nome: await atualizarNomeDeVerdade(endpointDe(req.params.id), req.params.pkg) });
}));
app.post('/api/dispositivos/:id/apps/:pkg/congelar', envolver(async (req, res) => {
    await congelarApp(endpointDe(req.params.id), req.params.pkg); res.json({ ok: true });
}));
app.post('/api/dispositivos/:id/apps/:pkg/descongelar', envolver(async (req, res) => {
    await descongelarApp(endpointDe(req.params.id), req.params.pkg); res.json({ ok: true });
}));
app.delete('/api/dispositivos/:id/apps/:pkg', envolver(async (req, res) => {
    await removerApp(endpointDe(req.params.id), req.params.pkg); res.json({ ok: true });
}));
app.post('/api/dispositivos/:id/apps', upload.single('apk'), envolver(async (req, res) => {
    if (!req.file) throw Object.assign(new Error('Nenhum arquivo enviado'), { status: 400 });
    try { await instalarApp(endpointDe(req.params.id), req.file.path); res.json({ ok: true }); }
    finally { fs.unlink(req.file.path, () => {}); }
}));

app.get('/api/dispositivos/:id/apps/:pkg/permissoes', envolver(async (req, res) => {
    res.json(await listarPermissoes(endpointDe(req.params.id), req.params.pkg));
}));
app.post('/api/dispositivos/:id/apps/:pkg/permissoes/:permissao/conceder', envolver(async (req, res) => {
    await concederPermissao(endpointDe(req.params.id), req.params.pkg, req.params.permissao); res.json({ ok: true });
}));
app.post('/api/dispositivos/:id/apps/:pkg/permissoes/:permissao/revogar', envolver(async (req, res) => {
    await revogarPermissao(endpointDe(req.params.id), req.params.pkg, req.params.permissao); res.json({ ok: true });
}));

app.get('/api/dispositivos/:id/arquivos', envolver(async (req, res) => {
    const pasta = req.query.pasta || '/sdcard/Download';
    res.json({ pasta, itens: await listarArquivos(endpointDe(req.params.id), pasta) });
}));
app.post('/api/dispositivos/:id/arquivos', upload.single('arquivo'), envolver(async (req, res) => {
    if (!req.file) throw Object.assign(new Error('Nenhum arquivo enviado'), { status: 400 });
    const pasta = req.body.pasta || '/sdcard/Download';
    try {
        const destino = await enviarArquivo(endpointDe(req.params.id), req.file.path, pasta, req.file.originalname);
        res.json({ ok: true, destino });
    } finally { fs.unlink(req.file.path, () => {}); }
}));
app.get('/api/dispositivos/:id/arquivos/baixar', envolver(async (req, res) => {
    const caminhoRemoto = req.query.caminho;
    if (!caminhoRemoto) throw Object.assign(new Error('Falta o caminho do arquivo'), { status: 400 });
    const nomeArquivo = caminhoRemoto.split('/').pop();
    const tmp = path.join(os.tmpdir(), `baixado-${Date.now()}-${nomeArquivo}`);
    await baixarArquivo(endpointDe(req.params.id), caminhoRemoto, tmp);
    res.download(tmp, nomeArquivo, () => fs.unlink(tmp, () => {}));
}));

// Tela do aparelho: devolve o link pronto pro ws-scrcpy (que fica rodando em outra porta, escondido).
// O painel nunca mostra a tela de lista do ws-scrcpy - so este link direto pro stream do aparelho pedido.
const WS_SCRCPY_URL = 'http://localhost:8000';
const WS_SCRCPY_WS_URL = 'ws://localhost:8000';
const PORTA_SCRCPY_NO_APARELHO = 8886; // fixo no ws-scrcpy (SERVER_PORT em common/Constants.ts)
// scrcpy-server no aparelho: o ws-scrcpy só o inicia quando o aparelho (re)conecta no adb do PC (Device.ts:
// fetchDeviceInfo roda na conexão e em novas tentativas após falha, nunca mais). Se o servidor morre — ou não subiu
// naquele momento — a tela fica preta até a TV reconectar. Essa era a causa da "tela preta" em aberto (medido em
// 04/11/2026: matei o servidor e nem abrir o stream nem abrir a lista do ws-scrcpy o religou).
// Então o painel confere e, se faltar, sobe ele mesmo, com o MESMO comando que o ws-scrcpy usa (visto no log dele).
const SCRCPY_JAR = '/data/local/tmp/scrcpy-server.jar';
const JAR_LOCAL = path.join(os.homedir(), 'ws-scrcpy', 'vendor', 'Genymobile', 'scrcpy', 'scrcpy-server.jar');
const SCRCPY_ARGS = `com.genymobile.scrcpy.Server 1.19-ws8 web ERROR ${8886} true`;
async function scrcpyRodando(endpoint) {
    const ps = await adbShell(endpoint, "ps -A -o ARGS | grep 'app_process / com.genymobile.scrcpy.Server' | grep -v grep; true");
    return ps.trim().length > 0;
}
async function garantirScrcpyServer(endpoint) {
    if (await scrcpyRodando(endpoint)) return 'ja rodando';
    // O scrcpy-server APAGA o próprio jar ao iniciar (medido: some de /data/local/tmp), e o ws-scrcpy o reenvia antes
    // de cada partida. Fazemos o mesmo, com o jar que vem no próprio ws-scrcpy.
    const temJar = (await adbShell(endpoint, `ls ${SCRCPY_JAR} 2>/dev/null; true`)).trim();
    if (!temJar) await adb(['-s', endpoint, 'push', JAR_LOCAL, SCRCPY_JAR], { timeoutMs: 20000 });
    // "&" + redirecionar tudo: o adb devolve na hora e o servidor continua (nohup).
    await adbShell(endpoint, `CLASSPATH=${SCRCPY_JAR} nohup app_process / ${SCRCPY_ARGS} >/dev/null 2>&1 &`, { timeoutMs: 8000 });
    for (let i = 0; i < 10; i++) {
        await new Promise((r) => setTimeout(r, 500));
        if (await scrcpyRodando(endpoint)) return 'iniciado pelo painel';
    }
    return 'nao subiu';
}

app.get('/api/dispositivos/:id/tela', envolver(async (req, res) => {
    const endpoint = endpointDe(req.params.id);
    try { console.log('scrcpy-server:', await garantirScrcpyServer(endpoint)); } catch (e) { console.warn('scrcpy-server:', e.message); }
    // O link precisa de "ws" (a URL do proxy adb->websocket que o ws-scrcpy monta sozinho quando a pessoa
    // clica na lista normal dele); aqui montamos ela a mao com a mesma formula, pra abrir direto sem passar
    // por aquela lista.
    const wsQuery = new URLSearchParams({ action: 'proxy-adb', remote: `tcp:${PORTA_SCRCPY_NO_APARELHO}`, udid: endpoint });
    const ws = `${WS_SCRCPY_WS_URL}/?${wsQuery.toString()}`;
    const hash = new URLSearchParams({ action: 'stream', udid: endpoint, player: 'mse', ws }).toString();
    // Passa pela página auxiliar (ver ws-scrcpy-extra/painel-tela.html): ela fixa 1280x720 antes de abrir o stream.
    // Se a cópia dela falhou, cai no link direto (imagem pode sair embaralhada, mas abre).
    const pagina = paginaTelaInstalada ? '/painel-tela.html' : '/';
    res.json({ url: `${WS_SCRCPY_URL}${pagina}#!${hash}` });
}));

// Copia a página auxiliar da tela para a pasta pública do ws-scrcpy (dist/public é saída de build dele, fora do git;
// o código-fonte do ws-scrcpy continua intacto). Feito a cada início, porque um rebuild do ws-scrcpy apaga a cópia.
const PASTA_PUBLICA_WS_SCRCPY = path.join(os.homedir(), 'ws-scrcpy', 'dist', 'public');
let paginaTelaInstalada = false;
try {
    fs.copyFileSync(path.join(__dirname, 'ws-scrcpy-extra', 'painel-tela.html'), path.join(PASTA_PUBLICA_WS_SCRCPY, 'painel-tela.html'));
    paginaTelaInstalada = true;
} catch (e) {
    console.warn('nao consegui copiar painel-tela.html para o ws-scrcpy:', e.message);
}

// Só neste PC (04/11/2026). O painel instala/desinstala apps, mexe em permissões e arquivos da TV e não tem senha;
// antes escutava em todas as interfaces e o firewall do Windows deixa o Node receber da rede. A tela ao vivo já só
// funcionava aqui (o link do ws-scrcpy é http://localhost:8000). Para abrir de outro aparelho, seria preciso subir
// com PAINEL_HOST=0.0.0.0 E pôr uma senha antes (não existe hoje).
const HOST = process.env.PAINEL_HOST || '127.0.0.1';
app.listen(PORTA, HOST, () => console.log(`Painel do Chromecast em http://localhost:${PORTA} (escutando em ${HOST})`));
