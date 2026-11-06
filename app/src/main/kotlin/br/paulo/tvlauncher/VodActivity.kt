package br.paulo.tvlauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.util.Locale

/**
 * Player universal, filmes e séries (06/11/2026): toca um vídeo sob demanda (filme ou episódio), com pausa, avançar,
 * voltar, escolha de áudio/legenda e "continuar de onde parou".
 *
 * Roda no processo ":player", como a TV ao vivo. Recebe só os ids (conta, filme, extensão), e o endereço com a senha é
 * montado aqui dentro: senha não viaja em Intent.
 *
 * Controle:
 *   OK / ⏯          pausa e continua             ◀ ▶ (ou ⏪ ⏩)  volta 10 s / avança 30 s (segurado acelera)
 *   ▼                áudio e legenda              VOLTAR          sai (a posição fica salva)
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class VodActivity : Activity() {
    companion object {
        private const val EXTRA_FONTE = "fonte"
        private const val EXTRA_ID = "id"
        private const val EXTRA_EXT = "ext"
        private const val EXTRA_TITULO = "titulo"
        private const val EXTRA_TIPO = "tipo"
        const val TIPO_FILME = "filme"
        const val TIPO_EPISODIO = "episodio"
        const val RESULTADO_TERMINOU = "terminou"
        private const val SALVAR_MS = 15_000L
        private const val ESCONDER_MS = 4_000L

        fun abrir(ctx: Context, tipo: String, fonteId: String, id: String, extensao: String, titulo: String, doComeco: Boolean = false) {
            ctx.startActivity(intent(ctx, tipo, fonteId, id, extensao, titulo, doComeco))
        }

        /** Para quem quer saber se o vídeo foi até o fim (séries: próximo episódio). */
        fun abrirComResultado(act: Activity, codigo: Int, tipo: String, fonteId: String, id: String, extensao: String, titulo: String, doComeco: Boolean = false) {
            act.startActivityForResult(intent(act, tipo, fonteId, id, extensao, titulo, doComeco), codigo)
        }

        private fun intent(ctx: Context, tipo: String, fonteId: String, id: String, extensao: String, titulo: String, doComeco: Boolean) =
            Intent(ctx, VodActivity::class.java)
                .putExtra(EXTRA_TIPO, tipo).putExtra(EXTRA_FONTE, fonteId).putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_EXT, extensao).putExtra(EXTRA_TITULO, titulo).putExtra("do_comeco", doComeco)

        /** Chave da posição salva (a mesma que Filme.chave / Episodio.chave usam). */
        fun chave(tipo: String, fonteId: String, id: String) = if (tipo == TIPO_EPISODIO) "$fonteId:e$id" else "$fonteId:f$id"
    }

    private val tela = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var url: String? = null
    private var chave = ""
    private var retomarEm = 0L
    private var visivel = false
    private var legendaDecidida = false

    private val papel = 0xFFF5F3EE.toInt()
    private val suave = 0xB3F5F3EE.toInt()
    private val coral = 0xFFFF5A4E.toInt()

    private lateinit var video: SurfaceView
    private lateinit var barra: LinearLayout
    private lateinit var titulo: TextView
    private lateinit var tempo: TextView
    private lateinit var progresso: ProgressBar
    private lateinit var aviso: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val tipo = intent.getStringExtra(EXTRA_TIPO) ?: TIPO_FILME
        val fonte = intent.getStringExtra(EXTRA_FONTE).orEmpty()
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        val ext = intent.getStringExtra(EXTRA_EXT).orEmpty()
        chave = chave(tipo, fonte, id)

        val raiz = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = SurfaceView(this)
        raiz.addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        raiz.addView(montarBarra(intent.getStringExtra(EXTRA_TITULO).orEmpty()),
            FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(32), 0, dp(32), dp(26)) })
        aviso = TextView(this).apply {
            textSize = 18f; setTextColor(papel); gravity = Gravity.CENTER; background = vidro(16)
            setPadding(dp(24), dp(16), dp(24), dp(16)); text = getString(R.string.player_carregando_canal)
        }
        raiz.addView(aviso, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setContentView(raiz)

        // O endereço (com a senha) e a posição salva saem do disco: fora da thread da tela.
        Thread {
            val endereco = PlayerArmazem.fontes(this).firstOrNull { it.id == fonte }?.xtream?.let { conta ->
                if (tipo == TIPO_EPISODIO) Series.urlEpisodio(conta, id, ext) else Vod.urlFilme(conta, Filme(id, fonte, "", extensao = ext))
            }
            val pos = if (intent.getBooleanExtra("do_comeco", false)) 0L else Vod.inicio(PlayerArmazem.posicoes(this)[chave])
            tela.post {
                if (isDestroyed) return@post
                if (endereco == null) { mostrarAviso(getString(R.string.vod_sem_conta)); return@post }
                url = endereco; retomarEm = pos
                tocar()
            }
        }.start()
    }

    override fun onStart() { super.onStart(); visivel = true; if (url != null && player == null) tocar() }

    override fun onStop() {
        super.onStop()
        visivel = false
        salvar()
        player?.let { retomarEm = it.currentPosition; it.release() }
        player = null
    }

    override fun onDestroy() { tela.removeCallbacksAndMessages(null); super.onDestroy() }

    // ---------------------------------------------------------------- vídeo

    private fun tocar() {
        val u = url ?: return
        if (!visivel) return
        val http = DefaultHttpDataSource.Factory().setUserAgent("TvLauncher-Player/1.0").setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000).setReadTimeoutMs(20_000)
        val p = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(30_000).build()
        p.setVideoSurfaceView(video)
        p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        // Prefere o áudio em português quando o arquivo tiver (filme dublado costuma vir com as duas faixas).
        // A legenda em português só liga sozinha se o áudio NÃO for português (ver onTracksChanged).
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setPreferredAudioLanguage("pt").build()
        p.addListener(ouvinte)
        p.setMediaItem(MediaItem.fromUri(u), retomarEm)
        p.prepare()
        p.playWhenReady = true
        player = p
        tela.removeCallbacks(salvarDepois); tela.postDelayed(salvarDepois, SALVAR_MS)
        mostrarBarra()
    }

    private val ouvinte = object : Player.Listener {
        override fun onPlaybackStateChanged(estado: Int) {
            when (estado) {
                Player.STATE_READY -> esconderAviso()
                Player.STATE_BUFFERING -> mostrarAviso(getString(R.string.player_carregando_canal))
                Player.STATE_ENDED -> {
                    salvar(terminou = true)
                    // A tela de séries usa isto para tocar o próximo episódio sozinha.
                    setResult(RESULT_OK, Intent().putExtra(RESULTADO_TERMINOU, true))
                    finish()
                }
                else -> {}
            }
            atualizarBarra()
        }
        override fun onIsPlayingChanged(tocando: Boolean) { atualizarBarra(); if (!tocando) mostrarBarra(fixa = true) else mostrarBarra() }
        override fun onPlayerError(erro: PlaybackException) { mostrarAviso(getString(R.string.vod_erro)) }

        override fun onTracksChanged(faixas: Tracks) {
            if (legendaDecidida || faixas.groups.isEmpty()) return
            legendaDecidida = true
            val p = player ?: return
            val audioPt = faixas.groups.any { g -> g.type == C.TRACK_TYPE_AUDIO && g.isSelected &&
                (0 until g.length).any { g.isTrackSelected(it) && g.getTrackFormat(it).language?.startsWith("pt") == true } }
            if (audioPt) return
            for (g in faixas.groups) {
                if (g.type != C.TRACK_TYPE_TEXT) continue
                val i = (0 until g.length).firstOrNull { g.isTrackSupported(it) && g.getTrackFormat(it).language?.startsWith("pt") == true } ?: continue
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                return
            }
        }
    }

    private val salvarDepois: Runnable = object : Runnable {
        override fun run() { salvar(); tela.postDelayed(this, SALVAR_MS) }
    }

    /** Grava onde parou. Terminou = posição igual à duração (sai do "continuar assistindo"). */
    private fun salvar(terminou: Boolean = false) {
        val p = player ?: return
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val ms = if (terminou) dur else p.currentPosition
        if (ms <= 0 && !terminou) return
        val agora = System.currentTimeMillis()
        Thread {
            val antes = PlayerArmazem.posicoes(this)
            PlayerArmazem.definirPosicoes(this, Vod.registrar(antes, chave, Posicao(ms, dur, agora)))
        }.start()
    }

    // ---------------------------------------------------------------- controle

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val p = player
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                if (event.repeatCount == 0) p?.let { if (it.isPlaying) it.pause() else it.play() }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { p?.play(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { p?.pause(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { pular(p, -10_000L, event.repeatCount); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { pular(p, +30_000L, event.repeatCount); return true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_INFO -> { mostrarBarra(); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CAPTIONS -> { if (event.repeatCount == 0) escolherFaixas(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Segurado acelera: depois de ~1 s de tecla, cada passo vale 2 minutos. */
    private fun pular(p: ExoPlayer?, passo: Long, repeticao: Int) {
        p ?: return
        val delta = if (repeticao > 8) passo.coerceIn(-1L, 1L) * 120_000L else passo
        val dur = p.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        p.seekTo((p.currentPosition + delta).coerceIn(0, (dur - 1_000).coerceAtLeast(0)))
        mostrarBarra()
    }

    /** Áudio e legenda: lista as faixas que o arquivo tem (dublado, legendado, etc.). */
    private fun escolherFaixas() {
        val p = player ?: return
        data class Opcao(val rotulo: String, val aplicar: () -> Unit)
        val opcoes = ArrayList<Opcao>()
        val semLegenda = getString(R.string.vod_sem_legenda)
        for (grupo in p.currentTracks.groups) {
            val tipo = grupo.type
            if (tipo != C.TRACK_TYPE_AUDIO && tipo != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until grupo.length) {
                if (!grupo.isTrackSupported(i)) continue
                val f = grupo.getTrackFormat(i)
                val idioma = f.language?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()).ifEmpty { it } } ?: getString(R.string.vod_faixa)
                val nome = listOfNotNull(idioma, f.label?.takeIf { it.isNotBlank() && it != idioma }).joinToString(" · ")
                val marcado = if (grupo.isTrackSelected(i)) "● " else "    "
                val prefixo = getString(if (tipo == C.TRACK_TYPE_AUDIO) R.string.vod_audio else R.string.vod_legenda)
                val g = grupo.mediaTrackGroup; val indice = i
                opcoes += Opcao("$marcado$prefixo: $nome") {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(tipo, false)
                        .setOverrideForType(TrackSelectionOverride(g, indice)).build()
                }
            }
        }
        opcoes += Opcao("    $semLegenda") {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.vod_faixas)
            .setItems(opcoes.map { it.rotulo }.toTypedArray()) { _, i -> opcoes[i].aplicar() }
            .show()
    }

    // ---------------------------------------------------------------- barra de baixo

    private fun montarBarra(t: String): View {
        barra = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = vidro(18); setPadding(dp(20), dp(16), dp(20), dp(14))
        }
        val linha = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titulo = TextView(this).apply {
            text = t; textSize = 20f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setTextColor(papel)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        linha.addView(titulo, LinearLayout.LayoutParams(0, -2, 1f))
        tempo = TextView(this).apply { textSize = 14f; setTextColor(suave) }
        linha.addView(tempo)
        barra.addView(linha)
        progresso = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progressTintList = ColorStateList.valueOf(coral)
            progressBackgroundTintList = ColorStateList.valueOf(0x38FFFFFF); secondaryProgressTintList = ColorStateList.valueOf(0x66FFFFFF)
        }
        barra.addView(progresso, LinearLayout.LayoutParams(-1, dp(5)).apply { topMargin = dp(12) })
        barra.addView(TextView(this).apply { text = getString(R.string.vod_teclas); textSize = 11.5f; setTextColor(suave) },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        return barra
    }

    private val esconderBarra = Runnable { if (player?.isPlaying == true) barra.visibility = View.GONE }
    private val tique: Runnable = object : Runnable {
        override fun run() { atualizarBarra(); if (barra.visibility == View.VISIBLE) tela.postDelayed(this, 1_000) }
    }

    private fun mostrarBarra(fixa: Boolean = false) {
        barra.visibility = View.VISIBLE
        atualizarBarra()
        tela.removeCallbacks(tique); tela.postDelayed(tique, 1_000)
        tela.removeCallbacks(esconderBarra)
        if (!fixa) tela.postDelayed(esconderBarra, ESCONDER_MS)
    }

    private fun atualizarBarra() {
        val p = player ?: return
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val pos = p.currentPosition
        val pausa = if (p.isPlaying) "" else "⏸  "
        tempo.text = pausa + (dur?.let { "${relogio(pos)} / ${relogio(it)}   −${relogio(it - pos)}" } ?: relogio(pos))
        progresso.progress = dur?.let { (pos * 1000 / it).toInt() } ?: 0
        progresso.secondaryProgress = dur?.let { (p.bufferedPosition * 1000 / it).toInt() } ?: 0
    }

    private fun relogio(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    private fun mostrarAviso(t: String) { aviso.text = t; aviso.visibility = View.VISIBLE }
    private fun esconderAviso() { aviso.visibility = View.GONE }

    private fun vidro(raio: Int) = GradientDrawable().apply {
        cornerRadius = dp(raio).toFloat(); setColor(0xD90B0F17.toInt()); setStroke(dp(1), 0x1FFFFFFF)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}
