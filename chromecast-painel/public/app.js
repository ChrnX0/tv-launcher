// Painel do Chromecast - tudo em requisicoes simples pro servidor local (server.js). Sem framework.
'use strict';

let aparelhoAtual = null; // { id, nome }

async function chamar(metodo, caminho, corpo) {
  const opcoes = { method: metodo, headers: {} };
  if (corpo instanceof FormData) {
    opcoes.body = corpo;
  } else if (corpo) {
    opcoes.headers['Content-Type'] = 'application/json';
    opcoes.body = JSON.stringify(corpo);
  }
  const resp = await fetch(caminho, opcoes);
  const dados = await resp.json().catch(() => ({}));
  if (!resp.ok) throw new Error(dados.erro || `Falha (${resp.status})`);
  return dados;
}

// ---------- Tela 1: aparelhos ----------
async function carregarAparelhos() {
  const lista = document.getElementById('listaAparelhos');
  lista.innerHTML = '<p class="aviso">Procurando na rede...</p>';
  try {
    const aparelhos = await chamar('GET', '/api/dispositivos');
    lista.innerHTML = '';
    document.getElementById('semAparelhos').hidden = aparelhos.length > 0;
    for (const ap of aparelhos) {
      const card = document.createElement('div');
      card.className = 'cartao-aparelho';
      card.innerHTML = `<div class="nome">${esc(ap.nome)}</div><div class="status ${ap.online ? 'online' : 'offline'}">${ap.online ? 'Na rede agora' : 'Não visto recentemente'}</div>`;
      card.onclick = () => abrirAparelho(ap);
      lista.appendChild(card);
    }
  } catch (e) {
    lista.innerHTML = `<p class="aviso">Não consegui procurar: ${esc(e.message)}</p>`;
  }
}

function abrirAparelho(ap) {
  aparelhoAtual = ap;
  document.getElementById('titulo').textContent = ap.nome;
  document.getElementById('btnVoltar').hidden = false;
  document.getElementById('telaAparelhos').hidden = true;
  document.getElementById('telaAparelho').hidden = false;
  mudarAba('tela');
}
function voltarParaAparelhos() {
  aparelhoAtual = null;
  document.getElementById('titulo').textContent = 'Chromecasts da casa';
  document.getElementById('btnVoltar').hidden = true;
  document.getElementById('telaAparelho').hidden = true;
  document.getElementById('telaAparelhos').hidden = false;
  document.getElementById('iframeTela').src = 'about:blank';
}

// ---------- abas ----------
function mudarAba(nome) {
  for (const btn of document.querySelectorAll('.aba')) btn.classList.toggle('ativa', btn.dataset.aba === nome);
  document.getElementById('painelTela').hidden = nome !== 'tela';
  document.getElementById('painelApps').hidden = nome !== 'apps';
  document.getElementById('painelArquivos').hidden = nome !== 'arquivos';
  document.getElementById('painelBackup').hidden = nome !== 'backup';
  if (nome === 'tela') carregarTela();
  if (nome === 'apps') carregarApps();
  if (nome === 'arquivos') carregarArquivos();
  if (nome === 'backup') carregarBackups();
}

// ---------- Tela do aparelho ----------
// O iframe tem 1328x736 de verdade (ver style.css: é o que faz o vídeo sair 1280x720); aqui ele é reduzido para a
// largura da caixa. A escala não atrapalha os cliques: o navegador converte as coordenadas sozinho.
function ajustarEscalaTela() {
  const caixa = document.getElementById('caixaTela');
  const iframe = document.getElementById('iframeTela');
  if (!caixa || !iframe) return;
  iframe.style.transform = `scale(${caixa.clientWidth / 1328})`;
}
window.addEventListener('resize', ajustarEscalaTela);

async function carregarTela() {
  try {
    const { url } = await chamar('GET', `/api/dispositivos/${aparelhoAtual.id}/tela`);
    document.getElementById('iframeTela').src = url;
    ajustarEscalaTela();
  } catch (e) {
    document.getElementById('painelTela').innerHTML = `<p class="aviso">Não consegui abrir a tela: ${esc(e.message)}</p>`;
  }
}

// ---------- Apps ----------
async function carregarApps() {
  const lista = document.getElementById('listaApps');
  lista.innerHTML = '<p class="aviso">Carregando aplicativos...</p>';
  try {
    const apps = await chamar('GET', `/api/dispositivos/${aparelhoAtual.id}/apps`);
    lista.innerHTML = '';
    for (const app of apps) lista.appendChild(linhaApp(app));
  } catch (e) {
    lista.innerHTML = `<p class="aviso">Não consegui listar: ${esc(e.message)}</p>`;
  }
}

// Texto que vem do aparelho (nome de app, de arquivo) vai para innerHTML: escapar, senão um app com nome tipo
// "<img onerror=...>" rodaria código no painel.
function esc(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function linhaApp(app) {
  const linha = document.createElement('div');
  linha.className = 'linha-app';
  linha.innerHTML = `
    <div class="info">
      <span class="nome">${esc(app.nome)}</span>
      <span class="pacote">${esc(app.pkg)}</span>
    </div>
    <div class="acoes">${app.protegido ? '<span class="protegido">tela inicial — protegido</span>' : `
      <label class="interruptor" title="Ligado / congelado">
        <input type="checkbox" ${app.congelado ? '' : 'checked'} />
        <span class="trilho"></span>
      </label>
      <button class="botao-perigo">Remover</button>`}
    </div>`;
  linha.querySelector('.info').onclick = () => abrirPermissoes(app);
  if (app.protegido) return linha;   // o launcher (HOME + Device Owner) não tem interruptor nem "Remover"
  linha.querySelector('input').onchange = async (ev) => {
    const ligar = ev.target.checked;
    ev.target.disabled = true;
    try { await chamar('POST', `/api/dispositivos/${aparelhoAtual.id}/apps/${app.pkg}/${ligar ? 'descongelar' : 'congelar'}`); }
    catch (e) { alert('Não consegui mudar: ' + e.message); ev.target.checked = !ligar; }
    finally { ev.target.disabled = false; }
  };
  linha.querySelector('.botao-perigo').onclick = async () => {
    if (!confirm(`Remover "${app.nome}" do aparelho?`)) return;
    try { await chamar('DELETE', `/api/dispositivos/${aparelhoAtual.id}/apps/${app.pkg}`); linha.remove(); }
    catch (e) { alert('Não consegui remover: ' + e.message); }
  };
  return linha;
}

document.getElementById('arquivoApk').addEventListener('change', async (ev) => {
  const arquivo = ev.target.files[0];
  if (!arquivo) return;
  const status = document.getElementById('statusInstalacao');
  status.hidden = false; status.textContent = `Instalando ${arquivo.name}...`;
  const formulario = new FormData(); formulario.append('apk', arquivo);
  try {
    await chamar('POST', `/api/dispositivos/${aparelhoAtual.id}/apps`, formulario);
    status.textContent = 'Instalado!';
    carregarApps();
  } catch (e) { status.textContent = 'Não consegui instalar: ' + e.message; }
  finally { ev.target.value = ''; setTimeout(() => (status.hidden = true), 4000); }
});

// ---------- Permissões ----------
async function abrirPermissoes(app) {
  document.getElementById('tituloModalPermissoes').textContent = `Permissões de ${app.nome}`;
  const lista = document.getElementById('listaPermissoes');
  lista.innerHTML = '<p class="aviso">Carregando...</p>';
  document.getElementById('modalPermissoes').hidden = false;
  try {
    const permissoes = await chamar('GET', `/api/dispositivos/${aparelhoAtual.id}/apps/${app.pkg}/permissoes`);
    lista.innerHTML = '';
    if (!permissoes.length) lista.innerHTML = '<p class="aviso">Este aplicativo não pede nenhuma permissão sensível.</p>';
    for (const p of permissoes) lista.appendChild(linhaPermissao(app.pkg, p));
  } catch (e) {
    lista.innerHTML = `<p class="aviso">Não consegui carregar: ${esc(e.message)}</p>`;
  }
}
function nomeAmigavelPermissao(permissao) {
  const mapa = {
    CAMERA: 'Câmera', RECORD_AUDIO: 'Microfone', ACCESS_FINE_LOCATION: 'Localização precisa',
    ACCESS_COARSE_LOCATION: 'Localização aproximada', READ_EXTERNAL_STORAGE: 'Ler arquivos',
    WRITE_EXTERNAL_STORAGE: 'Salvar arquivos', READ_CONTACTS: 'Contatos',
  };
  const chave = permissao.replace('android.permission.', '');
  return mapa[chave] || chave.replace(/_/g, ' ').toLowerCase();
}
function linhaPermissao(pkg, p) {
  const linha = document.createElement('div');
  linha.className = 'linha-permissao';
  linha.innerHTML = `
    <span class="info">${nomeAmigavelPermissao(p.permissao)}</span>
    <label class="interruptor">
      <input type="checkbox" ${p.concedida ? 'checked' : ''} />
      <span class="trilho"></span>
    </label>`;
  linha.querySelector('input').onchange = async (ev) => {
    const conceder = ev.target.checked;
    ev.target.disabled = true;
    try { await chamar('POST', `/api/dispositivos/${aparelhoAtual.id}/apps/${pkg}/permissoes/${p.permissao}/${conceder ? 'conceder' : 'revogar'}`); }
    catch (e) { alert('Não consegui mudar: ' + e.message); ev.target.checked = !conceder; }
    finally { ev.target.disabled = false; }
  };
  return linha;
}

// ---------- Arquivos ----------
async function carregarArquivos() {
  const pasta = document.getElementById('pastaAtalho').value;
  const lista = document.getElementById('listaArquivos');
  lista.innerHTML = '<p class="aviso">Carregando...</p>';
  try {
    const { itens } = await chamar('GET', `/api/dispositivos/${aparelhoAtual.id}/arquivos?pasta=${encodeURIComponent(pasta)}`);
    lista.innerHTML = '';
    if (!itens.length) lista.innerHTML = '<p class="aviso">Pasta vazia.</p>';
    for (const linhaBruta of itens) lista.appendChild(linhaArquivo(pasta, linhaBruta));
  } catch (e) {
    lista.innerHTML = `<p class="aviso">Não consegui listar: ${esc(e.message)}</p>`;
  }
}
function linhaArquivo(pasta, linhaBrutaLs) {
  // "ls -la" tem 7 campos fixos (permissões, links, dono, grupo, tamanho, data, hora) antes do nome;
  // juntar so o ultimo token quebraria nomes com espaço, como "Downloader (1.5.3...).apk".
  const nome = linhaBrutaLs.split(/\s+/).slice(7).join(' ');
  const linha = document.createElement('div');
  linha.className = 'linha-arquivo';
  // Pasta (o "ls -la" começa com "d"): não dá para baixar uma pasta inteira; antes ela aparecia com "Baixar" (04/11).
  if (linhaBrutaLs.trim().startsWith('d')) {
    linha.innerHTML = `<span>📁 ${esc(nome)}</span><div class="acoes"><span class="protegido">pasta</span></div>`;
    return linha;
  }
  linha.innerHTML = `<span>${esc(nome)}</span><div class="acoes"><button class="botao-secundario">Baixar</button></div>`;
  linha.querySelector('button').onclick = () => {
    const caminho = pasta.replace(/\/+$/, '') + '/' + nome;
    window.open(`/api/dispositivos/${aparelhoAtual.id}/arquivos/baixar?caminho=${encodeURIComponent(caminho)}`, '_blank');
  };
  return linha;
}
document.getElementById('pastaAtalho').addEventListener('change', carregarArquivos);
document.getElementById('arquivoEnviar').addEventListener('change', async (ev) => {
  const arquivo = ev.target.files[0];
  if (!arquivo) return;
  const status = document.getElementById('statusArquivo');
  status.hidden = false; status.textContent = `Enviando ${arquivo.name}...`;
  const formulario = new FormData();
  formulario.append('arquivo', arquivo);
  formulario.append('pasta', document.getElementById('pastaAtalho').value);
  try {
    await chamar('POST', `/api/dispositivos/${aparelhoAtual.id}/arquivos`, formulario);
    status.textContent = 'Enviado!';
    carregarArquivos();
  } catch (e) { status.textContent = 'Não consegui enviar: ' + e.message; }
  finally { ev.target.value = ''; setTimeout(() => (status.hidden = true), 4000); }
});

// ---------- Backup das configurações do launcher ----------
function dataBonita(ms) {
  return ms ? new Date(ms).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' }) : '?';
}
function mostrarStatusBackup(texto) {
  const status = document.getElementById('statusBackup');
  status.hidden = false; status.textContent = texto;
}
async function carregarBackups() {
  const lista = document.getElementById('listaBackups');
  lista.innerHTML = '<p class="aviso">Carregando...</p>';
  try {
    const backups = await chamar('GET', `/api/dispositivos/${aparelhoAtual.id}/backups`);
    lista.innerHTML = '';
    if (!backups.length) lista.innerHTML = '<p class="aviso">Nenhum backup ainda. Use "Fazer backup agora".</p>';
    backups.forEach((b, i) => {
      const linha = document.createElement('div');
      linha.className = 'linha-arquivo';
      linha.innerHTML = `<span>${esc(dataBonita(b.criadoEm))}${i === 0 ? ' (mais recente)' : ''} · ${esc(String(b.chaves ?? '?'))} itens</span>` +
        '<div class="acoes"><button class="botao-secundario">Restaurar</button></div>';
      linha.querySelector('button').onclick = async () => {
        if (!confirm(`Voltar as configurações do launcher para ${dataBonita(b.criadoEm)}? O launcher vai reiniciar na TV.`)) return;
        mostrarStatusBackup('Restaurando...');
        try {
          await chamar('POST', `/api/dispositivos/${aparelhoAtual.id}/backups/${encodeURIComponent(b.nome)}/restaurar`);
          mostrarStatusBackup('Restaurado. O launcher reiniciou na TV (o estado anterior também foi salvo na lista).');
          carregarBackups();
        } catch (e) { mostrarStatusBackup('Não consegui restaurar: ' + e.message); }
      };
      lista.appendChild(linha);
    });
  } catch (e) {
    lista.innerHTML = `<p class="aviso">Não consegui listar: ${esc(e.message)}</p>`;
  }
}
document.getElementById('btnBackupAgora').onclick = async () => {
  mostrarStatusBackup('Fazendo backup...');
  try {
    const r = await chamar('POST', `/api/dispositivos/${aparelhoAtual.id}/backups`);
    mostrarStatusBackup(r.novo ? 'Backup salvo.' : 'Nada mudou desde o último backup.');
    carregarBackups();
  } catch (e) { mostrarStatusBackup('Não consegui fazer o backup: ' + e.message); }
};

// ---------- ligacoes gerais ----------
document.getElementById('btnVoltar').onclick = voltarParaAparelhos;
document.getElementById('btnAtualizarAparelhos').onclick = carregarAparelhos;
document.getElementById('btnFecharModal').onclick = () => (document.getElementById('modalPermissoes').hidden = true);
for (const btn of document.querySelectorAll('.aba')) btn.onclick = () => mudarAba(btn.dataset.aba);

carregarAparelhos();
