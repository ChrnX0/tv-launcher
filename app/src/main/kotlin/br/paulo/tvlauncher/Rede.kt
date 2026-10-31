package br.paulo.tvlauncher

import android.content.Context
import android.net.wifi.WifiManager
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Qualidade da conexão: sinal do Wi-Fi (dBm), velocidade do enlace (Mbps) e latência
 * (quanto tempo leva para abrir uma conexão TCP até a internet).
 * Para IPTV, sinal fraco ou latência alta costumam ser a causa de travadas.
 */
object Rede {
    /** nivel: 0 = bom, 1 = atenção, 2 = ruim. Define a cor do texto na tela. */
    class Resultado(val texto: String, val nivel: Int)

    @Suppress("DEPRECATION")
    fun medir(contexto: Context): Resultado {
        val wifi = contexto.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wifi.connectionInfo
        val dbm = info?.rssi ?: -127
        val mbps = info?.linkSpeed ?: 0
        val ms = latenciaMs()

        val temSinal = dbm in -126..-1
        val partes = ArrayList<String>()
        partes += if (temSinal) "Wi-Fi $dbm dBm" else "Rede"
        if (mbps > 0) partes += "$mbps Mbps"
        partes += if (ms >= 0) "$ms ms" else "sem internet"

        val nivelSinal = when {
            !temSinal -> 0
            dbm >= -60 -> 0
            dbm >= -70 -> 1
            else -> 2
        }
        val nivelLatencia = when {
            ms < 0 -> 2
            ms <= 60 -> 0
            ms <= 120 -> 1
            else -> 2
        }
        return Resultado(partes.joinToString("  ·  "), maxOf(nivelSinal, nivelLatencia))
    }

    /** Menor tempo de 3 tentativas de conexão ao DNS da Cloudflare (1.1.1.1), em milissegundos. -1 se falhar. */
    private fun latenciaMs(): Int {
        var melhor = Int.MAX_VALUE
        repeat(3) {
            val inicio = System.nanoTime()
            try {
                Socket().use { it.connect(InetSocketAddress("1.1.1.1", 443), 2000) }
                melhor = minOf(melhor, ((System.nanoTime() - inicio) / 1_000_000).toInt())
            } catch (e: Exception) {
                // tentativa perdida
            }
        }
        return if (melhor == Int.MAX_VALUE) -1 else melhor
    }
}
