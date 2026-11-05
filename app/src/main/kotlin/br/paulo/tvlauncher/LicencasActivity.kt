package br.paulo.tvlauncher

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * "Licenças e créditos" (05/11/2026): mostra assets/licencas.txt numa tela rolável. Aberta pela última linha das
 * Configurações do launcher. O texto mora num arquivo (e não aqui no código) para ser o mesmo que o repositório público
 * mostra e para dar para atualizar sem mexer em Kotlin.
 */
class LicencasActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val texto = try { assets.open("licencas.txt").bufferedReader().use { it.readText() } } catch (e: Exception) { getString(R.string.lic_sem_arquivo) }
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d + 0.5f).toInt()
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = resources.getDrawable(R.drawable.fundo, theme)
            setPadding(dp(96), dp(48), dp(96), dp(32))
        }
        raiz.addView(TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
            setTextColor(0xFFE8EEEA.toInt())
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            text = getString(R.string.cfg_licencas)
        })
        val corpo = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(0xFFE8EEEA.toInt())
            setLineSpacing(0f, 1.15f)
            text = texto
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        // A ScrollView recebe o foco: as setas do controle rolam o texto, BACK fecha.
        val rolagem = ScrollView(this).apply { isFocusable = true; addView(corpo) }
        raiz.addView(rolagem, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(16) })
        setContentView(raiz)
        rolagem.requestFocus()
    }
}
