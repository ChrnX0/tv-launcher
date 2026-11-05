// Painel no navegador, servido pelo próprio launcher (PainelWeb.kt). Sem framework e sem innerHTML com dados:
// nome de app vem do app (qualquer um pode instalar um app com nome "<script>"), então todo texto entra por
// textContent, que o navegador nunca interpreta como HTML.
'use strict';

const $ = (id) => document.getElementById(id);
let apps = [];

// ---------- chamadas ao launcher ----------

/** Erro que pede o PIN de novo (ação sem volta). */
class PrecisaPin extends Error {}

async function chamar(metodo, caminho, corpo, corpoCru) {
  // X-Painel: um site de fora não consegue mandar este cabeçalho (o painel não libera CORS) — barra CSRF.
  const opcoes = { method: metodo, headers: { 'X-Painel': '1' }, credentials: 'same-origin', cache: 'no-store' };
  if (corpoCru !== undefined) {
    opcoes.headers['Content-Type'] = 'application/json';
    opcoes.body = corpoCru;
  } else if (corpo !== undefined) {
    opcoes.headers['Content-Type'] = 'application/json';
    opcoes.body = JSON.stringify(corpo);
  }
  let resp;
  try {
    resp = await fetch(caminho, opcoes);
  } catch (e) {
    // O navegador só diz "Failed to fetch"/"Load failed": a porta está fechada ou a TV saiu da rede.
    throw new Error('Não consegui falar com a TV. Confira se ela está ligada e se o painel está ligado nas Configurações do launcher.');
  }
  const dados = await resp.json().catch(() => ({}));
  // 401 em /api/entrar e /api/confirmar é "PIN errado" (a sessão continua); nas outras rotas é sessão expirada.
  if (resp.status === 401 && caminho !== '/api/entrar' && caminho !== '/api/confirmar') {
    mostrarEntrar('Sessão expirada: entre de novo.');
    throw new Error('Sessão expirada: entre de novo.');
  }
  if (resp.status === 403 && dados.precisaPin) throw new PrecisaPin(dados.erro);
  if (!resp.ok) { const e = new Error(dados.erro || `Falha (${resp.status})`); e.dados = dados; throw e; }
  return dados;
}

function mostrar(el, texto) { el.textContent = texto; el.hidden = !texto; }

// ---------- entrar ----------

function mostrarEntrar(aviso) {
  fecharModalAberto();
  mostrar($('avisoEntrar'), aviso || '');
  $('telaPainel').hidden = true;
  $('telaEntrar').hidden = false;
  $('campoPin').value = '';
  $('campoPin').focus();
}

$('formEntrar').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const btn = $('btnEntrar');
  btn.disabled = true;
  mostrar($('erroEntrar'), '');
  const pin = $('campoPin').value;
  if (!pinValido(pin)) { mostrar($('erroEntrar'), 'O PIN tem de 6 a 12 números.'); btn.disabled = false; return; }
  try {
    await chamar('POST', '/api/entrar', { pin });
    mostrar($('avisoEntrar'), '');
    await abrirPainel();
  } catch (e) {
    let msg = e.message;
    if (e.dados && e.dados.restantes) msg += ` (mais ${e.dados.restantes} tentativa(s) antes de bloquear)`;
    mostrar($('erroEntrar'), msg);
    $('campoPin').value = '';
  } finally {
    btn.disabled = false;
  }
});

$('btnSair').addEventListener('click', async () => {
  try { await chamar('POST', '/api/sair'); } catch (e) { /* tanto faz */ }
  mostrarEntrar();
});

async function abrirPainel() {
  $('telaEntrar').hidden = true;
  $('telaPainel').hidden = false;
  await atualizarEstado();
  mudarAba('apps');
}

let ultimoEstado = {};
async function atualizarEstado() {
  const e = await chamar('GET', '/api/estado');
  if (!e.logado) { mostrarEntrar(); return; }
  ultimoEstado = e;
  $('nomeAparelho').textContent = e.aparelho;
  document.title = e.aparelho;
  $('naTela').textContent = 'TV: ' + e.naTela;
  atualizarOutrasTvs().catch(() => {});
}

// Outras TVs da casa com o painel ligado (achadas por mDNS na rede). Cada uma tem o próprio PIN: o link só leva até lá.
async function atualizarOutrasTvs() {
  const r = await chamar('GET', '/api/tvs');
  const alvo = $('outrasTvs');
  alvo.textContent = '';
  if (!r.outras || !r.outras.length) { alvo.hidden = true; return; }
  alvo.append('Outras TVs: ');
  r.outras.forEach((t, i) => {
    if (i) alvo.append(' · ');
    const a = document.createElement('a');
    a.href = t.url;
    a.textContent = `${t.nome} (${t.id})`;   // textContent: o nome vem da rede, nunca vira HTML
    alvo.append(a);
  });
  alvo.hidden = false;
}

// Atualiza "o que está na TV" a cada 30 s, só com a aba do navegador visível (custo zero com a aba escondida).
setInterval(() => {
  if (document.visibilityState === 'visible' && !$('telaPainel').hidden) atualizarEstado().catch(() => {});
}, 30000);

// ---------- abas ----------

function mudarAba(nome) {
  for (const b of document.querySelectorAll('.aba')) b.classList.toggle('ativa', b.dataset.aba === nome);
  $('abaApps').hidden = nome !== 'apps';
  $('abaArquivos').hidden = nome !== 'arquivos';
  $('abaControle').hidden = nome !== 'controle';
  $('abaFalar').hidden = nome !== 'falar';
  if (nome === 'falar') carregarIa();
  if (nome === 'controle') atualizarFoto(); else pararFoto();
  $('abaBackup').hidden = nome !== 'backup';
  $('abaSaude').hidden = nome !== 'saude';
  if (nome === 'saude') carregarSaude();
  $('abaFotos').hidden = nome !== 'fotos';
  if (nome === 'fotos') carregarFotos();
  $('abaShizuku').hidden = nome !== 'shizuku';
  if (nome === 'shizuku') carregarShizuku();
  $('abaRede').hidden = nome !== 'rede';
  if (nome === 'rede') carregarRede();
  if (nome === 'controle') carregarAutoDesligar();
  $('abaRoteador').hidden = nome !== 'roteador';
  if (nome === 'roteador') carregarRoteador();
  $('abaRegistro').hidden = nome !== 'registro';
  if (nome === 'apps') carregarApps();
  if (nome === 'registro') carregarRegistro();
  if (nome === 'backup') carregarCopias();
  if (nome === 'arquivos') abrirPasta(pastaAtual);
}
for (const b of document.querySelectorAll('.aba')) b.addEventListener('click', () => mudarAba(b.dataset.aba));

// ---------- confirmação (e PIN de novo) ----------

function pinValido(pin) { return /^[0-9]{6,12}$/.test(pin); }

let fecharModalAberto = () => {};
let modalAtual = 0;   // cada confirmar() ganha um número: resposta atrasada de um quadro antigo não mexe no novo

/**
 * Pergunta antes de uma ação sem volta. Se o launcher responder que precisa do PIN (passaram 5 min desde a
 * última vez), o mesmo quadro mostra o campo de PIN, confirma no launcher e tenta a ação de novo.
 */
function confirmar(titulo, texto, rotuloOk, fazer) {
  fecharModalAberto();
  const meu = ++modalAtual;
  return new Promise((resolve) => {
    $('tituloModal').textContent = titulo;
    $('textoModal').textContent = texto;
    $('btnOkModal').textContent = rotuloOk;
    $('pinModal').hidden = true;
    $('pinModal').value = '';
    mostrar($('erroModal'), '');
    mostrar($('progressoModal'), '');
    $('btnOkModal').disabled = false;
    $('btnCancelarModal').disabled = false;
    $('modal').hidden = false;
    $('btnOkModal').focus();

    const fechar = (resultado) => {
      if (meu !== modalAtual) return;
      modalAtual++;
      fecharModalAberto = () => {};
      $('modal').hidden = true;
      $('formModal').onsubmit = null;
      $('btnCancelarModal').onclick = null;
      resolve(resultado);
    };
    fecharModalAberto = () => fechar(null);
    $('btnCancelarModal').onclick = () => fechar(null);
    $('formModal').onsubmit = async (ev) => {
      ev.preventDefault();
      if (meu !== modalAtual) return;
      const pedirPin = !$('pinModal').hidden;
      if (pedirPin && !pinValido($('pinModal').value)) {
        mostrar($('erroModal'), 'Digite o PIN (6 a 12 números).');
        return;
      }
      // Enquanto o pedido roda, nem confirmar de novo nem cancelar: a ação já foi para a TV e não para no meio.
      $('btnOkModal').disabled = true;
      $('btnCancelarModal').disabled = true;
      mostrar($('erroModal'), '');
      try {
        if (pedirPin) {
          await chamar('POST', '/api/confirmar', { pin: $('pinModal').value });
          $('pinModal').hidden = true;   // confirmado: uma nova tentativa da ação não reenvia o PIN
        }
        const r = await fazer();
        fechar(r);
      } catch (e) {
        if (meu !== modalAtual) return;
        if (e instanceof PrecisaPin) {
          $('pinModal').hidden = false;
          $('pinModal').value = '';
          $('pinModal').focus();
          mostrar($('erroModal'), 'Por segurança, digite o PIN de novo.');
        } else {
          let msg = e.message;
          if (e.dados && e.dados.restantes) msg += ` (mais ${e.dados.restantes} tentativa(s) antes de bloquear)`;
          mostrar($('erroModal'), msg);
          $('pinModal').value = '';
        }
      } finally {
        if (meu === modalAtual) {
          $('btnOkModal').disabled = false;
          $('btnCancelarModal').disabled = false;
        }
      }
    };
  });
}

/** A TV está em uso agora? Devolve o nome do que está na tela, ou '' se está em espera ou na tela inicial. */
async function tvEmUso() {
  await atualizarEstado().catch(() => {});
  const naTela = ultimoEstado.naTela || '';
  return (naTela && naTela !== 'em espera' && naTela !== 'na tela inicial' && naTela !== 'ligada') ? naTela : '';
}

// ---------- aplicativos ----------

let usoHoje = {};
async function carregarApps() {
  mostrar($('statusApps'), 'Carregando…');
  try {
    chamar('GET', '/api/uso').then((u) => { usoHoje = u.minutos || {}; desenharApps(); }).catch(() => {});
    const r = await chamar('GET', '/api/apps');
    apps = r.apps.sort((a, b) => a.nome.localeCompare(b.nome, 'pt-BR'));
    desenharApps();
  } catch (e) {
    mostrar($('statusApps'), 'Não consegui listar: ' + e.message);
  }
}

$('busca').addEventListener('input', desenharApps);
// "apps do sistema" vem marcado (todos os apps) e o navegador lembra a escolha.
try { if (localStorage.getItem('mostrarSistema') === '0') $('mostrarSistema').checked = false; } catch (e) { /* sem armazenamento */ }
$('mostrarSistema').addEventListener('change', () => {
  try { localStorage.setItem('mostrarSistema', $('mostrarSistema').checked ? '1' : '0'); } catch (e) { /* tanto faz */ }
  desenharApps();
});

/** Sem "apps do sistema": só os instalados por você, os que têm tela de abrir e os ocultos (para poder reexibir). */
function visivelNaLista(a) {
  if ($('mostrarSistema').checked) return true;
  return !a.sistema || a.abrivel || a.oculto;
}

function selo(texto, classe) {
  const s = document.createElement('span');
  s.className = 'selo' + (classe ? ' ' + classe : '');
  s.textContent = texto;
  return s;
}

function desenharApps() {
  const termo = $('busca').value.trim().toLowerCase();
  const lista = $('listaApps');
  lista.replaceChildren();
  const filtrados = apps.filter((a) => visivelNaLista(a) &&
    (!termo || a.nome.toLowerCase().includes(termo) || a.pacote.toLowerCase().includes(termo)));
  mostrar($('statusApps'), filtrados.length ? '' : 'Nenhum app.');
  const sistema = apps.filter((a) => a.sistema).length;
  $('contagemApps').textContent = `${filtrados.length} de ${apps.length} apps na TV (${apps.length - sistema} instalados por você, ${sistema} do sistema)`;
  for (const a of filtrados) lista.appendChild(linhaApp(a));
}

function linhaApp(a) {
  const linha = document.createElement('div');
  linha.className = 'linha-app';

  const img = document.createElement('img');
  img.loading = 'lazy';
  img.alt = '';
  img.src = '/api/apps/' + encodeURIComponent(a.pacote) + '/icone';
  linha.appendChild(img);

  const info = document.createElement('div');
  info.className = 'info';
  const nome = document.createElement('span');
  nome.className = 'nome';
  nome.textContent = a.nome;
  const pacote = document.createElement('span');
  pacote.className = 'pacote';
  pacote.textContent = a.pacote + (a.versao ? ' · ' + a.versao : '') + (usoHoje[a.pacote] ? ` · hoje: ${usoHoje[a.pacote]} min` : '');
  const selos = document.createElement('div');
  selos.className = 'selos';
  if (a.oculto) selos.appendChild(selo('oculto', 'oculto'));
  if (a.desativado) selos.appendChild(selo('desativado'));
  if (a.sistema) selos.appendChild(selo('sistema'));
  if (a.ocultoPeloLauncher) selos.appendChild(selo('o launcher oculta sozinho'));
  if (a.risco) selos.appendChild(selo(a.risco, 'risco-' + a.risco.replace(/\s+/g, '-')));
  info.append(nome, pacote, selos);
  linha.appendChild(info);

  const acoes = document.createElement('div');
  acoes.className = 'acoes';
  const bp = document.createElement('button');
  bp.className = 'botao-secundario';
  bp.textContent = 'Detalhes';
  bp.addEventListener('click', () => abrirPermissoes(a));
  acoes.appendChild(bp);
  if (a.protegido) {
    const p = document.createElement('span');
    p.className = 'protegido';
    p.textContent = 'protegido';
    acoes.appendChild(p);
  } else {
    if (a.podeOcultar) {
      const b = document.createElement('button');
      b.className = 'botao-secundario';
      b.textContent = a.oculto ? 'Mostrar' : 'Ocultar';
      b.addEventListener('click', () => alternarOculto(a, b));
      acoes.appendChild(b);
    }
    if (a.podeDesinstalar) {
      const b = document.createElement('button');
      b.className = 'botao-perigo';
      b.textContent = 'Desinstalar';
      b.addEventListener('click', () => desinstalar(a));
      acoes.appendChild(b);
    }
  }
  linha.appendChild(acoes);
  return linha;
}

async function alternarOculto(a, botao) {
  const fazer = () => chamar('POST', '/api/apps/' + encodeURIComponent(a.pacote) + '/ocultar', { oculto: !a.oculto });
  botao.disabled = true;
  try {
    // App que não é "seguro": mostra antes o que para de funcionar (vem da ficha).
    if (!a.oculto && a.risco && a.risco !== 'seguro') {
      const f = await chamar('GET', '/api/apps/' + encodeURIComponent(a.pacote) + '/ficha').catch(() => null);
      const emUso = await tvEmUso();
      const ok = await confirmar('Ocultar ' + a.nome + '?',
        `Risco: ${a.risco}. ${f ? f.efeito : ''}` + (emUso ? ` A TV está em uso agora (${emUso}).` : ''), 'Ocultar mesmo assim', fazer);
      if (!ok) { botao.disabled = false; return; }
      a.oculto = true;
      desenharApps();
      return;
    }
    // Ocultar fecha o app na hora. Com a TV em uso, pergunta antes (a família pode estar vendo justamente ele).
    const emUso = a.oculto ? '' : await tvEmUso();
    if (emUso) {
      const ok = await confirmar('Ocultar ' + a.nome + '?',
        `A TV está em uso agora (${emUso}). Ocultar fecha o app na hora, se for ele que está na tela.`, 'Ocultar', fazer);
      if (!ok) { botao.disabled = false; return; }
    } else {
      await fazer();
    }
    a.oculto = !a.oculto;
    desenharApps();
  } catch (e) {
    mostrar($('statusApps'), `${a.nome}: ${e.message}`);
    botao.disabled = false;
  }
}

async function desinstalar(a) {
  const emUso = await tvEmUso();
  const texto = `Remove o app ${a.nome} e os dados dele da TV. Não tem volta.` +
    (emUso ? ` Atenção: a TV está em uso agora (${emUso}).` : '');
  const ok = await confirmar('Desinstalar ' + a.nome + '?', texto, 'Desinstalar',
    () => chamar('POST', '/api/apps/' + encodeURIComponent(a.pacote) + '/desinstalar', {}));
  if (ok) {
    apps = apps.filter((x) => x.pacote !== a.pacote);
    desenharApps();
    mostrar($('statusApps'), `${a.nome} desinstalado.`);
  }
}

// ---------- backup ----------

$('arquivoBackup').addEventListener('change', async (ev) => {
  const arquivo = ev.target.files[0];
  ev.target.value = '';
  if (!arquivo) return;
  const status = $('statusBackup');
  let texto;
  try {
    texto = await arquivo.text();
    JSON.parse(texto);   // confere antes de mandar; o launcher confere tudo de novo
  } catch (e) {
    mostrar(status, 'Esse arquivo não é um backup válido (JSON).');
    return;
  }
  const emUso = await tvEmUso();
  const r = await confirmar('Restaurar backup?',
    `Substitui as configurações do launcher pelas de "${arquivo.name}" e reinicia o launcher na TV (a tela pisca).` +
    (emUso ? ` Atenção: a TV está em uso agora (${emUso}).` : ''),
    'Restaurar', () => chamar('POST', '/api/backup/restaurar', undefined, texto));
  if (!r) return;
  mostrar(status, `Restaurado (${r.chaves} configurações). O launcher está reiniciando…`);
  esperarVoltar(status);
});

/** Depois de restaurar, o launcher reinicia: espera o painel responder de novo e pede o PIN (as sessões caem). */
function esperarVoltar(status) {
  let tentativas = 0;
  const t = setInterval(async () => {
    tentativas++;
    try {
      const r = await fetch('/api/estado', { cache: 'no-store' });
      if (r.ok) { clearInterval(t); mostrarEntrar('Backup restaurado e launcher reiniciado. Entre de novo com o PIN.'); }
    } catch (e) {
      if (tentativas > 30) { clearInterval(t); mostrar(status, 'O launcher não voltou em 1 min. Confira a TV.'); }
    }
  }, 2000);
}

/** Baixa um arquivo pelo chamar() (que explica sessão expirada e "não existe" em português) e salva pelo navegador. */
async function baixar(caminho, nome, status) {
  mostrar(status, '');
  try {
    let resp;
    try { resp = await fetch(caminho, { credentials: 'same-origin', cache: 'no-store' }); } catch (e) { throw new Error('Não consegui falar com a TV.'); }
    if (resp.status === 401) { mostrarEntrar('Sessão expirada: entre de novo.'); return; }
    if (!resp.ok) { const d = await resp.json().catch(() => ({})); throw new Error(d.erro || `Falha (${resp.status})`); }
    const url = URL.createObjectURL(await resp.blob());
    const a = document.createElement('a');
    a.href = url; a.download = nome;
    document.body.appendChild(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 10000);
  } catch (e) {
    mostrar(status, e.message);
  }
}
$('btnBaixar').addEventListener('click', () => baixar('/api/backup', 'launcher-backup.json', $('statusBaixar')));
$('btnBaixarAnterior').addEventListener('click', () => baixar('/api/backup/anterior', 'launcher-antes-de-restaurar.json', $('statusBackup')));

// ---------- cópias automáticas (BackupAutomatico.kt) ----------

/** "backup-20261105.json" -> "05/11/2026". */
function diaDaCopia(nome) {
  const m = /^backup-(\d{4})(\d{2})(\d{2})\.json$/.exec(nome);
  return m ? `${m[3]}/${m[2]}/${m[1]}` : nome;
}

async function carregarCopias() {
  const lista = $('listaCopias');
  const status = $('statusCopias');
  lista.replaceChildren();
  mostrar(status, '');
  let r;
  try { r = await chamar('GET', '/api/backup/automaticos'); } catch (e) { mostrar(status, e.message); return; }
  if (!r.copias.length) { mostrar(status, 'Nenhuma cópia ainda: a primeira sai quando a HOME abrir.'); return; }
  for (const c of r.copias) {
    const linha = document.createElement('div');
    linha.className = 'linha-registro';
    const dia = document.createElement('span');
    dia.className = 'quando';
    dia.textContent = `${diaDaCopia(c.nome)} · ${Math.max(1, Math.round(c.tamanho / 1024))} KB`;
    const baixarBtn = document.createElement('button');
    baixarBtn.type = 'button'; baixarBtn.className = 'link'; baixarBtn.textContent = 'baixar';
    baixarBtn.addEventListener('click', () => baixar(`/api/backup/automatico?nome=${encodeURIComponent(c.nome)}`, `launcher-${c.nome}`, status));
    const voltarBtn = document.createElement('button');
    voltarBtn.type = 'button'; voltarBtn.className = 'link'; voltarBtn.textContent = 'voltar a este dia';
    voltarBtn.addEventListener('click', async () => {
      const emUso = await tvEmUso();
      const ok = await confirmar('Voltar as configurações?',
        `Substitui as configurações do launcher pelas de ${diaDaCopia(c.nome)} e reinicia o launcher na TV (a tela pisca).` +
        (emUso ? ` Atenção: a TV está em uso agora (${emUso}).` : ''),
        'Voltar', () => chamar('POST', `/api/backup/automatico/restaurar?nome=${encodeURIComponent(c.nome)}`));
      if (!ok) return;
      mostrar(status, `Restaurado (${ok.chaves} configurações). O launcher está reiniciando…`);
      esperarVoltar(status);
    });
    linha.append(dia, ' ', baixarBtn, ' · ', voltarBtn);
    lista.appendChild(linha);
  }
}

// ---------- saúde (PainelSaude.kt) ----------

function mb(b) { return `${Math.round(b / 1048576).toLocaleString('pt-BR')} MB`; }
function gb(b) { return `${(b / 1073741824).toLocaleString('pt-BR', { maximumFractionDigits: 1 })} GB`; }
function duracao(ms) {
  const min = Math.floor(ms / 60000), h = Math.floor(min / 60), d = Math.floor(h / 24);
  return d ? `${d} d ${h % 24} h` : h ? `${h} h ${min % 60} min` : `${min} min`;
}

function desenharNumeros(s) {
  const caixa = $('numerosSaude');
  caixa.replaceChildren();
  const item = (rotulo, valor, ruim) => {
    const d = document.createElement('div');
    d.className = 'numero' + (ruim ? ' ruim' : '');
    const v = document.createElement('strong'); v.textContent = valor;
    const r = document.createElement('span'); r.textContent = rotulo;
    d.append(v, r); caixa.appendChild(d);
  };
  item('RAM livre', `${mb(s.ramLivre)} de ${mb(s.ramTotal)}`, s.poucaMemoria);
  if (s.swapTotal) item('swap livre', `${mb(s.swapLivre)} de ${mb(s.swapTotal)}`, s.swapLivre < s.swapTotal * 0.1);
  if (s.discoTotal) item('espaço livre', `${gb(s.discoLivre)} de ${gb(s.discoTotal)}`, s.discoLivre < s.discoTotal * 0.1);
  const maisQuente = (s.temperaturas || []).reduce((a, t) => (!a || t.celsius > a.celsius ? t : a), null);
  if (maisQuente) item('temperatura', `${maisQuente.celsius.toLocaleString('pt-BR')} °C`, maisQuente.celsius >= 80);
  if (s.termico) item('estado térmico', s.termico, !['normal', 'desconhecido'].includes(s.termico));
  item('ligada há', duracao(s.ligadaHa), false);
}

async function carregarSaude() {
  const status = $('statusSaude');
  const lista = $('listaMemoria');
  mostrar(status, '');
  try {
    desenharNumeros(await chamar('GET', '/api/saude?rapido=1'));   // os números na hora; a lista leva uns segundos
    lista.replaceChildren(Object.assign(document.createElement('p'), { className: 'aviso', textContent: 'Lendo…' }));
    const s = await chamar('GET', '/api/saude');
    desenharNumeros(s);
    lista.replaceChildren();
    if (s.semShell) { mostrar(status, 'O shell do launcher não respondeu: sem a lista de memória e sem liberar (a depuração sem fio está ligada?).'); return; }
    for (const a of s.apps || []) {
      const linha = document.createElement('div');
      linha.className = 'linha-registro';
      const q = document.createElement('span'); q.className = 'quando'; q.textContent = mb(a.kb * 1024);
      linha.append(q, ' ', a.nome);
      if (a.nome !== a.processo) { const p = document.createElement('span'); p.className = 'aviso-curto'; p.textContent = ` ${a.processo}`; linha.appendChild(p); }
      lista.appendChild(linha);
    }
  } catch (e) { mostrar(status, e.message); }
}

$('btnLiberar').addEventListener('click', async () => {
  const b = $('btnLiberar');
  b.disabled = true;
  mostrar($('statusSaude'), 'Liberando…');
  try {
    const r = await chamar('POST', '/api/saude/liberar');
    mostrar($('statusSaude'), r.ganho > 0 ? `Pronto: ${mb(r.ganho)} a mais de RAM livre.` : 'Pronto. Não havia muito o que fechar.');
    await carregarSaude().catch(() => {});
    if (r.ganho > 0) mostrar($('statusSaude'), `Pronto: ${mb(r.ganho)} a mais de RAM livre.`);
  } catch (e) { mostrar($('statusSaude'), e.message); }
  b.disabled = false;
});

// ---------- fotos do protetor (FotosProtetor.kt) ----------

/** Nome que a TV aceita: troca o que não for letra/número/espaço/.-_ por "_" e garante a extensão. */
function nomeDeFoto(nome) {
  const ext = (/\.(jpe?g|png|webp)$/i.exec(nome) || ['.jpg'])[0].toLowerCase();
  let base = nome.replace(/\.[^.]*$/, '').replace(/[^\p{L}\p{N} ._-]/gu, '_').replace(/^\.+/, '').slice(0, 90);
  if (base.length < 1) base = 'foto';
  return base + ext;
}

async function carregarFotos() {
  const grade = $('gradeFotos');
  grade.replaceChildren();
  let r;
  try { r = await chamar('GET', '/api/fotos'); } catch (e) { mostrar($('statusFotos'), e.message); return; }
  if (!r.fotos.length) { mostrar($('statusFotos'), 'Nenhuma foto ainda: o protetor mostra só o relógio.'); return; }
  mostrar($('statusFotos'), `${r.fotos.length} foto(s) na TV.`);
  for (const f of r.fotos) {
    const caixa = document.createElement('figure');
    caixa.className = 'foto';
    const img = document.createElement('img');
    img.loading = 'lazy'; img.alt = f.nome;
    img.src = `/api/fotos/ver?nome=${encodeURIComponent(f.nome)}`;
    const legenda = document.createElement('figcaption');
    const apagar = document.createElement('button');
    apagar.type = 'button'; apagar.className = 'link'; apagar.textContent = 'apagar';
    apagar.addEventListener('click', async () => {
      try { await chamar('POST', `/api/fotos/apagar?nome=${encodeURIComponent(f.nome)}`); carregarFotos(); }
      catch (e) { mostrar($('statusFotos'), e.message); }
    });
    legenda.append(f.nome, ' · ', apagar);
    caixa.append(img, legenda);
    grade.appendChild(caixa);
  }
}

$('arquivoFotos').addEventListener('change', async (ev) => {
  const arquivos = [...ev.target.files];
  ev.target.value = '';
  let ok = 0;
  for (const [i, a] of arquivos.entries()) {
    try {
      await enviarComProgresso(`/api/fotos/enviar?nome=${encodeURIComponent(nomeDeFoto(a.name))}`, a, (p) =>
        mostrar($('statusFotos'), `Enviando ${i + 1} de ${arquivos.length}… ${Math.round(p * 100)}%`));
      ok++;
    } catch (e) { mostrar($('statusFotos'), `${a.name}: ${e.message}`); await new Promise((r) => setTimeout(r, 1500)); }
  }
  await carregarFotos();
  if (arquivos.length) mostrar($('statusFotos'), `${ok} de ${arquivos.length} foto(s) enviada(s).`);
});

// ---------- Shizuku (PainelShizuku.kt) ----------

async function carregarShizuku() {
  const lista = $('listaShizuku'), hist = $('histShizuku'), status = $('statusShizuku');
  lista.replaceChildren(); hist.replaceChildren(); mostrar(status, '');
  let r;
  try { r = await chamar('GET', '/api/shizuku'); } catch (e) { mostrar(status, e.message); return; }
  $('estadoShizuku').textContent = `Modo: ${r.modo}. Servidor ${r.rodando ? 'rodando' : 'parado'}.` +
    (r.rodando ? '' : ' Com ele parado não dá para ver nem mudar quem tem acesso (ele sobe quando um desses apps abre).');
  if (!r.apps.length) lista.appendChild(Object.assign(document.createElement('p'), { className: 'aviso', textContent: 'Nenhum app instalado pede o Shizuku.' }));
  for (const a of r.apps) {
    const linha = document.createElement('div');
    linha.className = 'linha-registro';
    const estado = a.permitido === undefined ? 'estado desconhecido' : a.permitido ? 'permitido' : 'sem acesso';
    linha.append(`${a.nome} · ${estado} `);
    if (a.permitido !== undefined) {
      const b = document.createElement('button');
      b.type = 'button'; b.className = 'link'; b.textContent = a.permitido ? 'revogar' : 'permitir';
      b.addEventListener('click', async () => {
        const acao = a.permitido ? 'revogar' : 'permitir';
        const fazer = () => chamar('POST', '/api/shizuku/app', { pacote: a.pacote, acao });
        try {
          if (acao === 'permitir') {
            const ok = await confirmar('Permitir o Shizuku?', `${a.nome} vai poder fazer na TV o que o adb faz.`, 'Permitir', fazer);
            if (!ok) return;
          } else await fazer();
          carregarShizuku();
        } catch (e) { mostrar(status, e.message); }
      });
      linha.appendChild(b);
    }
    lista.appendChild(linha);
  }
  if (!r.historico.length) hist.appendChild(Object.assign(document.createElement('p'), { className: 'aviso', textContent: 'Nenhuma subida registrada ainda.' }));
  for (const h of r.historico) {
    const linha = document.createElement('div');
    linha.className = 'linha-registro';
    const q = document.createElement('span'); q.className = 'quando'; q.textContent = new Date(h.t).toLocaleString('pt-BR');
    linha.append(q, ' ', h.nome);
    hist.appendChild(linha);
  }
}

// ---------- acessos ----------

async function carregarRegistro() {
  const lista = $('listaRegistro');
  lista.replaceChildren();
  try {
    const r = await chamar('GET', '/api/registro');
    if (!r.itens.length) { const p = document.createElement('p'); p.className = 'aviso'; p.textContent = 'Nenhum acesso ainda.'; lista.appendChild(p); }
    for (const i of r.itens) {
      const linha = document.createElement('div');
      linha.className = 'linha-registro';
      const quando = document.createElement('span');
      quando.className = 'quando';
      quando.textContent = new Date(i.quando).toLocaleString('pt-BR') + ' · ' + i.ip;
      const oque = document.createElement('span');
      oque.textContent = `${i.acao}: ${i.resultado}`;
      linha.append(quando, oque);
      lista.appendChild(linha);
    }
  } catch (e) {
    const p = document.createElement('p'); p.className = 'erro'; p.textContent = e.message; lista.appendChild(p);
  }
}

// ---------- roteador UniFi (só leitura; UnifiCliente.kt) ----------

const TIPOS_APARELHO = { WIRED: 'cabo', WIRELESS: 'Wi-Fi', VPN: 'VPN', TELEPORT: 'Teleport' };
let certLido = null;

function desenharRoteador(r) {
  if (document.activeElement !== $('campoEnderecoRoteador')) $('campoEnderecoRoteador').value = r.endereco || '';
  $('campoChaveRoteador').value = '';
  $('chaveAtualRoteador').textContent = r.chave ? `(guardada: ${r.chave})` : '(nenhuma guardada)';
  $('certAtualRoteador').textContent = r.certificado ? `Confirmado: ${r.certificado}` : 'Nenhum certificado confirmado ainda.';
  $('chkRoteadorLigado').checked = r.ligado;
  $('chkRoteadorAviso').checked = r.avisoNovos;
  $('infoConhecidos').textContent = r.avisoNovos ? `${r.conhecidos} aparelho(s) já conhecidos.` : '';
  $('certNovoRoteador').hidden = true;
}

async function carregarRoteador() {
  try {
    const r = await chamar('GET', '/api/roteador');
    desenharRoteador(r);
    if (r.pronto) carregarAparelhos(false);
    else { $('listaAparelhos').replaceChildren(); $('estadoRoteador').textContent = faltaRoteador(r); }
  } catch (e) { mostrar($('statusRoteador'), e.message); }
}

function faltaRoteador(r) {
  if (!r.endereco) return 'Falta o endereço do roteador.';
  if (!r.chave) return 'Falta a chave de API.';
  if (!r.certificado) return 'Falta confirmar o certificado.';
  if (!r.ligado) return 'A leitura está desligada.';
  return '';
}

async function carregarAparelhos(atualizar) {
  const lista = $('listaAparelhos');
  $('estadoRoteador').textContent = 'Lendo o roteador…';
  try {
    const r = await chamar('GET', '/api/roteador/aparelhos' + (atualizar ? '?atualizar=1' : ''));
    $('estadoRoteador').textContent = `Roteador respondendo em ${r.respostaMs} ms` + (r.versao ? ` · UniFi Network ${r.versao}` : '') +
      ` · ${r.aparelhos.length} aparelho(s) · lido às ${new Date(r.em).toLocaleTimeString('pt-BR')}`;
    lista.replaceChildren();
    for (const a of r.aparelhos) {
      const linha = document.createElement('div');
      linha.className = 'linha-registro';
      const nome = document.createElement('span');
      nome.textContent = a.nome;
      const det = document.createElement('span');
      det.className = 'quando';
      const desde = a.desde ? ' · desde ' + new Date(a.desde).toLocaleString('pt-BR') : '';
      det.textContent = [a.ip, TIPOS_APARELHO[a.tipo] || a.tipo, a.mac].filter(Boolean).join(' · ') + desde;
      linha.append(nome, det);
      lista.appendChild(linha);
    }
  } catch (e) {
    lista.replaceChildren();
    $('estadoRoteador').textContent = e.message;
  }
}

async function salvarRoteador(corpo, texto) {
  mostrar($('statusRoteador'), 'Salvando…');
  try {
    const r = await chamar('POST', '/api/roteador/config', corpo);
    desenharRoteador(r);
    mostrar($('statusRoteador'), texto || 'Salvo.');
    if (r.pronto) carregarAparelhos(true); else $('estadoRoteador').textContent = faltaRoteador(r);
  } catch (e) { mostrar($('statusRoteador'), e.message); carregarRoteador(); }
}

$('formRoteador').addEventListener('submit', (ev) => {
  ev.preventDefault();
  const corpo = {};
  const end = $('campoEnderecoRoteador').value.trim();
  if (end) corpo.endereco = end;
  const chave = $('campoChaveRoteador').value.trim();
  if (chave) corpo.chave = chave;
  if (!Object.keys(corpo).length) { mostrar($('statusRoteador'), 'Preencha o endereço e/ou a chave.'); return; }
  salvarRoteador(corpo);
});
$('chkRoteadorLigado').addEventListener('change', (ev) => salvarRoteador({ ligado: ev.target.checked }));
$('chkRoteadorAviso').addEventListener('change', (ev) => salvarRoteador({ avisoNovos: ev.target.checked },
  ev.target.checked ? 'Ligado. A primeira leitura só anota os aparelhos de agora; o recado vem para os que aparecerem depois.' : 'Desligado.'));
$('btnAtualizarAparelhos').addEventListener('click', () => carregarAparelhos(true));

$('btnWifiTv').addEventListener('click', async () => {
  const dicas = $('dicasWifiTv');
  dicas.replaceChildren();
  $('estadoWifiTv').textContent = 'Medindo…';
  try {
    const w = await chamar('GET', '/api/roteador/wifi-tv');
    if (!w.noWifi) { $('estadoWifiTv').textContent = 'A TV não está no Wi-Fi (no cabo ou desconectada): nada a ajustar no Wi-Fi.'; return; }
    const partes = [`Sinal ${w.dbm} dBm`];
    if (w.banda) partes.push(`${w.banda.replace('.', ',')} GHz`);
    if (w.mbps > 0) partes.push(`link ${w.mbps} Mbps`);
    if (w.pontoDeAcesso) {
      const ap = w.pontoDeAcesso;
      partes.push(`ligada em ${ap.nome}${ap.modelo ? ' (' + ap.modelo + ')' : ''}`);
      for (const r of ap.radios) {
        const det = [r.canal != null ? `canal ${r.canal}` : '', r.larguraMhz ? `${r.larguraMhz} MHz` : '',
          r.retransmissoesPct != null ? `${Math.round(r.retransmissoesPct)}% retransmissões` : ''].filter(Boolean).join(', ');
        if (det) partes.push(`${r.ghz.replace('.', ',')} GHz: ${det}`);
      }
    } else if (!w.achadaNoRoteador) partes.push('a TV não apareceu na lista do roteador (sem dados do ponto de acesso)');
    $('estadoWifiTv').textContent = partes.join(' · ');
    const lista = w.dicas.length ? w.dicas : ['Wi-Fi da TV está bom: nada a ajustar.'];
    for (const d of lista) { const li = document.createElement('li'); li.textContent = d; dicas.appendChild(li); }
  } catch (e) { $('estadoWifiTv').textContent = e.message; }
});

$('btnLerCert').addEventListener('click', async () => {
  $('certNovoRoteador').hidden = true;
  mostrar($('statusRoteador'), 'Lendo o certificado (a chave não vai junto)…');
  try {
    const c = await chamar('POST', '/api/roteador/certificado');
    certLido = c.impressao;
    $('certNovoTexto').textContent = `SHA-256: ${c.impressao}\nEmitido para: ${c.emitidoPara}\nVálido até: ${new Date(c.validoAte).toLocaleDateString('pt-BR')}`;
    $('certNovoRoteador').hidden = c.igualAoConfirmado;
    mostrar($('statusRoteador'), c.igualAoConfirmado ? 'É o mesmo certificado já confirmado.' : 'Confira a impressão antes de confiar.');
  } catch (e) { mostrar($('statusRoteador'), e.message); }
});

$('btnConfiarCert').addEventListener('click', async () => {
  try {
    const r = await chamar('POST', '/api/roteador/confiar', { impressao: certLido });
    desenharRoteador(r);
    mostrar($('statusRoteador'), 'Certificado confirmado.');
    if (r.pronto) carregarAparelhos(true); else $('estadoRoteador').textContent = faltaRoteador(r);
  } catch (e) { mostrar($('statusRoteador'), e.message); }
});

$('btnEsquecerRoteador').addEventListener('click', async () => {
  const r = await confirmar('Esquecer o roteador?', 'Apaga o endereço, a chave guardada, o certificado e a lista de aparelhos conhecidos.',
    'Esquecer', () => chamar('POST', '/api/roteador/esquecer'));
  if (r) { desenharRoteador(r); $('listaAparelhos').replaceChildren(); $('estadoRoteador').textContent = faltaRoteador(r); }
});

// ---------- envio com progresso (instalar APK e aba Arquivos) ----------

/**
 * Manda um arquivo inteiro como corpo do POST, mostrando o progresso. fetch() não informa o progresso do envio,
 * por isso XMLHttpRequest. O arquivo vai cru (sem multipart): o launcher grava direto no disco.
 */
function enviarComProgresso(url, arquivo, aoProgresso) {
  return new Promise((resolve, reject) => {
    const x = new XMLHttpRequest();
    x.open('POST', url);
    x.setRequestHeader('X-Painel', '1');
    x.setRequestHeader('Content-Type', 'application/octet-stream');
    x.upload.onprogress = (ev) => { if (ev.lengthComputable && aoProgresso) aoProgresso(ev.loaded / ev.total); };
    x.onload = () => {
      let dados = {};
      try { dados = JSON.parse(x.responseText); } catch (e) { /* sem corpo */ }
      if (x.status === 401) { mostrarEntrar('Sessão expirada: entre de novo.'); reject(new Error('Sessão expirada.')); return; }
      if (x.status === 403 && dados.precisaPin) { reject(new PrecisaPin(dados.erro)); return; }
      if (x.status < 200 || x.status >= 300) { const e = new Error(dados.erro || `Falha (${x.status})`); e.dados = dados; reject(e); return; }
      resolve(dados);
    };
    // A TV fecha a conexão quando recusa no meio (sem espaço, nome repetido) ou quando a rede cai.
    x.onerror = () => reject(new Error('A conexão com a TV caiu no meio do envio (rede, ou a TV recusou o arquivo).'));
    x.send(arquivo);
  });
}

/** A sessão já tem o PIN confirmado nos últimos 5 min? Senão, pede ANTES de mandar um arquivo grande à toa. */
async function exigirPinRecente() {
  await atualizarEstado();
  if (!ultimoEstado.elevada) throw new PrecisaPin('confirme o PIN');
}

function tamanhoLegivel(b) {
  if (b < 1024) return b + ' B';
  const u = ['KB', 'MB', 'GB', 'TB'];
  let i = -1;
  do { b /= 1024; i++; } while (b >= 1024 && i < u.length - 1);
  return b.toLocaleString('pt-BR', { maximumFractionDigits: b < 10 ? 1 : 0 }) + ' ' + u[i];
}

// ---------- instalar APK ----------

$('arquivoApk').addEventListener('change', async (ev) => {
  const arquivo = ev.target.files[0];
  ev.target.value = '';
  if (!arquivo) return;
  if (!/\.apk$/i.test(arquivo.name)) { mostrar($('statusApps'), 'Escolha um arquivo .apk (.apks e .xapk ainda não são aceitos).'); return; }
  const emUso = await tvEmUso();
  const r = await confirmar('Instalar ' + arquivo.name + '?',
    `Instala (ou atualiza) o app na TV, sem pedir confirmação lá (${tamanhoLegivel(arquivo.size)}). ` +
    'Só instale APK de fonte confiável: o app pode pedir permissões.' +
    (emUso ? ` Atenção: a TV está em uso agora (${emUso}); atualizar um app que está aberto fecha ele.` : ''),
    'Instalar', async () => {
      await exigirPinRecente();
      return enviarComProgresso('/api/apps/instalar?nome=' + encodeURIComponent(arquivo.name), arquivo, (f) =>
        mostrar($('progressoModal'), f < 1 ? `Enviando… ${Math.round(f * 100)}%` : 'Instalando na TV…'));
    });
  if (!r) return;
  mostrar($('statusApps'), `${r.nome} ${r.versao} instalado.`);
  carregarApps().then(() => mostrar($('statusApps'), `${r.nome} ${r.versao} instalado.`));
});

// ---------- permissões ----------

let permAtual = 0;   // resposta atrasada de um app aberto antes não sobrescreve o atual

function fecharPermissoes() { permAtual++; $('modalPerm').hidden = true; }
$('btnFecharPerm').addEventListener('click', fecharPermissoes);

async function abrirPermissoes(a) {
  const meu = ++permAtual;
  $('tituloPerm').textContent = a.nome;
  $('avisoPerm').textContent = 'Carregando…';
  mostrar($('statusPerm'), '');
  $('listaPerm').replaceChildren();
  $('modalPerm').hidden = false;
  try {
    const base = '/api/apps/' + encodeURIComponent(a.pacote);
    const [f, p] = await Promise.all([chamar('GET', base + '/ficha'), chamar('GET', base + '/permissoes')]);
    if (meu !== permAtual) return;
    desenharPermissoes(a, p);
    $('listaPerm').prepend(desenharFicha(f), acoesDoApp(a));
  } catch (e) {
    if (meu === permAtual) $('avisoPerm').textContent = e.message;
  }
}

function linhaPerm(rotulo, nome, direita) {
  const l = document.createElement('div');
  l.className = 'linha-perm';
  const info = document.createElement('div');
  info.className = 'info';
  const n = document.createElement('span'); n.className = 'nome'; n.textContent = rotulo;
  const t = document.createElement('span'); t.className = 'pacote'; t.textContent = nome;
  info.append(n, t);
  l.append(info, direita);
  return l;
}

function estadoTexto(sim, textoSim, textoNao) {
  const s = document.createElement('span');
  s.className = sim ? 'estado-sim' : 'estado-nao';
  s.textContent = sim ? textoSim : textoNao;
  return s;
}

/** Uma caixa de escolha que, ao mudar, chama a TV; se der errado, volta ao valor de antes e mostra o motivo. */
function escolha(opcoes, valor, aoMudar, travada) {
  const sel = document.createElement('select');
  for (const [v, t] of opcoes) { const o = document.createElement('option'); o.value = v; o.textContent = t; sel.appendChild(o); }
  sel.value = valor;
  sel.disabled = !!travada;
  let anterior = valor;
  sel.addEventListener('change', async () => {
    sel.disabled = true;
    mostrar($('statusPerm'), 'Aplicando…');
    try {
      await aoMudar(sel.value);
      anterior = sel.value;
      mostrar($('statusPerm'), 'Feito.');
    } catch (e) {
      sel.value = anterior;
      mostrar($('statusPerm'), e.message);
    } finally {
      sel.disabled = false;
    }
  });
  return sel;
}

/** A ficha do app: o que faz, o risco de ocultar ou desinstalar, papéis e quem depende dele. */
function desenharFicha(f) {
  const c = document.createElement('div');
  c.className = 'ficha';
  const topo = document.createElement('div');
  topo.className = 'selos';
  topo.appendChild(selo(f.risco, 'risco-' + f.risco.replace(/\s+/g, '-')));
  if (f.sistema) topo.appendChild(selo('sistema'));
  const d = document.createElement('p'); d.textContent = f.descricao;
  const q = document.createElement('p'); q.className = 'aviso';
  q.textContent = 'Se ocultar ou desinstalar: ' + f.efeito;
  c.append(topo, d, q);
  const lista = (titulo, itens) => {
    if (!itens.length) return;
    const h = document.createElement('h3'); h.textContent = titulo; c.appendChild(h);
    const ul = document.createElement('ul');
    for (const t of itens) { const li = document.createElement('li'); li.textContent = t; ul.appendChild(li); }
    c.appendChild(ul);
  };
  if (f.espaco) {
    const e = document.createElement('p'); e.className = 'aviso-curto';
    e.textContent = `Espaço: app ${tamanhoLegivel(f.espaco.app)} · dados ${tamanhoLegivel(f.espaco.dados)} (cache ${tamanhoLegivel(f.espaco.cache)})`;
    c.appendChild(e);
  }
  lista('O que ele faz nesta TV agora', f.papeis);
  lista('Quem depende dele (pela TV)', f.usadoPor.map((u) => `${u.nome} — ${u.como}`));
  lista('Quem depende dele (pela lista)', f.dependentesConhecidos);
  const fonte = document.createElement('p'); fonte.className = 'aviso-curto';
  fonte.textContent = `Fonte da descrição: ${f.fonte}. Papéis e dependências "pela TV" são lidos do aparelho agora.`;
  c.appendChild(fonte);
  return c;
}

function desenharPermissoes(a, p) {
  const lista = $('listaPerm');
  lista.replaceChildren();
  const base = '/api/apps/' + encodeURIComponent(a.pacote);
  $('avisoPerm').textContent = p.podeMudar
    ? 'Negar uma permissão fecha o app se ele estiver aberto. "O app pede" devolve a decisão para quem estiver na TV.'
    : 'Este app é protegido: o sistema ou o launcher dependem dele. As permissões aparecem só para consulta.';

  const titulo = (t) => { const h = document.createElement('h3'); h.textContent = t; lista.appendChild(h); };

  titulo(`Permissões que se trocam (${p.perigosas.length})`);
  if (!p.perigosas.length) { const x = document.createElement('p'); x.className = 'aviso'; x.textContent = 'Nenhuma: este app não pede localização, microfone, câmera, contatos etc.'; lista.appendChild(x); }
  for (const perm of p.perigosas) {
    const direita = document.createElement('div');
    direita.className = 'acoes';
    const est = estadoTexto(perm.concedida, 'concedida', 'negada');
    const sel = escolha([['padrao', 'O app pede'], ['conceder', 'Sempre permitir'], ['negar', 'Sempre negar']], perm.politica,
      async (v) => {
        await chamar('POST', base + '/permissao', { permissao: perm.nome, acao: v });
        // "O app pede" não muda o que já estava concedido; "sempre" muda.
        if (v === 'conceder') { est.className = 'estado-sim'; est.textContent = 'concedida'; }
        if (v === 'negar') { est.className = 'estado-nao'; est.textContent = 'negada'; }
      }, !p.podeMudar);
    direita.append(est, sel);
    lista.appendChild(linhaPerm(perm.rotulo, perm.nome, direita));
  }

  if (p.especiais.length) {
    titulo(`Acessos especiais (${p.especiais.length})`);
    for (const esp of p.especiais) {
      const direita = document.createElement('div');
      direita.className = 'acoes';
      const est = estadoTexto(esp.estado === 'permitido', 'permitido', esp.estado === 'desconhecido' ? '?' : 'negado');
      const sel = escolha([['padrao', 'Padrão'], ['permitir', 'Permitir'], ['negar', 'Negar']],
        esp.padrao ? 'padrao' : (esp.estado === 'permitido' ? 'permitir' : 'negar'),
        async (v) => {
          await chamar('POST', base + '/especial', { op: esp.op, acao: v });
          if (v !== 'padrao') { const s = v === 'permitir'; est.className = s ? 'estado-sim' : 'estado-nao'; est.textContent = s ? 'permitido' : 'negado'; }
        }, !p.podeMudar);
      direita.append(est, sel);
      lista.appendChild(linhaPerm(esp.rotulo, esp.op, direita));
    }
  }

  if (p.outras.length) {
    const d = document.createElement('details');
    const s = document.createElement('summary');
    s.textContent = `Outras ${p.outras.length} permissões (o Android concede na instalação; só consulta)`;
    d.appendChild(s);
    for (const o of p.outras) d.appendChild(linhaPerm(o.rotulo, o.nome, estadoTexto(o.concedida, 'concedida', 'não concedida')));
    lista.appendChild(d);
  }
}

// ---------- arquivos ----------

let pastaAtual = '';
let itensPasta = [];
let livreBytes = 0;
let pastaPedido = 0;

function juntar(pasta, nome) { return pasta ? pasta + '/' + nome : nome; }

async function abrirPasta(caminho) {
  const meu = ++pastaPedido;
  mostrar($('statusArquivos'), 'Carregando…');
  try {
    const r = await chamar('GET', '/api/arquivos?caminho=' + encodeURIComponent(caminho));
    if (meu !== pastaPedido) return;
    pastaAtual = r.caminho;
    itensPasta = r.itens;
    livreBytes = r.livre;
    desenharTrilha();
    desenharArquivos(r);
  } catch (e) {
    if (meu !== pastaPedido) return;
    mostrar($('statusArquivos'), e.message);
    // Pasta sumiu (apagada na TV): volta para a raiz na próxima vez.
    if (e.dados === undefined || /não existe/.test(e.message)) pastaAtual = '';
  }
}

function desenharTrilha() {
  const t = $('trilha');
  t.replaceChildren();
  const partes = pastaAtual ? pastaAtual.split('/') : [];
  const pedacos = [['Memória interna', '']];
  partes.forEach((p, i) => pedacos.push([p, partes.slice(0, i + 1).join('/')]));
  pedacos.forEach(([nome, caminho], i) => {
    if (i > 0) { const sep = document.createElement('span'); sep.textContent = '›'; t.appendChild(sep); }
    if (i === pedacos.length - 1) {
      const s = document.createElement('span'); s.className = 'atual'; s.textContent = nome; t.appendChild(s);
    } else {
      const b = document.createElement('button'); b.textContent = nome; b.addEventListener('click', () => abrirPasta(caminho)); t.appendChild(b);
    }
  });
}

function desenharArquivos(r) {
  const lista = $('listaArquivos');
  lista.replaceChildren();
  $('espacoArquivos').textContent = `Livre na TV: ${tamanhoLegivel(r.livre)} de ${tamanhoLegivel(r.total)}`;
  mostrar($('statusArquivos'), !r.itens.length ? 'Pasta vazia.' : (r.cortado ? 'Pasta grande: mostrando os 2000 primeiros.' : ''));
  for (const it of r.itens) {
    const linha = document.createElement('div');
    linha.className = 'linha-app linha-arquivo';
    const tipo = document.createElement('span');
    tipo.className = 'tipo';
    tipo.textContent = it.pasta ? '📁' : '📄';
    const info = document.createElement('div');
    info.className = 'info';
    const nome = document.createElement('span');
    nome.className = 'nome' + (it.pasta ? ' pasta' : '');
    nome.textContent = it.nome;
    if (it.pasta) nome.addEventListener('click', () => abrirPasta(juntar(pastaAtual, it.nome)));
    const det = document.createElement('span');
    det.className = 'pacote';
    det.textContent = (it.pasta ? 'pasta' : tamanhoLegivel(it.tamanho)) + ' · ' + new Date(it.modificado).toLocaleString('pt-BR');
    info.append(nome, det);
    const acoes = document.createElement('div');
    acoes.className = 'acoes';
    if (!it.pasta) {
      // Link comum: o navegador baixa direto da TV (com barra de progresso dele), sem passar o arquivo pela memória.
      const a = document.createElement('a');
      a.className = 'botao-secundario';
      a.textContent = 'Baixar';
      a.href = '/api/arquivos/baixar?caminho=' + encodeURIComponent(juntar(pastaAtual, it.nome));
      a.setAttribute('download', it.nome);
      acoes.appendChild(a);
    }
    const apagar = document.createElement('button');
    apagar.className = 'botao-perigo';
    apagar.textContent = 'Apagar';
    apagar.addEventListener('click', () => apagarItem(it));
    acoes.appendChild(apagar);
    linha.append(tipo, info, acoes);
    lista.appendChild(linha);
  }
}

async function apagarItem(it) {
  const caminho = juntar(pastaAtual, it.nome);
  const ok = await confirmar('Apagar ' + it.nome + '?',
    it.pasta ? 'Apaga a pasta e TUDO o que está dentro dela, na TV. Não tem volta.' : 'Apaga o arquivo da TV. Não tem volta.',
    'Apagar', () => chamar('POST', '/api/arquivos/apagar', { caminho }));
  if (ok) abrirPasta(pastaAtual);
}

$('formPasta').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const nome = $('nomePasta').value.trim();
  if (!nome) return;
  if (/[\/\\]/.test(nome) || nome === '.' || nome === '..') { mostrar($('statusArquivos'), 'Nome de pasta inválido.'); return; }
  try {
    await chamar('POST', '/api/arquivos/pasta', { pasta: pastaAtual, nome });
    $('nomePasta').value = '';
    abrirPasta(pastaAtual);
  } catch (e) {
    mostrar($('statusArquivos'), e.message);
  }
});

$('arquivosEnviar').addEventListener('change', async (ev) => {
  const arquivos = Array.from(ev.target.files);
  ev.target.value = '';
  if (!arquivos.length) return;
  const total = arquivos.reduce((s, f) => s + f.size, 0);
  const status = $('statusArquivos');
  if (total > livreBytes) { mostrar(status, `Não cabe: são ${tamanhoLegivel(total)} e a TV tem ${tamanhoLegivel(livreBytes)} livres.`); return; }
  const destino = pastaAtual;
  const existentes = new Set(itensPasta.map((i) => i.nome));
  const repetidos = arquivos.filter((f) => existentes.has(f.name));
  let substituir = false;
  if (repetidos.length) {
    substituir = await confirmar('Substituir?',
      `Já existe(m) na pasta: ${repetidos.map((f) => f.name).join(', ')}. Substituir pelos novos?`, 'Substituir', async () => true);
    if (!substituir) return;
  }
  let feitos = 0;
  for (const [i, f] of arquivos.entries()) {
    const url = '/api/arquivos/enviar?pasta=' + encodeURIComponent(destino) + '&nome=' + encodeURIComponent(f.name) +
      (substituir && existentes.has(f.name) ? '&substituir=1' : '');
    try {
      await enviarComProgresso(url, f, (p) =>
        mostrar(status, `Enviando ${i + 1} de ${arquivos.length}: ${f.name} — ${Math.round(p * 100)}%`));
      feitos++;
    } catch (e) {
      mostrar(status, `${f.name}: ${e.message}`);
      if (pastaAtual === destino) await abrirPasta(destino);
      return;
    }
  }
  await abrirPasta(destino);
  mostrar(status, `${feitos} arquivo(s) enviado(s).`);
});

// ---------- ações do app (no Detalhes) ----------

function acoesDoApp(a) {
  const c = document.createElement('div');
  c.className = 'acoes acoes-app';
  const base = '/api/apps/' + encodeURIComponent(a.pacote);
  const botao = (texto, classe, fazer) => {
    const b = document.createElement('button');
    b.className = classe; b.textContent = texto;
    b.addEventListener('click', async () => {
      b.disabled = true;
      try { const msg = await fazer(); if (msg) mostrar($('statusPerm'), msg); } catch (e) { mostrar($('statusPerm'), e.message); } finally { b.disabled = false; }
    });
    c.appendChild(b);
  };
  if (a.abrivel) botao('Abrir na TV', 'botao-secundario', async () => {
    const emUso = await tvEmUso();
    if (emUso && !(await confirmar('Abrir ' + a.nome + '?', `A TV está em uso agora (${emUso}). Abrir troca o que está na tela.`, 'Abrir', async () => true))) return '';
    await chamar('POST', base + '/abrir', {}); return a.nome + ' aberto na TV.';
  });
  if (!a.protegido) botao('Parar', 'botao-secundario', async () => {
    const emUso = await tvEmUso();
    const ok = await confirmar('Parar ' + a.nome + '?', 'Fecha o app à força (como "Forçar parada"). Ele volta ao normal quando for aberto de novo.' +
      (emUso ? ` A TV está em uso agora (${emUso}).` : ''), 'Parar', () => chamar('POST', base + '/parar', {}));
    return ok ? a.nome + ' parado.' : '';
  });
  if (!a.protegido && !a.sistema) botao('Limpar dados', 'botao-perigo', async () => {
    const ok = await confirmar('Limpar os dados de ' + a.nome + '?',
      'Apaga login, configurações e downloads do app, como se tivesse acabado de instalar. Não tem volta.', 'Limpar dados',
      () => chamar('POST', base + '/limpar', {}));
    return ok ? 'Dados de ' + a.nome + ' apagados.' : '';
  });
  return c;
}

// ---------- controle: foto da tela e teclas ----------

let fotoTimer = null;
let fotoUrl = null;
let fotoPedindo = false;

function pararFoto() { if (fotoTimer) { clearTimeout(fotoTimer); fotoTimer = null; } }

/** Pede uma foto a cada 2 s, só com a aba Controle aberta e o navegador visível (sem ninguém olhando, custo zero). */
async function atualizarFoto() {
  pararFoto();
  if ($('abaControle').hidden || $('telaPainel').hidden) return;
  if (document.visibilityState === 'visible' && !fotoPedindo) {
    fotoPedindo = true;
    try {
      const r = await fetch('/api/tela/foto', { credentials: 'same-origin', cache: 'no-store' });
      if (r.status === 401) { mostrarEntrar('Sessão expirada: entre de novo.'); return; }
      if (!r.ok) { const d = await r.json().catch(() => ({})); throw new Error(d.erro || `Falha (${r.status})`); }
      const url = URL.createObjectURL(await r.blob());
      $('fotoTela').src = url;
      $('fotoTela').hidden = false;
      $('semFoto').hidden = true;
      if (fotoUrl) URL.revokeObjectURL(fotoUrl);
      fotoUrl = url;
      $('statusTela').textContent = 'Atualiza a cada 2 s · ' + new Date().toLocaleTimeString('pt-BR');
    } catch (e) {
      // Sem foto (TV em espera, por exemplo): troca a imagem pelo motivo, em vez de mostrar uma imagem quebrada.
      $('fotoTela').hidden = true;
      $('semFoto').hidden = false;
      $('semFoto').textContent = e.message;
      $('statusTela').textContent = '';
    } finally {
      fotoPedindo = false;
    }
  }
  fotoTimer = setTimeout(atualizarFoto, 2000);
}
document.addEventListener('visibilitychange', () => { if (!$('abaControle').hidden) atualizarFoto(); });

for (const b of document.querySelectorAll('[data-tecla]')) {
  b.addEventListener('click', async () => {
    try {
      await chamar('POST', '/api/tela/tecla', { tecla: b.dataset.tecla });
      mostrar($('statusControle'), '');
      setTimeout(atualizarFoto, 400);   // mostra logo o efeito da tecla
    } catch (e) { mostrar($('statusControle'), e.message); }
  });
}

$('formTexto').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const t = $('campoTexto').value;
  if (!t) return;
  if (!/^[A-Za-z0-9 .,@_\-]{1,100}$/.test(t)) { mostrar($('statusControle'), 'Só letras sem acento, números, espaço e . , @ _ -'); return; }
  try {
    await chamar('POST', '/api/tela/texto', { texto: t });
    $('campoTexto').value = '';
    mostrar($('statusControle'), '');
    setTimeout(atualizarFoto, 400);
  } catch (e) { mostrar($('statusControle'), e.message); }
});

$('formRecado').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const texto = $('campoRecado').value.trim();
  if (!texto) { mostrar($('statusControle'), 'Escreva o recado.'); return; }
  try {
    const r = await chamar('POST', '/api/recado', {
      texto, segundos: Number($('duracaoRecado').value), som: $('somRecado').checked });
    $('campoRecado').value = '';
    mostrar($('statusControle'), r.situacao === 'guardado'
      ? 'A tela da TV está apagada: o recado aparece quando ela ligar.'
      : 'Recado na tela da TV.');
    setTimeout(atualizarFoto, 600);   // a foto mostra o recado
  } catch (e) { mostrar($('statusControle'), e.message); }
});

$('btnReiniciarTv').addEventListener('click', async () => {
  const emUso = await tvEmUso();
  const ok = await confirmar('Reiniciar a TV?', 'A TV desliga e liga de novo (cerca de 1 min). O painel volta sozinho depois.' +
    (emUso ? ` Atenção: a TV está em uso agora (${emUso}).` : ''), 'Reiniciar', () => chamar('POST', '/api/tv/reiniciar', {}));
  if (ok) mostrar($('statusControle'), 'Reiniciando… o painel volta em cerca de 1 min.');
});

// ---------- Falar: IA na nuvem (PedidoIa.kt) ----------

// Onde pegar a chave de cada IA (só links de documentação; a chave nunca passa por aqui).
const LINKS_CHAVE = {
  gemini: 'https://aistudio.google.com/apikey',
  claude: 'https://platform.claude.com/settings/keys',
  openai: 'https://platform.openai.com/api-keys',
  perplexity: 'https://www.perplexity.ai/account/api/keys',
};
let estadoIa = null;

/** A IA cuja chave/modelo o formulário mostra: a escolhida na lista (que é a ativa, depois de trocar). */
function provedorIa() {
  return estadoIa?.provedores.find((p) => p.id === $('campoProvedor').value) ?? null;
}

async function carregarIa() {
  try {
    const e = estadoIa = await chamar('GET', '/api/ia');
    const sel = $('campoProvedor');
    sel.replaceChildren();
    for (const p of e.provedores) {
      const o = document.createElement('option');
      o.value = p.id;
      o.textContent = p.nome + (p.chave ? ` · chave ${p.chave}` : ' · sem chave');
      sel.appendChild(o);
    }
    sel.value = e.provedor;
    const ativo = provedorIa();
    let aviso = '';
    if (!e.ligada) aviso = 'A IA está desligada. Ligue em Configurações do launcher › IA na nuvem, na TV.';
    else if (!e.chave) aviso = `Falta cadastrar a chave do ${ativo.nome} (abaixo).`;
    else aviso = `Usando ${ativo.nome}. O que você pedir aparece na TV, e só acontece se alguém apertar OK no controle.`;
    $('avisoIa').textContent = aviso;
    $('chaveIa').textContent = (e.chave ? `Chave cadastrada (${e.chave}).` : 'Nenhuma chave.') + ` Modelo: ${e.modelo}.`;
    $('campoModelo').value = e.modelo;
    $('campoModelo').placeholder = ativo.modeloPadrao;
    $('linkChaveIa').href = LINKS_CHAVE[ativo.id];
  } catch (e) { $('avisoIa').textContent = e.message; }
}

// Trocar a IA na lista já troca a ativa na TV (só entre as chaves que você cadastrou).
$('campoProvedor').addEventListener('change', async () => {
  try {
    await chamar('POST', '/api/ia/provedor', { provedor: $('campoProvedor').value });
    mostrar($('statusChave'), '');
  } catch (e) { mostrar($('statusChave'), e.message); }
  carregarIa();
});

/** Mesmo teste do servidor (ComandosIa.detectar): só para avisar, nunca para barrar. */
function detectarIa(chave) {
  if (chave.startsWith('sk-ant-')) return 'claude';
  if (chave.startsWith('pplx-')) return 'perplexity';
  if (chave.startsWith('sk-')) return 'openai';
  if (chave.startsWith('AQ.') || chave.startsWith('AIza')) return 'gemini';
  return null;
}

let pedidoAtual = 0;
$('formFalar').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const frase = $('campoFrase').value.trim();
  if (!frase) return;
  const meu = ++pedidoAtual;
  $('btnFalar').disabled = true;
  $('acoesFalar').hidden = true;
  mostrar($('statusFalar'), 'Perguntando à IA…');
  try {
    let p = await chamar('POST', '/api/ia/pedir', { frase });
    // Acompanha até sair de "pensando"/"esperando" (no máximo ~75 s: 60 s de espera na TV + a IA).
    for (let i = 0; i < 75 && (p.situacao === 'pensando' || p.situacao === 'esperando'); i++) {
      if (meu !== pedidoAtual) return;
      mostrarPedido(p);
      await new Promise((r) => setTimeout(r, 1000));
      p = await chamar('GET', '/api/ia/pedido?id=' + encodeURIComponent(p.id));
    }
    if (meu === pedidoAtual) mostrarPedido(p);
  } catch (e) {
    if (meu === pedidoAtual) mostrar($('statusFalar'), e.message);
  } finally {
    if (meu === pedidoAtual) $('btnFalar').disabled = false;
  }
});

function mostrarPedido(p) {
  let texto = p.texto.charAt(0).toUpperCase() + p.texto.slice(1);
  if (p.situacao === 'esperando') texto = 'Confirme na TV: aperte OK em "Executar" com o controle.';
  if (p.mensagem) texto += ' · ' + p.mensagem;
  mostrar($('statusFalar'), texto);
  const ul = $('acoesFalar');
  ul.replaceChildren();
  for (const a of p.acoes) { const li = document.createElement('li'); li.textContent = a; ul.appendChild(li); }
  for (const r of p.recusadas) { const li = document.createElement('li'); li.className = 'recusada'; li.textContent = 'Recusado: ' + r; ul.appendChild(li); }
  ul.hidden = ul.childElementCount === 0;
}

$('formChave').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  // Tira espaços e quebras de linha que vêm junto ao colar.
  const chave = $('campoChave').value.replace(/\s+/g, '');
  if (!/^[A-Za-z0-9._-]{20,300}$/.test(chave)) {
    mostrar($('statusChave'), `Isso não parece uma chave de API: esperado de 20 a 300 letras, números, . - _ (veio com ${chave.length} caracteres).`);
    return;
  }
  // Se o começo da chave diz de qual IA ela é, guarda nessa (ex.: colou "sk-ant-…" com Gemini escolhido).
  const escolhido = provedorIa();
  const detectado = estadoIa?.provedores.find((p) => p.id === detectarIa(chave)) ?? null;
  const destino = detectado ?? escolhido;
  if (!destino) return;
  const texto = (detectado && detectado.id !== escolhido?.id ? `Pelo começo, é uma chave do ${detectado.nome}. ` : '') +
    `Guardar como chave do ${destino.nome}? Os pedidos que usarem essa IA passam a gastar a cota desta chave.`;
  const r = await confirmar('Guardar a chave na TV?', texto, 'Guardar',
    () => chamar('POST', '/api/ia/chave', { chave, provedor: destino.id }));
  if (r) {
    $('campoChave').value = '';
    mostrar($('statusChave'), `Chave do ${destino.nome} guardada.` + (r.aviso ? ' Atenção: ' + r.aviso + '.' : ''));
    carregarIa();
  }
});

$('btnApagarChave').addEventListener('click', async () => {
  const p = provedorIa();
  if (!p) return;
  const ok = await confirmar(`Apagar a chave do ${p.nome}?`, 'Essa IA para de funcionar até cadastrar outra.', 'Apagar',
    () => chamar('POST', '/api/ia/chave', { apagar: true, provedor: p.id }));
  if (ok) { mostrar($('statusChave'), 'Chave apagada.'); carregarIa(); }
});

$('formModelo').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const p = provedorIa();
  if (!p) return;
  try {
    // Vazio volta ao padrão daquela IA.
    await chamar('POST', '/api/ia/modelo', { modelo: $('campoModelo').value.trim() || p.modeloPadrao, provedor: p.id });
    mostrar($('statusChave'), 'Modelo trocado.');
    carregarIa();
  } catch (e) { mostrar($('statusChave'), e.message); }
});

// ---------- desligar sozinha (aba Controle) ----------

function rotuloMinutos(m) {
  if (!m) return 'Nunca';
  return m % 60 === 0 ? `Depois de ${m / 60} h sem usar` : `Depois de ${m} min sem usar`;
}

async function carregarAutoDesligar() {
  const sel = $('autoDesligar');
  try {
    const r = await chamar('GET', '/api/tv/autodesligar');
    sel.replaceChildren();
    for (const m of r.opcoes) {
      const o = document.createElement('option');
      o.value = String(m); o.textContent = rotuloMinutos(m); o.selected = m === r.minutos;
      sel.appendChild(o);
    }
    $('statusDesligar').textContent = r.ultimoDesligamento
      ? 'Última vez que desligou sozinha: ' + new Date(r.ultimoDesligamento).toLocaleString('pt-BR') : '';
  } catch (e) { $('statusDesligar').textContent = e.message; }
}

$('autoDesligar').addEventListener('change', async () => {
  try {
    const r = await chamar('POST', '/api/tv/autodesligar', { minutos: Number($('autoDesligar').value) });
    $('statusDesligar').textContent = 'Gravado: ' + rotuloMinutos(r.minutos).toLowerCase() + '.';
  } catch (e) { $('statusDesligar').textContent = e.message; }
});

// ---------- histórico de rede (aba Rede) ----------

let horasRede = 24;

function duracaoLegivel(ms) {
  const min = Math.round(ms / 60000);
  if (min < 1) return 'menos de 1 min';
  if (min < 60) return `${min} min`;
  const h = Math.floor(min / 60), r = min % 60;
  return r ? `${h} h ${r} min` : `${h} h`;
}

function bloco(titulo, valor, classe) {
  const d = document.createElement('div');
  d.className = 'bloco-rede' + (classe ? ' ' + classe : '');
  const v = document.createElement('strong'); v.textContent = valor;
  const t = document.createElement('span'); t.textContent = titulo;
  d.append(v, t);
  return d;
}

function linhaSimples(quando, texto) {
  const linha = document.createElement('div');
  linha.className = 'linha-registro';
  const q = document.createElement('span'); q.className = 'quando'; q.textContent = quando;
  const o = document.createElement('span'); o.textContent = texto;
  linha.append(q, o);
  return linha;
}

function vazio(lista, texto) {
  const p = document.createElement('p'); p.className = 'aviso'; p.textContent = texto; lista.appendChild(p);
}

async function carregarRede() {
  mostrar($('statusRede'), 'Carregando…');
  let r;
  try { r = await chamar('GET', '/api/rede/historico?horas=' + horasRede); } catch (e) { mostrar($('statusRede'), e.message); return; }
  mostrar($('statusRede'), r.amostras.length ? '' :
    'Ainda sem medições: o launcher mede a cada ' + r.intervaloMin + ' min. Volte daqui a pouco.');
  const s = r.resumo;
  const cob = Math.max(s.coberturaMs, 1);
  const pct = s.coberturaMs ? Math.max(0, 100 * (1 - s.foraMs / cob)) : null;
  const res = $('resumoRede');
  res.replaceChildren(
    bloco('internet no ar (no tempo medido)', pct === null ? '—' : pct.toFixed(pct >= 99.95 || pct < 10 ? 0 : 1) + '%', pct !== null && pct < 99 ? 'ruim' : ''),
    bloco('quedas de internet', String(s.quedasInternet), s.quedasInternet ? 'ruim' : ''),
    bloco('quedas só de DNS', String(s.quedasDns), s.quedasDns ? 'ruim' : ''),
    bloco('maior queda', s.maiorQuedaMs ? duracaoLegivel(s.maiorQuedaMs) : '—'),
    bloco('demora média (95% abaixo de)', s.latMedia === null ? '—' : `${s.latMedia} ms (${s.latP95} ms)`),
    bloco('sinal médio do Wi-Fi', s.dbmMedio === null ? '—' : `${s.dbmMedio} dBm`),
    bloco('tempo medido', duracaoLegivel(s.coberturaMs)),
  );
  desenharGrafico(r);

  const lq = $('listaQuedas'); lq.replaceChildren();
  if (!r.quedas.length) vazio(lq, 'Nenhuma queda no período.');
  for (const q of r.quedas.slice().reverse()) {
    const tipo = q.tipo === 'dns' ? 'DNS não resolvia nomes (internet ok)' : 'sem internet';
    const fim = q.aberta ? 'ainda agora' : new Date(q.fim).toLocaleTimeString('pt-BR');
    lq.appendChild(linhaSimples(new Date(q.inicio).toLocaleString('pt-BR') + ' até ' + fim,
      `${tipo} · ${q.aberta ? 'em andamento' : duracaoLegivel(q.fim - q.inicio)}`));
  }
  const le = $('listaEventosRede'); le.replaceChildren();
  if (!r.eventos.length) vazio(le, 'Nenhum aviso no período.');
  for (const [t, txt] of r.eventos.slice().reverse().slice(0, 100)) le.appendChild(linhaSimples(new Date(t).toLocaleString('pt-BR'), txt));
}

/** Uma barra por medição: altura = demora até a internet; cor = estado. Buracos (sem medição) ficam cinza. */
function desenharGrafico(r) {
  const c = $('graficoRede');
  const dpr = window.devicePixelRatio || 1;
  const larg = c.clientWidth || 600, alt = 160;
  c.width = Math.round(larg * dpr); c.height = Math.round(alt * dpr);
  const g = c.getContext('2d');
  g.setTransform(dpr, 0, 0, dpr, 0, 0);
  g.clearRect(0, 0, larg, alt);
  const css = getComputedStyle(document.documentElement);
  const cor = (n) => css.getPropertyValue(n).trim();
  const topo = 4, base = alt - 22, faixa = base - topo;
  const x = (t) => ((t - r.desde) / (r.agora - r.desde)) * larg;
  // Fundo "sem dados" e a régua de horas.
  g.fillStyle = cor('--superficie2'); g.fillRect(0, base + 4, larg, 6);
  g.fillStyle = cor('--texto-fraco'); g.font = '11px system-ui, sans-serif'; g.textAlign = 'center';
  const passo = horasRede > 24 ? 24 * 3600e3 : 3 * 3600e3;
  const primeiro = Math.ceil(r.desde / passo) * passo;
  for (let t = primeiro; t <= r.agora; t += passo) {
    const d = new Date(t);
    const rot = horasRede > 24 ? d.toLocaleDateString('pt-BR', { weekday: 'short', day: '2-digit' }) : d.toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' });
    g.fillText(rot, Math.min(Math.max(x(t), 20), larg - 20), alt - 2);
  }
  const a = r.amostras;
  const buraco = 15 * 60e3;
  for (let i = 0; i < a.length; i++) {
    const [t, net, lat, dns] = a[i];
    const prox = i + 1 < a.length ? a[i + 1][0] : r.agora;
    const fim = Math.min(prox, t + buraco, r.agora);
    const x0 = x(t), w = Math.max(x(fim) - x0, 1);
    const estado = !net ? '--perigo' : dns < 0 ? '--alerta' : '--ok';
    g.fillStyle = cor(estado);
    g.fillRect(x0, base + 4, w, 6);   // faixa de estado
    const h = net ? Math.max(2, Math.min(lat, 300) / 300 * faixa) : faixa;
    g.globalAlpha = net ? 0.85 : 0.35;
    g.fillRect(x0, base - h, w, h);
    g.globalAlpha = 1;
  }
}

for (const b of document.querySelectorAll('.periodo')) {
  b.addEventListener('click', () => {
    horasRede = Number(b.dataset.horas);
    for (const o of document.querySelectorAll('.periodo')) o.classList.toggle('ativo', o === b);
    carregarRede();
  });
}
$('btnAtualizarRede').addEventListener('click', () => carregarRede());
window.addEventListener('resize', () => { if (!$('abaRede').hidden) carregarRede(); });

// ---------- início ----------

(async () => {
  try {
    const e = await chamar('GET', '/api/estado');
    if (e.logado) await abrirPainel(); else mostrarEntrar();
  } catch (e) {
    mostrarEntrar();
  }
})();
