package br.paulo.tvlauncher

import android.content.Context
import android.util.Log
import android.util.Xml
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Player universal, passo 2 (06/11/2026): baixa a lista de canais e o guia de cada conta, com cache em arquivo.
 *
 * Tudo aqui faz rede ou disco: chamar FORA da thread da tela. Regras:
 * - Lista: vale 12 h no cache. Sem rede, usa o cache velho (melhor um canal que talvez mudou do que tela vazia).
 * - Guia: vale 6 h, só os canais da lista e só de 2 h atrás até 26 h à frente (o arquivo inteiro não cabe na memória).
 * - Tetos de tamanho: 30 MB para a lista, 150 MB para o guia (lido em fluxo, nunca inteiro na memória).
 * - O endereço nunca vai para o log (no Xtream ele leva a senha): só o nome do servidor.
 */
object PlayerCarga {
    private const val TAG = "TvLauncher.Player"
    private const val VALIDADE_LISTA = 12 * 3_600_000L
    private const val VALIDADE_GUIA = 6 * 3_600_000L
    private const val MAX_LISTA = 30L * 1024 * 1024
    private const val MAX_GUIA = 150L * 1024 * 1024
    private const val AGENTE = "TvLauncher-Player/1.0"

    private fun arquivoCanais(ctx: Context, id: String) = File(PlayerArmazem.pasta(ctx), "$id.canais.json")
    private fun arquivoGuia(ctx: Context) = File(PlayerArmazem.pasta(ctx), "guia.json")

    fun apagarCache(ctx: Context, id: String) {
        arquivoCanais(ctx, id).delete()
        File(PlayerArmazem.pasta(ctx), "$id.filmes.json").delete()
        File(PlayerArmazem.pasta(ctx), "$id.series.json").delete()
        arquivoGuia(ctx).delete()   // o guia é de todas as contas juntas: refaz na próxima abertura
    }

    /** Canais de todas as contas, juntos e sem número repetido. */
    fun canais(ctx: Context, forcar: Boolean = false): List<Canal> =
        PlayerDados.juntar(PlayerArmazem.fontes(ctx).map { it to canaisDaFonte(ctx, it, forcar) })

    fun canaisDaFonte(ctx: Context, fonte: FonteTv, forcar: Boolean = false): List<Canal> {
        val cache = arquivoCanais(ctx, fonte.id)
        val fresco = cache.exists() && System.currentTimeMillis() - cache.lastModified() < VALIDADE_LISTA
        if (fresco && !forcar) PlayerDados.canaisDeJson(cache.readText(), fonte).takeIf { it.isNotEmpty() }?.let { return it }
        val baixados = runCatching { baixarCanais(fonte) }
            .onFailure { Log.w(TAG, "lista de ${fonte.servidorVisivel}: ${it.javaClass.simpleName} ${it.message?.take(80)}") }
            .getOrNull()
        if (!baixados.isNullOrEmpty()) {
            runCatching { cache.writeText(PlayerDados.canaisParaJson(baixados, fonte)) }
            return baixados
        }
        return if (cache.exists()) PlayerDados.canaisDeJson(cache.readText(), fonte) else emptyList()
    }

    private fun baixarCanais(fonte: FonteTv): List<Canal> {
        val conta = fonte.xtream
        if (conta == null) return M3u.ler(baixarTexto(fonte.endereco, MAX_LISTA))
        val situacao = Xtream.lerSituacao(baixarTexto(Xtream.urlApi(conta), 1024 * 1024))
            ?: throw IOException("o servidor não respondeu como Xtream")
        if (!situacao.ativa) throw IOException("conta ${situacao.status.ifEmpty { "recusada" }}")
        val categorias = runCatching { Xtream.lerCategorias(baixarTexto(Xtream.urlCategoriasAoVivo(conta), 5L * 1024 * 1024)) }.getOrDefault(emptyList())
        return Xtream.lerCanais(baixarTexto(Xtream.urlCanaisAoVivo(conta), MAX_LISTA), conta, categorias)
    }

    // ---------------------------------------------------------------- filmes (só contas Xtream)

    private fun arquivoFilmes(ctx: Context, id: String) = File(PlayerArmazem.pasta(ctx), "$id.filmes.json")

    /** Há alguma conta Xtream? (só essas têm filmes e séries pelo protocolo.) */
    fun temVod(ctx: Context): Boolean = PlayerArmazem.fontes(ctx).any { it.xtream != null }

    /** Filmes de todas as contas Xtream. Cache de 12 h por conta; sem rede, o cache velho. */
    fun filmes(ctx: Context, forcar: Boolean = false): List<Filme> = PlayerArmazem.fontes(ctx).flatMap { fonte ->
        val conta = fonte.xtream ?: return@flatMap emptyList()
        val cache = arquivoFilmes(ctx, fonte.id)
        val fresco = cache.exists() && System.currentTimeMillis() - cache.lastModified() < VALIDADE_LISTA
        if (fresco && !forcar) Vod.filmesDeJson(cache.readText(), fonte.id).takeIf { it.isNotEmpty() }?.let { return@flatMap it }
        val baixados = runCatching {
            val cats = runCatching { Xtream.lerCategorias(baixarTexto(Vod.urlCategoriasFilmes(conta), 5L * 1024 * 1024)) }.getOrDefault(emptyList())
            Vod.lerFilmes(baixarTexto(Vod.urlFilmes(conta), MAX_LISTA), fonte.id, cats)
        }.onFailure { Log.w(TAG, "filmes de ${fonte.servidorVisivel}: ${it.javaClass.simpleName}") }.getOrNull()
        if (!baixados.isNullOrEmpty()) {
            runCatching { cache.writeText(Vod.filmesParaJson(baixados)) }
            baixados
        } else if (cache.exists()) Vod.filmesDeJson(cache.readText(), fonte.id) else emptyList()
    }

    /** A ficha (sinopse, elenco...) de um filme. Pequena: vem sempre da rede, sem cache em disco. */
    fun fichaFilme(ctx: Context, f: Filme): FichaFilme? {
        val conta = PlayerArmazem.fontes(ctx).firstOrNull { it.id == f.fonteId }?.xtream ?: return null
        return runCatching { Vod.lerFicha(baixarTexto(Vod.urlFichaFilme(conta, f.id), 2L * 1024 * 1024)) }
            .onFailure { Log.w(TAG, "ficha: ${it.javaClass.simpleName}") }.getOrNull()
    }

    // ---------------------------------------------------------------- séries (só contas Xtream)

    /** Séries de todas as contas Xtream. Cache de 12 h por conta; sem rede, o cache velho. */
    fun series(ctx: Context, forcar: Boolean = false): List<Serie> = PlayerArmazem.fontes(ctx).flatMap { fonte ->
        val conta = fonte.xtream ?: return@flatMap emptyList()
        val cache = File(PlayerArmazem.pasta(ctx), "${fonte.id}.series.json")
        val fresco = cache.exists() && System.currentTimeMillis() - cache.lastModified() < VALIDADE_LISTA
        if (fresco && !forcar) Series.seriesDeJson(cache.readText(), fonte.id).takeIf { it.isNotEmpty() }?.let { return@flatMap it }
        val baixadas = runCatching {
            val cats = runCatching { Xtream.lerCategorias(baixarTexto(Series.urlCategorias(conta), 5L * 1024 * 1024)) }.getOrDefault(emptyList())
            Series.lerSeries(baixarTexto(Series.urlSeries(conta), MAX_LISTA), fonte.id, cats)
        }.onFailure { Log.w(TAG, "séries de ${fonte.servidorVisivel}: ${it.javaClass.simpleName}") }.getOrNull()
        if (!baixadas.isNullOrEmpty()) {
            runCatching { cache.writeText(Series.seriesParaJson(baixadas)) }
            baixadas
        } else if (cache.exists()) Series.seriesDeJson(cache.readText(), fonte.id) else emptyList()
    }

    /** Ficha e episódios de uma série (sem cache em disco: episódio novo aparece na hora). */
    fun fichaSerie(ctx: Context, s: Serie): FichaSerie? {
        val conta = PlayerArmazem.fontes(ctx).firstOrNull { it.id == s.fonteId }?.xtream ?: return null
        return runCatching { Series.lerFicha(baixarTexto(Series.urlFicha(conta, s.id), 10L * 1024 * 1024)) }
            .onFailure { Log.w(TAG, "ficha da série: ${it.javaClass.simpleName}") }.getOrNull()
    }

    /** O endereço para tocar (com a senha: não guardar, não logar). */
    fun urlFilme(ctx: Context, f: Filme): String? =
        PlayerArmazem.fontes(ctx).firstOrNull { it.id == f.fonteId }?.xtream?.let { Vod.urlFilme(it, f) }

    /** Link do guia de uma conta: o cadastrado, senão o do Xtream, senão o que a lista anuncia. */
    private fun linkGuia(fonte: FonteTv): String? {
        if (fonte.guia.isNotEmpty()) return fonte.guia
        fonte.xtream?.let { return Xtream.urlGuiaXmltv(it) }
        return runCatching { M3u.guiaDaLista(baixarTexto(fonte.endereco, MAX_LISTA)) }.getOrNull()
    }

    /** O guia dos canais da lista. Sem guia nenhum: Guia vazio (o player mostra só o nome do canal). */
    fun guia(ctx: Context, canais: List<Canal>, forcar: Boolean = false): Guia {
        val cache = arquivoGuia(ctx)
        val agora = System.currentTimeMillis()
        if (!forcar && cache.exists() && agora - cache.lastModified() < VALIDADE_GUIA) {
            return Guia(PlayerDados.programasDeJson(cache.readText()))
        }
        val ids = canais.mapNotNull { it.guiaId.ifEmpty { null } }.toSet()
        if (ids.isEmpty()) return Guia(emptyList())
        val todos = ArrayList<Programa>()
        for (fonte in PlayerArmazem.fontes(ctx)) {
            val link = linkGuia(fonte) ?: continue
            runCatching {
                abrir(link, MAX_GUIA).use { entrada ->
                    todos += Xmltv.ler(Xml.newPullParser(), Xmltv.abrir(entrada), ids, agora - 2 * 3_600_000L, agora + 26 * 3_600_000L)
                }
            }.onFailure { Log.w(TAG, "guia de ${fonte.servidorVisivel}: ${it.javaClass.simpleName}") }
        }
        if (todos.isNotEmpty()) runCatching { cache.writeText(PlayerDados.programasParaJson(todos)) }
        else if (cache.exists()) return Guia(PlayerDados.programasDeJson(cache.readText()))
        return Guia(todos)
    }

    // ---------------------------------------------------------------- rede

    private fun baixarTexto(url: String, max: Long): String = abrir(url, max).use { it.readBytes().toString(Charsets.UTF_8) }

    /**
     * Abre o endereço seguindo até 4 redirecionamentos (o HttpURLConnection não segue de http para https sozinho).
     * A verificação de TLS é a padrão do Android: nunca desligada.
     */
    private fun abrir(endereco: String, max: Long): InputStream {
        var url = endereco
        repeat(5) {
            if (!M3u.urlAceita(url)) throw IOException("endereço recusado")
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", AGENTE)
            c.setRequestProperty("Accept-Encoding", "gzip")
            val codigo = c.responseCode
            if (codigo in 300..399) {
                val destino = c.getHeaderField("Location")
                c.disconnect()
                url = URL(URL(url), destino ?: throw IOException("redirecionamento sem destino")).toString()
                return@repeat
            }
            if (codigo != 200) { c.disconnect(); throw IOException("HTTP $codigo") }
            if (c.contentLengthLong > max) { c.disconnect(); throw IOException("arquivo grande demais") }
            val bruto = c.inputStream
            val corpo = if (c.contentEncoding.equals("gzip", ignoreCase = true)) java.util.zip.GZIPInputStream(bruto) else bruto
            return Teto(corpo, max)
        }
        throw IOException("redirecionamentos demais")
    }

    /** Fluxo que dá erro se passar de `max` bytes: servidor que manda sem fim não enche a memória nem o disco. */
    private class Teto(entrada: InputStream, private val max: Long) : FilterInputStream(entrada) {
        private var lidos = 0L
        override fun read(): Int = super.read().also { if (it >= 0) somar(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) somar(it.toLong()) }
        private fun somar(n: Long) { lidos += n; if (lidos > max) throw IOException("arquivo grande demais") }
    }
}
