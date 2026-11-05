package br.paulo.tvlauncher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView

/**
 * A tela que aparece quando um alarme toca: acende a tela (e, pelo HDMI-CEC, normalmente liga a TV), mostra a hora
 * e toca o som até alguém apertar Parar ou Soneca. Sem ninguém, para sozinha em 10 min.
 * Voltar = Soneca (para um toque sem querer não desligar o alarme).
 * Também é a tela do fim do timer (EXTRA_TIMER, ver Timer.kt): aí só tem "Parar" e o Voltar também para.
 */
class AlarmeTocandoActivity : Activity() {
    companion object { const val EXTRA_TIMER = "timer" }
    private var timer = false
    private var nomeTimer = ""
    private val ui = Handler(Looper.getMainLooper())
    private var id = 0
    private var player: MediaPlayer? = null
    private var bip: ToneGenerator? = null

    // Plano B do som: se o arquivo não abrir, um bipe a cada 1,2 s.
    private val bipar = object : Runnable {
        override fun run() {
            bip?.startTone(ToneGenerator.TONE_PROP_BEEP2, 700)
            ui.postDelayed(this, 1200)
        }
    }
    private val desistir = Runnable { parar(falar = false) }   // ninguém apareceu: sem "Bom dia" para a sala vazia

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Acende a tela mesmo com o Chromecast em standby e não deixa apagar enquanto toca.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        id = intent.getIntExtra(Despertador.EXTRA_ID, 0)
        timer = intent.getBooleanExtra(EXTRA_TIMER, false)
        if (timer) {
            nomeTimer = getSharedPreferences("timer", MODE_PRIVATE).getString("nome", "") ?: ""
            Timer.limpar(this)   // acabou: some da HOME
        }
        setContentView(montar())
        tocarSom()
        ui.postDelayed(desistir, 10 * 60_000L)
        Log.i("TvLauncher.Alarme", if (timer) "timer acabou ('$nomeTimer')" else "tocando (id $id)")
    }

    private fun montar(): View {
        val alarme = Despertador.listar(this).firstOrNull { it.id == id }
        val coluna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false   // o botao com foco cresce um pouco: nao pode ser cortado
            clipToPadding = false
        }
        coluna.addView(TextClock(this).apply {
            format12Hour = "HH:mm"; format24Hour = "HH:mm"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 120f)
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            includeFontPadding = false
            gravity = Gravity.CENTER   // numa coluna vertical cada linha ocupa a largura toda: o texto precisa se centralizar
        })
        coluna.addView(texto(24f, 0xFFE8EEEA.toInt()).apply {
            text = if (timer) "⏱  " + nomeTimer.ifBlank { "Tempo esgotado" }
                else "⏰  " + (alarme?.nome?.takeIf { it.isNotBlank() } ?: getString(R.string.al_titulo))
            setPadding(0, dp(12), 0, dp(36))
            gravity = Gravity.CENTER
        })
        val parar = botao(getString(R.string.al_parar)) { parar() }
        val soneca = botao(getString(R.string.al_soneca)) { soneca() }
        coluna.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL   // no timer só há um botão: centralizado embaixo da hora
            clipChildren = false
            addView(parar, LinearLayout.LayoutParams(dp(220), LinearLayout.LayoutParams.WRAP_CONTENT).apply { if (!timer) marginEnd = dp(24) })
            if (!timer) addView(soneca, LinearLayout.LayoutParams(dp(220), LinearLayout.LayoutParams.WRAP_CONTENT))
        })
        ui.post { parar.requestFocus() }
        return FrameLayout(this).apply {
            clipChildren = false
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF1B2326.toInt(), 0xFF0A0D0E.toInt()))
            addView(coluna, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
    }

    private fun tocarSom() {
        val alarme = if (timer) null else Despertador.listar(this).firstOrNull { it.id == id }
        val rampa = alarme?.rampa ?: 0
        player = SomAlarme.criar(this, alarme?.som ?: 0)
        if (player != null) {
            // Rampa: o volume do player vai de 5% a 100% em [rampa] segundos, em passos de 1 s (curva ao quadrado:
            // o ouvido percebe o volume em escala logarítmica, então linear soaria "pulando" no começo).
            val inicio = System.currentTimeMillis()
            fun volume(): Float = if (rampa <= 0) 1f else {
                val x = ((System.currentTimeMillis() - inicio) / (rampa * 1000f)).coerceIn(0f, 1f)
                0.05f + 0.95f * x * x
            }
            player?.setVolume(volume(), volume())
            player?.start()
            if (rampa > 0) ui.post(object : Runnable {
                override fun run() {
                    val v = volume()
                    player?.setVolume(v, v)
                    if (v < 1f) ui.postDelayed(this, 1000)
                }
            })
            return
        }
        // Último recurso (arquivo não abriu): bipe pelo stream de música, que sai pelo HDMI.
        Log.i("TvLauncher.Alarme", "som do app falhou: usando bipe")
        bip = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        ui.post(bipar)
    }

    private fun pararSom() {
        player?.release()
        player = null
        ui.removeCallbacks(bipar)
        bip?.release()
        bip = null
    }

    private fun parar(falar: Boolean = true) {
        if (timer) { finish(); return }
        val bomDia = Despertador.listar(this).firstOrNull { it.id == id }?.bomDia == true && falar
        Despertador.tocou(this, id)
        // "Bom dia" (05/11/2026): abre a previsão, que fala a saudação, a hora, o tempo e a agenda de hoje.
        // Só no Parar: a soneca (e o Voltar, que é soneca) não fala nada, a pessoa quer dormir mais 10 min.
        if (bomDia) startActivity(Intent(this, PrevisaoActivity::class.java)
            .putExtra(PrevisaoActivity.EXTRA_BOM_DIA, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun soneca() {
        Despertador.soneca(this, id)
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { if (timer) parar() else soneca(); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        pararSom()
        ui.removeCallbacks(desistir)
        super.onDestroy()
    }

    private fun botao(rotulo: String, aoClicar: () -> Unit) = texto(20f, Color.WHITE).apply {
        text = rotulo
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(16), dp(18), dp(16))
        isFocusable = true
        isClickable = true
        background = fundo(false)
        setOnClickListener { aoClicar() }
        setOnFocusChangeListener { v, f ->
            v.background = fundo(f)
            v.animate().scaleX(if (f) 1.06f else 1f).scaleY(if (f) 1.06f else 1f).setDuration(120).start()
        }
    }

    private fun fundo(focado: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        setColor(if (focado) 0x33FFFFFF else 0x14FFFFFF)
        setStroke(dp(2), if (focado) 0xD9FFFFFF.toInt() else 0x22FFFFFF)
    }

    private fun texto(sp: Float, cor: Int) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(cor)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}
