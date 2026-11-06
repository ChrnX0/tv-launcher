package br.paulo.tvlauncher

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * O servidor MCP da TV (opção E do estudo, 05/11/2026): o Claude, da nuvem, chama ferramentas da TV.
 *
 * Caminho de um pedido:  Claude -> https://tv-sala.<rede>.ts.net (Tailscale Funnel) -> programa tsnet na TV
 * (TunelTailscale.kt) -> ESTE servidor em 127.0.0.1:[Config.MCP_PORTA].
 *
 * Por que só 127.0.0.1: daqui ninguém da rede de casa nem da internet entra direto; só o túnel (que roda na própria
 * TV). O painel :8080 NÃO passa pelo túnel. Outro app da TV também alcança 127.0.0.1, mas sem o login (PIN) não faz nada.
 *
 * Rotas:
 *  - /.well-known/oauth-protected-resource e /.well-known/oauth-authorization-server: onde o Claude descobre o login.
 *  - POST /register, GET+POST /authorize (página do PIN), POST /token: o OAuth (McpAutorizacao).
 *  - POST /mcp: o protocolo (McpProtocolo), só com token válido.
 *
 * Desligado por padrão; liga nas Configurações do launcher, na TV. Desligado = sem socket nem thread.
 */
object McpServidor {
    private const val TAG = "TvLauncher.Mcp"
    private const val PREFS = "mcp_seguro"            // fora do backup, como painel_seguro (BackupConfig não exporta)
    private const val LIMITE_CORPO = 64 * 1024
    private const val TEMPO_LEITURA_MS = 20_000

    @Volatile private var servidor: ServerSocket? = null
    @Volatile private var auth: McpAutorizacao? = null
    @Volatile private var tentativas: ControleTentativas? = null
    private val travaPin = Any()
    private lateinit var app: Context

    private val pool by lazy {
        ThreadPoolExecutor(1, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(16)) { r -> Thread(r, "Mcp-req").apply { isDaemon = true } }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ligado(ctx: Context): Boolean = prefs(ctx).getBoolean("ligado", false)

    /** Ligar exige o PIN do painel já definido (é ele que o Paulo digita no login). */
    fun definirLigado(ctx: Context, sim: Boolean) {
        prefs(ctx).edit().putBoolean("ligado", sim).putInt("falhas", if (sim) 0 else prefs(ctx).getInt("falhas", 0)).apply()
        if (sim) tentativas = null
        ajustar(ctx)
        TunelTailscale.ajustar(ctx)
    }

    /** Liga ou desliga o socket conforme a preferência. Idempotente. */
    @Synchronized fun ajustar(ctx: Context) {
        app = ctx.applicationContext
        val querLigado = ligado(app) && PainelArmazem.temPin(app)
        if (querLigado && servidor == null) {
            val s = ServerSocket()
            try {
                s.reuseAddress = true
                s.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), Config.MCP_PORTA), 16)
            } catch (e: IOException) {
                Log.w(TAG, "não consegui abrir 127.0.0.1:${Config.MCP_PORTA}: $e"); try { s.close() } catch (_: IOException) {}
                return
            }
            servidor = s
            Thread({ aceitar(s) }, "Mcp-aceitar").apply { isDaemon = true }.start()
            Log.i(TAG, "MCP ligado em 127.0.0.1:${Config.MCP_PORTA}")
        } else if (!querLigado && servidor != null) {
            try { servidor?.close() } catch (_: IOException) {}
            servidor = null
            Log.i(TAG, "MCP desligado")
        }
    }

    /** "Revogar acessos": o Claude precisa fazer o login (PIN) de novo. */
    fun revogar(ctx: Context) { app = ctx.applicationContext; autorizacao().revogarTudo() }

    fun logins(ctx: Context): Int { app = ctx.applicationContext; return autorizacao().quantosLogins() }

    private fun autorizacao(): McpAutorizacao = auth ?: synchronized(this) {
        auth ?: McpAutorizacao({ System.currentTimeMillis() }, prefs(app).getString("estado", null),
            { prefs(app).edit().putString("estado", it).apply() }).also { auth = it }
    }

    private fun controle(): ControleTentativas = tentativas ?: ControleTentativas(
        { System.currentTimeMillis() }, prefs(app).getInt("falhas", 0)
    ) { prefs(app).edit().putInt("falhas", it).apply() }.also { tentativas = it }

    // ---------------------------------------------------------------- conexões

    private fun aceitar(s: ServerSocket) {
        while (!s.isClosed) {
            val c = try { s.accept() } catch (e: IOException) {
                if (s.isClosed) break
                try { Thread.sleep(200) } catch (_: InterruptedException) { break }
                continue
            }
            if (!c.inetAddress.isLoopbackAddress) { fechar(c); continue }   // segurança extra: só a própria TV
            try { pool.execute { atender(c) } } catch (e: RejectedExecutionException) { fechar(c) }
        }
    }

    private fun fechar(c: Socket) = try { c.close() } catch (_: IOException) {}

    private class Resposta(val codigo: Int, val tipo: String?, val corpo: ByteArray, val extras: Map<String, String> = emptyMap())

    private fun json(codigo: Int, o: JSONObject, extras: Map<String, String> = emptyMap()) =
        Resposta(codigo, "application/json; charset=utf-8", o.toString().toByteArray(), extras)

    private fun erroOAuth(codigo: Int, e: String, descricao: String) =
        json(codigo, JSONObject().put("error", e).put("error_description", descricao))

    private fun atender(c: Socket) {
        try {
            c.soTimeout = TEMPO_LEITURA_MS
            val entrada = c.getInputStream().buffered(8192)
            val saida = c.getOutputStream().buffered(16 * 1024)
            val r = try {
                val req = PainelHttp.ler(entrada, LIMITE_CORPO)
                if (req.corpo.isNotEmpty() && req.cabecalho("content-type")?.contains("json") == true && !ChecagensPainel.jsonRaso(req.corpoTexto()))
                    json(400, JSONObject().put("erro", "JSON inválido"))
                else try { rotear(req) } catch (e: Exception) {
                    Log.w(TAG, "erro em ${req.metodo} ${req.caminho}: ${e.javaClass.simpleName}")
                    json(500, JSONObject().put("erro", "erro na TV"))
                }
            } catch (e: ErroHttp) { json(e.codigo, JSONObject().put("erro", e.message ?: "pedido inválido")) }
            PainelHttp.escrever(saida, r.codigo, r.tipo, r.corpo, r.extras)
        } catch (e: IOException) {
        } catch (e: Exception) {
            Log.w(TAG, "erro atendendo: $e")
        } finally { fechar(c) }
    }

    /**
     * O endereço público, montado do Host que o túnel repassa. Só aceitamos o nome que o túnel anunciou
     * (TunelTailscale.nome); sem ele ainda, qualquer *.ts.net (o túnel é o único caminho até aqui).
     */
    private fun base(req: Requisicao): String? {
        val host = req.cabecalho("host")?.lowercase()?.substringBefore(':') ?: return null
        val esperado = TunelTailscale.nome(app)
        val ok = if (esperado != null) host == esperado else host.endsWith(".ts.net") && host.all { it.isLetterOrDigit() || it in ".-" }
        return if (ok) "https://$host" else null
    }

    private fun rotear(req: Requisicao): Resposta {
        if (!ligado(app)) return json(503, JSONObject().put("erro", "MCP desligado na TV"))
        val base = base(req) ?: return json(421, JSONObject().put("erro", "nome desconhecido"))
        return when (req.caminho) {
            "/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp" -> json(200, JSONObject()
                .put("resource", "$base/mcp")
                .put("authorization_servers", JSONArray().put(base))
                .put("bearer_methods_supported", JSONArray().put("header")))
            "/.well-known/oauth-authorization-server" -> json(200, JSONObject()
                .put("issuer", base)
                .put("authorization_endpoint", "$base/authorize")
                .put("token_endpoint", "$base/token")
                .put("registration_endpoint", "$base/register")
                .put("response_types_supported", JSONArray().put("code"))
                .put("grant_types_supported", JSONArray().put("authorization_code").put("refresh_token"))
                .put("code_challenge_methods_supported", JSONArray().put("S256"))
                .put("token_endpoint_auth_methods_supported", JSONArray().put("none")))
            "/register" -> if (req.metodo != "POST") json(405, JSONObject()) else try {
                json(201, autorizacao().registrar(JSONObject(req.corpoTexto())))
            } catch (e: McpAutorizacao.Erro) { erroOAuth(400, e.codigo, e.descricao) } catch (e: Exception) { erroOAuth(400, "invalid_client_metadata", "JSON inválido") }
            "/authorize" -> authorize(req)
            "/token" -> if (req.metodo != "POST") json(405, JSONObject()) else token(req)
            "/mcp" -> mcp(req, base)
            else -> json(404, JSONObject().put("erro", "não encontrado"))
        }
    }

    // ---------------------------------------------------------------- MCP

    private fun mcp(req: Requisicao, base: String): Resposta {
        val token = req.cabecalho("authorization")?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()
        if (!autorizacao().tokenValido(token)) return Resposta(401, "application/json", """{"erro":"faça o login"}""".toByteArray(),
            mapOf("WWW-Authenticate" to "Bearer resource_metadata=\"$base/.well-known/oauth-protected-resource\""))
        return when (req.metodo) {
            "POST" -> {
                val r = McpProtocolo.tratar(req.corpoTexto(), McpFerramentas(app))
                if (r == null) Resposta(202, null, ByteArray(0)) else json(200, r)
            }
            "GET" -> Resposta(405, null, ByteArray(0), mapOf("Allow" to "POST"))   // sem fluxo SSE: tudo responde no POST
            "DELETE" -> Resposta(204, null, ByteArray(0))
            else -> json(405, JSONObject())
        }
    }

    // ---------------------------------------------------------------- OAuth: página do PIN e token

    private fun form(texto: String): Map<String, String> = texto.split('&').filter { it.isNotEmpty() }.associate {
        val i = it.indexOf('=')
        val k = URLDecoder.decode(if (i < 0) it else it.substring(0, i), "UTF-8")
        val v = if (i < 0) "" else URLDecoder.decode(it.substring(i + 1), "UTF-8")
        k to v
    }

    private fun authorize(req: Requisicao): Resposta {
        val p = if (req.metodo == "POST") form(req.corpoTexto()) else req.consulta
        val cliente = p["client_id"]; val redirect = p["redirect_uri"]; val desafio = p["code_challenge"]
        try {
            autorizacao().conferirPedido(cliente, redirect, p["response_type"], desafio, p["code_challenge_method"])
        } catch (e: McpAutorizacao.Erro) {
            return pagina("Pedido inválido", "<p>${esc(e.descricao)}</p>", null)
        }
        val nome = autorizacao().nomeDoCliente(cliente) ?: "?"
        if (req.metodo != "POST") return paginaPin(p, nome, null, redirect!!)

        synchronized(travaPin) {
            val ctl = controle()
            if (ctl.esgotado() || !ligado(app)) return pagina("Desligado", "<p>O acesso pela internet foi desligado. Religue nas Configurações do launcher, na TV.</p>", null)
            val espera = ctl.esperaMs()
            if (espera > 0) return paginaPin(p, nome, "Muitas tentativas erradas. Espere ${(espera + 999) / 1000} s.", redirect!!)
            if (!PainelArmazem.conferirPin(app, p["pin"] ?: "")) {
                PainelArmazem.registrar(app, "internet", "login MCP", "PIN errado")
                return when (val r = ctl.registrarFalha()) {
                    is ControleTentativas.Resultado.Errou -> paginaPin(p, nome, "PIN errado (${r.restantes} tentativa(s) antes de esperar).", redirect!!)
                    is ControleTentativas.Resultado.Bloqueou -> paginaPin(p, nome, "PIN errado. Bloqueado por ${r.ms / 60_000} min.", redirect!!)
                    ControleTentativas.Resultado.Desligar -> {
                        prefs(app).edit().putBoolean("ligado", false).apply()
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ ajustar(app); TunelTailscale.ajustar(app) }, 1000)
                        Log.w(TAG, "PIN errado demais: MCP desligado")
                        pagina("Desligado", "<p>PIN errado demais. O acesso pela internet foi desligado; religue na TV.</p>", null)
                    }
                }
            }
            ctl.registrarSucesso()
        }
        val codigo = autorizacao().criarCodigo(cliente!!, redirect!!, desafio!!)
        PainelArmazem.registrar(app, "internet", "login MCP ($nome)", "ok")
        val destino = StringBuilder(redirect).append(if ('?' in redirect) '&' else '?')
            .append("code=").append(URLEncoder.encode(codigo, "UTF-8"))
        p["state"]?.let { destino.append("&state=").append(URLEncoder.encode(it, "UTF-8")) }
        return Resposta(302, null, ByteArray(0), mapOf("Location" to destino.toString()))
    }

    private fun token(req: Requisicao): Resposta {
        val p = form(req.corpoTexto())
        return try {
            val par = when (p["grant_type"]) {
                "authorization_code" -> autorizacao().trocarCodigo(p["code"], p["client_id"], p["redirect_uri"], p["code_verifier"])
                "refresh_token" -> autorizacao().renovar(p["refresh_token"], p["client_id"])
                else -> return erroOAuth(400, "unsupported_grant_type", "grant_type não suportado")
            }
            json(200, JSONObject().put("access_token", par.acesso).put("token_type", "Bearer")
                .put("expires_in", par.segundos).put("refresh_token", par.renovar), mapOf("Pragma" to "no-cache"))
        } catch (e: McpAutorizacao.Erro) { erroOAuth(400, e.codigo, e.descricao) }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun paginaPin(p: Map<String, String>, nome: String, aviso: String?, redirect: String): Resposta {
        val ocultos = listOf("client_id", "redirect_uri", "response_type", "code_challenge", "code_challenge_method", "state", "scope", "resource")
            .mapNotNull { k -> p[k]?.let { "<input type=\"hidden\" name=\"$k\" value=\"${esc(it)}\">" } }.joinToString("")
        // O nome vem do próprio cliente (pode mentir); o destino da volta não: mostramos os dois.
        val destino = try { URI(redirect).host ?: "?" } catch (e: Exception) { "?" }
        val corpo = "<p><b>${esc(nome)}</b> (volta para <b>${esc(destino)}</b>) quer usar as ferramentas da TV da sala.</p>" +
            "<p>Ações só acontecem depois de um OK no controle da TV.</p>" +
            (aviso?.let { "<p class=\"aviso\">${esc(it)}</p>" } ?: "") +
            "<form method=\"post\" action=\"/authorize\">$ocultos" +
            "<input name=\"pin\" type=\"password\" inputmode=\"numeric\" autocomplete=\"off\" maxlength=\"12\" placeholder=\"PIN do painel\" autofocus>" +
            "<button type=\"submit\">Permitir</button></form>"
        return pagina("Entrar na TV", corpo, redirect)
    }

    /** [redirect]: o form-action da CSP precisa permitir o destino do redirecionamento (o navegador confere). */
    private fun pagina(titulo: String, corpo: String, redirect: String?): Resposta {
        val origem = redirect?.let { try { URI(it).let { u -> "${u.scheme}://${u.authority}" } } catch (e: Exception) { null } }
        val html = "<!doctype html><html lang=\"pt-BR\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<title>${esc(titulo)}</title><style>body{font-family:sans-serif;background:#111;color:#eee;max-width:420px;margin:40px auto;padding:0 16px}" +
            "input,button{font-size:18px;padding:10px;margin:6px 0;width:100%;box-sizing:border-box}.aviso{color:#f99}</style></head>" +
            "<body><h1>${esc(titulo)}</h1>$corpo</body></html>"
        val csp = "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'" +
            (origem?.let { " $it" } ?: "")
        return Resposta(200, "text/html; charset=utf-8", html.toByteArray(), mapOf("Content-Security-Policy" to csp))
    }
}
