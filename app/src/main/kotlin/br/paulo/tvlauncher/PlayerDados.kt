package br.paulo.tvlauncher

import org.json.JSONArray
import org.json.JSONObject

/**
 * Player universal, passo 2 (06/11/2026): as contas (fontes) do player e a conversão para JSON, para guardar em
 * arquivo. Só lógica pura, testada em PlayerDadosTest. Quem cifra e grava na TV é o PlayerArmazem.
 */
data class FonteTv(
    val id: String,               // gerado na criação, nunca muda (nome dos arquivos de cache)
    val nome: String,             // o que aparece para o usuário ("Lista da casa")
    val tipo: String,             // TIPO_M3U ou TIPO_XTREAM
    val endereco: String,         // M3U: o link da lista. Xtream: o servidor (já normalizado)
    val usuario: String = "",     // só Xtream
    val senha: String = "",       // só Xtream
    val guia: String = "",        // link XMLTV opcional (vazio = o da própria lista/conta, se houver)
) {
    companion object {
        const val TIPO_M3U = "m3u"
        const val TIPO_XTREAM = "xtream"
    }

    val xtream: Xtream.Conta? get() = if (tipo == TIPO_XTREAM) Xtream.Conta(endereco, usuario, senha) else null

    /** Só o nome do servidor, sem caminho, usuário ou senha: é o que pode aparecer em tela e em log. */
    val servidorVisivel: String get() = endereco.substringAfter("://").substringBefore('/').substringBefore('?').substringAfter('@')
}

object PlayerDados {
    /** Valida e monta uma fonte nova. Devolve o erro em texto para o usuário, ou a fonte pronta. */
    fun criar(id: String, nome: String, tipo: String, endereco: String, usuario: String = "", senha: String = "", guia: String = ""): Result<FonteTv> {
        val guiaLimpo = guia.trim()
        if (guiaLimpo.isNotEmpty() && !M3u.urlAceita(guiaLimpo)) return Result.failure(IllegalArgumentException("o link do guia precisa começar com http:// ou https://"))
        val nomeLimpo = nome.trim().take(40)
        return when (tipo) {
            FonteTv.TIPO_M3U -> {
                val u = endereco.trim()
                if (!M3u.urlAceita(u)) Result.failure(IllegalArgumentException("o link da lista precisa começar com http:// ou https://"))
                else Result.success(FonteTv(id, nomeLimpo.ifEmpty { "Lista" }, tipo, u, guia = guiaLimpo))
            }
            FonteTv.TIPO_XTREAM -> {
                val s = Xtream.normalizarServidor(endereco)
                when {
                    s == null -> Result.failure(IllegalArgumentException("servidor inválido"))
                    usuario.isBlank() || senha.isEmpty() -> Result.failure(IllegalArgumentException("falta usuário ou senha"))
                    else -> Result.success(FonteTv(id, nomeLimpo.ifEmpty { "Conta" }, tipo, s, usuario.trim(), senha, guiaLimpo))
                }
            }
            else -> Result.failure(IllegalArgumentException("tipo desconhecido: use m3u ou xtream"))
        }
    }

    fun fontesParaJson(fontes: List<FonteTv>): String = JSONArray().apply {
        fontes.forEach { f ->
            put(JSONObject().put("id", f.id).put("nome", f.nome).put("tipo", f.tipo).put("endereco", f.endereco)
                .put("usuario", f.usuario).put("senha", f.senha).put("guia", f.guia))
        }
    }.toString()

    fun fontesDeJson(texto: String?): List<FonteTv> {
        val a = runCatching { JSONArray(texto ?: return emptyList()) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id"); val tipo = o.optString("tipo"); val end = o.optString("endereco")
            if (id.isEmpty() || end.isEmpty() || (tipo != FonteTv.TIPO_M3U && tipo != FonteTv.TIPO_XTREAM)) null
            else FonteTv(id, o.optString("nome"), tipo, end, o.optString("usuario"), o.optString("senha"), o.optString("guia"))
        }
    }

    // ---- cache dos canais (arquivo por fonte). Numa conta Xtream o endereço do vídeo leva usuário e senha
    // (ver Xtream.urlCanal), então o cache guarda só o número do canal no servidor, e o endereço é remontado na leitura
    // com a conta, que fica cifrada. Assim a senha nunca está em texto puro no disco.

    fun canaisParaJson(canais: List<Canal>, fonte: FonteTv): String = JSONArray().apply {
        canais.forEach { c ->
            val url = if (fonte.tipo == FonteTv.TIPO_XTREAM) "" else c.url
            put(JSONObject().put("id", c.id).put("nome", c.nome).put("n", c.numero).put("g", c.grupo).put("logo", c.logo)
                .put("url", url).put("guia", c.guiaId).put("ua", c.agenteUsuario))
        }
    }.toString()

    fun canaisDeJson(texto: String?, fonte: FonteTv): List<Canal> {
        val a = runCatching { JSONArray(texto ?: return emptyList()) }.getOrNull() ?: return emptyList()
        val conta = fonte.xtream
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            // Xtream: id "x123" -> stream 123. Sem conta (fonte M3U), vale o endereço guardado.
            val url = if (conta != null) id.removePrefix("x").takeIf { id.startsWith("x") && it.isNotEmpty() }?.let { Xtream.urlCanal(conta, it) }
                else o.optString("url")
            if (url == null || !M3u.urlAceita(url)) null
            else Canal(id, o.optString("nome"), o.optInt("n"), o.optString("g"), o.optString("logo"), url,
                o.optString("guia"), o.optString("ua"))
        }
    }

    fun programasParaJson(programas: List<Programa>): String = JSONArray().apply {
        programas.forEach { p ->
            put(JSONObject().put("c", p.guiaId).put("i", p.inicio).put("f", p.fim).put("t", p.titulo).put("d", p.descricao).put("k", p.categoria))
        }
    }.toString()

    fun programasDeJson(texto: String?): List<Programa> {
        val a = runCatching { JSONArray(texto ?: return emptyList()) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val ini = o.optLong("i"); val fim = o.optLong("f")
            if (fim <= ini) null else Programa(o.optString("c"), ini, fim, o.optString("t"), o.optString("d"), o.optString("k"))
        }
    }

    /**
     * Junta os canais de várias fontes numa lista só, sem número repetido: a 1ª fonte fica com os seus números;
     * as seguintes, se baterem, vão para depois do maior número usado (o usuário vê "Canal 1001" em vez de dois "1").
     * O id ganha o prefixo da fonte, para o mesmo canal em duas contas não se misturar.
     */
    fun juntar(porFonte: List<Pair<FonteTv, List<Canal>>>): List<Canal> {
        val usados = HashSet<Int>()
        val saida = ArrayList<Canal>()
        for ((fonte, canais) in porFonte) {
            for (c in canais) {
                var n = c.numero
                if (n in usados) { n = (usados.maxOrNull() ?: 0) + 1 }
                usados += n
                saida += c.copy(id = "${fonte.id}:${c.id}", numero = n)
            }
        }
        return saida.sortedBy { it.numero }
    }

    /** O canal com aquele número, ou null. */
    fun porNumero(canais: List<Canal>, numero: Int): Canal? = canais.firstOrNull { it.numero == numero }

    /** Próximo/anterior na lista, dando a volta no fim. */
    fun vizinho(canais: List<Canal>, atual: Canal?, passo: Int): Canal? {
        if (canais.isEmpty()) return null
        val i = canais.indexOfFirst { it.id == atual?.id }.coerceAtLeast(0)
        return canais[Math.floorMod(i + passo, canais.size)]
    }
}
