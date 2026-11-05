package br.paulo.tvlauncher

import java.net.HttpURLConnection
import java.net.URL

/**
 * Teste de velocidade da internet (download): baixa 25 MB do servidor de teste da Cloudflare e mede o tempo.
 * É a velocidade real até a internet (diferente da do Wi-Fi, que é só TV <-> roteador). Chamar fora da thread da tela.
 */
object Velocidade {
    fun medir(): String = try {
        val bytesPedidos = 25_000_000
        val conexao = URL("https://speed.cloudflare.com/__down?bytes=$bytesPedidos").openConnection() as HttpURLConnection
        conexao.connectTimeout = 8000
        conexao.readTimeout = 20000
        val inicio = System.nanoTime()
        var total = 0L
        // try/finally (item 31 da revisão): antes um erro no meio do download pulava o disconnect().
        try {
            conexao.inputStream.use { entrada ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = entrada.read(buffer)
                    if (n < 0) break
                    total += n
                }
            }
        } finally { conexao.disconnect() }
        val segundos = (System.nanoTime() - inicio) / 1e9
        val mbps = total * 8 / segundos / 1e6
        txt(R.string.vel_resultado, mbps, total / 1e6, segundos)
    } catch (e: Exception) {
        txt(R.string.vel_falhou)
    }
}
