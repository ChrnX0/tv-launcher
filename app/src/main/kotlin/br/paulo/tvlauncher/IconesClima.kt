package br.paulo.tvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
import android.widget.ImageView

/**
 * Os ícones do clima: imagens PNG (192 px) em assets/clima/, do conjunto Meteocons de Bas Milius (licença MIT),
 * estilo "fill". Cada ícone vira uma "chave" de texto que começa com [PREFIXO] ("clima:rain"); o resto do app só
 * passa essa chave adiante, e quem desenha (tela principal, previsão da semana) usa [imagem].
 */
object IconesClima {
    const val PREFIXO = "clima:"
    private val cache = HashMap<String, Bitmap?>()

    fun ehIcone(chave: String) = chave.startsWith(PREFIXO)

    /**
     * Código de clima WMO (Open-Meteo) -> chave do ícone. [dia] escolhe sol ou lua; [chovendo] força chuva quando
     * o código diz só "nublado" mas o aeroporto/modelo registra chuva agora.
     */
    fun chave(codigo: Int, dia: Boolean = true, chovendo: Boolean = false): String {
        val d = if (dia) "day" else "night"
        val nome = when {
            chovendo && codigo < 51 -> "overcast-rain"
            codigo == 0 -> "clear-$d"
            codigo == 1 -> "mostly-clear-$d"
            codigo == 2 -> "partly-cloudy-$d"
            codigo == 3 -> "overcast-$d"
            codigo == 45 || codigo == 48 -> "fog-$d"
            codigo == 51 || codigo == 53 -> "drizzle"
            codigo == 55 -> "overcast-drizzle"
            codigo == 56 || codigo == 57 -> "sleet"
            codigo == 61 -> "partly-cloudy-$d-rain"
            codigo == 63 -> "rain"
            codigo == 65 -> "extreme-rain"
            codigo == 66 -> "sleet"
            codigo == 67 -> "extreme-sleet"
            codigo == 71 -> "partly-cloudy-$d-snow"
            codigo == 73 -> "snow"
            codigo == 75 || codigo == 77 -> "extreme-snow"
            codigo == 80 -> "partly-cloudy-$d-rain"
            codigo == 81 -> "rain"
            codigo == 82 -> "extreme-rain"
            codigo == 85 -> "partly-cloudy-$d-snow"
            codigo == 86 -> "extreme-snow"
            codigo == 95 -> if (chovendo) "thunderstorms-rain" else "thunderstorms-$d"
            codigo == 96 -> "thunderstorms-hail"
            codigo == 99 -> "thunderstorms-extreme-hail"
            else -> "not-available"
        }
        return PREFIXO + nome
    }

    /** A imagem da chave (lida do assets uma vez só). null se o arquivo faltar: quem chama cai no texto. */
    fun bitmap(ctx: Context, chave: String): Bitmap? = cache.getOrPut(chave) {
        try {
            ctx.assets.open("clima/" + chave.removePrefix(PREFIXO) + ".png").use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) { null }
    }

    /** Um ImageView já com o ícone, para colocar em qualquer linha. */
    fun imagem(ctx: Context, chave: String): ImageView = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setImageBitmap(bitmap(ctx, chave))
        visibility = View.VISIBLE
    }
}
