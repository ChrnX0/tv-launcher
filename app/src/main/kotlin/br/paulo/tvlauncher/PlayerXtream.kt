package br.paulo.tvlauncher

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Player universal, passo 1 (06/11/2026): conta "Xtream Codes", o formato que o 1-Stream e quase todo player de
 * IPTV usam (servidor + usuário + senha, em vez de um link de lista).
 *
 * Aqui só ficam a montagem dos endereços e a leitura das respostas (JSON), sem rede nem Android, para testar no
 * computador. Quem baixa é o passo 2. A senha vai no endereço porque o protocolo é assim (não há outra forma):
 * por isso esses endereços NUNCA vão para log nem para o painel.
 */
object Xtream {
    data class Conta(val servidor: String, val usuario: String, val senha: String)

    /** O que o servidor diz da conta: situação, validade e telas simultâneas. */
    data class Situacao(
        val ativa: Boolean,
        val status: String,          // "Active", "Expired", "Banned"... (o texto que o servidor mandar)
        val expiraEm: Long?,         // segundos desde 1970; null = sem validade (ou não informado)
        val telasMax: Int?,
        val telasEmUso: Int?,
    )

    data class Categoria(val id: String, val nome: String)

    /**
     * Arruma o servidor digitado pelo usuário: põe http:// se faltar, tira "/" e "player_api.php" do fim.
     * Devolve null se não der um endereço http/https usável.
     */
    fun normalizarServidor(digitado: String): String? {
        var s = digitado.trim()
        if (s.isEmpty() || ' ' in s) return null
        if (!s.contains("://")) s = "http://$s"
        val minusculo = s.lowercase()
        if (!minusculo.startsWith("http://") && !minusculo.startsWith("https://")) return null
        s = s.substringBefore('?').removeSuffix("/").removeSuffix("/player_api.php").removeSuffix("/get.php").removeSuffix("/")
        // "http://" sozinho vira "http:" depois de tirar as barras: sem "://" não tem servidor nenhum
        val host = s.substringAfter("://", "").substringBefore('/').substringBefore(':')
        return if (host.isEmpty()) null else s
    }

    private fun cod(v: String) = URLEncoder.encode(v, "UTF-8")   // senha com &, # ou espaço não quebra o endereço

    /** Endereço da API. Sem `acao` = login (devolve user_info). */
    fun urlApi(conta: Conta, acao: String? = null, extra: Map<String, String> = emptyMap()): String {
        val b = StringBuilder("${conta.servidor}/player_api.php?username=${cod(conta.usuario)}&password=${cod(conta.senha)}")
        if (acao != null) b.append("&action=").append(cod(acao))
        extra.forEach { (k, v) -> b.append('&').append(cod(k)).append('=').append(cod(v)) }
        return b.toString()
    }

    fun urlCategoriasAoVivo(conta: Conta) = urlApi(conta, "get_live_categories")
    fun urlCanaisAoVivo(conta: Conta) = urlApi(conta, "get_live_streams")
    fun urlGuiaCurto(conta: Conta, streamId: String, quantos: Int = 4) =
        urlApi(conta, "get_short_epg", mapOf("stream_id" to streamId, "limit" to quantos.toString()))
    /** Guia completo de todos os canais, no formato XMLTV (lido pelo Xmltv.kt). */
    fun urlGuiaXmltv(conta: Conta) = "${conta.servidor}/xmltv.php?username=${cod(conta.usuario)}&password=${cod(conta.senha)}"

    /**
     * Endereço do vídeo de um canal. "ts" é o padrão do protocolo; "m3u8" (HLS) quando o servidor permitir, porque
     * o Media3 se recupera melhor de queda de rede com HLS.
     */
    fun urlCanal(conta: Conta, streamId: String, formato: String = "ts"): String =
        "${conta.servidor}/live/${cod(conta.usuario)}/${cod(conta.senha)}/${cod(streamId)}.${if (formato == "m3u8") "m3u8" else "ts"}"

    /** Lê a resposta do login. null = não é uma resposta Xtream (servidor errado, página de erro em HTML...). */
    fun lerSituacao(json: String): Situacao? {
        val raiz = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val u = raiz.optJSONObject("user_info") ?: return null
        val status = u.optString("status", "")
        // Alguns servidores mandam número como texto ("1735689600"), outros como número, outros null.
        fun numero(chave: String): Long? = when (val v = u.opt(chave)) {
            is Number -> v.toLong()
            is String -> v.trim().toLongOrNull()
            else -> null
        }
        // auth 1 = usuário e senha certos; mesmo assim a conta pode estar vencida ("Expired"), por isso olha os dois.
        val autenticou = u.optString("auth", "0").trim() == "1"
        return Situacao(
            ativa = autenticou && (status.isEmpty() || status.equals("Active", ignoreCase = true)),
            status = status,
            expiraEm = numero("exp_date")?.takeIf { it > 0 },
            telasMax = numero("max_connections")?.toInt(),
            telasEmUso = numero("active_cons")?.toInt(),
        )
    }

    fun lerCategorias(json: String): List<Categoria> {
        val a = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.opt("category_id")?.toString()?.takeIf { it.isNotEmpty() && it != "null" } ?: return@mapNotNull null
            Categoria(id, o.optString("category_name", "").trim())
        }
    }

    /**
     * Lê a lista de canais ao vivo e monta os Canal com o endereço de vídeo já pronto.
     * `categorias` traduz category_id -> nome do grupo (vem de lerCategorias).
     */
    fun lerCanais(json: String, conta: Conta, categorias: List<Categoria> = emptyList(), formato: String = "ts"): List<Canal> {
        val a = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val grupos = categorias.associate { it.id to it.nome }
        val usados = HashSet<Int>()
        val canais = ArrayList<Canal>()
        for (i in 0 until a.length()) {
            if (canais.size >= M3u.MAX_CANAIS) break
            val o = a.optJSONObject(i) ?: continue
            val sid = o.opt("stream_id")?.toString()?.takeIf { it.isNotEmpty() && it != "null" } ?: continue
            // "num" é o número que o servidor sugere; repetido ou ausente -> próximo livre
            var numero = (o.opt("num")?.toString()?.toIntOrNull())?.takeIf { it in 1..99_999 && it !in usados }
                ?: (canais.size + 1)
            while (numero in usados) numero++
            usados += numero
            val logo = o.optString("stream_icon", "").trim()
            canais += Canal(
                id = "x$sid",
                nome = o.optString("name", "").trim().ifEmpty { "Canal $numero" },
                numero = numero,
                grupo = grupos[o.opt("category_id")?.toString()].orEmpty(),
                logo = if (M3u.urlAceita(logo)) logo else "",
                url = urlCanal(conta, sid, formato),
                guiaId = o.optString("epg_channel_id", "").let { if (it == "null") "" else it.trim() },
            )
        }
        return canais
    }
}
