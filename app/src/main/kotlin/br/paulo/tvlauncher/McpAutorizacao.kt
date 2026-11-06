package br.paulo.tvlauncher

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * O "login" do servidor MCP da TV (opção E do estudo, 05/11/2026): um servidor OAuth 2.1 pequeno, do jeito que o Claude
 * exige para um conector pessoal (registro dinâmico de cliente + código com PKCE S256 + token). Docs:
 * https://claude.com/docs/connectors/building/authentication
 *
 * Como funciona, em português:
 *  1. O Claude se REGISTRA ([registrar]) e recebe um client_id. Só aceitamos endereços de volta (redirect_uri) do
 *     claude.ai/claude.com ou do próprio computador (localhost, para o Claude Code). Isso impede que um site qualquer
 *     use a nossa página de PIN para roubar um acesso.
 *  2. O Claude abre a nossa página /authorize no navegador do Paulo. Ele digita o PIN do painel. Certo o PIN, damos um
 *     CÓDIGO de uso único que vale 2 minutos ([criarCodigo]).
 *  3. O Claude troca o código por um TOKEN ([trocarCodigo]), provando com o "code_verifier" que foi ele quem começou
 *     (PKCE: só quem tem o segredo original chega no mesmo resumo SHA-256).
 *  4. Cada chamada ao /mcp leva o token ([tokenValido]). Ele vale 1 hora; o "refresh token" (30 dias) pega outro.
 *
 * Guardamos só o RESUMO (SHA-256) de cada token, nunca o token: quem copiar o arquivo não consegue usar.
 * Nada de Android aqui (só org.json), para os testes rodarem no computador (McpAutorizacaoTest).
 */
class McpAutorizacao(
    private val relogio: () -> Long,
    estadoSalvo: String? = null,
    private val salvar: (String) -> Unit = {},
    private val aleatorio: SecureRandom = SecureRandom(),
) {
    companion object {
        const val CODIGO_MS = 2 * 60_000L
        const val ACESSO_MS = 60 * 60_000L
        const val RENOVAR_MS = 30L * 24 * 60 * 60_000L
        const val MAX_CLIENTES = 10
        const val MAX_TOKENS = 40
        /** Para onde o login pode voltar: o Claude na web/app e o Claude Code/Desktop no computador. */
        // Clientes na nuvem que voltam por https: Claude, ChatGPT (connector_platform_oauth_redirect e connector/oauth/*)
        // e VS Code web. Os de linha de comando e apps de computador (Claude Code, Codex, Gemini CLI, Cursor, VS Code)
        // voltam para localhost/127.0.0.1 (HOSTS_LOCAIS).
        val HOSTS_HTTPS = setOf("claude.ai", "claude.com", "chatgpt.com", "vscode.dev")
        val HOSTS_LOCAIS = setOf("localhost", "127.0.0.1")

        fun resumo(texto: String): String = MessageDigest.getInstance("SHA-256").digest(texto.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        /** PKCE S256: base64url(SHA-256(verifier)), sem "=" no fim. */
        fun desafioS256(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        /** redirect_uri aceito: https em claude.ai/claude.com, ou http em localhost/127.0.0.1 (qualquer porta). */
        fun redirectPermitido(uri: String?): Boolean {
            if (uri == null || uri.length > 500) return false
            val u = try { URI(uri) } catch (e: Exception) { return false }
            if (u.rawFragment != null || u.userInfo != null) return false
            val host = u.host?.lowercase() ?: return false
            return when (u.scheme?.lowercase()) {
                "https" -> host in HOSTS_HTTPS
                "http" -> host in HOSTS_LOCAIS
                else -> false
            }
        }

        /** O code_verifier do PKCE: 43 a 128 caracteres de [A-Za-z0-9-._~] (RFC 7636). */
        fun verifierValido(v: String?): Boolean = v != null && v.length in 43..128 &&
            v.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "-._~" }
    }

    private class Cliente(val id: String, val nome: String, val redirects: List<String>, val criado: Long)
    private class Codigo(val clienteId: String, val redirect: String, val desafio: String, val expira: Long)
    /** [renovar] = true para o refresh token. [familia]: tokens do mesmo login (revogados juntos). */
    private class Token(val clienteId: String, val expira: Long, val renovar: Boolean, val familia: String)

    private val clientes = LinkedHashMap<String, Cliente>()
    private val codigos = HashMap<String, Codigo>()          // só na memória: valem 2 minutos
    private val tokens = LinkedHashMap<String, Token>()      // chave = resumo do token

    init { carregar(estadoSalvo) }

    class Erro(val codigo: String, val descricao: String) : Exception(descricao)

    /** O par entregue ao Claude no fim do login (e a cada renovação). */
    class Par(val acesso: String, val renovar: String, val segundos: Long)

    // ---------------------------------------------------------------- 1. registro do cliente

    /** Registro dinâmico (RFC 7591). Devolve o JSON da resposta, ou lança [Erro]. */
    @Synchronized fun registrar(pedido: JSONObject): JSONObject {
        val lista = pedido.optJSONArray("redirect_uris") ?: throw Erro("invalid_redirect_uri", "faltam redirect_uris")
        val redirects = (0 until lista.length()).map { lista.optString(it) }
        if (redirects.isEmpty() || redirects.size > 5 || !redirects.all { redirectPermitido(it) })
            throw Erro("invalid_redirect_uri", "endereço de volta não permitido")
        val nome = pedido.optString("client_name").filter { it.isLetterOrDigit() || it in " .-_()" }.take(60).ifBlank { "cliente" }
        // Cheio: sai o registro mais antigo (o Claude registra de novo quando precisa).
        while (clientes.size >= MAX_CLIENTES) clientes.remove(clientes.keys.first())
        val c = Cliente("c_" + aleatorioTexto(16), nome, redirects, relogio())
        clientes[c.id] = c
        gravar()
        return JSONObject()
            .put("client_id", c.id)
            .put("client_name", c.nome)
            .put("redirect_uris", JSONArray(c.redirects))
            .put("token_endpoint_auth_method", "none")
            .put("grant_types", JSONArray(listOf("authorization_code", "refresh_token")))
            .put("response_types", JSONArray(listOf("code")))
            .put("client_id_issued_at", c.criado / 1000)
    }

    /** O nome que o cliente deu ao se registrar (para a página de PIN mostrar quem está pedindo). Null = desconhecido. */
    @Synchronized fun nomeDoCliente(id: String?): String? = clientes[id]?.nome

    // ---------------------------------------------------------------- 2. página do PIN

    /**
     * Confere o pedido da página /authorize ANTES de mostrar o PIN. Erro aqui não volta para o redirect_uri (pode ser
     * de um site falso): a página mostra a mensagem.
     */
    @Synchronized fun conferirPedido(clienteId: String?, redirect: String?, tipo: String?, desafio: String?, metodo: String?) {
        val c = clientes[clienteId] ?: throw Erro("invalid_client", "cliente desconhecido; registre o conector de novo")
        if (redirect == null || redirect !in c.redirects) throw Erro("invalid_request", "endereço de volta diferente do registrado")
        if (tipo != "code") throw Erro("unsupported_response_type", "só response_type=code")
        if (metodo != "S256" || desafio == null || desafio.length !in 43..128) throw Erro("invalid_request", "falta PKCE S256")
    }

    /** PIN certo: cria o código de uso único. Chamar só depois de [conferirPedido] e do PIN conferido. */
    @Synchronized fun criarCodigo(clienteId: String, redirect: String, desafio: String): String {
        limpar()
        val codigo = aleatorioTexto(32)
        codigos[resumo(codigo)] = Codigo(clienteId, redirect, desafio, relogio() + CODIGO_MS)
        return codigo
    }

    // ---------------------------------------------------------------- 3. código -> token

    @Synchronized fun trocarCodigo(codigo: String?, clienteId: String?, redirect: String?, verifier: String?): Par {
        // remove já: o código vale UMA vez, mesmo que o resto dê errado
        val c = codigo?.let { codigos.remove(resumo(it)) } ?: throw Erro("invalid_grant", "código inválido ou já usado")
        if (c.expira < relogio()) throw Erro("invalid_grant", "código vencido")
        if (c.clienteId != clienteId || c.redirect != redirect) throw Erro("invalid_grant", "código de outro cliente")
        if (!verifierValido(verifier) || desafioS256(verifier!!) != c.desafio) throw Erro("invalid_grant", "PKCE não confere")
        return emitir(c.clienteId, aleatorioTexto(12))
    }

    /** Renovação: o refresh token usado morre e nasce outro (rotação). Reuso de um já usado derruba a família. */
    @Synchronized fun renovar(refresh: String?, clienteId: String?): Par {
        val chave = refresh?.let { resumo(it) } ?: throw Erro("invalid_grant", "falta refresh_token")
        val t = tokens.remove(chave) ?: throw Erro("invalid_grant", "refresh token inválido")
        if (!t.renovar || t.expira < relogio() || t.clienteId != clienteId) { gravar(); throw Erro("invalid_grant", "refresh token inválido") }
        tokens.values.removeAll { it.familia == t.familia }   // o token de acesso antigo também sai
        return emitir(t.clienteId, t.familia)
    }

    private fun emitir(clienteId: String, familia: String): Par {
        limpar()
        val acesso = aleatorioTexto(32)
        val renovar = aleatorioTexto(32)
        val agora = relogio()
        tokens[resumo(acesso)] = Token(clienteId, agora + ACESSO_MS, false, familia)
        tokens[resumo(renovar)] = Token(clienteId, agora + RENOVAR_MS, true, familia)
        while (tokens.size > MAX_TOKENS) tokens.remove(tokens.keys.first())
        gravar()
        return Par(acesso, renovar, ACESSO_MS / 1000)
    }

    // ---------------------------------------------------------------- 4. cada chamada ao /mcp

    /** O token de acesso do cabeçalho "Authorization: Bearer ..." vale? (refresh token NÃO serve aqui). */
    @Synchronized fun tokenValido(acesso: String?): Boolean {
        val t = acesso?.let { tokens[resumo(it)] } ?: return false
        return !t.renovar && t.expira >= relogio()
    }

    /** "Revogar acessos" nas Configurações da TV: apaga clientes e tokens. O Claude terá de fazer o login de novo. */
    @Synchronized fun revogarTudo() { clientes.clear(); codigos.clear(); tokens.clear(); gravar() }

    @Synchronized fun quantosLogins(): Int { limpar(); return tokens.values.filter { it.renovar }.map { it.familia }.toSet().size }

    // ---------------------------------------------------------------- guardar

    private fun limpar() {
        val agora = relogio()
        codigos.values.removeAll { it.expira < agora }
        tokens.values.removeAll { it.expira < agora }
    }

    private fun aleatorioTexto(bytes: Int): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also { aleatorio.nextBytes(it) })

    private fun gravar() {
        val cs = JSONArray()
        for (c in clientes.values) cs.put(JSONObject().put("id", c.id).put("nome", c.nome).put("redirects", JSONArray(c.redirects)).put("criado", c.criado))
        val ts = JSONArray()
        for ((h, t) in tokens) ts.put(JSONObject().put("h", h).put("c", t.clienteId).put("e", t.expira).put("r", t.renovar).put("f", t.familia))
        salvar(JSONObject().put("clientes", cs).put("tokens", ts).toString())
    }

    private fun carregar(texto: String?) {
        if (texto.isNullOrEmpty()) return
        try {
            val o = JSONObject(texto)
            val cs = o.optJSONArray("clientes") ?: JSONArray()
            for (i in 0 until cs.length()) {
                val c = cs.getJSONObject(i)
                val r = c.getJSONArray("redirects")
                clientes[c.getString("id")] = Cliente(c.getString("id"), c.optString("nome"), (0 until r.length()).map { r.getString(it) }, c.optLong("criado"))
            }
            val ts = o.optJSONArray("tokens") ?: JSONArray()
            for (i in 0 until ts.length()) {
                val t = ts.getJSONObject(i)
                tokens[t.getString("h")] = Token(t.getString("c"), t.getLong("e"), t.getBoolean("r"), t.getString("f"))
            }
        } catch (e: Exception) {
            // Arquivo estragado: começa do zero (o pior caso é o Claude pedir o login de novo).
            clientes.clear(); tokens.clear()
        }
    }
}
