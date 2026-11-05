package br.paulo.tvlauncher

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Confirmação NA TV do "Reconectar a TV" pedido pelo painel (a única ação que o launcher faz no roteador UniFi).
 * Mesmo jeito da ConfirmarIaActivity: foco começa em "Cancelar", ◀ ▶ trocam, OK escolhe, Voltar cancela, e sem
 * resposta em [UnifiCliente.ESPERA_CONFIRMAR_MS] a tela fecha e o pedido é cancelado. Quem está com o controle na mão
 * decide; o painel sozinho não derruba o Wi-Fi da TV no meio de um filme.
 */
class ConfirmarRoteadorActivity : Activity() {
    private var respondido = false
    private val ui = Handler(Looper.getMainLooper())

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        if (UnifiCliente.estadoReconectar != UnifiCliente.EstadoReconectar.ESPERANDO_TV) { respondido = true; finish(); return }
        val tema = Tema.atual(this)
        fun texto(t: String, tamanho: Float, cor: Int, media: Boolean = false) = TextView(this).apply {
            text = t; setTextColor(cor); textSize = tamanho; gravity = Gravity.CENTER
            typeface = if (media) Fontes.uiMedia(this@ConfirmarRoteadorActivity, tema) else Fontes.ui(this@ConfirmarRoteadorActivity, tema)
        }
        fun botao(t: String, aoClicar: () -> Unit) = texto(t, 20f, Color.WHITE, true).apply {
            setPadding(dp(28), dp(14), dp(28), dp(14))
            isFocusable = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(0x55FFFFFF) })
                addState(intArrayOf(), GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(0x1AFFFFFF) })
            }
            setOnClickListener { aoClicar() }
        }
        val cancelar = botao("Cancelar") { responder(false) }
        val reconectar = botao("Reconectar") { responder(true) }
        val caixa = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(28), dp(32), dp(24))
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(0xF01C2024.toInt()) }
            addView(texto("Pedido pelo painel", 15f, 0xCCFFFFFF.toInt()))
            addView(texto("Reconectar a TV no Wi-Fi?", 24f, Color.WHITE, true),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(texto("O roteador desconecta a TV por alguns segundos e ela volta sozinha, escolhendo de novo o melhor " +
                "ponto de acesso. Um vídeo tocando pode pausar.", 18f, 0xFFE8EEEA.toInt()),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16); bottomMargin = dp(22) })
            addView(LinearLayout(this@ConfirmarRoteadorActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(cancelar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(16) })
                addView(reconectar)
            })
        }
        setContentView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(0xCC000000.toInt())
            addView(caixa, LinearLayout.LayoutParams(dp(680), ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        cancelar.requestFocus()   // o padrão é o seguro
        ui.postDelayed({ responder(false) }, UnifiCliente.ESPERA_CONFIRMAR_MS)
    }

    private fun responder(sim: Boolean) {
        if (respondido) return
        respondido = true
        finish()
        UnifiCliente.responderReconectar(applicationContext, sim)
    }

    @Deprecated("API antiga, mas é a que existe em Activity (sem AndroidX)")
    override fun onBackPressed() = responder(false)

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        if (!respondido && isFinishing) { respondido = true; UnifiCliente.responderReconectar(applicationContext, false) }
    }
}
