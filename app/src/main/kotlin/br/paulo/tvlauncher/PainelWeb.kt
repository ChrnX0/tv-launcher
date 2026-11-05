package br.paulo.tvlauncher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Painel no navegador SERVIDO PELO PRÓPRIO LAUNCHER (04/11/2026), sem PC: em qualquer celular ou computador da casa,
 * abrir http://<IP da TV>:8080/ (ou http://chromecast:8080/ com o nome cadastrado no roteador), digitar o PIN
 * definido nas Configurações do launcher e administrar a TV. Plano completo: painel-no-launcher.md.
 *
 * Fase 1: entrar com PIN, lista de apps com ícone, ocultar/reexibir, desinstalar e backup (baixar/restaurar).
 * Fase 2: permissões de cada app, instalar APK e a aba Arquivos (memória interna: listar, enviar, baixar, apagar).
 *
 * Custo: com o painel DESLIGADO (o padrão), nenhum socket nem thread; [ajustar] só lê uma preferência. Ligado e
 * parado, uma thread presa no accept() e nada mais (sem timer, sem alarme). Atendendo, até 3 threads de prioridade
 * baixa, que morrem depois de 30 s sem trabalho.
 *
 * Segurança (seção 3 do plano), na ordem em que cada requisição passa:
 *  1. A conexão tem de chegar pela rede local (Wi-Fi/cabo), não pela VPN, de um endereço privado.
 *  2. O Host tem de ser um nome nosso (barra DNS rebinding). Em POST, Origin = o próprio painel e o cabeçalho
 *     X-Painel (barra CSRF).
 *  3. Fora a página e o login, tudo exige sessão (cookie HttpOnly, SameSite=Strict).
 *  4. Desinstalar e restaurar backup exigem o PIN redigitado nos últimos 5 min.
 *  5. PIN errado: bloqueio crescente e, com 20 erros, o painel se desliga (só volta pelas Configurações da TV).
 *  Não existe rota de shell genérico: cada ação é uma função fixa, e o pacote é validado antes de tudo.
 */
object PainelWeb {
    private const val TAG = "TvLauncher.Painel"
    const val COOKIE = "painel_sessao"
    private const val LIMITE_CORPO = 4 * 1024   // login e ações: corpo pequeno
    private const val TEMPO_LEITURA_MS = 15_000

    @Volatile private var servidor: ServerSocket? = null
    private lateinit var app: Context

    val sessoes = Sessoes({ System.currentTimeMillis() })
    @Volatile private var tentativas: ControleTentativas? = null
    private val travaPin = Any()   // um PBKDF2 por vez: muitas tentativas juntas não derrubam a CPU da TV

    /**
     * Até 4 threads atendendo e até 24 conexões esperando; além disso a conexão é recusada. 4 e não 3 (fase 2): um
     * envio e um download grandes ao mesmo tempo ainda deixam a página responder.
     */
    private val pool by lazy {
        ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(24)) { r ->
            Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "Painel-atende")
        }.apply { allowCoreThreadTimeOut(true) }
    }

    @Volatile private var iniciando = false
    private var geracao = 0   // cada iniciar() ganha um número; uma thread de bind antiga não mexe no estado da nova

    fun ligado(): Boolean = servidor?.isClosed == false

    /** Liga ou desliga conforme as preferências (ver PainelArmazem.podeLigar). Barato e idempotente: chamar à vontade. */
    @Synchronized fun ajustar(ctx: Context) {
        app = ctx.applicationContext
        val deve = PainelArmazem.podeLigar(app)
        if (deve && !ligado() && !iniciando) iniciar() else if (!deve && (ligado() || iniciando)) parar()
    }

    /** Só chamar dentro de synchronized(travaPin): assim todas as threads usam o MESMO contador. */
    private fun controle(): ControleTentativas = tentativas ?: ControleTentativas(
        { System.currentTimeMillis() }, PainelArmazem.falhas(app),
    ) { PainelArmazem.definirFalhas(app, it) }.also { tentativas = it }

    /** Chamado pelas Configurações quando o PIN muda ou o painel é religado: derruba as sessões e zera os erros. */
    @Synchronized fun recomecar(ctx: Context) {
        app = ctx.applicationContext
        sessoes.encerrarTodas()
        tentativas?.zerar()
        ajustar(ctx)
    }

    /** Abre a porta numa thread própria (abrir socket na thread da tela é proibido no Android) e fica no accept(). */
    private fun iniciar() {
        iniciando = true
        val minha = ++geracao
        Thread({
            val s = ServerSocket()
            try {
                s.reuseAddress = true   // religar logo depois de desligar não dá "endereço em uso"
                // 0.0.0.0 (só IPv4): a checagem da conexão (redeLocal) decide quem pode falar.
                s.bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0)), Config.PAINEL_PORTA), 16)
            } catch (e: IOException) {
                Log.w(TAG, "não consegui abrir a porta ${Config.PAINEL_PORTA}: $e")
                try { s.close() } catch (_: IOException) {}
                synchronized(this) { if (geracao == minha) iniciando = false }
                return@Thread
            }
            synchronized(this) {
                // Desligaram (ou religaram) enquanto abria? Então esta porta não vale mais: fecha já.
                if (geracao != minha || !iniciando) { try { s.close() } catch (_: IOException) {}; return@Thread }
                servidor = s
                iniciando = false
            }
            Log.i(TAG, "painel ligado na porta ${Config.PAINEL_PORTA}")
            aceitar(s)
            // Saiu do laço: se não foi um parar() (a porta ainda é a atual), limpa para o próximo ajustar() religar.
            synchronized(this) { if (servidor === s) servidor = null }
            try { s.close() } catch (_: IOException) {}
        }, "Painel-aceita").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start()
    }

    @Synchronized private fun parar() {
        iniciando = false
        geracao++
        try { servidor?.close() } catch (_: IOException) {}
        servidor = null
        sessoes.encerrarTodas()
        Log.i(TAG, "painel desligado")
    }

    private fun aceitar(s: ServerSocket) {
        while (!s.isClosed) {
            val c = try { s.accept() } catch (e: IOException) {
                if (s.isClosed) break   // parar() fechou a porta: fim
                // Erro passageiro (ex.: faltou descritor de arquivo): não deixa o painel morrer em silêncio.
                Log.w(TAG, "accept falhou: $e")
                try { Thread.sleep(200) } catch (_: InterruptedException) { break }
                continue
            }
            if (!redeLocal(c)) { fechar(c); continue }
            try {
                pool.execute { atender(c) }
            } catch (e: RejectedExecutionException) {
                fechar(c)   // fila cheia: alguém está martelando o painel
            }
        }
    }

    private fun fechar(c: Socket) = try { c.close() } catch (_: IOException) {}

    // ---------------------------------------------------------------- de onde vem a conexão

    /** IPv4 da TV na rede de casa -> tamanho do prefixo da sub-rede (ex.: "192.168.1.153" -> 24). */
    @Volatile private var redeCache: Pair<Long, Map<String, Int>> = 0L to emptyMap()

    /**
     * Os IPv4 da TV na rede de casa: interfaces ligadas de Wi-Fi ou cabo (wlan*, eth*), menos túneis de VPN e o
     * Wi-Fi Direct (p2p). Recalculado no máximo a cada 30 s (o IP só muda se o roteador mudar).
     */
    private fun redes(): Map<String, Int> {
        val (quando, atual) = redeCache
        if (System.currentTimeMillis() - quando < 30_000 && atual.isNotEmpty()) return atual
        val novas = HashMap<String, Int>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces() ?: return emptyMap()) {
                if (!ni.isUp || ni.isLoopback || ni.isVirtual) continue
                val nome = ni.name.lowercase()
                if (!nome.startsWith("wlan") && !nome.startsWith("eth")) continue
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address
                    if (a is Inet4Address && a.isSiteLocalAddress) novas[a.hostAddress ?: continue] = ia.networkPrefixLength.toInt()
                }
            }
        } catch (e: Exception) { Log.w(TAG, "redes: $e") }
        redeCache = System.currentTimeMillis() to novas
        return novas
    }

    fun ipsDaRede(): Set<String> = redes().keys

    /** O endereço para digitar no navegador (o primeiro IP da rede), ou null sem rede. */
    fun endereco(): String? = ipsDaRede().minOrNull()?.let { "http://$it:${Config.PAINEL_PORTA}/" }

    /**
     * A conexão chegou num IP nosso de Wi-Fi/cabo (não no da VPN) e veio da MESMA sub-rede (ex.: 192.168.1.x)?
     * Recusa tudo o que vier pela VPN, de outra VLAN do roteador ou de fora da casa.
     */
    private fun redeLocal(c: Socket): Boolean {
        val local = c.localAddress as? Inet4Address ?: return false
        val remoto = c.inetAddress as? Inet4Address ?: return false
        val prefixo = redes()[local.hostAddress] ?: return false
        return !remoto.isLoopbackAddress && ChecagensPainel.mesmaRede(local.address, remoto.address, prefixo)
    }

    private fun nomesPermitidos(): Set<String> = ipsDaRede() + Config.PAINEL_NOMES

    // ---------------------------------------------------------------- atender uma requisição

    /** [arquivo] != null: o corpo é esse arquivo, mandado aos pedaços (download da aba Arquivos). */
    private class Resposta(val codigo: Int, val tipo: String?, val corpo: ByteArray, val extras: Map<String, String> = emptyMap(),
                           val arquivo: File? = null)

    /** Rotas que recebem o corpo como fluxo, direto para o disco (arquivo de vários GB não cabe na memória). */
    private val ROTAS_FLUXO = setOf("/api/arquivos/enviar", "/api/apps/instalar")

    private fun json(codigo: Int, o: JSONObject, extras: Map<String, String> = emptyMap()) =
        Resposta(codigo, "application/json; charset=utf-8", o.toString().toByteArray(), extras)

    private fun erro(codigo: Int, mensagem: String, extra: JSONObject.() -> Unit = {}) =
        json(codigo, JSONObject().put("erro", mensagem).apply(extra))

    private fun atender(c: Socket) {
        val ip = c.inetAddress.hostAddress ?: "?"
        try {
            c.soTimeout = TEMPO_LEITURA_MS
            val entrada = c.getInputStream().buffered(8192)
            val saida = c.getOutputStream().buffered(16 * 1024)
            val resposta = try {
                val req = PainelHttp.lerCabecalhos(entrada)
                // O corpo: nas rotas de fluxo fica na conexão (a rota lê depois de conferir sessão e PIN); nas outras
                // é lido já, com limite (só a restauração de backup aceita um corpo maior).
                if (req.caminho !in ROTAS_FLUXO)
                    req.lerCorpo(if (req.caminho == "/api/backup/restaurar") BackupConfig.LIMITE_BYTES else LIMITE_CORPO)
                when {
                    // JSON aninhado demais estoura a pilha do leitor do Android e derruba o launcher: recusa antes.
                    req.caminho !in ROTAS_FLUXO && req.corpo.isNotEmpty() && !ChecagensPainel.jsonRaso(req.corpoTexto()) -> erro(400, "JSON inválido")
                    else -> try {
                        rotear(req, ip)
                    } catch (e: PainelArquivos.Erro) {
                        erro(e.codigo, e.message ?: "erro")
                    } catch (e: IOException) {
                        throw e   // a conexão caiu no meio (ex.: envio cancelado): nada a responder
                    } catch (e: Exception) {
                        // Erro nosso no meio de uma rota: responde 500 em vez de fechar a conexão calado.
                        Log.w(TAG, "erro em ${req.metodo} ${req.caminho}: $e")
                        erro(500, "erro na TV: ${e.javaClass.simpleName}")
                    }
                }
            } catch (e: ErroHttp) {
                erro(e.codigo, e.message ?: "pedido inválido")
            }
            val arq = resposta.arquivo
            if (arq == null) {
                PainelHttp.escrever(saida, resposta.codigo, resposta.tipo, resposta.corpo, resposta.extras)
            } else {
                // Arquivo: abre ANTES de mandar o cabeçalho (se sumiu, ainda dá para responder 404) e manda em pedaços.
                // O tamanho é o do arquivo aberto; se ele crescer no meio, mandamos só os bytes anunciados.
                val fluxo = try { java.io.FileInputStream(arq) } catch (e: IOException) { null }
                if (fluxo == null) {
                    val r = erro(404, "não existe (talvez apagado na TV)")
                    PainelHttp.escrever(saida, r.codigo, r.tipo, r.corpo)
                } else fluxo.use {
                    val tamanho = arq.length()
                    PainelHttp.escreverCabecalho(saida, 200, resposta.tipo, tamanho, resposta.extras)
                    val buf = ByteArray(64 * 1024)
                    var faltam = tamanho
                    while (faltam > 0) {
                        val n = it.read(buf, 0, minOf(buf.size.toLong(), faltam).toInt())
                        if (n < 0) break
                        saida.write(buf, 0, n)
                        faltam -= n
                    }
                    saida.flush()
                }
            }
        } catch (e: IOException) {
            // conexão caiu ou demorou demais: nada a responder
        } catch (e: Exception) {
            Log.w(TAG, "erro atendendo $ip: $e")
        } finally {
            fechar(c)
        }
    }

    private fun rotear(req: Requisicao, ip: String): Resposta {
        val host = req.cabecalho("host")
        if (!ChecagensPainel.hostPermitido(host, nomesPermitidos(), Config.PAINEL_PORTA)) return erro(421, "nome desconhecido")
        if (req.metodo == "POST") {
            if (!ChecagensPainel.origemPermitida(req.cabecalho("origin"), host) || req.cabecalho("x-painel") != "1")
                return erro(403, "pedido de fora do painel")
        } else if (req.metodo != "GET") {
            return erro(405, "método não suportado")
        }

        // Página e arquivos dela: livres (a página de login é ela mesma).
        if (req.metodo == "GET") estatico(req.caminho)?.let { return it }

        val token = PainelHttp.cookie(req.cabecalho("cookie"), COOKIE)
        when (req.caminho) {
            "/api/estado" -> return estado(token)
            "/api/entrar" -> if (req.metodo == "POST") return entrar(req, ip)
            "/api/sair" -> if (req.metodo == "POST") { sessoes.encerrar(token); return json(200, JSONObject().put("ok", true), cookieApagado()) }
        }
        if (!req.caminho.startsWith("/api/")) return erro(404, "não existe")
        if (!sessoes.valida(token)) return erro(401, "entre com o PIN")

        when (req.caminho) {
            "/api/confirmar" -> if (req.metodo == "POST") return confirmar(req, token, ip)
            "/api/apps" -> if (req.metodo == "GET") return json(200, JSONObject().put("apps", PainelApps.listar(app)))
            "/api/backup" -> if (req.metodo == "GET") {
                PainelArmazem.registrar(app, ip, "baixar backup", "ok")
                return Resposta(200, "application/json; charset=utf-8", BackupConfig.exportar(app).toByteArray(),
                    mapOf("Content-Disposition" to "attachment; filename=\"launcher-backup.json\""))
            }
            "/api/backup/anterior" -> if (req.metodo == "GET") {
                val f = BackupConfig.arquivoAnterior(app)
                if (!f.exists()) return erro(404, "nenhuma restauração feita ainda")
                return Resposta(200, "application/json; charset=utf-8", f.readBytes(),
                    mapOf("Content-Disposition" to "attachment; filename=\"launcher-antes-de-restaurar.json\""))
            }
            "/api/backup/restaurar" -> if (req.metodo == "POST") return restaurar(req, token, ip)
            "/api/registro" -> if (req.metodo == "GET") return json(200, JSONObject().put("itens", PainelArmazem.registro(app)))
            "/api/apps/instalar" -> if (req.metodo == "POST") return instalar(req, token, ip)
            "/api/arquivos" -> if (req.metodo == "GET") { semAcesso()?.let { return it }; return json(200, PainelArquivos.listar(req.consulta["caminho"])) }
            "/api/arquivos/baixar" -> if (req.metodo == "GET") return baixarArquivo(req, ip)
            "/api/arquivos/enviar" -> if (req.metodo == "POST") return enviarArquivo(req, ip)
            "/api/arquivos/pasta" -> if (req.metodo == "POST") return criarPasta(req, ip)
            "/api/arquivos/apagar" -> if (req.metodo == "POST") return apagarArquivo(req, token, ip)
            "/api/tela/foto" -> if (req.metodo == "GET") return fotoTela()
            "/api/tela/tecla" -> if (req.metodo == "POST") return tecla(req)
            "/api/tela/texto" -> if (req.metodo == "POST") return texto(req, ip)
            "/api/tv/reiniciar" -> if (req.metodo == "POST") return reiniciarTv(token, ip)
            "/api/uso" -> if (req.metodo == "GET") return json(200, JSONObject().put("minutos", PainelControle.usoHoje(app)))
            // IA na nuvem (bloco 6, PedidoIa.kt). Ligar a IA não tem rota: só pelas Configurações, na TV.
            "/api/ia" -> if (req.metodo == "GET") return iaEstado()
            "/api/ia/chave" -> if (req.metodo == "POST") return iaChave(req, token, ip)
            "/api/ia/modelo" -> if (req.metodo == "POST") return iaModelo(req, ip)
            "/api/ia/pedir" -> if (req.metodo == "POST") return iaPedir(req, ip)
            "/api/ia/pedido" -> if (req.metodo == "GET") return PedidoIa.estado(req.consulta["id"])?.let { json(200, it.json()) } ?: erro(404, "pedido não encontrado")
        }

        // /api/apps/<pacote>/<ação>
        val partes = req.caminho.removePrefix("/api/apps/").split('/')
        if (req.caminho.startsWith("/api/apps/") && partes.size == 2) {
            val pacote = partes[0]
            if (!ChecagensPainel.pacoteValido(pacote)) return erro(400, "pacote inválido")
            when (partes[1]) {
                "icone" -> if (req.metodo == "GET") return icone(req, pacote)
                "ocultar" -> if (req.metodo == "POST") return ocultar(req, pacote, ip)
                "desinstalar" -> if (req.metodo == "POST") return desinstalar(pacote, token, ip)
                "ficha" -> if (req.metodo == "GET") return PainelFicha.ficha(app, pacote)
                    ?.apply { put("espaco", PainelControle.espaco(app, pacote) ?: JSONObject.NULL) }
                    ?.let { json(200, it) } ?: erro(404, "app não encontrado")
                "abrir" -> if (req.metodo == "POST") return acaoApp(ip, "abrir", pacote) { PainelControle.abrir(app, pacote) }
                "parar" -> if (req.metodo == "POST") return acaoApp(ip, "parar", pacote) { PainelControle.parar(app, pacote) }
                "limpar" -> if (req.metodo == "POST") {
                    if (!sessoes.elevada(token)) return precisaPin()
                    return acaoApp(ip, "limpar dados de", pacote) { PainelControle.limparDados(app, pacote) }
                }
                "permissoes" -> if (req.metodo == "GET") return PainelPermissoes.listar(app, pacote)?.let { json(200, it) } ?: erro(404, "app não encontrado")
                "permissao" -> if (req.metodo == "POST") return mudarPermissao(req, pacote, ip)
                "especial" -> if (req.metodo == "POST") return mudarEspecial(req, pacote, ip)
            }
        }
        return erro(404, "não existe")
    }

    // ---------------------------------------------------------------- página

    private val TIPOS = mapOf(
        "/" to ("index.html" to "text/html; charset=utf-8"),
        "/app.js" to ("app.js" to "text/javascript; charset=utf-8"),
        "/style.css" to ("style.css" to "text/css; charset=utf-8"),
    )

    private fun estatico(caminho: String): Resposta? {
        val (arquivo, tipo) = TIPOS[caminho] ?: return null
        val bytes = app.assets.open("painel/$arquivo").use { it.readBytes() }
        return Resposta(200, tipo, bytes)
    }

    // ---------------------------------------------------------------- login

    private fun cookieSessao(token: String) = mapOf("Set-Cookie" to "$COOKIE=$token; Path=/; HttpOnly; SameSite=Strict")
    private fun cookieApagado() = mapOf("Set-Cookie" to "$COOKIE=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0")

    private fun estado(token: String?): Resposta {
        // existe() e não valida(): a página consulta isto sozinha a cada 30 s, e isso não pode manter a sessão
        // viva para sempre (a sessão tem de cair com 30 min sem ninguém mexer).
        val logado = sessoes.existe(token)
        val o = JSONObject().put("logado", logado)
        if (logado) {
            o.put("aparelho", Settings.Global.getString(app.contentResolver, "device_name") ?: "TV")
            o.put("naTela", PainelApps.naTela(app))
            o.put("elevada", sessoes.elevada(token))
        }
        return json(200, o)
    }

    /**
     * Confere o PIN com o contador global. Devolve null se acertou, ou a resposta de erro pronta (PIN errado,
     * bloqueado, painel desligado por excesso de erros).
     */
    private fun conferirPin(req: Requisicao, ip: String, acao: String): Resposta? {
        val pin = try { JSONObject(req.corpoTexto()).optString("pin") } catch (e: Exception) { "" }
        synchronized(travaPin) {
            val ctl = controle()
            // Painel desligado (por erros ou nas Configurações) ou contador esgotado: não confere mais nada, nem os
            // pedidos que já estavam na fila quando a porta fechou.
            if (ctl.esgotado() || !PainelArmazem.podeLigar(app)) return erro(403, "painel desligado. Religue nas Configurações do launcher, na TV.")
            val espera = ctl.esperaMs()
            if (espera > 0) return erro(429, "muitas tentativas erradas; espere ${(espera + 999) / 1000} s") { put("esperarSeg", (espera + 999) / 1000) }
            if (PainelArmazem.conferirPin(app, pin)) { ctl.registrarSucesso(); return null }
            PainelArmazem.registrar(app, ip, acao, "PIN errado")
            return when (val r = ctl.registrarFalha()) {
                is ControleTentativas.Resultado.Errou -> erro(401, "PIN errado") { put("restantes", r.restantes) }
                is ControleTentativas.Resultado.Bloqueou -> erro(429, "PIN errado; bloqueado por ${r.ms / 60_000} min") { put("esperarSeg", r.ms / 1000) }
                ControleTentativas.Resultado.Desligar -> { desligarPorErros(ip); erro(403, "PIN errado demais: o painel foi desligado. Religue nas Configurações do launcher, na TV.") }
            }
        }
    }

    private fun desligarPorErros(ip: String) {
        PainelArmazem.definirDesligadoPorErros(app, true)
        PainelArmazem.registrar(app, ip, "painel", "desligado por PIN errado demais")
        Log.w(TAG, "PIN errado ${ControleTentativas.LIMITE_DESLIGAR} vezes: painel desligado")
        // Fecha a porta logo depois de responder (a conexão atual continua aberta até a resposta sair).
        Handler(Looper.getMainLooper()).post {
            ajustar(app)
            try { Toast.makeText(app, "Painel no navegador desligado: PIN errado muitas vezes", Toast.LENGTH_LONG).show() } catch (_: Exception) {}
        }
    }

    private fun entrar(req: Requisicao, ip: String): Resposta {
        conferirPin(req, ip, "entrar")?.let { return it }
        if (!ligado()) return erro(503, "painel desligado")
        val token = sessoes.criar()
        PainelArmazem.registrar(app, ip, "entrar", "ok")
        return json(200, JSONObject().put("ok", true), cookieSessao(token))
    }

    /** PIN redigitado para uma ação sem volta: a sessão fica "elevada" por 5 min. */
    private fun confirmar(req: Requisicao, token: String?, ip: String): Resposta {
        conferirPin(req, ip, "confirmar PIN")?.let { return it }
        sessoes.elevar(token)
        return json(200, JSONObject().put("ok", true))
    }

    private fun precisaPin() = erro(403, "confirme o PIN") { put("precisaPin", true) }

    // ---------------------------------------------------------------- apps

    private fun icone(req: Requisicao, pacote: String): Resposta {
        val (png, etiqueta) = PainelApps.icone(app, pacote) ?: return erro(404, "app não encontrado")
        // O ícone pode ficar no cache do navegador (privado, 1 dia); a etiqueta muda quando o app atualiza.
        val extras = mapOf("Cache-Control" to "private, max-age=86400", "ETag" to etiqueta)
        if (req.cabecalho("if-none-match") == etiqueta) return Resposta(304, null, ByteArray(0), extras)
        return Resposta(200, "image/png", png, extras)
    }

    private fun ocultar(req: Requisicao, pacote: String, ip: String): Resposta {
        val oculto = try { JSONObject(req.corpoTexto()).getBoolean("oculto") } catch (e: Exception) { return erro(400, "falta \"oculto\"") }
        val motivo = PainelApps.ocultar(app, pacote, oculto)
        PainelArmazem.registrar(app, ip, (if (oculto) "ocultar " else "reexibir ") + pacote, motivo ?: "ok")
        return if (motivo == null) json(200, JSONObject().put("ok", true)) else erro(409, motivo)
    }

    private fun desinstalar(pacote: String, token: String?, ip: String): Resposta {
        if (!sessoes.elevada(token)) return precisaPin()
        val motivo = PainelApps.desinstalar(app, pacote)
        PainelArmazem.registrar(app, ip, "desinstalar $pacote", motivo ?: "ok")
        return if (motivo == null) json(200, JSONObject().put("ok", true)) else erro(409, motivo)
    }

    // ---------------------------------------------------------------- backup

    private fun restaurar(req: Requisicao, token: String?, ip: String): Resposta {
        if (!sessoes.elevada(token)) return precisaPin()
        val n = try {
            BackupConfig.importar(app, req.corpoTexto())
        } catch (e: IllegalArgumentException) {
            PainelArmazem.registrar(app, ip, "restaurar backup", "recusado: ${e.message}")
            return erro(400, "backup recusado: ${e.message}")
        } catch (e: Exception) {
            PainelArmazem.registrar(app, ip, "restaurar backup", "falhou: $e")
            return erro(500, "a restauração falhou na TV (${e.javaClass.simpleName}); as configurações de antes foram mantidas")
        }
        PainelArmazem.registrar(app, ip, "restaurar backup", "ok, $n chaves")
        // Reinicia o launcher depois de a resposta sair (várias telas leem as preferências só ao abrir). É o mesmo
        // jeito do REINICIAR do adb. O sistema sobe o processo de novo para religar o serviço de acessibilidade, e é
        // ele (AutorizaShizukuService.onServiceConnected) que reabre o painel mesmo sem a HOME na frente. As sessões caem.
        Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 1500)
        return json(200, JSONObject().put("ok", true).put("chaves", n).put("reiniciando", true))
    }

    // ---------------------------------------------------------------- fase 2: permissões

    private fun corpoJson(req: Requisicao): JSONObject? = try { JSONObject(req.corpoTexto()) } catch (e: Exception) { null }

    private fun mudarPermissao(req: Requisicao, pacote: String, ip: String): Resposta {
        val o = corpoJson(req) ?: return erro(400, "JSON inválido")
        val permissao = o.optString("permissao")
        val acao = o.optString("acao")
        if (permissao.length > 200 || !ChecagensPainel.pacoteValido(permissao)) return erro(400, "permissão inválida")
        val motivo = PainelPermissoes.mudar(app, pacote, permissao, acao)
        PainelArmazem.registrar(app, ip, "permissão $acao ${permissao.substringAfterLast('.')} em $pacote", motivo ?: "ok")
        return if (motivo == null) json(200, JSONObject().put("ok", true)) else erro(409, motivo)
    }

    private fun mudarEspecial(req: Requisicao, pacote: String, ip: String): Resposta {
        val o = corpoJson(req) ?: return erro(400, "JSON inválido")
        val op = o.optString("op")
        val acao = o.optString("acao")
        val motivo = PainelPermissoes.mudarEspecial(app, pacote, op, acao)
        PainelArmazem.registrar(app, ip, "acesso $acao $op em $pacote", motivo ?: "ok")
        return if (motivo == null) json(200, JSONObject().put("ok", true)) else erro(409, motivo)
    }

    // ---------------------------------------------------------------- fase 2: instalar APK

    private fun instalar(req: Requisicao, token: String?, ip: String): Resposta {
        // Instalar é tão sem volta quanto desinstalar (o app novo pode pedir tudo): PIN de novo, ANTES de ler o arquivo.
        if (!sessoes.elevada(token)) return precisaPin()
        if (req.tamanho <= 0 || req.tamanho > PainelApps.LIMITE_APK) return erro(413, "APK vazio ou grande demais")
        val r = PainelApps.instalar(app, req.fluxoCorpo(), req.tamanho)
        val quem = r.pacote?.let { "${r.nome} ($it ${r.versao})" } ?: (req.consulta["nome"]?.take(80) ?: "APK")
        PainelArmazem.registrar(app, ip, "instalar $quem", r.motivo ?: "ok")
        if (r.motivo != null) return erro(409, r.motivo)
        return json(200, JSONObject().put("ok", true).put("pacote", r.pacote).put("nome", r.nome).put("versao", r.versao))
    }

    // ---------------------------------------------------------------- fase 2: arquivos

    /** Sem o "acesso a todos os arquivos", tenta se conceder pelo shell; se não der, a resposta de erro pronta. */
    private fun semAcesso(): Resposta? =
        if (PainelArquivos.garantirAcesso(app)) null
        else erro(503, "o launcher ainda não tem acesso à memória interna, e o shell dele não respondeu para conceder. " +
            "Confira se a depuração sem fio está ligada na TV e tente de novo.")

    private fun baixarArquivo(req: Requisicao, ip: String): Resposta {
        semAcesso()?.let { return it }
        val f = PainelArquivos.paraBaixar(req.consulta["caminho"])
        PainelArmazem.registrar(app, ip, "baixar ${f.name.take(80)}", "ok")
        return Resposta(200, "application/octet-stream", ByteArray(0),
            mapOf("Content-Disposition" to PainelHttp.nomeParaBaixar(f.name)), arquivo = f)
    }

    private fun enviarArquivo(req: Requisicao, ip: String): Resposta {
        semAcesso()?.let { return it }
        val nome = req.consulta["nome"]
        val f = PainelArquivos.receber(app, req.consulta["pasta"], nome, req.tamanho,
            req.consulta["substituir"] == "1", req.fluxoCorpo())
        PainelArmazem.registrar(app, ip, "enviar ${f.name.take(80)} (${req.tamanho / 1024} KB)", "ok")
        return json(200, JSONObject().put("ok", true).put("livre", PainelArquivos.livre()))
    }

    private fun criarPasta(req: Requisicao, ip: String): Resposta {
        semAcesso()?.let { return it }
        val o = corpoJson(req) ?: return erro(400, "JSON inválido")
        PainelArquivos.criarPasta(o.optString("pasta"), o.optString("nome"))
        PainelArmazem.registrar(app, ip, "nova pasta ${o.optString("nome").take(80)}", "ok")
        return json(200, JSONObject().put("ok", true))
    }

    private fun apagarArquivo(req: Requisicao, token: String?, ip: String): Resposta {
        if (!sessoes.elevada(token)) return precisaPin()
        semAcesso()?.let { return it }
        val o = corpoJson(req) ?: return erro(400, "JSON inválido")
        val caminho = o.optString("caminho")
        val n = PainelArquivos.apagar(app, caminho)
        PainelArmazem.registrar(app, ip, "apagar ${caminho.take(120)}", "ok, $n item(ns)")
        return json(200, JSONObject().put("ok", true).put("apagados", n))
    }

    // ---------------------------------------------------------------- bloco 6: IA na nuvem

    private fun iaEstado(): Resposta = json(200, JSONObject()
        .put("ligada", IaArmazem.ligada(app))
        .put("chave", IaArmazem.finalDaChave(app) ?: JSONObject.NULL)   // nunca a chave: só os 4 últimos caracteres
        .put("modelo", IaArmazem.modelo(app)))

    /** Cadastrar ou apagar a chave exige o PIN redigitado (quem trocar a chave escolhe para onde vão os pedidos). */
    private fun iaChave(req: Requisicao, token: String?, ip: String): Resposta {
        if (!sessoes.elevada(token)) return precisaPin()
        val o = corpoJson(req) ?: return erro(400, "JSON inválido")
        if (o.optBoolean("apagar")) {
            IaArmazem.definirChave(app, null)
            PainelArmazem.registrar(app, ip, "IA: apagar chave", "ok")
            return json(200, JSONObject().put("ok", true))
        }
        val chave = ComandosIa.limparChave(o.optString("chave"))
        // A mensagem diz o formato esperado e o tamanho recebido, nunca a chave.
        if (!ComandosIa.chaveValida(chave)) return erro(400, "isso não parece uma chave do Google AI Studio (esperado \"AQ.…\" ou " +
            "\"AIza…\", de 30 a 200 letras, números, . - _; recebi ${chave.length} caracteres)")
        if (!IaArmazem.definirChave(app, chave)) return erro(500, "não consegui guardar a chave na TV")
        PainelArmazem.registrar(app, ip, "IA: cadastrar chave ${IaArmazem.finalDaChave(app)}", "ok")
        return json(200, JSONObject().put("ok", true).put("chave", IaArmazem.finalDaChave(app)))
    }

    private fun iaModelo(req: Requisicao, ip: String): Resposta {
        val m = corpoJson(req)?.optString("modelo")?.trim() ?: return erro(400, "JSON inválido")
        if (!IaArmazem.definirModelo(app, m)) return erro(400, "nome de modelo inválido")
        PainelArmazem.registrar(app, ip, "IA: modelo $m", "ok")
        return json(200, JSONObject().put("ok", true).put("modelo", m))
    }

    private fun iaPedir(req: Requisicao, ip: String): Resposta {
        val frase = corpoJson(req)?.optString("frase") ?: return erro(400, "JSON inválido")
        val p = PedidoIa.pedir(app, frase, ip)
        // A frase não vai para o registro (pode ser pessoal); só o tamanho.
        PainelArmazem.registrar(app, ip, "IA: pedido de ${p.frase.length} caractere(s)", if (p.situacao == PedidoIa.Situacao.ERRO) p.mensagem else "enviado")
        return if (p.situacao == PedidoIa.Situacao.ERRO) erro(409, p.mensagem) else json(200, p.json())
    }

    // ---------------------------------------------------------------- bloco C: tela, controle, ações de app

    private fun acaoApp(ip: String, acao: String, pacote: String, fazer: () -> String?): Resposta {
        val motivo = fazer()
        PainelArmazem.registrar(app, ip, "$acao $pacote", motivo ?: "ok")
        return if (motivo == null) json(200, JSONObject().put("ok", true)) else erro(409, motivo)
    }

    private fun fotoTela(): Resposta {
        val (jpg, motivo) = PainelControle.foto()
        return if (jpg != null) Resposta(200, "image/jpeg", jpg) else erro(503, motivo ?: "sem foto")
    }

    private fun tecla(req: Requisicao): Resposta {
        val nome = corpoJson(req)?.optString("tecla") ?: return erro(400, "JSON inválido")
        val motivo = PainelControle.tecla(app, nome)
        return if (motivo == null) json(200, JSONObject().put("ok", true)) else erro(409, motivo)
    }

    private fun texto(req: Requisicao, ip: String): Resposta {
        val t = corpoJson(req)?.optString("texto") ?: return erro(400, "JSON inválido")
        val motivo = PainelControle.digitar(app, t)
        // O texto em si não vai para o registro (pode ser uma senha digitada pelo painel).
        PainelArmazem.registrar(app, ip, "digitar ${t.length} caractere(s)", motivo ?: "ok")
        return if (motivo == null) json(200, JSONObject().put("ok", true)) else erro(409, motivo)
    }

    private fun reiniciarTv(token: String?, ip: String): Resposta {
        if (!sessoes.elevada(token)) return precisaPin()
        PainelArmazem.registrar(app, ip, "reiniciar a TV", "pedido")
        // Depois da resposta sair: senão o navegador fica sem saber se deu certo.
        Handler(Looper.getMainLooper()).postDelayed({ PainelControle.reiniciarTv(app) }, 1500)
        return json(200, JSONObject().put("ok", true))
    }
}
