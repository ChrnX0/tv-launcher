package br.paulo.tvlauncher

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log

/**
 * Quando cada app foi usado pela última vez (últimos 14 dias).
 * Exige a permissão "acesso ao uso", liberada uma vez por adb:
 *   adb shell appops set br.paulo.tvlauncher GET_USAGE_STATS allow
 * Sem a permissão (ou sem dados) a lista vem vazia e o launcher cai na ordem do Config.kt, sem erro.
 *
 * Tenta duas fontes: o resumo agregado e, se vier vazio, a lista de eventos "app foi para o primeiro plano".
 */
object Uso {
    private const val TAG = "TvLauncher.Uso"
    private const val QUATORZE_DIAS = 14L * 24 * 3600 * 1000

    fun ultimoUso(contexto: Context): Map<String, Long> {
        return try {
            val gerente = contexto.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val agora = System.currentTimeMillis()
            val inicio = agora - QUATORZE_DIAS

            val mapa = HashMap<String, Long>()
            val agregado = gerente.queryAndAggregateUsageStats(inicio, agora)
            for ((pacote, u) in agregado) {
                if (u.lastTimeUsed > 0L) mapa[pacote] = u.lastTimeUsed
            }
            Log.i(TAG, "agregado: ${agregado.size} pacotes, ${mapa.size} com uso")

            if (mapa.isEmpty()) {
                @Suppress("DEPRECATION")
                val primeiroPlano = UsageEvents.Event.MOVE_TO_FOREGROUND
                val eventos = gerente.queryEvents(inicio, agora)
                val e = UsageEvents.Event()
                while (eventos.hasNextEvent()) {
                    eventos.getNextEvent(e)
                    if (e.eventType == primeiroPlano && e.timeStamp > (mapa[e.packageName] ?: 0L)) {
                        mapa[e.packageName] = e.timeStamp
                    }
                }
                Log.i(TAG, "eventos: ${mapa.size} pacotes com uso")
            }
            mapa
        } catch (e: Exception) {
            Log.w(TAG, "falhou: $e")
            emptyMap()
        }
    }
}
