package br.paulo.tvlauncher

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Player universal, séries do Xtream (06/11/2026): catálogo, temporadas, episódios e "continuar de onde parou".
 * Só lógica pura (sem Android), testada em PlayerSeriesTest. Como nos filmes, nenhum endereço com senha é guardado:
 * o do episódio é montado na hora de tocar (urlEpisodio).
 */
data class Serie(
    val id: String,          // series_id
    val fonteId: String,
    val nome: String,
    val grupo: String = "",
    val capa: String = "",
    val nota: Float? = null,
    val ano: Int? = null,
    val atualizada: Long = 0, // last_modified (s): série com episódio novo sobe em "recentes"
) {
    val chave: String get() = "$fonteId:s$id"
}

data class Episodio(
    val id: String,          // id do episódio no servidor (vai no endereço)
    val temporada: Int,
    val numero: Int,
    val titulo: String,
    val extensao: String = "mp4",
    val sinopse: String = "",
    val duracaoMin: Int? = null,
) {
    fun chave(fonteId: String) = "$fonteId:e$id"
    val rotulo: String get() = "T${temporada}E${numero}"
}

data class FichaSerie(
    val sinopse: String = "",
    val elenco: String = "",
    val genero: String = "",
    val temporadas: Map<Int, List<Episodio>> = emptyMap(),   // ordenadas por número
)

/**
 * Quanto da série já foi visto (07/11/2026, "Assistidos" das séries): episódios vistos / total, calculado quando a ficha
 * abre (só ela tem a lista de episódios). `atualizada` = o last_modified da série naquela hora: se o servidor publicar
 * episódio novo depois, a série deixa de contar como "Finalizado" até a ficha abrir de novo.
 */
data class ProgressoSerie(val vistos: Int, val total: Int, val atualizada: Long)

/** O último episódio que a pessoa abriu numa série (para o "continuar" sem baixar a ficha de todas). */
data class UltimoEpisodio(val episodioId: String, val temporada: Int, val numero: Int, val titulo: String, val extensao: String, val quando: Long)

object Series {
    private fun cod(v: String) = URLEncoder.encode(v, "UTF-8")

    fun urlCategorias(conta: Xtream.Conta) = Xtream.urlApi(conta, "get_series_categories")
    fun urlSeries(conta: Xtream.Conta) = Xtream.urlApi(conta, "get_series")
    fun urlFicha(conta: Xtream.Conta, id: String) = Xtream.urlApi(conta, "get_series_info", mapOf("series_id" to id))

    fun urlEpisodio(conta: Xtream.Conta, episodioId: String, extensao: String): String =
        "${conta.servidor}/series/${cod(conta.usuario)}/${cod(conta.senha)}/${cod(episodioId)}.${Vod.extensaoSegura(extensao)}"

    private fun numero(o: JSONObject, chave: String): Double? = when (val v = o.opt(chave)) {
        is Number -> v.toDouble()
        is String -> v.trim().replace(',', '.').toDoubleOrNull()
        else -> null
    }

    private fun texto(o: JSONObject, k: String) = o.optString(k, "").trim().takeIf { it != "null" }.orEmpty()

    fun lerSeries(json: String, fonteId: String, categorias: List<Xtream.Categoria> = emptyList()): List<Serie> {
        val a = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val grupos = categorias.associate { it.id to it.nome }
        val saida = ArrayList<Serie>()
        for (i in 0 until a.length()) {
            if (saida.size >= Vod.MAX_FILMES) break
            val o = a.optJSONObject(i) ?: continue
            val id = o.opt("series_id")?.toString()?.takeIf { it.isNotEmpty() && it != "null" } ?: continue
            val nome = texto(o, "name")
            if (nome.isEmpty()) continue
            val capa = texto(o, "cover")
            val ano = Vod.anoDoNome(nome) ?: texto(o, "releaseDate").ifEmpty { texto(o, "release_date") }.take(4).toIntOrNull()?.takeIf { it in 1900..2100 }
            saida += Serie(id, fonteId, nome, grupos[o.opt("category_id")?.toString()].orEmpty(),
                if (M3u.urlAceita(capa)) capa else "",
                numero(o, "rating")?.toFloat()?.takeIf { it > 0f && it <= 10f }, ano,
                numero(o, "last_modified")?.toLong() ?: 0)
        }
        return saida
    }

    /**
     * A ficha da série com os episódios. O Xtream manda "episodes" como objeto {"1":[...], "2":[...]} (às vezes como
     * lista de listas); os dois formatos são aceitos. Episódio sem id é descartado (não teria como tocar).
     */
    fun lerFicha(json: String): FichaSerie? {
        val raiz = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val info = raiz.optJSONObject("info") ?: JSONObject()
        val listas = ArrayList<Pair<Int?, JSONArray>>()
        when (val e = raiz.opt("episodes")) {
            is JSONObject -> e.keys().forEach { k -> e.optJSONArray(k)?.let { listas += k.toIntOrNull() to it } }
            is JSONArray -> for (i in 0 until e.length()) e.optJSONArray(i)?.let { listas += null to it }
        }
        if (listas.isEmpty() && !raiz.has("info")) return null
        val porTemporada = HashMap<Int, MutableList<Episodio>>()
        for ((tempChave, arr) in listas) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.opt("id")?.toString()?.takeIf { it.isNotEmpty() && it != "null" } ?: continue
                val inf = o.optJSONObject("info") ?: JSONObject()
                val temporada = numero(o, "season")?.toInt() ?: tempChave ?: 1
                val num = numero(o, "episode_num")?.toInt() ?: (i + 1)
                val seg = numero(inf, "duration_secs")
                porTemporada.getOrPut(temporada) { ArrayList() } += Episodio(
                    id, temporada, num, texto(o, "title").ifEmpty { "Episódio $num" }, Vod.extensaoSegura(texto(o, "container_extension")),
                    texto(inf, "plot").take(800),
                    (seg?.let { (it / 60).toInt() } ?: Vod.duracaoTexto(texto(inf, "duration")))?.takeIf { it in 1..600 },
                )
            }
        }
        return FichaSerie(
            sinopse = texto(info, "plot").take(1500), elenco = texto(info, "cast").take(300), genero = texto(info, "genre").take(120),
            temporadas = porTemporada.toSortedMap().mapValues { (_, l) -> l.sortedBy { it.numero } },
        )
    }

    /** Todos os episódios em ordem (T1E1, T1E2, ..., T2E1...). */
    fun emOrdem(f: FichaSerie): List<Episodio> = f.temporadas.values.flatten()

    /** O episódio depois deste (passa para a próxima temporada), ou null no último. */
    fun proximo(f: FichaSerie, atual: Episodio): Episodio? {
        val todos = emOrdem(f)
        val i = todos.indexOfFirst { it.id == atual.id }
        return if (i < 0) null else todos.getOrNull(i + 1)
    }

    /**
     * O que o botão principal da ficha oferece: o último episódio aberto, se não terminou; se terminou, o seguinte;
     * sem nada salvo, o primeiro. `retomar` = true quando é "Continuar" (tem posição no meio).
     */
    fun sugestao(f: FichaSerie, fonteId: String, ultimo: UltimoEpisodio?, posicoes: Map<String, Posicao>): Pair<Episodio, Boolean>? {
        val todos = emOrdem(f)
        if (todos.isEmpty()) return null
        val ep = ultimo?.let { u -> todos.firstOrNull { it.id == u.episodioId } } ?: return todos.first() to false
        val pos = posicoes[ep.chave(fonteId)]
        if (Vod.deveRetomar(pos)) return ep to true
        val terminou = pos != null && pos.duracao > 0 && pos.ms >= pos.duracao * 0.95
        return if (terminou) (proximo(f, ep) ?: ep) to false else ep to false
    }

    /**
     * Séries com episódio aberto, da mais recente para a mais velha (o "Continuar assistindo" das séries). Série
     * finalizada sai daqui (como o filme visto até o fim) e fica só em "Assistidos"; volta se sair episódio novo.
     */
    fun continuar(series: List<Serie>, ultimos: Map<String, UltimoEpisodio>, progresso: Map<String, ProgressoSerie> = emptyMap()): List<Serie> =
        series.filter { it.chave in ultimos && percentual(it, progresso[it.chave]) != 100 }.sortedByDescending { ultimos[it.chave]?.quando ?: 0 }

    // ---------------------------------------------------------------- assistidos (07/11/2026)

    /**
     * Conta os episódios vistos (1 min ou mais, ou até o fim: a régua dos filmes) com a ficha na mão. Se o total não
     * mudou, nunca desce: posição velha de episódio pode sair do posicoes.json (limite de 1000) e o visto continua visto.
     */
    fun progressoDaFicha(s: Serie, f: FichaSerie, posicoes: Map<String, Posicao>, anterior: ProgressoSerie?): ProgressoSerie? {
        val todos = emOrdem(f)
        if (todos.isEmpty()) return anterior
        val vistos = todos.count { Vod.assistido(posicoes[it.chave(s.fonteId)]) }
        val manter = if (anterior != null && anterior.total == todos.size) anterior.vistos else 0
        return ProgressoSerie(maxOf(vistos, manter).coerceAtMost(todos.size), todos.size, s.atualizada)
    }

    /**
     * O número da etiqueta, na mesma régua dos filmes: null = sem etiqueta; 1 a 99 = % dos episódios vistos;
     * 100 = Finalizado (todos vistos e nada novo desde a conta).
     */
    fun percentual(s: Serie, p: ProgressoSerie?): Int? {
        if (p == null || p.total <= 0 || p.vistos <= 0) return null
        if (p.vistos >= p.total && s.atualizada <= p.atualizada) return 100
        return (p.vistos * 100 / p.total).coerceIn(1, 99)
    }

    /** "Assistidos": toda série com episódio visto, terminada ou não, da mais recente para a mais velha. */
    fun assistidos(series: List<Serie>, ultimos: Map<String, UltimoEpisodio>, posicoes: Map<String, Posicao>,
                   progresso: Map<String, ProgressoSerie>): List<Serie> =
        series.filter { s ->
            (progresso[s.chave]?.vistos ?: 0) > 0 || ultimos[s.chave]?.let { Vod.assistido(posicoes["${s.fonteId}:e${it.episodioId}"]) } == true
        }.sortedByDescending { ultimos[it.chave]?.quando ?: 0 }

    fun progressoParaJson(p: Map<String, ProgressoSerie>): String = JSONObject().apply {
        p.forEach { (k, v) -> put(k, JSONArray().put(v.vistos).put(v.total).put(v.atualizada)) }
    }.toString()

    fun progressoDeJson(texto: String?): Map<String, ProgressoSerie> {
        val o = runCatching { JSONObject(texto ?: return emptyMap()) }.getOrNull() ?: return emptyMap()
        val saida = LinkedHashMap<String, ProgressoSerie>()
        for (k in o.keys()) {
            val a = o.optJSONArray(k) ?: continue
            if (k.length > 200 || a.length() < 3) continue
            saida[k] = ProgressoSerie(a.optInt(0), a.optInt(1), a.optLong(2))
        }
        return saida
    }

    fun ordenar(series: List<Serie>, ordem: Vod.Ordem): List<Serie> = when (ordem) {
        Vod.Ordem.RECENTES -> series.sortedByDescending { it.atualizada }
        Vod.Ordem.NOME -> series.sortedBy { PlayerFiltro.normalizar(Vod.nomeLimpo(it.nome)) }
        Vod.Ordem.NOTA -> series.sortedByDescending { it.nota ?: -1f }
        Vod.Ordem.ANO -> series.sortedByDescending { it.ano ?: 0 }
    }

    fun buscar(series: List<Serie>, termo: String, max: Int = 500): List<Serie> {
        val t = PlayerFiltro.normalizar(termo)
        if (t.isEmpty()) return emptyList()
        val palavras = t.split(' ')
        return series.filter { s -> val n = PlayerFiltro.normalizar(s.nome); palavras.all { n.contains(it) } }.take(max)
    }

    fun grupos(series: List<Serie>): List<Pair<String, Int>> {
        val m = LinkedHashMap<String, Int>()
        series.forEach { m[it.grupo.trim()] = (m[it.grupo.trim()] ?: 0) + 1 }
        return m.entries.map { it.key to it.value }
    }

    // ---------------------------------------------------------------- arquivos (sem senha)

    fun seriesParaJson(series: List<Serie>): String = JSONArray().apply {
        series.forEach { s ->
            put(JSONObject().put("i", s.id).put("n", s.nome).put("g", s.grupo).put("c", s.capa).put("r", s.nota?.toDouble() ?: 0.0)
                .put("a", s.ano ?: 0).put("m", s.atualizada))
        }
    }.toString()

    fun seriesDeJson(texto: String?, fonteId: String): List<Serie> {
        val a = runCatching { JSONArray(texto ?: return emptyList()) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("i").ifEmpty { return@mapNotNull null }
            Serie(id, fonteId, o.optString("n"), o.optString("g"), o.optString("c").takeIf { M3u.urlAceita(it) }.orEmpty(),
                o.optDouble("r", 0.0).toFloat().takeIf { it > 0f }, o.optInt("a").takeIf { it > 0 }, o.optLong("m"))
        }
    }

    fun ultimosParaJson(u: Map<String, UltimoEpisodio>): String = JSONObject().apply {
        u.forEach { (k, v) ->
            put(k, JSONObject().put("e", v.episodioId).put("t", v.temporada).put("n", v.numero).put("ti", v.titulo.take(120))
                .put("x", v.extensao).put("q", v.quando))
        }
    }.toString()

    fun ultimosDeJson(texto: String?): Map<String, UltimoEpisodio> {
        val o = runCatching { JSONObject(texto ?: return emptyMap()) }.getOrNull() ?: return emptyMap()
        val saida = LinkedHashMap<String, UltimoEpisodio>()
        for (k in o.keys()) {
            val v = o.optJSONObject(k) ?: continue
            val e = v.optString("e")
            if (e.isEmpty()) continue
            saida[k] = UltimoEpisodio(e, v.optInt("t"), v.optInt("n"), v.optString("ti"), Vod.extensaoSegura(v.optString("x")), v.optLong("q"))
        }
        return saida
    }

    /** Guarda o último episódio aberto da série; mantém só as 200 séries mais recentes. */
    fun registrarUltimo(u: Map<String, UltimoEpisodio>, serieChave: String, e: UltimoEpisodio): Map<String, UltimoEpisodio> {
        val novo = LinkedHashMap(u); novo[serieChave] = e
        return if (novo.size <= 200) novo else novo.entries.sortedByDescending { it.value.quando }.take(200).associate { it.key to it.value }
    }
}
