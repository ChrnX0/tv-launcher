package br.paulo.tvlauncher

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
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
 */
class AlarmeTocandoActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private var id = 0
    private var toque: Ringtone? = null
    private var bip: ToneGenerator? = null

    // Plano B do som: se a TV não tiver toque de alarme, um bipe a cada 1,2 s.
    private val bipar = object : Runnable {
        override fun run() {
            bip?.startTone(ToneGenerator.TONE_PROP_BEEP2, 700)
            ui.postDelayed(this, 1200)
        }
    }
    private val desistir = Runnable { parar() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Acende a tela mesmo com o Chromecast em standby e não deixa apagar enquanto toca.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        id = intent.getIntExtra(Despertador.EXTRA_ID, 0)
        setContentView(montar())
        tocarSom()
        ui.postDelayed(desistir, 10 * 60_000L)
        Log.i("TvLauncher.Alarme", "tocando (id $id)")
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
            text = "⏰  " + (alarme?.nome?.takeIf { it.isNotBlank() } ?: "Despertador")
            setPadding(0, dp(12), 0, dp(36))
            gravity = Gravity.CENTER
        })
        val parar = botao("Parar") { parar() }
        val soneca = botao("Soneca 10 min") { soneca() }
        coluna.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            addView(parar, LinearLayout.LayoutParams(dp(220), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(24) })
            addView(soneca, LinearLayout.LayoutParams(dp(220), LinearLayout.LayoutParams.WRAP_CONTENT))
        })
        ui.post { parar.requestFocus() }
        return FrameLayout(this).apply {
            clipChildren = false
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF1B2326.toInt(), 0xFF0A0D0E.toInt()))
            addView(coluna, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
    }

    private fun tocarSom() {
        val atributos = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        toque = uri?.let { RingtoneManager.getRingtone(this, it) }?.apply {
            audioAttributes = atributos
            isLooping = true
            play()
        }
        // Meio segundo depois: se o toque não está tocando (a TV pode não ter som de alarme), usa o bipe.
        ui.postDelayed({
            if (toque?.isPlaying != true) {
                Log.i("TvLauncher.Alarme", "sem toque de alarme na TV: usando bipe")
                bip = ToneGenerator(AudioManager.STREAM_ALARM, 100)
                ui.post(bipar)
            }
        }, 500)
    }

    private fun pararSom() {
        toque?.stop()
        ui.removeCallbacks(bipar)
        bip?.release()
        bip = null
    }

    private fun parar() {
        Despertador.tocou(this, id)
        finish()
    }

    private fun soneca() {
        Despertador.soneca(this, id)
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { soneca(); return true }
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
