package br.paulo.tvlauncher

import android.content.Context

/**
 * O lugar do clima escolhido à mão (05/11/2026). Sem ele, vale a localização automática (Wi-Fi do Android, IP, Config.kt;
 * ver Localizacao.kt), que pode cair num bairro vizinho: foi o que o Gemini mostrou ("Jardim America" em vez do Centro).
 *
 * Guardado nas preferências "launcher" (entra no backup do painel) e só na TV: nunca vai para o git nem para o APK.
 * Define-se pelo painel (Controle > Clima) ou pelo adb:
 *   am broadcast -a br.paulo.tvlauncher.LOCAL_CLIMA --es nome "Higienópolis" --es coordenadas "-21.18,-47.81" -n br.paulo.tvlauncher/.ControleShizukuAdbReceiver
 *   (sem extras = volta para o automático)
 */
object LocalClima {
    private const val PREFS = "launcher"
    const val CHAVE_NOME = "clima_local_nome"
    const val CHAVE_LAT = "clima_local_lat"
    const val CHAVE_LON = "clima_local_lon"
    const val MAX_NOME = 40

    class Lugar(val nome: String, val lat: Double, val lon: Double)

    /** O lugar fixo, ou null quando o clima segue a localização automática. */
    fun ler(ctx: Context): Lugar? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Guardado como texto: Float perderia casas decimais (e o SharedPreferences não tem Double).
        val lat = p.getString(CHAVE_LAT, null)?.toDoubleOrNull() ?: return null
        val lon = p.getString(CHAVE_LON, null)?.toDoubleOrNull() ?: return null
        if (!coordenadaValida(lat, lon)) return null
        return Lugar(p.getString(CHAVE_NOME, null).orEmpty(), lat, lon)
    }

    fun gravar(ctx: Context, lugar: Lugar) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(CHAVE_NOME, lugar.nome).putString(CHAVE_LAT, lugar.lat.toString()).putString(CHAVE_LON, lugar.lon.toString())
            .apply()
    }

    fun limpar(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(CHAVE_NOME).remove(CHAVE_LAT).remove(CHAVE_LON).apply()
    }

    fun coordenadaValida(lat: Double, lon: Double) =
        !lat.isNaN() && !lon.isNaN() && lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)

    /**
     * Lê o que se cola do Google Maps (clique no mapa > copiar): "-21.1795, -47.8105". Aceita também ponto-e-vírgula ou
     * espaço entre os dois números e vírgula decimal com ponto-e-vírgula ("-21,1795; -47,8105"). Null se não entender.
     */
    fun lerCoordenadas(texto: String): Pair<Double, Double>? {
        val t = texto.trim().removePrefix("(").removeSuffix(")").trim()
        if (t.isEmpty() || t.length > 60) return null
        val partes = when {
            ';' in t -> t.split(';').map { it.trim().replace(',', '.') }
            ',' in t -> t.split(',').map { it.trim() }
            else -> t.split(Regex("\\s+"))
        }.filter { it.isNotEmpty() }
        if (partes.size != 2) return null
        val lat = partes[0].toDoubleOrNull() ?: return null
        val lon = partes[1].toDoubleOrNull() ?: return null
        return if (coordenadaValida(lat, lon)) lat to lon else null
    }

    /** Nome limpo (uma linha, sem caracteres de controle, até [MAX_NOME]); vazio vira "Local escolhido". */
    fun limparNome(nome: String?): String {
        val n = nome.orEmpty().filter { !it.isISOControl() }.trim().take(MAX_NOME)
        return n.ifEmpty { "Local escolhido" }
    }
}
