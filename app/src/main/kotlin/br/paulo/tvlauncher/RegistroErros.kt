package br.paulo.tvlauncher

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro local de erros (05/11/2026, fila longa do brainstorm). Quando o launcher cai (exceção não tratada em
 * qualquer thread), grava a pilha num arquivo dentro da pasta do próprio app, ANTES do Android fechar o processo.
 * Assim dá para ver o que aconteceu sem PC, sem adb e sem serviço externo: a tela de Diagnóstico mostra a lista.
 *
 * Por que não basta o DropBox do Android (que o Diagnóstico já lê): ler o DropBox exige READ_LOGS, que o launcher
 * só ganha pelo shell embutido; e quem instala sem Device Owner/shell não teria nada. Este arquivo é nosso, sempre dá.
 *
 * Onde fica: filesDir/erros/erro-<data>.txt, no máximo [MAXIMO] arquivos (o mais velho sai). Nada sai da TV.
 */
object RegistroErros {
    private const val TAG = "TvLauncher.Erros"
    const val MAXIMO = 10
    private const val LIMITE_CHARS = 32_000   // uma pilha enorme (StackOverflow) não enche a pasta

    fun pasta(ctx: Context) = File(ctx.filesDir, "erros")

    /**
     * Liga o registro no processo atual. Encadeia com o tratador que já existia (o do Android, que mostra "o app parou"
     * e fecha o processo): primeiro grava, depois deixa o Android seguir como sempre. Idempotente.
     */
    fun instalar(ctx: Context) {
        val app = ctx.applicationContext
        val anterior = Thread.getDefaultUncaughtExceptionHandler()
        if (anterior is Tratador) return
        Thread.setDefaultUncaughtExceptionHandler(Tratador(app, anterior))
    }

    private class Tratador(val app: Context, val anterior: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            // try/catch amplo de propósito: um erro aqui dentro não pode esconder o erro original.
            try { gravar(app, t.name, e) } catch (_: Throwable) {}
            anterior?.uncaughtException(t, e)
        }
    }

    /** Também serve para erros tratados que vale guardar (ex.: um recurso que falhou mas não derrubou o app). */
    fun gravar(ctx: Context, thread: String, e: Throwable) {
        val versao = try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            @Suppress("DEPRECATION")
            "${pi.versionName} (${if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()})"
        } catch (_: Exception) { "?" }
        val agora = System.currentTimeMillis()
        val texto = montarTexto(agora, versao, "Android ${Build.VERSION.RELEASE} · ${Build.MODEL}", thread, e)
        val dir = pasta(ctx)
        dir.mkdirs()
        File(dir, nomeArquivo(agora)).writeText(texto)
        podar(dir, MAXIMO)
        Log.e(TAG, "erro gravado em ${dir.path}", e)
    }

    /** Texto do arquivo: cabeçalho legível e a pilha completa (com as causas). Separado para o teste unitário. */
    fun montarTexto(quando: Long, versao: String, aparelho: String, thread: String, e: Throwable): String {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val pilha = sw.toString().let { if (it.length > LIMITE_CHARS) it.take(LIMITE_CHARS) + "\n… (cortado)" else it }
        return buildString {
            append("Quando: ").append(SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US).format(Date(quando))).append('\n')
            append("Versão do launcher: ").append(versao).append('\n')
            append("Aparelho: ").append(aparelho).append('\n')
            append("Thread: ").append(thread).append('\n')
            append("Erro: ").append(resumo(e)).append("\n\n")
            append(pilha)
        }
    }

    /** "NullPointerException: mensagem" da causa mais funda (é a que costuma dizer o que houve). */
    fun resumo(e: Throwable): String {
        var raiz = e
        var voltas = 0
        while (raiz.cause != null && raiz.cause !== raiz && voltas++ < 20) raiz = raiz.cause!!
        val msg = raiz.message?.lineSequence()?.firstOrNull()?.take(160)
        return raiz.javaClass.simpleName + (if (msg.isNullOrBlank()) "" else ": $msg")
    }

    // Nome com a data em ordem (ano primeiro): ordenar pelo nome = ordenar pelo tempo. O milissegundo evita colisão.
    fun nomeArquivo(quando: Long) = "erro-" + SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(quando)) + ".txt"

    /** Apaga os mais antigos até sobrarem [maximo]. */
    fun podar(dir: File, maximo: Int) {
        val todos = dir.listFiles { f -> f.name.startsWith("erro-") && f.name.endsWith(".txt") }?.sortedBy { it.name } ?: return
        todos.dropLast(maximo).forEach { it.delete() }
    }

    class Entrada(val arquivo: File, val quando: Long, val resumo: String, val texto: String)

    /** Os erros gravados, do mais recente para o mais antigo. */
    fun listar(ctx: Context): List<Entrada> {
        val arquivos = pasta(ctx).listFiles { f -> f.name.startsWith("erro-") && f.name.endsWith(".txt") } ?: return emptyList()
        return arquivos.sortedByDescending { it.name }.mapNotNull { f ->
            try {
                val t = f.readText()
                val resumo = t.lineSequence().firstOrNull { it.startsWith("Erro: ") }?.removePrefix("Erro: ") ?: "?"
                Entrada(f, f.lastModified(), resumo, t)
            } catch (_: Exception) { null }
        }
    }

    fun apagarTodos(ctx: Context) { pasta(ctx).listFiles()?.forEach { it.delete() } }
}
