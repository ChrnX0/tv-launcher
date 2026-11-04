package br.paulo.tvlauncher

import android.content.Context
import android.util.Log
import io.github.muntashirakon.adb.AdbStream
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Servidor do Shizuku EMBUTIDO no launcher (MARCO S1, 04/11/2026).
 *
 * Em vez de instalar o app Shizuku, o launcher sobe ele mesmo o processo `shizuku_server` como uid 2000 (shell), pelo
 * shell próprio (cliente ADB embutido, ver [AdbConnectionManager]). O código do servidor está DENTRO do APK do
 * launcher (módulos em shizuku/, fork thedjchi), por isso o comando aponta o `app_process` para o nosso próprio APK.
 *
 * Como o servidor descobre quem é o "gerente" (o app que manda nele): pela variável de ambiente CLASSPATH, pegando o
 * nome da pasta do APK antes do '-' (/data/app/~~xxx/br.paulo.tvlauncher-yyy/base.apk -> br.paulo.tvlauncher). Depois
 * ele entrega o binder ao gerente pelo provider [ProvedorShizuku] e `Shizuku.pingBinder()` passa a dar true aqui.
 *
 * O comando é o mesmo que o starter nativo do Shizuku monta (starter.cpp): app_process com --nice-name=shizuku_server,
 * em uma sessão nova (setsid), no diretório /, sem entrada nem saída — assim ele sobrevive ao fechamento do shell adb.
 */
object ServidorShizuku {
    const val TAG = "TvLauncher.Shizuku"
    private const val NOME_PROCESSO = "shizuku_server"
    private const val CLASSE = "rikka.shizuku.server.ShizukuService"

    fun anotar(ctx: Context, linha: String) {
        Log.i(TAG, linha)
        try { File(ctx.getExternalFilesDir(null), "shell_proprio.log").appendText("[shizuku] $linha\n") } catch (_: Exception) {}
    }

    /** Monta o comando de partida do servidor, apontando para o APK instalado do próprio launcher. */
    fun comando(ctx: Context): String {
        val ai = ctx.applicationInfo
        val apk = ai.sourceDir                 // .../br.paulo.tvlauncher-XXXX/base.apk
        val libs = ai.nativeLibraryDir         // .../br.paulo.tvlauncher-XXXX/lib/arm
        // O redirecionamento fica no GRUPO inteiro "( ... )", não só no app_process: senão o subshell em segundo plano
        // segura a saída do adb aberta e o comando só "termina" quando o servidor morre (medido: travava 20 s).
        return "(cd / && CLASSPATH=$apk exec setsid /system/bin/app_process -Djava.class.path=$apk " +
            "-Dshizuku.library.path=$libs /system/bin --nice-name=$NOME_PROCESSO $CLASSE) </dev/null >/dev/null 2>&1 &"
    }

    /**
     * Garante o servidor de pé: se `pidof shizuku_server` vier vazio, sobe o processo. Depois espera o binder chegar
     * no launcher (até 10 s) e registra o resultado. Roda na thread de quem chama (não chamar na main thread).
     * Devolve true se no fim o launcher tem um binder vivo do Shizuku.
     */
    fun iniciar(ctx: Context, m: AdbConnectionManager): Boolean {
        val pid = executar(ctx, m, "pidof $NOME_PROCESSO")
        if (pid.isNullOrBlank()) {
            anotar(ctx, "servidor parado; subindo: ${comando(ctx)}")
            // O "&" no fim faz o shell devolver na hora; o processo continua sozinho (setsid).
            // "sleep 2" antes de sair: se o shell do adb terminar na hora, a sessão fecha e leva junto o subshell em
            // segundo plano ANTES de ele chegar no setsid (medido: com "& echo" direto o servidor nem chegava a nascer).
            executar(ctx, m, "${comando(ctx)} sleep 2; echo disparado")?.let { anotar(ctx, "partida: $it") }
            Thread.sleep(3000)
            anotar(ctx, "pidof depois da partida: ${executar(ctx, m, "pidof $NOME_PROCESSO") ?: "(vazio)"}")
        } else {
            anotar(ctx, "servidor ja rodando (pid $pid)")
        }
        // O binder chega pelo ProvedorShizuku, em outra thread; aqui só esperamos ele aparecer.
        repeat(20) {
            if (Shizuku.pingBinder()) {
                anotar(ctx, "pingBinder=true (uid do servidor=${Shizuku.getUid()}, versao=${Shizuku.getVersion()})")
                return true
            }
            Thread.sleep(500)
        }
        anotar(ctx, "pingBinder=false depois de 10 s")
        return false
    }

    /**
     * Lê tudo o que o comando escreveu. A libadb-android (3.1.1) lança IOException("Stream closed.") quando o adbd fecha
     * o fluxo SEM ter mandado nada (ex.: `pidof` de um processo que não existe) — isso é fim de saída, não erro.
     * Medido em 04/11/2026: `pidof shizuku_server` vazio dava "Stream closed." com readText().
     */
    fun lerAteOFim(fluxo: AdbStream): String {
        val ent = fluxo.openInputStream()
        val buf = java.io.ByteArrayOutputStream()
        val b = ByteArray(4096)
        while (true) {
            val n = try { ent.read(b) } catch (e: java.io.IOException) {
                if (fluxo.isClosed) -1 else throw e
            }
            if (n < 0) break
            buf.write(b, 0, n)
        }
        return buf.toString(Charsets.UTF_8.name())
    }

    /**
     * Roda um comando pelo shell próprio e devolve a saída (sem espaços nas pontas), ou null se falhar. Um vigia fecha
     * o fluxo se passar de [limiteMs], para um read() pendurado não travar a thread para sempre.
     */
    fun executar(ctx: Context, m: AdbConnectionManager, cmd: String, limiteMs: Long = 20_000): String? {
        val fluxoRef = AtomicReference<AdbStream?>(null)
        val acabou = AtomicBoolean(false)
        val vigia = thread(name = "Shizuku-vigia") {
            try { Thread.sleep(limiteMs) } catch (_: InterruptedException) { return@thread }
            if (!acabou.get()) {
                anotar(ctx, "sem resposta em ${limiteMs / 1000} s para: $cmd")
                try { fluxoRef.get()?.close() } catch (_: Exception) {}
            }
        }
        return try {
            if (!m.isConnected && !m.autoConnect(ctx, 15_000)) return null
            val fluxo = m.openStream("shell:$cmd").also { fluxoRef.set(it) }
            try { fluxo.openOutputStream().close() } catch (_: Exception) {}   // fim do stdin
            val saida = lerAteOFim(fluxo).trim()
            try { fluxo.close() } catch (_: Exception) {}
            saida
        } catch (e: Throwable) {
            anotar(ctx, "falhou '$cmd': ${e.javaClass.simpleName}: ${e.message}")
            try { m.disconnect() } catch (_: Exception) {}   // a próxima chamada reconecta (ver PareamentoReceiver)
            null
        } finally {
            acabou.set(true)
            vigia.interrupt()
        }
    }
}
