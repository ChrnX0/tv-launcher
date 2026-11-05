package br.paulo.tvlauncher

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * A faixa de previsão da tela inicial (05/11/2026, pedido do Paulo: "as mesmas informações" da tela de clima do Google):
 * [DIAS] dias, cada um com o nome do dia, o ícone, a máxima e a mínima e a chance de chuva. Aqui fica só a lógica
 * (ler o JSON da Open-Meteo, nomes dos dias, liga/desliga), testável no computador; o desenho fica na MainActivity.
 */
object FaixaDias {
    const val DIAS = 9                      // o mesmo que a tela do Google mostra
    private const val PREFS = "launcher"
    const val CHAVE = "home_faixa_dias"

    data class Dia(val data: String, val codigo: Int, val max: Int, val min: Int, val chuva: Int)

    /** Ligada por padrão; desliga em Configurações > Tela inicial (deixa o fundo inteiro à mostra). */
    fun ligada(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CHAVE, true)
    fun definirLigada(ctx: Context, sim: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(CHAVE, sim).apply()
    }

    /**
     * Lê o bloco "daily" da Open-Meteo (pedido com weather_code, temperature_2m_max/min e precipitation_probability_max).
     * [tempAgora] é a temperatura mostrada no topo: se ela já passou da máxima/mínima de hoje, o modelo errou e a de hoje
     * se ajusta a ela (a mesma regra do topo, para os dois números baterem). Dias com dado faltando são pulados.
     * [diasChuva]: a chance de chuva por dia pela mediana de ECMWF/ICON/GFS (o ECMWF sozinho exagera aqui, ver
     * MainActivity.chuvaMediana); null = usa a do best_match que veio no [daily].
     */
    fun ler(daily: JSONObject, tempAgora: Int?, dias: Int = DIAS, diasChuva: List<Int>? = null): List<Dia> {
        val datas = daily.optJSONArray("time") ?: return emptyList()
        val codigos = daily.optJSONArray("weather_code")
        val maximas = daily.optJSONArray("temperature_2m_max") ?: return emptyList()
        val minimas = daily.optJSONArray("temperature_2m_min") ?: return emptyList()
        val chuvas = daily.optJSONArray("precipitation_probability_max")
        val lista = ArrayList<Dia>()
        for (i in 0 until minOf(dias, datas.length())) {
            val mx = maximas.optDouble(i); val mn = minimas.optDouble(i)
            if (mx.isNaN() || mn.isNaN()) continue
            var max = Math.round(mx).toInt(); var min = Math.round(mn).toInt()
            if (i == 0 && tempAgora != null) { max = maxOf(max, tempAgora); min = minOf(min, tempAgora) }
            lista += Dia(
                datas.getString(i),
                codigos?.optInt(i, 3) ?: 3,
                max, min,
                (diasChuva?.getOrNull(i) ?: chuvas?.optInt(i, 0) ?: 0).coerceIn(0, 100),
            )
        }
        return lista
    }

    /** "Hoje" no primeiro; nos outros, o dia da semana curto do idioma da TV ("sex.", "Fri"). */
    fun rotulo(dia: Dia, indice: Int, hoje: String, locale: Locale = Locale.getDefault()): String {
        if (indice == 0) return hoje
        return try {
            val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dia.data) ?: return dia.data
            SimpleDateFormat("EEE", locale).format(d)
        } catch (e: Exception) {
            dia.data
        }
    }
}
