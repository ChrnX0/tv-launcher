package br.paulo.tvlauncher

import java.util.Calendar
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Sol no céu, calculado no aparelho (fórmulas simplificadas do NOAA / Almanaque Náutico, erro de ~1°).
 * Usado pelos fundos "Céu ao vivo" e "Astrolábio": cor do céu, posição do sol, nascer e pôr.
 */
object Astronomia {
    private const val RAD = PI / 180

    /** Altura do sol acima do horizonte, em graus (negativa = abaixo), no instante [ms] e no lugar [lat]/[lon]. */
    fun alturaSol(ms: Long, lat: Double, lon: Double): Double {
        val d = ms / 86_400_000.0 + 2440587.5 - 2451545.0              // dias desde 1/1/2000 12h UTC (J2000)
        val g = (357.529 + 0.98560028 * d) * RAD                         // anomalia média
        val q = 280.459 + 0.98564736 * d                                 // longitude média
        val l = (q + 1.915 * sin(g) + 0.020 * sin(2 * g)) * RAD          // longitude eclíptica
        val e = (23.439 - 0.00000036 * d) * RAD                          // inclinação do eixo da Terra
        val ra = atan2(cos(e) * sin(l), cos(l))                          // ascensão reta
        val dec = asin(sin(e) * sin(l))                                  // declinação
        val gmst = (18.697374558 + 24.06570982441908 * d) % 24          // hora sideral de Greenwich
        val h = (gmst * 15 + lon) * RAD - ra                             // ângulo horário
        return asin(sin(lat * RAD) * sin(dec) + cos(lat * RAD) * cos(dec) * cos(h)) / RAD
    }

    /**
     * Nascer e pôr do sol do dia de [ms] (horário local), em ms. Procura de 2 em 2 minutos quando a altura cruza
     * -0,833° (o disco inteiro + a refração da atmosfera). null onde não cruza (perto dos polos).
     */
    fun nascerEPor(ms: Long, lat: Double, lon: Double): Pair<Long, Long>? {
        val c = Calendar.getInstance().apply {
            timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val meiaNoite = c.timeInMillis
        val passo = 2 * 60_000L
        var nascer: Long? = null
        var por: Long? = null
        var anterior = alturaSol(meiaNoite, lat, lon)
        var t = meiaNoite + passo
        while (t <= meiaNoite + 86_400_000L) {
            val alt = alturaSol(t, lat, lon)
            if (anterior < -0.833 && alt >= -0.833 && nascer == null) nascer = t
            if (anterior >= -0.833 && alt < -0.833) por = t
            anterior = alt
            t += passo
        }
        return if (nascer != null && por != null) nascer to por else null
    }
}
