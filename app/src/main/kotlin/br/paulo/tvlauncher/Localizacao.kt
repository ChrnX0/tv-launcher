package br.paulo.tvlauncher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.util.Log
import org.json.JSONObject

/**
 * Onde a TV está, do jeito mais preciso disponível (nesta ordem):
 *  1. A localização do próprio Android: o Google compara as redes Wi-Fi que a TV enxerga com um mapa delas.
 *     Na Sala, em 31/10/2026, deu ±57 m. Precisa de ACCESS_FINE_LOCATION (dada uma vez por adb).
 *  2. Pelo IP da internet (ipwho.is): acerta a cidade, mas pode cair no centro dela ou na central do provedor.
 *  3. As coordenadas fixas do Config.kt.
 * Chamar fora da thread da tela (o passo 2 usa a rede).
 */
object Localizacao {
    /** [fonte] diz de onde veio o ponto (aparece no log TvLauncher.Local). */
    class Ponto(val lat: Double, val lon: Double, val fonte: String)

    @Volatile private var guardadoIp: Ponto? = null   // o IP quase nunca muda: busca uma vez por execução

    fun atual(ctx: Context, httpGet: (String) -> String?): Ponto {
        val ponto = doAparelho(ctx) ?: porIp(httpGet) ?: Ponto(Config.LATITUDE, Config.LONGITUDE, "Config.kt")
        Log.i("TvLauncher.Local", "${ponto.lat},${ponto.lon} (${ponto.fonte})")
        return ponto
    }

    private fun doAparelho(ctx: Context): Ponto? {
        val fina = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val grossa = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fina && !grossa) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val semana = 7L * 24 * 3600 * 1000
        // "fused" junta todas as fontes; "network" é a do Wi-Fi; "passive" é a última que qualquer app recebeu.
        val melhor: Location = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { provedor -> try { lm.getLastKnownLocation(provedor) } catch (e: Exception) { null } }
            .filter { it.accuracy in 0f..2000f && System.currentTimeMillis() - it.time < semana }
            .minByOrNull { it.accuracy } ?: return null
        return Ponto(melhor.latitude, melhor.longitude, "Wi-Fi do Android, ±${melhor.accuracy.toInt()} m")
    }

    private fun porIp(httpGet: (String) -> String?): Ponto? {
        guardadoIp?.let { return it }
        return try {
            val j = JSONObject(httpGet("https://ipwho.is/") ?: return null)
            if (!j.optBoolean("success")) return null
            Ponto(j.getDouble("latitude"), j.getDouble("longitude"), "IP (${j.optString("city")})").also { guardadoIp = it }
        } catch (e: Exception) {
            null
        }
    }

    /** Distância aproximada em km entre dois pontos (boa para escolher a estação mais perto). */
    fun km(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val x = Math.toRadians(lon2 - lon1) * Math.cos(Math.toRadians((lat1 + lat2) / 2))
        val y = Math.toRadians(lat2 - lat1)
        return Math.sqrt(x * x + y * y) * 6371.0
    }
}
