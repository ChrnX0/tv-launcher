package br.paulo.tvlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.concurrent.thread

/**
 * Teste do MARCO 2 do shell próprio, disparado por adb (o código do pareamento aparece na TV, então precisa de alguém lá):
 *
 *   adb shell am broadcast -n br.paulo.tvlauncher/.PareamentoReceiver -a br.paulo.tvlauncher.PAREAR --es codigo 123456 --ei porta 37123
 *
 * Fluxo: pareia na porta de pareamento (localhost) com o código, conecta no adbd e roda `id` para provar que temos shell
 * pelo NOSSO app, sem o Shizuku. Depois do pareamento, a nossa chave fica confiável e os próximos connect() dispensam código.
 */
class PareamentoReceiver : BroadcastReceiver() {
    companion object {
        const val ACAO = "br.paulo.tvlauncher.PAREAR"
        private const val TAG = "TvLauncher.ShellProprio"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ACAO) return
        val codigo = intent.getStringExtra("codigo") ?: run { Log.w(TAG, "sem codigo"); return }
        val porta = intent.getIntExtra("porta", -1)
        thread(name = "Pareamento") {
            try {
                val m = AdbConnectionManager.obter(ctx)
                if (porta > 0) {
                    val pareou = m.pair("127.0.0.1", porta, codigo)
                    Log.i(TAG, "pair(127.0.0.1:$porta) => $pareou")
                }
                val conectou = m.autoConnect(ctx, 15_000)
                Log.i(TAG, "autoConnect => $conectou")
                val fluxo = m.openStream("shell:id")
                val saida = fluxo.openInputStream().bufferedReader().readText()
                Log.i(TAG, "shell PROPRIO diz: ${saida.trim()}")
                fluxo.close()
            } catch (e: Throwable) {
                Log.e(TAG, "pareamento/conexao falhou", e)
            }
        }
    }
}
