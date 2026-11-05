package br.paulo.tvlauncher

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Qualidade da conexão: sinal do Wi-Fi (dBm), velocidade do enlace (Mbps) e latência
 * (quanto tempo leva para abrir uma conexão TCP até a internet).
 * Para IPTV, sinal fraco ou latência alta costumam ser a causa de travadas.
 * Os limites de "bom / atenção / ruim" ficam no Config.kt.
 */
object Rede {
    /**
     * [nome]: nome da rede (SSID). [barras]: quantas das 4 barrinhas do ícone acendem (0 a 4), só pelo sinal.
     * [nivel]: 0 = bom, 1 = atenção, 2 = ruim; é a PIOR das três medidas (sinal, latência e velocidade do enlace)
     * e define a cor do ícone. [ms] é -1 quando não há internet.
     */
    class Resultado(val nome: String, val dbm: Int, val mbps: Int, val ms: Int, val barras: Int, val nivel: Int) {
        /** false = o Wi-Fi não está conectado a nenhuma rede (o Android devolve -127 dBm). */
        val conectado: Boolean get() = dbm in -126..-1
    }

    @Suppress("DEPRECATION")
    fun medir(contexto: Context): Resultado {
        val wifi = contexto.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wifi.connectionInfo
        val dbm = info?.rssi ?: -127
        val mbps = info?.linkSpeed ?: 0
        val ms = latenciaMs()

        val temSinal = dbm in -126..-1
        val nome = nomeDaRede(info?.ssid) ?: if (temSinal) "Wi-Fi" else txt(R.string.cfg_sec_rede)

        val nivelSinal = when {
            !temSinal -> 0
            dbm >= Config.WIFI_DBM_BOM -> 0
            dbm >= Config.WIFI_DBM_OK -> 1
            else -> 2
        }
        val nivelLatencia = when {
            ms < 0 -> 2
            ms <= Config.LATENCIA_BOA_MS -> 0
            ms <= Config.LATENCIA_OK_MS -> 1
            else -> 2
        }
        val nivelEnlace = when {
            mbps <= 0 -> 0   // o Android não informou: não pune
            mbps >= Config.LINK_BOM_MBPS -> 0
            mbps >= Config.LINK_OK_MBPS -> 1
            else -> 2
        }
        val barras = when {
            !temSinal -> 0
            dbm >= -55 -> 4
            dbm >= -65 -> 3
            dbm >= -75 -> 2
            else -> 1
        }
        val nivel = maxOf(nivelSinal, nivelLatencia, nivelEnlace)
        // Para calibrar os limites do Config.kt: mostra qual das 3 medidas definiu a cor.
        Log.i("TvLauncher.Rede", "dbm=$dbm (nivel $nivelSinal)  link=${mbps}Mbps (nivel $nivelEnlace)  latencia=${ms}ms (nivel $nivelLatencia)  -> nivel $nivel")
        return Resultado(nome, dbm, mbps, ms, barras, nivel)
    }

    /**
     * O Android devolve o nome entre aspas ("Cerberus") e, sem permissão de localização,
     * o texto "<unknown ssid>". Tiramos as aspas e tratamos o "desconhecido" como ausente.
     */
    private fun nomeDaRede(bruto: String?): String? {
        val nome = bruto?.trim('"')
        return if (nome.isNullOrBlank() || nome == "<unknown ssid>") null else nome
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
