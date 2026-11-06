package br.paulo.tvlauncher

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Player universal, passo 2 (06/11/2026): a TV ao vivo em tela cheia (esboço F da versão 2: reprodução com mini-guia).
 *
 * Roda no processo ":player" (manifesto), separado da HOME: se o vídeo travar ou gastar memória demais, o Android
 * derruba só o player, e a HOME continua de pé. O motor é o Media3 (ExoPlayer), o mesmo do YouTube e de muitos
 * players de IPTV. A tela é toda em código (Views), como o resto do launcher.
 *
 * Controle:
 *   ▲ ▼ (ou CH+ CH-)  troca de canal (dentro da categoria escolhida na lista, se o canal for dela)
 *   OK                 abre a lista completa (PlayerListaTela: categorias, canais com logo e guia, detalhe)
 *   ◀                  volta ao canal anterior  0 a 9    digita o número do canal (do celular ou de controle com números)
 *   VOLTAR             fecha o que estiver aberto; sem nada aberto, sai
 * Na lista: ▲ ▼ escolhem, ◀ ▶ trocam de coluna, OK assiste, OK segurado favorita.
 * (06/11/2026: a lista completa substituiu o mini-guia de 7 linhas.)
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlayerActivity : Activity() {
    companion object {
        const val EXTRA_CANAL = "canal"   // id do canal para abrir (vazio = o último assistido)
        private const val ESCONDER_INFO_MS = 5_000L
        private const val ESPERA_NUMERO_MS = 1_500L
    }

    private val tela = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var canais: List<Canal> = emptyList()
    private var guia = Guia(emptyList())
    private var atual: Canal? = null
    private var anterior: Canal? = null
    private var tentativas = 0
    private var digitado = ""
    private var favoritos: Set<String> = emptySet()
    private var recentes: List<String> = emptyList()
    private var categoria = PlayerLista.TODOS   // a escolhida na lista: o ▲▼ zapeia dentro dela
    private var escondidos: Set<String> = emptySet()   // grupos que o usuário escondeu
    private var liberados: Set<String> = emptySet()    // grupos adultos que ele liberou (começam ocultos)
    private var filtro = PlayerFiltro.Filtro()
    private var visiveis: List<Canal> = emptyList()    // canais depois do filtro: é o que a lista, o ▲▼ e o número usam
    private var ultimaBusca = ""
    private lateinit var janelas: PlayerJanelas
    private var visivel = false   // entre onStart e onStop: fora disso nada toca (nem a nova tentativa atrasada)

    private val papel = 0xFFF5F3EE.toInt()
    private val suave = 0xB3F5F3EE.toInt()
    private val ambar = 0xFFF5B544.toInt()
    private val coral = 0xFFFF5A4E.toInt()
    private val fonteMedia = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private lateinit var video: SurfaceView
    private lateinit var info: LinearLayout
    private lateinit var infoNumero: TextView
    private lateinit var infoTitulo: TextView
    private lateinit var infoLinha: TextView
    private lateinit var infoHora: TextView
    private lateinit var infoProgresso: ProgressBar
    private lateinit var lista: PlayerListaTela
    private lateinit var grade: PlayerGradeView
    private lateinit var numero: TextView
    private lateinit var aviso: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // a TV não dorme no meio do jogo
        val raiz = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = SurfaceView(this)
        raiz.addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        raiz.addView(montarInfo(), FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(32), 0, dp(32), dp(26)) })
        janelas = PlayerJanelas(this)
        lista = PlayerListaTela(this, aoAssistir = { c, chave -> escolherNaLista(c, chave) }, aoFavoritar = { alternarFavorito(it) },
            aoBuscar = { buscar() }, aoFiltros = { abrirFiltros() }, aoOcultarGrupo = { alternarOculto(it) },
            aoGuia = { cs, nome -> abrirGrade(cs, nome) },
            aoFilmes = { startActivity(android.content.Intent(this, FilmesActivity::class.java)) },
            aoSeries = { startActivity(android.content.Intent(this, SeriesActivity::class.java)) })
        raiz.addView(lista.raiz, FrameLayout.LayoutParams(-1, -1))
        grade = PlayerGradeView(this, aoAssistir = { c -> grade.fechar(); lista.fechar(); if (c.id != atual?.id) sintonizar(c) else mostrarInfo() },
            aoFechar = { grade.fechar(); if (lista.aberta) lista.voltarFoco() })
        raiz.addView(grade, FrameLayout.LayoutParams(-1, -1))
        numero = TextView(this).apply {
            textSize = 56f; typeface = Typeface.DEFAULT_BOLD; setTextColor(papel); background = vidro(16)
            setPadding(dp(22), dp(6), dp(22), dp(10)); visibility = View.GONE
        }
        raiz.addView(numero, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(32), dp(28), 0, 0) })
        aviso = TextView(this).apply {
            textSize = 18f; setTextColor(papel); gravity = Gravity.CENTER; background = vidro(16)
            setPadding(dp(24), dp(16), dp(24), dp(16)); text = getString(R.string.player_carregando)
        }
        raiz.addView(aviso, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setContentView(raiz)
        carregar()
    }

    override fun onStart() {
        super.onStart()
        visivel = true
        atual?.let { sintonizar(it, guardarAnterior = false) }   // voltou para o player: retoma o canal
    }

    override fun onStop() {
        super.onStop()
        visivel = false
        // Saiu da tela (HOME, outro app): solta o vídeo na hora. Canal ao vivo não tem "pausa" que valha a memória.
        player?.release(); player = null
    }

    override fun onDestroy() {
        tela.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- carga (fora da thread da tela)

    private fun carregar() {
        Thread {
            val lidos = PlayerCarga.canais(this)   // "lidos", não "lista": lista é a tela de canais
            val fav = PlayerArmazem.favoritos(this)
            val rec = PlayerArmazem.recentes(this)
            val cat = PlayerArmazem.categoria(this) ?: PlayerLista.TODOS
            val esc = PlayerArmazem.gruposEscondidos(this)
            val lib = PlayerArmazem.gruposLiberados(this)
            val vod = PlayerCarga.temVod(this)
            tela.post {
                if (isDestroyed) return@post
                canais = lidos
                favoritos = fav; recentes = rec; categoria = cat; escondidos = esc; liberados = lib; lista.temVod = vod
                refiltrar()
                if (visiveis.isEmpty()) { mostrarAviso(getString(R.string.player_sem_canais)); return@post }
                // Abre no último canal só se ele ainda passa no filtro (um canal de categoria oculta não abre sem PIN).
                val pedido = intent.getStringExtra(EXTRA_CANAL) ?: PlayerArmazem.ultimoCanal(this)
                sintonizar(visiveis.firstOrNull { it.id == pedido } ?: visiveis.first(), guardarAnterior = false)
            }
            // O guia vem depois: pode levar alguns segundos (arquivo grande) e o canal já está tocando.
            val g = PlayerCarga.guia(this, lidos)
            tela.post { if (!isDestroyed) { guia = g; atualizarInfo(); lista.atualizarGuia(g); grade.atualizarGuia(g) } }
        }.start()
    }

    // ---------------------------------------------------------------- vídeo

    private fun sintonizar(c: Canal, guardarAnterior: Boolean = true) {
        if (guardarAnterior && atual != null && atual?.id != c.id) anterior = atual
        atual = c
        tentativas = 0
        gravarUltimo(c)
        tocar(c)
        mostrarInfo()
    }

    private fun tocar(c: Canal) {
        if (!visivel) return
        lembrarRecente(c)
        val p = player ?: ExoPlayer.Builder(this).build().also { novo ->
            novo.setVideoSurfaceView(video)
            novo.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            novo.addListener(ouvinte)
            player = novo
        }
        // Algumas listas exigem um User-Agent próprio por canal: por isso a fábrica é montada a cada troca.
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(c.agenteUsuario.ifEmpty { "TvLauncher-Player/1.0" })
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
        val item = MediaItem.Builder().setUri(c.url).apply {
            // .m3u8 = HLS. Sem extensão conhecida, o Media3 descobre sozinho pelo conteúdo (ex.: .ts do Xtream).
            if (c.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        p.setMediaSource(DefaultMediaSourceFactory(http).createMediaSource(item))
        p.prepare()
        p.playWhenReady = true
    }

    private val ouvinte = object : Player.Listener {
        override fun onPlaybackStateChanged(estado: Int) {
            when (estado) {
                Player.STATE_READY -> { tentativas = 0; esconderAviso() }
                Player.STATE_BUFFERING -> if (aviso.visibility != View.VISIBLE) tela.postDelayed(avisoCarregando, 1_500)
                else -> {}
            }
        }

        override fun onPlayerError(erro: PlaybackException) {
            val p = player ?: return
            // Ficou para trás na janela ao vivo (rede engasgou): pula para o "agora" sem incomodar ninguém.
            if (erro.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) { p.seekToDefaultPosition(); p.prepare(); return }
            val c = atual ?: return
            if (tentativas < 3) {
                tentativas++
                mostrarAviso(getString(R.string.player_tentando, c.numero))
                tela.postDelayed({ if (atual?.id == c.id) tocar(c) }, 2_000L shl (tentativas - 1))   // 2, 4, 8 s
            } else {
                mostrarAviso(getString(R.string.player_sem_sinal, c.numero))
            }
        }
    }

    private val avisoCarregando = Runnable {
        if (player?.playbackState == Player.STATE_BUFFERING) mostrarAviso(getString(R.string.player_carregando_canal))
    }

    // ---------------------------------------------------------------- controle

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (grade.aberta) return super.onKeyDown(keyCode, event)   // a grade trata as dela; o que sobra (volume) segue
        if (keyCode == KeyEvent.KEYCODE_GUIDE || keyCode == KeyEvent.KEYCODE_TV_DATA_SERVICE) {
            // Tecla GUIA do controle (quando há): a grade da categoria escolhida, direto do vídeo.
            val onde = PlayerLista.filtrar(visiveis, categoria, favoritos, recentes).ifEmpty { visiveis }
            abrirGrade(onde, getString(R.string.player_acao_guia)); return true
        }
        if (lista.aberta) {
            if (lista.tecla(keyCode)) return true
            if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) lista.fechar()   // número digitado: some a lista e troca
            else return super.onKeyDown(keyCode, event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { zapear(+1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { zapear(-1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                // repeatCount > 0 = OK segurado: não reabre a lista que acabou de fechar ao escolher um canal.
                if (digitado.isNotEmpty()) confirmarNumero() else if (event.repeatCount == 0) abrirLista()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_LAST_CHANNEL -> {
                anterior?.let { sintonizar(it) } ?: mostrarInfo()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> { mostrarInfo(); return true }
            KeyEvent.KEYCODE_BACK -> {
                when {
                    digitado.isNotEmpty() -> { digitado = ""; numero.visibility = View.GONE }
                    info.visibility == View.VISIBLE -> info.visibility = View.GONE
                    else -> finish()
                }
                return true
            }
        }
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) { digitar((keyCode - KeyEvent.KEYCODE_0).toString()); return true }
        return super.onKeyDown(keyCode, event)
    }

    /** ▲ sobe o número do canal (como num controle de TV comum), dentro da categoria escolhida na lista. */
    private fun zapear(passo: Int) {
        val onde = PlayerLista.listaDeZapping(visiveis, categoria, favoritos, recentes, atual)
        PlayerDados.vizinho(onde, atual, passo)?.let { sintonizar(it) }
    }

    private fun digitar(d: String) {
        if (digitado.length >= 5) return
        digitado += d
        numero.text = "${digitado}_"
        numero.visibility = View.VISIBLE
        tela.removeCallbacks(confirmarNumeroDepois)
        tela.postDelayed(confirmarNumeroDepois, ESPERA_NUMERO_MS)
    }

    private val confirmarNumeroDepois = Runnable { confirmarNumero() }

    private fun confirmarNumero() {
        tela.removeCallbacks(confirmarNumeroDepois)
        val n = digitado.toIntOrNull()
        digitado = ""
        numero.visibility = View.GONE
        val c = n?.let { PlayerDados.porNumero(visiveis, it) }
        if (c != null) sintonizar(c) else if (n != null) mostrarAvisoBreve(getString(R.string.player_canal_nao_existe, n))
    }

    // ---------------------------------------------------------------- faixa de informação (embaixo)

    private fun montarInfo(): View {
        info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = vidro(18)
            setPadding(dp(20), dp(16), dp(20), dp(14))
            visibility = View.GONE
        }
        val linha = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        infoNumero = TextView(this).apply {
            textSize = 22f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFF0B0F17.toInt()); gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(ambar) }
        }
        linha.addView(infoNumero, LinearLayout.LayoutParams(dp(48), dp(44)).apply { marginEnd = dp(14) })
        val textos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        infoTitulo = TextView(this).apply { textSize = 22f; typeface = fonteMedia; setTextColor(papel); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        infoLinha = TextView(this).apply { textSize = 13f; setTextColor(suave); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        textos.addView(infoTitulo); textos.addView(infoLinha)
        linha.addView(textos, LinearLayout.LayoutParams(0, -2, 1f))
        val vivo = TextView(this).apply {
            text = getString(R.string.player_ao_vivo); textSize = 11f; typeface = Typeface.DEFAULT_BOLD; setTextColor(papel)
            letterSpacing = 0.08f; setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(coral) }
        }
        linha.addView(vivo, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
        infoHora = TextView(this).apply { textSize = 13f; setTextColor(suave) }
        linha.addView(infoHora)
        info.addView(linha)
        infoProgresso = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(coral)
            progressBackgroundTintList = ColorStateList.valueOf(0x38FFFFFF)
        }
        info.addView(infoProgresso, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(12) })
        info.addView(TextView(this).apply {
            text = getString(R.string.player_teclas); textSize = 11.5f; setTextColor(suave)
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        return info
    }

    private val esconderInfo = Runnable { info.visibility = View.GONE }

    private fun mostrarInfo() {
        atualizarInfo()
        info.visibility = View.VISIBLE
        tela.removeCallbacks(esconderInfo)
        tela.postDelayed(esconderInfo, ESCONDER_INFO_MS)
    }

    // O que já foi gravado no ultimo.json (canal + início do 1º programa): só regrava quando muda.
    private var gravado = ""

    /** O último canal e os próximos programas dele, para o destaque da HOME (que roda em outro processo). */
    private fun gravarUltimo(c: Canal) {
        val programas = c.guiaId.takeIf { it.isNotEmpty() }?.let { guia.proximos(it, System.currentTimeMillis(), 4) }.orEmpty()
        val marca = "${c.id}|${programas.firstOrNull()?.inicio}|${programas.size}"
        if (marca == gravado) return
        gravado = marca
        PlayerArmazem.definirUltimoCanal(this, c, programas)
    }

    private fun atualizarInfo() {
        val c = atual ?: return
        gravarUltimo(c)   // o guia pode ter acabado de carregar, ou o programa ter mudado
        val agora = System.currentTimeMillis()
        val hora = SimpleDateFormat("HH:mm", Locale.getDefault())
        val prog = c.guiaId.takeIf { it.isNotEmpty() }?.let { guia.proximos(it, agora, 2) }.orEmpty()
        val atualProg = prog.firstOrNull()?.takeIf { agora >= it.inicio }
        infoNumero.text = c.numero.toString()
        infoTitulo.text = atualProg?.titulo ?: c.nome
        val partes = mutableListOf(c.nome)
        atualProg?.let { partes += "${hora.format(Date(it.inicio))} – ${hora.format(Date(it.fim))}" }
        prog.getOrNull(if (atualProg != null) 1 else 0)?.let { partes += getString(R.string.player_a_seguir, it.titulo) }
        infoLinha.text = partes.joinToString("  ·  ")
        infoHora.text = hora.format(Date(agora))
        infoProgresso.visibility = if (atualProg != null) View.VISIBLE else View.INVISIBLE
        atualProg?.let { infoProgresso.progress = (guia.progresso(it, agora) * 1000).toInt() }
    }

    // ---------------------------------------------------------------- lista completa (PlayerListaTela)

    private fun abrirLista(focarCategorias: Boolean = false) {
        if (canais.isEmpty()) return
        info.visibility = View.GONE
        lista.abrir(visiveis, guia, favoritos, recentes, categoria, atual, PlayerFiltro.resumo(filtro),
            if (filtro.mostrarOcultas) filtro.ocultas else emptySet(), focarCategorias)
    }

    private fun abrirGrade(canaisDaGrade: List<Canal>, nome: String) {
        if (canaisDaGrade.isEmpty()) return
        info.visibility = View.GONE
        grade.abrir(canaisDaGrade, guia, nome, atual)
    }

    /** Recalcula as ocultas e a lista visível (depois de carregar, mudar filtro ou esconder um grupo). */
    private fun refiltrar() {
        filtro = filtro.copy(ocultas = PlayerFiltro.ocultasEfetivas(canais, escondidos, liberados))
        visiveis = PlayerFiltro.aplicar(canais, filtro)
    }

    // ---------------------------------------------------------------- busca e filtros

    private fun buscar() {
        janelas.buscar(ultimaBusca) { termo ->
            if (termo.isEmpty()) return@buscar
            ultimaBusca = termo
            val agora = System.currentTimeMillis()
            val achados = PlayerFiltro.buscar(visiveis, termo, { c -> c.guiaId.takeIf { it.isNotEmpty() }?.let { guia.agora(it, agora)?.titulo } })
            lista.mostrarBusca(termo, achados)
            if (achados.isEmpty()) mostrarAvisoBreve(getString(R.string.player_busca_nada, termo))
        }
    }

    private fun abrirFiltros() {
        janelas.filtros(if (filtro.mostrarOcultas) canais else visiveisSemFiltroDeNome(), filtro, filtro.ocultas.isNotEmpty(),
            aoMudar = { novo -> aplicarFiltro(novo) },
            aoPedirOcultas = { janelas.pedirPin(aoLiberar = { aplicarFiltro(filtro.copy(mostrarOcultas = true)) }, aviso = { mostrarAvisoBreve(it) }) })
    }

    /** Canais que as opções de filtro consideram: tudo menos as categorias ocultas (sem PIN, elas não contam). */
    private fun visiveisSemFiltroDeNome(): List<Canal> = PlayerFiltro.aplicar(canais, PlayerFiltro.Filtro(ocultas = filtro.ocultas))

    private fun aplicarFiltro(novo: PlayerFiltro.Filtro) {
        filtro = novo
        refiltrar()
        abrirLista(focarCategorias = true)   // reabre com o filtro novo, no mesmo lugar
    }

    /** OK segurado numa categoria: esconde; se ela já estava oculta (aparecendo pelo PIN), volta a mostrar. */
    private fun alternarOculto(grupo: String) {
        val oculto = grupo in filtro.ocultas
        if (oculto) {
            escondidos = escondidos - grupo
            if (PlayerFiltro.ehAdulto(grupo)) liberados = liberados + grupo
        } else {
            escondidos = escondidos + grupo
            liberados = liberados - grupo
            if (categoria == PlayerLista.chaveDoGrupo(grupo)) categoria = PlayerLista.TODOS
        }
        PlayerArmazem.definirGruposEscondidos(this, escondidos)
        PlayerArmazem.definirGruposLiberados(this, liberados)
        refiltrar()
        abrirLista(focarCategorias = true)
        mostrarAvisoBreve(getString(if (oculto) R.string.player_grupo_mostrado else R.string.player_grupo_oculto,
            grupo.ifEmpty { getString(R.string.player_cat_outros) }))
    }

    private fun escolherNaLista(c: Canal, chave: String) {
        lista.fechar()
        if (chave != categoria) { categoria = chave; PlayerArmazem.definirCategoria(this, chave) }
        if (c.id != atual?.id) sintonizar(c) else mostrarInfo()
    }

    private fun alternarFavorito(c: Canal) {
        val antes = favoritos
        favoritos = PlayerLista.alternarFavorito(favoritos, c.id)
        if (favoritos === antes) { mostrarAvisoBreve(getString(R.string.player_favoritos_cheio)); return }
        val virou = c.id in favoritos
        PlayerArmazem.definirFavoritos(this, favoritos)
        lista.atualizarFavoritos(favoritos)
        mostrarAvisoBreve(getString(if (virou) R.string.player_favorito_pos else R.string.player_favorito_tirou, c.nome))
    }

    /** Recentes: só grava quando muda o 1º (as novas tentativas do mesmo canal não escrevem no disco). */
    private fun lembrarRecente(c: Canal) {
        if (recentes.firstOrNull() == c.id) return
        recentes = PlayerLista.registrarRecente(recentes, c.id)
        PlayerArmazem.definirRecentes(this, recentes)
    }

    // ---------------------------------------------------------------- avisos

    private fun mostrarAviso(t: String) { aviso.text = t; aviso.visibility = View.VISIBLE }
    private fun esconderAviso() { tela.removeCallbacks(avisoCarregando); aviso.visibility = View.GONE }
    private fun mostrarAvisoBreve(t: String) { mostrarAviso(t); tela.postDelayed({ esconderAviso() }, 2_500) }

    // ---------------------------------------------------------------- aparência

    /** Painel de "vidro escuro" dos esboços: fundo quase opaco (sem desfoque de verdade: caro demais nesta TV). */
    private fun vidro(raio: Int) = GradientDrawable().apply {
        cornerRadius = dp(raio).toFloat()
        setColor(0xD90B0F17.toInt())
        setStroke(dp(1), 0x1FFFFFFF)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}
