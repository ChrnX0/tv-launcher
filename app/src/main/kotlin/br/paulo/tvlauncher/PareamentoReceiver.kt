package br.paulo.tvlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import kotlin.concurrent.thread

/**
 * Teste do MARCO 2 do shell próprio. Dispara por adb; o código do pareamento aparece na TV (precisa de alguém lá) só na
 * PRIMEIRA vez. Depois de pareado, a nossa chave fica confiável e basta reconectar (dispare SEM --es codigo):
 *
 *   1ª vez:  am broadcast -n .../.PareamentoReceiver -a br.paulo.tvlauncher.PAREAR --es codigo 123456 --ei porta 37123
 *   depois:  am broadcast -n .../.PareamentoReceiver -a br.paulo.tvlauncher.PAREAR
 *
 * Conecta no adbd pelo NOSSO app e roda `id` para provar que temos shell, sem o Shizuku. Grava em files/shell_proprio.log.
 */
class PareamentoReceiver : BroadcastReceiver() {
    companion object {
        const val ACAO = "br.paulo.tvlauncher.PAREAR"
        private const val TAG = "TvLauncher.ShellProprio"
    }

    private fun anotar(ctx: Context, linha: String) {
        Log.i(TAG, linha)
        try { File(ctx.getExternalFilesDir(null), "shell_proprio.log").appendText(linha + "\n") } catch (_: Exception) {}
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        anotar(ctx, "onReceive acao=${intent.action}")
        if (intent.action != ACAO) return
        val codigo = intent.getStringExtra("codigo")   // ausente = so reconectar (ja pareado)
        val porta = intent.getIntExtra("porta", -1)
        val pend = goAsync()   // segura o broadcast vivo enquanto a thread trabalha (background nao e morto no meio)
        thread(name = "Pareamento") {
            try {
                val m = AdbConnectionManager.obter(ctx)
                anotar(ctx, "gerente ok")
                if (codigo != null && porta > 0) {
                    val pareou = m.pair("127.0.0.1", porta, codigo)
                    anotar(ctx, "pair(127.0.0.1:$porta) => $pareou")
                }
                val conectou = m.autoConnect(ctx, 15_000)
                anotar(ctx, "autoConnect => $conectou")
                val fluxo = m.openStream("shell:id")
                // Sinaliza fim do stdin: sem isso, o shell do adb fica esperando entrada e o read() trava.
                try { fluxo.openOutputStream().close() } catch (_: Exception) {}
                val saida = fluxo.openInputStream().bufferedReader().readText()
                anotar(ctx, "shell PROPRIO diz: ${saida.trim()}")
                fluxo.close()
            } catch (e: Throwable) {
                anotar(ctx, "FALHOU: ${e.javaClass.simpleName}: ${e.message}")
                Log.e(TAG, "pareamento/conexao falhou", e)
            } finally {
                pend.finish()
            }
        }
    }
}
