package br.paulo.tvlauncher

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Player universal, filmes do Xtream (06/11/2026): catálogo, ficha e "continuar de onde parou". Só lógica pura (sem
 * Android), testada em PlayerVodTest. Quem baixa é o PlayerCarga; quem desenha, a FilmesActivity; quem toca, a VodActivity.
 *
 * Como nos canais, o endereço do vídeo leva usuário e senha (é o protocolo): por isso o Filme NÃO guarda endereço. Ele
 * é montado na hora de tocar (urlFilme), com a conta que fica cifrada, e o cache em disco fica sem senha.
 */
data class Filme(
    val id: String,          // stream_id no servidor
    val fonteId: String,     // de qual conta veio (FonteTv.id)
    val nome: String,
    val grupo: String = "",
    val capa: String = "",   // stream_icon (o pôster)
    val nota: Float? = null, // 0 a 10
    val ano: Int? = null,
    val extensao: String = "mp4",
    val adicionado: Long = 0, // segundos desde 1970 (quando entrou no servidor)
) {
    /** Chave única entre contas, para posição salva e favoritos. */
    val chave: String get() = "$fonteId:f$id"
}

data class FichaFilme(
    val sinopse: String = "",
    val elenco: String = "",
    val diretor: String = "",
    val genero: String = "",
    val duracaoMin: Int? = null,
    val lancamento: String = "",
    val fundo: String = "",   // imagem larga (backdrop), se houver
)

/** Onde o usuário parou: posição e duração em ms, e quando assistiu (ms desde 1970). */
data class Posicao(val ms: Long, val duracao: Long, val quando: Long)

object Vod {
    const val MAX_FILMES = 30_000
    const val MAX_POSICOES = 1000             // filmes + episódios; ~60 KB de JSON (era 300; subiu para os "assistidos" durarem)
    const val MAX_INTERESSES = 500
    private const val MIN_RETOMAR = 60_000L      // menos de 1 min assistido: começa do zero
    private const val FIM = 0.95                 // passou de 95%: conta como visto, não oferece "continuar"

    private fun cod(v: String) = URLEncoder.encode(v, "UTF-8")

    fun urlCategoriasFilmes(conta: Xtream.Conta) = Xtream.urlApi(conta, "get_vod_categories")
    fun urlFilmes(conta: Xtream.Conta) = Xtream.urlApi(conta, "get_vod_streams")
    fun urlFichaFilme(conta: Xtream.Conta, id: String) = Xtream.urlApi(conta, "get_vod_info", mapOf("vod_id" to id))

    /** Extensão do arquivo: só letras/números curtos (o valor vem do servidor e entra no endereço). */
    fun extensaoSegura(e: String?): String = e?.trim()?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9]{2,5}")) } ?: "mp4"

    fun urlFilme(conta: Xtream.Conta, f: Filme): String =
        "${conta.servidor}/movie/${cod(conta.usuario)}/${cod(conta.senha)}/${cod(f.id)}.${extensaoSegura(f.extensao)}"

    private val reAno = Regex("""[(\[]((?:19|20)\d{2})[)\]]""")

    /** Ano entre parênteses no nome ("Filme (2023)"), comum nas listas. */
    fun anoDoNome(nome: String): Int? = reAno.find(nome)?.groupValues?.get(1)?.toIntOrNull()

    /** Nome sem o ano e sem a sujeira de prefixo de país ("BR: ", "|PT| ") para a tela. */
    fun nomeLimpo(nome: String): String =
        nome.replace(reAno, "").replace(Regex("""^\s*[\[(|]?[A-Z]{2,3}[\])|]?\s*[:|\-]\s*"""), "").replace(Regex("""\s{2,}"""), " ").trim()
            .ifEmpty { nome.trim() }

    private fun numero(o: JSONObject, chave: String): Double? = when (val v = o.opt(chave)) {
        is Number -> v.toDouble()
        is String -> v.trim().replace(',', '.').toDoubleOrNull()
        else -> null
    }

    fun lerFilmes(json: String, fonteId: String, categorias: List<Xtream.Categoria> = emptyList()): List<Filme> {
        val a = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val grupos = categorias.associate { it.id to it.nome }
        val saida = ArrayList<Filme>()
        for (i in 0 until a.length()) {
            if (saida.size >= MAX_FILMES) break
            val o = a.optJSONObject(i) ?: continue
            val id = o.opt("stream_id")?.toString()?.takeIf { it.isNotEmpty() && it != "null" } ?: continue
            val nome = o.optString("name", "").trim()
            if (nome.isEmpty()) continue
            val capa = o.optString("stream_icon", "").trim()
            saida += Filme(
                id = id, fonteId = fonteId, nome = nome,
                grupo = grupos[o.opt("category_id")?.toString()].orEmpty(),
                capa = if (M3u.urlAceita(capa)) capa else "",
                nota = numero(o, "rating")?.toFloat()?.takeIf { it > 0f && it <= 10f },
                ano = anoDoNome(nome) ?: numero(o, "year")?.toInt()?.takeIf { it in 1900..2100 },
                extensao = extensaoSegura(o.optString("container_extension")),
                adicionado = numero(o, "added")?.toLong() ?: 0,
            )
        }
        return saida
    }

    fun lerFicha(json: String): FichaFilme? {
        val raiz = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val info = raiz.optJSONObject("info") ?: return null
        fun txt(k: String) = info.optString(k, "").trim().takeIf { it != "null" }.orEmpty()
        val fundo = when (val b = info.opt("backdrop_path")) {
            is JSONArray -> (0 until b.length()).map { b.optString(it) }.firstOrNull { M3u.urlAceita(it) }.orEmpty()
            is String -> b.takeIf { M3u.urlAceita(it) }.orEmpty()
            else -> ""
        }
        val segundos = numero(info, "duration_secs")
        val minutos = segundos?.let { (it / 60).toInt() } ?: txt("duration").let { duracaoTexto(it) }
        return FichaFilme(
            sinopse = (txt("plot").ifEmpty { txt("description") }).take(1500),
            elenco = txt("cast").ifEmpty { txt("actors") }.take(300),
            diretor = txt("director").take(120),
            genero = txt("genre").take(120),
            duracaoMin = minutos?.takeIf { it in 1..1000 },
            lancamento = txt("releasedate").ifEmpty { txt("release_date") }.take(20),
            fundo = fundo,
        )
    }

    /** "01:52:30" ou "112 min" -> minutos. */
    fun duracaoTexto(t: String): Int? {
        val partes = t.trim().split(':')
        if (partes.size == 3) return partes[0].toIntOrNull()?.let { h -> (partes[1].toIntOrNull() ?: 0) + h * 60 }
        return Regex("""(\d+)""").find(t)?.groupValues?.get(1)?.toIntOrNull()
    }

    // ---------------------------------------------------------------- continuar de onde parou

    fun deveRetomar(p: Posicao?): Boolean = p != null && p.ms >= MIN_RETOMAR && (p.duracao <= 0 || p.ms < p.duracao * FIM)

    /** Onde começar: a posição salva, voltando 5 s (para o usuário se situar), ou 0. */
    fun inicio(p: Posicao?): Long = if (deveRetomar(p)) (p!!.ms - 5_000).coerceAtLeast(0) else 0

    /** Registra a posição; mantém só as MAX_POSICOES mais recentes. Visto até o fim = some do "continuar". */
    fun registrar(posicoes: Map<String, Posicao>, chave: String, p: Posicao): Map<String, Posicao> {
        val novo = LinkedHashMap(posicoes)
        novo[chave] = p
        if (novo.size <= MAX_POSICOES) return novo
        return novo.entries.sortedByDescending { it.value.quando }.take(MAX_POSICOES).associate { it.key to it.value }
    }

    /** "Continuar assistindo": os começados e não terminados, do mais recente para o mais velho. */
    fun continuar(filmes: List<Filme>, posicoes: Map<String, Posicao>): List<Filme> =
        filmes.filter { deveRetomar(posicoes[it.chave]) }.sortedByDescending { posicoes[it.chave]?.quando ?: 0 }

    // ---------------------------------------------------------------- assistidos e interesses (07/11/2026)

    /** Já assistiu o bastante para contar (1 min, a mesma régua do "continuar") ou viu até o fim. */
    fun assistido(p: Posicao?): Boolean = p != null && (p.ms >= MIN_RETOMAR || finalizado(p))

    /** Passou de 95% (ou a VodActivity gravou o fim): "Finalizado". Sem duração conhecida não dá para saber. */
    fun finalizado(p: Posicao?): Boolean = p != null && p.duracao > 0 && p.ms >= p.duracao * FIM

    /**
     * O número da etiqueta do pôster: null = sem etiqueta (nem começou); 0 = assistido, mas o servidor não deu a duração
     * (mostra só "Assistido"); 1 a 99 = quanto viu; 100 = Finalizado. Nunca mostra 100 sem ter terminado nem 0% falso.
     */
    fun progresso(p: Posicao?): Int? = when {
        !assistido(p) -> null
        finalizado(p) -> 100
        p!!.duracao <= 0 -> 0
        else -> (p.ms * 100 / p.duracao).toInt().coerceIn(1, 99)
    }

    /** "Assistidos": tudo o que foi visto (terminado ou não), do mais recente para o mais velho. */
    fun assistidos(filmes: List<Filme>, posicoes: Map<String, Posicao>): List<Filme> =
        filmes.filter { assistido(posicoes[it.chave]) }.sortedByDescending { posicoes[it.chave]?.quando ?: 0 }

    /** Põe ou tira dos interesses (mesma regra dos favoritos de canais). Cheio = devolve o mesmo conjunto. */
    fun alternarInteresse(interesses: Set<String>, chave: String): Set<String> = when {
        chave in interesses -> interesses - chave
        interesses.size >= MAX_INTERESSES -> interesses
        else -> LinkedHashSet(interesses).apply { add(chave) }
    }

    /**
     * "Interesses" (filmes ou séries): o último adicionado primeiro.
     * O que saiu do catálogo some da lista, mas a chave fica guardada (volta se o servidor devolver o título).
     */
    fun <T> interesses(itens: List<T>, interesses: Set<String>, chaveDe: (T) -> String): List<T> {
        val porChave = itens.associateBy(chaveDe)
        return interesses.reversed().mapNotNull { porChave[it] }
    }

    fun posicoesParaJson(p: Map<String, Posicao>): String = JSONObject().apply {
        p.forEach { (k, v) -> put(k, JSONArray().put(v.ms).put(v.duracao).put(v.quando)) }
    }.toString()

    fun posicoesDeJson(texto: String?): Map<String, Posicao> {
        val o = runCatching { JSONObject(texto ?: return emptyMap()) }.getOrNull() ?: return emptyMap()
        val saida = LinkedHashMap<String, Posicao>()
        for (k in o.keys()) {
            val a = o.optJSONArray(k) ?: continue
            if (k.length > 200 || a.length() < 3) continue
            saida[k] = Posicao(a.optLong(0), a.optLong(1), a.optLong(2))
        }
        return saida
    }

    // ---------------------------------------------------------------- catálogo (categorias, ordem, busca)

    enum class Ordem { RECENTES, NOME, NOTA, ANO }

    fun ordenar(filmes: List<Filme>, ordem: Ordem): List<Filme> = when (ordem) {
        Ordem.RECENTES -> filmes.sortedByDescending { it.adicionado }
        Ordem.NOME -> filmes.sortedBy { PlayerFiltro.normalizar(nomeLimpo(it.nome)) }
        Ordem.NOTA -> filmes.sortedByDescending { it.nota ?: -1f }
        Ordem.ANO -> filmes.sortedByDescending { it.ano ?: 0 }
    }

    /** Busca no nome (mesma regra dos canais: sem acento, todas as palavras). */
    fun buscar(filmes: List<Filme>, termo: String, max: Int = 500): List<Filme> {
        val t = PlayerFiltro.normalizar(termo)
        if (t.isEmpty()) return emptyList()
        val palavras = t.split(' ')
        return filmes.filter { f -> val n = PlayerFiltro.normalizar(f.nome); palavras.all { n.contains(it) } }.take(max)
    }

    /** Grupos na ordem do servidor, com a contagem. */
    fun grupos(filmes: List<Filme>): List<Pair<String, Int>> {
        val m = LinkedHashMap<String, Int>()
        filmes.forEach { m[it.grupo.trim()] = (m[it.grupo.trim()] ?: 0) + 1 }
        return m.entries.map { it.key to it.value }
    }

    // ---------------------------------------------------------------- cache (sem senha: o Filme não tem endereço)

    fun filmesParaJson(filmes: List<Filme>): String = JSONArray().apply {
        filmes.forEach { f ->
            put(JSONObject().put("i", f.id).put("n", f.nome).put("g", f.grupo).put("c", f.capa).put("r", f.nota?.toDouble() ?: 0.0)
                .put("a", f.ano ?: 0).put("e", f.extensao).put("d", f.adicionado))
        }
    }.toString()

    fun filmesDeJson(texto: String?, fonteId: String): List<Filme> {
        val a = runCatching { JSONArray(texto ?: return emptyList()) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("i").ifEmpty { return@mapNotNull null }
            Filme(id, fonteId, o.optString("n"), o.optString("g"), o.optString("c").takeIf { M3u.urlAceita(it) }.orEmpty(),
                o.optDouble("r", 0.0).toFloat().takeIf { it > 0f }, o.optInt("a").takeIf { it > 0 },
                extensaoSegura(o.optString("e")), o.optLong("d"))
        }
    }
}
