package br.paulo.tvlauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Assistente de primeira abertura (05/11/2026, fila longa do brainstorm). Aparece UMA vez, na primeira HOME de quem
 * instalou o launcher sem Device Owner (instalação "comum", ver Recursos.kt). Numa TV já com Device Owner (a Sala) não
 * aparece nunca: ali a configuração já foi feita à mão.
 *
 * É uma tela só, com quatro linhas: ligar as permissões que dá para pedir na hora, abrir o serviço de teclas
 * (acessibilidade), ver a lista completa (Configurações do launcher) e começar. BACK também conta como "começar":
 * ninguém fica preso aqui.
 */
class BoasVindasActivity : Activity() {
    companion object {
        private const val CHAVE = "boas_vindas_vista"
        private const val PEDIDO = 42

        /** Mostra o assistente se for a primeira vez e o launcher não for Device Owner. Barato: uma leitura de prefs. */
        fun talvezMostrar(ctx: Context) {
            val prefs = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
            if (prefs.getBoolean(CHAVE, false)) return
            if (PrivilegioDO.somosDonos(ctx)) { prefs.edit().putBoolean(CHAVE, true).apply(); return }
            ctx.startActivity(Intent(ctx, BoasVindasActivity::class.java))
        }

        fun marcarVista(ctx: Context) =
            ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().putBoolean(CHAVE, true).apply()
    }

    private val textoClaro = 0xFFE8EEEA.toInt()
    private val textoSuave = 0xFF93A19A.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        marcarVista(this)   // já na abertura: se o app cair aqui, não reabre em laço
        montar()
    }

    override fun onRestart() { super.onRestart(); montar() }   // voltou das Configurações do Android

    @Deprecated("Activity simples, sem AndroidX")
    override fun onRequestPermissionsResult(codigo: Int, permissoes: Array<out String>, resultados: IntArray) { montar() }

    private fun montar() {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = resources.getDrawable(R.drawable.fundo, theme)
            setPadding(dp(96), dp(48), dp(96), dp(32))
        }
        raiz.addView(texto(36f, textoClaro).apply { text = getString(R.string.bv_titulo); typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) })
        raiz.addView(texto(16f, textoSuave).apply {
            text = getString(R.string.bv_texto, Recursos.resumo(this@BoasVindasActivity))
        })
        val lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(8)) }
        raiz.addView(ScrollView(this).apply { addView(lista) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(16) })

        val faltam = Recursos.perigosasFaltando(this)
        val comecar = linha(getString(R.string.bv_comecar), getString(R.string.bv_comecar_det)) { finish() }
        if (faltam.isNotEmpty()) lista.addView(linha(getString(R.string.bv_perm),
            getString(R.string.bv_perm_det)) { requestPermissions(faltam, PEDIDO) })
        if (AutorizaShizukuService.instancia == null) lista.addView(linha(getString(R.string.bv_teclas),
            getString(R.string.bv_teclas_det)) { abrir(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        lista.addView(linha("Usar com o Claude (opcional)", "Claude, ChatGPT ou Codex consultam a TV e pedem ações, de qualquer lugar. Passo a passo com QR code.") {
            abrir(Intent(this, ConectarClaudeActivity::class.java))
        })
        lista.addView(linha(getString(R.string.bv_tudo), getString(R.string.cfg_titulo)) {
            abrir(Intent(this, ConfigLauncherActivity::class.java))
        })
        lista.addView(comecar)
        setContentView(raiz)
        (lista.getChildAt(0) ?: comecar).requestFocus()
    }

    private fun abrir(i: Intent) = try { startActivity(i) } catch (e: Exception) {
        try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- peças (mesmo estilo das Configurações)

    private fun linha(titulo: String, detalhe: String, aoClicar: () -> Unit) = TextView(this).apply {
        text = "$titulo\n$detalhe"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTextColor(textoClaro)
        setPadding(dp(24), dp(12), dp(24), dp(12))
        isFocusable = true; isClickable = true
        background = vidro(false)
        setOnClickListener { aoClicar() }
        setOnFocusChangeListener { v, f -> v.background = vidro(f) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
    }

    private fun texto(sp: Float, cor: Int) = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(cor) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun vidro(focado: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(if (focado) 0x2EFFFFFF else 0x12FFFFFF)
        setStroke(dp(2), if (focado) 0xD9FFFFFF.toInt() else 0x1FFFFFFF)
    }
}
