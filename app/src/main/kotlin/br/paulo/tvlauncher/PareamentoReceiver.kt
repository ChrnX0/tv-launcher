package br.paulo.tvlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import io.github.muntashirakon.adb.AdbStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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
        private const val LIMITE_MS = 20_000L   // tempo maximo de uma tentativa (conectar + `id`)
    }

    private fun anotar(ctx: Context, linha: String) {
        Log.i(TAG, linha)
        try { File(ctx.getExternalFilesDir(null), "shell_proprio.log").appendText(linha + "\n") } catch (_: Exception) {}
    }

    override fun onReceive(recebido: Context, intent: Intent) {
        // O contexto do app (e nao o do receiver) continua valido depois que onReceive retorna e a thread segue.
        val ctx = recebido.applicationContext
        anotar(ctx, "onReceive acao=${intent.action}")
        if (intent.action != ACAO) return
        val codigo = intent.getStringExtra("codigo")   // ausente = so reconectar (ja pareado)
        val porta = intent.getIntExtra("porta", -1)
        // NAO seguramos o broadcast (sem goAsync): em 04/11/2026, logo depois de um reboot, o `id` travou e o sistema
        // matou o launcher por ANR ao estourar o limite de 60 s do broadcast. Como somos a HOME, o processo fica vivo
        // sem isso, e a thread pode esperar e tentar de novo o quanto precisar.
        thread(name = "Pareamento") {
            val m = try { AdbConnectionManager.obter(ctx) } catch (e: Throwable) {
                anotar(ctx, "FALHOU ao criar o gerente: ${e.javaClass.simpleName}: ${e.message}"); return@thread
            }
            anotar(ctx, "gerente ok")
            if (codigo != null && porta > 0) {
                try { anotar(ctx, "pair(127.0.0.1:$porta) => ${m.pair("127.0.0.1", porta, codigo)}") }
                catch (e: Throwable) { anotar(ctx, "pair FALHOU: ${e.javaClass.simpleName}: ${e.message}") }
            }
            // Logo apos o boot o adbd troca de porta e a TV fica sobrecarregada (load 20 medido): tenta ate 3 vezes,
            // esperando 0 s, 15 s e 45 s antes de cada tentativa.
            for ((n, espera) in listOf(0L, 15_000L, 45_000L).withIndex()) {
                if (espera > 0) Thread.sleep(espera)
                val saida = tentarShell(ctx, m, n + 1)
                if (saida != null) {
                    anotar(ctx, "shell PROPRIO diz: $saida")
                    // MARCO S1: com o shell de pé, sobe o servidor do Shizuku embutido (ver ServidorShizuku).
                    try { ServidorShizuku.iniciar(ctx, m) } catch (e: Throwable) {
                        anotar(ctx, "ServidorShizuku FALHOU: ${e.javaClass.simpleName}: ${e.message}")
                    }
                    return@thread
                }
            }
            anotar(ctx, "desisti depois de 3 tentativas")
        }
    }

    /** Uma tentativa: conecta e roda `id`. Se travar por mais de [LIMITE_MS], fecha tudo para destravar o read(). */
    private fun tentarShell(ctx: Context, m: AdbConnectionManager, n: Int): String? {
        val fluxoRef = AtomicReference<AdbStream?>(null)
        val acabou = AtomicBoolean(false)
        // Vigia: se a tentativa nao terminar a tempo, fecha o fluxo e a conexao; isso faz o read() lancar excecao.
        val vigia = thread(name = "Pareamento-vigia") {
            try { Thread.sleep(LIMITE_MS) } catch (_: InterruptedException) { return@thread }
            if (!acabou.get()) {
                anotar(ctx, "tentativa $n: sem resposta em ${LIMITE_MS / 1000} s, fechando a conexao")
                try { fluxoRef.get()?.close() } catch (_: Exception) {}
                try { m.disconnect() } catch (_: Exception) {}
            }
        }
        return try {
            val conectou = m.isConnected || m.autoConnect(ctx, 15_000)
            anotar(ctx, "tentativa $n: autoConnect => $conectou")
            if (!conectou) return null
            val fluxo = m.openStream("shell:id").also { fluxoRef.set(it) }
            // Sinaliza fim do stdin: sem isso, o shell do adb fica esperando entrada e o read() trava.
            try { fluxo.openOutputStream().close() } catch (_: Exception) {}
            val saida = ServidorShizuku.lerAteOFim(fluxo).trim()
            try { fluxo.close() } catch (_: Exception) {}
            saida.ifEmpty { null }
        } catch (e: Throwable) {
            anotar(ctx, "tentativa $n FALHOU: ${e.javaClass.simpleName}: ${e.message}")
            Log.w(TAG, "tentativa $n falhou", e)
            // Derruba a conexão para a próxima tentativa abrir uma NOVA. Medido em 04/11/2026: depois de um vigia
            // fechar um fluxo pendurado, o gerente (singleton) continuava isConnected=true, mas todo fluxo novo
            // fechava na hora ("Stream closed.") — e as 3 tentativas falhavam em cima da mesma conexão meio morta.
            try { m.disconnect() } catch (_: Exception) {}
            null
        } finally {
            acabou.set(true)
            vigia.interrupt()
        }
    }
}
