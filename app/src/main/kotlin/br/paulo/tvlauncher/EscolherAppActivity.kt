package br.paulo.tvlauncher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * A lista que aparece ao segurar 3 s um botão de app do controle (ver BotoesControle).
 * OK num app: salva para aquele botão e já abre o app. Voltar: sai sem mudar nada.
 */
class EscolherAppActivity : Activity() {
    companion object { const val EXTRA_TECLA = "tecla" }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        val botao = BotoesControle.botao(intent.getIntExtra(EXTRA_TECLA, -1)) ?: return finish()
        val tema = Tema.atual(this)
        val atual = BotoesControle.appDo(this, botao)

        // Apps que aparecem no menu de apps da TV (os mesmos que o launcher mostra), em ordem alfabética.
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }

        val lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var focoInicial: View? = null
        for (app in apps) {
            val pacote = app.activityInfo.packageName
            val linha = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
                isFocusable = true
                // Fundo: transparente parado, claro com foco (o "selector" troca sozinho conforme o estado).
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused),
                        GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0x33FFFFFF) })
                    addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
                }
                addView(ImageView(this@EscolherAppActivity).apply { setImageDrawable(app.loadIcon(pm)) },
                    LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(16) })
                addView(TextView(this@EscolherAppActivity).apply {
                    text = app.loadLabel(pm).toString() + if (pacote == atual) getString(R.string.esc_atual) else ""
                    setTextColor(Color.WHITE)
                    textSize = 18f
                    typeface = Fontes.ui(this@EscolherAppActivity, tema)
                })
                setOnClickListener {
                    BotoesControle.salvar(this@EscolherAppActivity, botao, pacote)
                    BotoesControle.abrirApp(this@EscolherAppActivity, pacote)
                    finish()
                }
            }
            if (pacote == atual) focoInicial = linha
            lista.addView(linha, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val painel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(0xF01C2024.toInt()) }
            addView(TextView(this@EscolherAppActivity).apply {
                text = getString(R.string.esc_botao, botao.nome)
                setTextColor(Color.WHITE)
                textSize = 24f
                typeface = Fontes.uiMedia(this@EscolherAppActivity, tema)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
            addView(ScrollView(this@EscolherAppActivity).apply { addView(lista) })
        }

        setContentView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(0xCC000000.toInt())
            addView(painel, LinearLayout.LayoutParams(dp(520), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(40); bottomMargin = dp(40)
            })
        })
        (focoInicial ?: lista.getChildAt(0))?.requestFocus()
    }
}
