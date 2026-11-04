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
}

// Atualiza "o que está na TV" a cada 30 s, só com a aba do navegador visível (custo zero com a aba escondida).
setInterval(() => {
  if (document.visibilityState === 'visible' && !$('telaPainel').hidden) atualizarEstado().catch(() => {});
}, 30000);

// ---------- abas ----------

function mudarAba(nome) {
  for (const b of document.querySelectorAll('.aba')) b.classList.toggle('ativa', b.dataset.aba === nome);
  $('abaApps').hidden = nome !== 'apps';
  $('abaBackup').hidden = nome !== 'backup';
  $('abaRegistro').hidden = nome !== 'registro';
  if (nome === 'apps') carregarApps();
  if (nome === 'registro') carregarRegistro();
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

async function carregarApps() {
  mostrar($('statusApps'), 'Carregando…');
  try {
    const r = await chamar('GET', '/api/apps');
    apps = r.apps.sort((a, b) => a.nome.localeCompare(b.nome, 'pt-BR'));
    desenharApps();
  } catch (e) {
    mostrar($('statusApps'), 'Não consegui listar: ' + e.message);
  }
}

$('busca').addEventListener('input', desenharApps);
$('mostrarSistema').addEventListener('change', desenharApps);

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
  pacote.textContent = a.pacote + (a.versao ? ' · ' + a.versao : '');
  const selos = document.createElement('div');
  selos.className = 'selos';
  if (a.oculto) selos.appendChild(selo('oculto', 'oculto'));
  if (a.desativado) selos.appendChild(selo('desativado'));
  if (a.sistema) selos.appendChild(selo('sistema'));
  if (a.ocultoPeloLauncher) selos.appendChild(selo('o launcher oculta sozinho'));
  info.append(nome, pacote, selos);
  linha.appendChild(info);

  const acoes = document.createElement('div');
  acoes.className = 'acoes';
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

// ---------- início ----------

(async () => {
  try {
    const e = await chamar('GET', '/api/estado');
    if (e.logado) await abrirPainel(); else mostrarEntrar();
  } catch (e) {
    mostrarEntrar();
  }
})();
