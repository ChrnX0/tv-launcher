package br.paulo.tvlauncher

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Painel da previsão para 7 dias: abre com OK no bloco do clima (topo da tela). Voltar fecha.
 * Mesma fonte do topo (Open-Meteo, no ponto da Localizacao). [buscar] faz a requisição (fora da thread da tela),
 * [emSegundoPlano] roda um bloco fora da tela, [descricao] traduz o código WMO em ícone + texto.
 */
class PrevisaoSemana(
    private val tela: Activity,
    private val emSegundoPlano: (() -> Unit) -> Unit,
    private val buscar: (String) -> String?,
    private val descricao: (Int) -> Pair<String, String>,
    private val tempAgora: () -> Int?,   // a medida do aeroporto mostrada no topo: corrige a max/min de HOJE se o modelo ficou para tras
) {
    private val textoClaro = 0xFFE8EEEA.toInt()
    private val textoSuave = 0xFF93A19A.toInt()
    private val azulChuva = 0xFF8EC5FF.toInt()
    private val fonteMedia = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private var janela: Dialog? = null

    fun abrir() {
        fechar()
        val lista = LinearLayout(tela).apply { orientation = LinearLayout.VERTICAL }
        val aviso = texto(16f, textoSuave).apply { text = "Carregando a previsão…" }
        lista.addView(aviso)
        val painel = LinearLayout(tela).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(26), dp(32), dp(26))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(0xFF161E1B.toInt())
                setStroke(dp(1), 0x33FFFFFF)
            }
            addView(texto(22f, Color.WHITE, fonteMedia).apply { text = "Próximos 7 dias" })
            addView(lista, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
            isFocusable = true   // o painel recebe o foco, assim Voltar fecha e as setas não vazam para a tela de trás
        }
        val raiz = FrameLayout(tela).apply {
            setOnClickListener { fechar() }
            addView(painel, FrameLayout.LayoutParams(dp(Grade.largura(8)), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        val d = Dialog(tela, android.R.style.Theme_Translucent_NoTitleBar)
        d.setContentView(raiz)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(0xB3000000.toInt()))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        d.setOnDismissListener { if (janela === d) janela = null }
        janela = d
        d.show()
        painel.requestFocus()

        emSegundoPlano {
            val linhas = carregar()
            tela.runOnUiThread {
                if (janela !== d) return@runOnUiThread
                lista.removeAllViews()
                if (linhas == null) {
                    lista.addView(texto(16f, textoSuave).apply { text = "Sem previsão agora (sem internet ou fonte fora do ar)." })
                } else {
                    linhas.forEach { lista.addView(it) }
                }
            }
        }
    }

    fun fechar() {
        janela?.dismiss()
        janela = null
    }

    /** Busca os 7 dias e monta uma linha por dia: dia da semana · ícone · descrição · máx / mín · chance de chuva. */
    private fun carregar(): List<LinearLayout>? = try {
        val ponto = Localizacao.atual(tela) { buscar(it) }
        val unidade = if (Config.USAR_FAHRENHEIT) "&temperature_unit=fahrenheit" else ""
        val j = JSONObject(buscar(
            "https://api.open-meteo.com/v1/forecast?latitude=${ponto.lat}&longitude=${ponto.lon}" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&timezone=auto&forecast_days=7$unidade"
        ) ?: throw IllegalStateException("sem resposta")).getJSONObject("daily")
        val datas = j.getJSONArray("time")
        val entrada = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val saida = SimpleDateFormat("EEE, d", Locale("pt", "BR"))
        (0 until datas.length()).map { i ->
            val dia = if (i == 0) "Hoje" else saida.format(entrada.parse(datas.getString(i))!!).replaceFirstChar { it.uppercase() }
            val (icone, texto) = descricao(j.getJSONArray("weather_code").getInt(i))
            var max = Math.round(j.getJSONArray("temperature_2m_max").getDouble(i)).toInt()
            var min = Math.round(j.getJSONArray("temperature_2m_min").getDouble(i)).toInt()
            val agora = tempAgora()
            if (i == 0 && agora != null) { max = maxOf(max, agora); min = minOf(min, agora) }   // igual ao topo
            val chuva = j.getJSONArray("precipitation_probability_max").optInt(i, -1)
            linha(dia, icone, texto, "$max°", "$min°", if (chuva >= 0) "$chuva%" else "")
        }
    } catch (e: Exception) {
        null
    }

    /** Colunas de largura fixa, para os números ficarem um embaixo do outro. */
    private fun linha(dia: String, icone: String, desc: String, max: String, min: String, chuva: String) =
        LinearLayout(tela).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, dp(7))
            addView(texto(17f, textoClaro, fonteMedia).apply { text = dia }, LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT))
            if (IconesClima.ehIcone(icone) && IconesClima.bitmap(tela, icone) != null)
                addView(IconesClima.imagem(tela, icone), LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(4) })
            else
                addView(texto(24f, Color.WHITE).apply { text = icone; gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(texto(16f, textoSuave).apply { text = desc; maxLines = 1 }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
            addView(texto(17f, Color.WHITE, fonteMedia).apply { text = max; gravity = Gravity.END }, LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(texto(17f, textoSuave).apply { text = min; gravity = Gravity.END }, LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(texto(16f, azulChuva).apply { text = if (chuva.isEmpty()) "" else "☔ $chuva"; gravity = Gravity.END }, LinearLayout.LayoutParams(dp(84), ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    private fun texto(sp: Float, cor: Int, fonte: Typeface? = null) = TextView(tela).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(cor)
        if (fonte != null) typeface = fonte
    }

    private fun dp(v: Int) = (v * tela.resources.displayMetrics.density + 0.5f).toInt()
}
