package br.paulo.tvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
import android.widget.ImageView

/**
 * Os ícones do clima: PNGs 3D (160 px) em assets/clima3d/ (Fluent Emoji 3D, Microsoft, MIT) e as fases da lua em
 * assets/clima/ (Meteocons de Bas Milius, MIT). Cada ícone vira uma "chave" de texto que começa com [PREFIXO] ("clima:rain"); o resto do app só
 * passa essa chave adiante, e quem desenha (tela principal, previsão da semana) usa [imagem].
 */
object IconesClima {
    const val PREFIXO = "clima:"
    private val cache = HashMap<String, Bitmap?>()

    fun ehIcone(chave: String) = chave.startsWith(PREFIXO)

    /** Texto em português do código WMO ("Chuva fraca", "Tempestade"...). */
    fun texto(codigo: Int, chovendo: Boolean = false): String = when {
        chovendo && codigo < 51 -> "Chuva"
        codigo == 0 -> "Céu limpo"
        codigo == 1 -> "Poucas nuvens"
        codigo == 2 -> "Parcialmente nublado"
        codigo == 3 -> "Nublado"
        codigo == 45 || codigo == 48 -> "Neblina"
        codigo == 51 -> "Garoa fraca"
        codigo == 53 -> "Garoa"
        codigo == 55 -> "Garoa forte"
        codigo == 56 || codigo == 57 -> "Garoa congelante"
        codigo == 61 -> "Chuva fraca"
        codigo == 63 -> "Chuva"
        codigo == 65 -> "Chuva forte"
        codigo == 66 || codigo == 67 -> "Chuva congelante"
        codigo == 71 -> "Neve fraca"
        codigo == 73 -> "Neve"
        codigo == 75 -> "Neve forte"
        codigo == 77 -> "Grãos de neve"
        codigo == 80 -> "Pancadas fracas"
        codigo == 81 -> "Pancadas de chuva"
        codigo == 82 -> "Pancadas fortes"
        codigo == 85 || codigo == 86 -> "Pancadas de neve"
        codigo == 95 -> "Tempestade"
        codigo == 96 || codigo == 99 -> "Tempestade com granizo"
        else -> "—"
    }

    /**
     * Código de clima WMO (Open-Meteo) -> chave do ícone 3D (assets/clima3d/, Fluent Emoji 3D da Microsoft, licença MIT).
     * [dia] escolhe sol ou lua; [chovendo] força chuva quando o código diz só "nublado" mas o aeroporto/modelo registra chuva agora.
     */
    fun chave(codigo: Int, dia: Boolean = true, chovendo: Boolean = false): String {
        val nome = when {
            chovendo && codigo < 51 -> if (dia) "sun_behind_rain_cloud" else "cloud_with_rain"
            codigo == 0 -> if (dia) "sun" else "crescent_moon"
            codigo == 1 -> if (dia) "sun_behind_small_cloud" else "lua_nuvem_pouca"
            codigo == 2 -> if (dia) "sun_behind_cloud" else "lua_nuvem_muita"
            codigo == 3 -> "cloud"
            codigo == 45 || codigo == 48 -> "fog"
            codigo == 51 || codigo == 53 || codigo == 61 || codigo == 80 -> if (dia) "sun_behind_rain_cloud" else "cloud_with_rain"
            codigo in 55..67 || codigo == 81 || codigo == 82 -> "cloud_with_rain"
            codigo in 71..77 || codigo == 85 || codigo == 86 -> "cloud_with_snow"
            codigo == 95 -> if (chovendo) "cloud_with_lightning_and_rain" else "cloud_with_lightning"
            codigo == 96 || codigo == 99 -> "cloud_with_lightning_and_rain"
            else -> "cloud"
        }
        return PREFIXO + "3d/" + nome
    }

    /** A imagem da chave (lida do assets uma vez só). null se o arquivo faltar: quem chama cai no texto. */
    // synchronized: desde 04/11/2026 o ícone também é preparado fora da thread da tela (ver MainActivity.buscarClima).
    @Synchronized
    fun bitmap(ctx: Context, chave: String): Bitmap? = cache.getOrPut(chave) {
        try {
            val img = ctx.assets.open(caminho(chave.removePrefix(PREFIXO))).use { BitmapFactory.decodeStream(it) }
            aparar(img)
        } catch (e: Exception) { null }
    }

    /** "3d/sun" -> assets/clima3d/sun.png; o resto (fases da lua) fica em assets/clima/. */
    private fun caminho(nome: String) = if (nome.startsWith("3d/")) "clima3d/${nome.removePrefix("3d/")}.png" else "clima/$nome.png"

    /** Corta a folga transparente dos lados (esquerda e direita), para o desenho encostar na borda e alinhar com o texto. */
    private fun aparar(img: Bitmap): Bitmap {
        val w = img.width; val h = img.height
        val px = IntArray(w * h); img.getPixels(px, 0, w, 0, 0, w, h)
        var ini = w; var fim = -1
        for (x in 0 until w) for (y in 0 until h) if ((px[y * w + x] ushr 24) > 8) { if (x < ini) ini = x; if (x > fim) fim = x; break }
        return if (fim < ini) img else Bitmap.createBitmap(img, ini, 0, fim - ini + 1, h)
    }

    /** Um ImageView já com o ícone, para colocar em qualquer linha. */
    fun imagem(ctx: Context, chave: String): ImageView = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setImageBitmap(bitmap(ctx, chave))
        visibility = View.VISIBLE
    }
}
