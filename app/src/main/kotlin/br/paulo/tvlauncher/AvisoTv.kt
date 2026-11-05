package br.paulo.tvlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Aviso por cima de QUALQUER tela (Netflix, YouTube, a HOME…): o recado mandado pelo painel e o aviso de chuva
 * (05/11/2026). Um cartão no alto da tela que some sozinho depois de alguns segundos.
 *
 * Por que a janela é do serviço de acessibilidade (TYPE_ACCESSIBILITY_OVERLAY) e não uma janela flutuante comum: é a
 * mesma escolha do PareamentoNaTv — medido em 04/11/2026, há telas do sistema que escondem as janelas flutuantes de
 * apps, e a de acessibilidade não entra nesse bloqueio. Sem o serviço ligado não dá para mostrar ([mostrar] = false).
 *
 * A janela NÃO pega o foco (FLAG_NOT_FOCUSABLE): quem está vendo um filme continua com o controle no filme. Para
 * fechar antes da hora, o VOLTAR do controle fecha o aviso (o serviço de teclas pergunta a [teclaVoltar] primeiro);
 * esse VOLTAR não chega ao app da frente, para não sair do filme sem querer.
 *
 * TV com a tela apagada: o recado fica GUARDADO e aparece quando a tela acender (até [VALIDADE_PENDENTE_MS]);
 * o aviso de chuva não espera ([mostrar] com guardarSeApagada = false), porque passaria da hora.
 */
object AvisoTv {
    private const val TAG = "TvLauncher.Aviso"
    private const val PREFS = "aviso_tv"
    private const val VALIDADE_PENDENTE_MS = 12 * 60 * 60_000L   // recado de mais de 12 h atrás já não serve

    private val principal = Handler(Looper.getMainLooper())
    private var janela: View? = null
    private var fecharDepois: Runnable? = null
    private var ouvindoTela = false

    enum class Resultado { MOSTRADO, GUARDADO, DESCARTADO, SEM_ACESSIBILIDADE }

    /**
     * Mostra o aviso agora (ou guarda até a tela acender). Pode chamar de qualquer thread.
     * [segundos] = quanto tempo fica na tela; [som] = dois bipes curtos para chamar a atenção.
     */
    fun mostrar(ctx: Context, titulo: String, texto: String, segundos: Int, som: Boolean, guardarSeApagada: Boolean): Resultado {
        val app = ctx.applicationContext
        if (AutorizaShizukuService.instancia == null) return Resultado.SEM_ACESSIBILIDADE
        if (!telaAcesa(app)) {
            if (!guardarSeApagada) return Resultado.DESCARTADO.also { Log.i(TAG, "tela apagada: aviso descartado ($titulo)") }
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("titulo", titulo).putString("texto", texto).putInt("segundos", segundos)
                .putBoolean("som", som).putLong("em", System.currentTimeMillis()).apply()
            principal.post { esperarTelaAcender(app) }
            return Resultado.GUARDADO
        }
        principal.post { abrirJanela(titulo, texto, segundos, som) }
        return Resultado.MOSTRADO
    }

    /** O serviço de teclas chama isto antes de tudo: VOLTAR com um aviso na tela fecha o aviso (true = tecla usada). */
    fun teclaVoltar(e: KeyEvent): Boolean {
        if (janela == null || e.keyCode != KeyEvent.KEYCODE_BACK) return false
        // Fecha no "sobe" da tecla; o "desce" também é engolido, senão o app da frente veria só metade da tecla.
        if (e.action == KeyEvent.ACTION_UP) principal.post { fechar() }
        return true
    }

    private fun telaAcesa(app: Context) = app.getSystemService(PowerManager::class.java)?.isInteractive != false

    // ---------------------------------------------------------------- recado guardado (tela apagada)

    private fun esperarTelaAcender(app: Context) {
        if (ouvindoTela) return
        ouvindoTela = true
        // Receiver registrado em código (SCREEN_ON não chega a receiver do manifest). Vive enquanto o processo vive;
        // como o serviço de acessibilidade segura o processo, é suficiente. Sai assim que entrega o recado.
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                try { app.unregisterReceiver(this) } catch (_: Exception) {}
                ouvindoTela = false
                // 3 s: dá tempo da imagem voltar na TV antes do cartão aparecer.
                principal.postDelayed({ entregarGuardado(app) }, 3000)
            }
        }, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    private fun entregarGuardado(app: Context) {
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val em = p.getLong("em", 0)
        val titulo = p.getString("titulo", null)
        val texto = p.getString("texto", null)
        val segundos = p.getInt("segundos", 60)
        val som = p.getBoolean("som", true)
        p.edit().clear().apply()
        if (titulo == null || texto == null || System.currentTimeMillis() - em > VALIDADE_PENDENTE_MS) return
        val h = java.text.SimpleDateFormat("HH:mm", java.util.Locale("pt", "BR")).format(java.util.Date(em))
        mostrar(app, "$titulo (enviado às $h)", texto, segundos, som, guardarSeApagada = true)
    }

    // ---------------------------------------------------------------- janela

    private fun abrirJanela(titulo: String, texto: String, segundos: Int, som: Boolean) {
        val svc = AutorizaShizukuService.instancia ?: return
        fechar()   // um aviso por vez: o novo substitui o anterior
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), svc.resources.displayMetrics).toInt()
        fun linha(t: String, sp: Float, cor: Int) = TextView(svc).apply {
            text = t; setTextColor(cor); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); gravity = Gravity.CENTER_HORIZONTAL
        }
        val cartao = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(20), dp(32), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat(); setColor(0xF01C2024.toInt()); setStroke(dp(2), 0x66FFFFFF)
            }
            addView(linha(titulo, 18f, 0xFFB8C4BF.toInt()))
            addView(linha(texto, 30f, Color.WHITE).apply { setPadding(0, dp(6), 0, dp(8)) })
            addView(linha("VOLTAR fecha · some sozinho em ${legivel(segundos)}", 13f, 0x99FFFFFF.toInt()))
        }
        // Largura: no máximo 70% da tela, para não tapar o filme inteiro com um recado longo.
        cartao.minimumWidth = dp(360)
        val largura = (svc.resources.displayMetrics.widthPixels * 0.7).toInt()
        cartao.getChildAt(1).let { (it as TextView).maxWidth = largura - dp(64) }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(40) }
        try {
            svc.getSystemService(WindowManager::class.java).addView(cartao, p)
        } catch (e: Exception) {
            Log.w(TAG, "não consegui abrir a janela: $e"); return
        }
        janela = cartao
        if (som) bipar()
        fecharDepois = Runnable { fechar() }.also { principal.postDelayed(it, segundos * 1000L) }
        Log.i(TAG, "aviso na tela por ${segundos}s: $titulo")
    }

    private fun fechar() {
        fecharDepois?.let { principal.removeCallbacks(it) }
        fecharDepois = null
        janela?.let { j -> try { j.context.getSystemService(WindowManager::class.java).removeView(j) } catch (_: Exception) {} }
        janela = null
    }

    private fun legivel(s: Int) = if (s % 60 == 0) "${s / 60} min" else "$s s"

    /** Dois bipes curtos no volume de notificação. Não pede o foco do áudio: o som do filme não pausa. */
    private fun bipar() {
        try {
            val t = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
            t.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
            principal.postDelayed({ t.release() }, 1000)
        } catch (e: Exception) {
            Log.w(TAG, "sem som: $e")   // alguns aparelhos não têm o gerador de tons; o aviso aparece mesmo assim
        }
    }
}
