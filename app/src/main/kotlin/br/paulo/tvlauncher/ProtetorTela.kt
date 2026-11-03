package br.paulo.tvlauncher

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView

/**
 * O protetor de tela do launcher (um DreamService do Android). Existe para APOSENTAR o protetor de fábrica
 * (com.google.android.apps.tv.dreamx), que media ~59 MB residentes: apontamos o protetor do sistema para este,
 * que é leve — só um relógio, a data e o clima que o launcher já guardou em SharedPreferences "clima".
 *
 * Anti-burn-in: a coluna inteira se desloca alguns dp a cada minuto (a TV da sala é LCD, mas um painel parado por horas
 * ainda sofre retenção de imagem; e de quebra gasta menos luz). A tela fica escurecida (setScreenBright(false)).
 * Qualquer tecla fecha (setInteractive(false): o toque/clique acorda e encerra o sonho).
 */
class ProtetorTela : DreamService() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var coluna: LinearLayout
    private var passo = 0

    private val deslocar = object : Runnable {
        override fun run() {
            // Caminho suave em "oito" lento: 8 posições ao longo de ~8 min, deslocamento de até ±dp(20).
            passo = (passo + 1) % 8
            val dx = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)[passo] * dp(20)
            val dy = intArrayOf(-1, -1, 0, 1, 1, 1, 0, -1)[passo] * dp(14)
            coluna.animate().translationX(dx.toFloat()).translationY(dy.toFloat()).setDuration(2000).start()
            ui.postDelayed(this, 60_000)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false        // qualquer interação encerra o sonho
        isFullscreen = true
        isScreenBright = false       // tela escurecida: descanso de verdade

        val fonteLeve = Typeface.create("sans-serif-light", Typeface.NORMAL)
        val fonteMedia = Typeface.create("sans-serif-medium", Typeface.NORMAL)

        coluna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        coluna.addView(TextClock(this).apply {
            format12Hour = "HH:mm"; format24Hour = "HH:mm"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 128f)
            setTextColor(0xFFE8EEEA.toInt())
            typeface = fonteLeve
            includeFontPadding = false
            gravity = Gravity.CENTER
        })
        coluna.addView(TextClock(this).apply {
            format12Hour = "EEEE, d 'de' MMMM"; format24Hour = "EEEE, d 'de' MMMM"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(0x99E8EEEA.toInt())
            typeface = fonteMedia
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })

        // Clima: só se o launcher mediu há < 3 h (senão, nada — melhor calar que mentir um número velho).
        val clima = getSharedPreferences("clima", MODE_PRIVATE)
        val frescoMs = System.currentTimeMillis() - clima.getLong("em", 0)
        if (clima.contains("temp") && frescoMs < 3 * 60 * 60_000L) {
            val temp = clima.getInt("temp", 0)
            val chuva = if (clima.getBoolean("chovendo", false)) "  ·  chovendo" else ""
            coluna.addView(TextView(this).apply {
                text = "$temp°$chuva"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                setTextColor(0x80E8EEEA.toInt())
                typeface = fonteLeve
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18) })
        }

        // Mesmo grafite do tema Escuro (ver Fundos.kt: FundoEscuro), simplificado para o sonho ficar barato.
        setContentView(FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF0E1317.toInt(), 0xFF0A0D10.toInt(), 0xFF07090B.toInt()))
            addView(coluna, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        })
        ui.postDelayed(deslocar, 60_000)
    }

    override fun onDetachedFromWindow() {
        ui.removeCallbacks(deslocar)
        super.onDetachedFromWindow()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}
