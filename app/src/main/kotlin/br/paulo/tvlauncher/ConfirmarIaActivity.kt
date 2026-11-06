package br.paulo.tvlauncher

import android.app.Activity
import android.content.Intent
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
 * A confirmação NA TV de um pedido de ações do catálogo (ComandosIa; hoje pelo adb, depois pelo MCP). Nada roda sem
 * alguém apertar OK aqui, no controle de verdade.
 *
 * O foco começa em "Cancelar" (o seguro), ◀ ▶ trocam, OK escolhe. Voltar = cancelar. Sem resposta em 60 s, a tela
 * fecha e o pedido é cancelado. Mesmo jeito da PedidoShizukuActivity.
 */
class ConfirmarIaActivity : Activity() {
    companion object { const val EXTRA_ID = "pedido_ia" }

    private var id: String? = null
    private var respondido = false
    private val ui = Handler(Looper.getMainLooper())

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        montar(intent)
    }

    // Um pedido novo enquanto esta tela está aberta: troca o conteúdo (o anterior já foi cancelado em PedidoIa).
    override fun onNewIntent(novo: Intent) {
        super.onNewIntent(novo)
        respondido = false
        ui.removeCallbacksAndMessages(null)
        montar(novo)
    }

    private fun montar(i: Intent) {
        id = i.getStringExtra(EXTRA_ID)
        val p = PedidoIa.estado(id)
        val acoes = p?.plano?.acoes.orEmpty()
        if (p == null || p.situacao != PedidoIa.Situacao.ESPERANDO || acoes.isEmpty()) { respondido = true; finish(); return }

        val tema = Tema.atual(this)
        fun texto(t: String, tamanho: Float, cor: Int, media: Boolean = false) = TextView(this).apply {
            text = t; setTextColor(cor); textSize = tamanho; gravity = Gravity.CENTER
            typeface = if (media) Fontes.uiMedia(this@ConfirmarIaActivity, tema) else Fontes.ui(this@ConfirmarIaActivity, tema)
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
        val executar = botao("Executar") { responder(true) }

        val caixa = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(28), dp(32), dp(24))
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(0xF01C2024.toInt()) }
            addView(texto("Pedido pelo painel (${p.origem})", 15f, 0xCCFFFFFF.toInt()))
            addView(texto("“${p.frase}”", 22f, Color.WHITE, true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(texto("A IA quer:\n" + acoes.joinToString("\n") { "• ${it.descricao}" }, 19f, 0xFFE8EEEA.toInt()).apply {
                gravity = Gravity.START
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16); bottomMargin = dp(22) })
            addView(LinearLayout(this@ConfirmarIaActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(cancelar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(16) })
                addView(executar)
            })
        }
        setContentView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(0xCC000000.toInt())
            addView(caixa, LinearLayout.LayoutParams(dp(680), ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        cancelar.requestFocus()   // o padrão é o seguro
        // Fecha junto com o prazo do PedidoIa (que marca EXPIRADO): um OK atrasado não executa nada velho.
        ui.postDelayed({ if (!respondido) { respondido = true; finish() } }, PedidoIa.ESPERA_CONFIRMAR_MS)
    }

    private fun responder(sim: Boolean) {
        if (respondido) return
        respondido = true
        val meu = id
        // Fecha ANTES de executar: assim o app aberto pela ação fica por cima, e não esta tela.
        finish()
        // Handler NOVO, não o `ui`: o onDestroy limpa o `ui`, e a resposta não pode sumir junto.
        val app = applicationContext
        if (meu != null) Handler(Looper.getMainLooper()).post { PedidoIa.responder(app, meu, sim) }
    }

    @Deprecated("API antiga, mas é a que existe em Activity (sem AndroidX)")
    override fun onBackPressed() = responder(false)

    override fun onDestroy() {
        super.onDestroy()
        if (!respondido && isFinishing) id?.let { PedidoIa.responder(applicationContext, it, false) }
        ui.removeCallbacksAndMessages(null)
    }
}
