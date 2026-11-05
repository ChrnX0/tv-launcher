package br.paulo.tvlauncher

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Backup automático (05/11/2026, fila longa do brainstorm). Uma vez por dia o launcher grava sozinho uma cópia das
 * configurações (o mesmo JSON do "Baixar backup" do painel, montado por BackupConfig.exportar) dentro da própria TV,
 * e guarda as [MANTER] últimas. O painel lista as cópias: dá para baixar uma (e restaurar em OUTRA TV pelo
 * "Restaurar" de lá) ou voltar esta TV para o estado de um dia anterior.
 *
 * Quando roda: ao abrir a HOME e no boot. Não usa alarme nem serviço: se a TV ficar dias desligada, não há o que
 * guardar mesmo. Custo: ler 3 arquivos de preferências e gravar poucos KB, numa thread separada, no máximo 1x por dia.
 * Se nada mudou desde a última cópia, não grava outra (só o "criadoEm" seria diferente).
 *
 * Onde fica: filesDir/backups/backup-AAAAMMDD.json. Some se os dados do launcher forem apagados (como tudo dele).
 */
object BackupAutomatico {
    private const val TAG = "TvLauncher.Backup"
    const val MANTER = 7
    private val NOME = Regex("""backup-\d{8}\.json""")
    private val fila = Executors.newSingleThreadExecutor()

    fun pasta(ctx: Context) = File(ctx.filesDir, "backups")

    /** Chamado da HOME e do boot: agenda a verificação numa thread e volta na hora. */
    fun talvezGravar(ctx: Context) {
        val app = ctx.applicationContext
        fila.execute { try { gravarSePreciso(app, System.currentTimeMillis()) } catch (e: Exception) { Log.w(TAG, "backup automático falhou", e) } }
    }

    /** Grava a cópia de hoje se ainda não existe. Devolve o arquivo gravado, ou null se não precisou. */
    fun gravarSePreciso(ctx: Context, agora: Long): File? {
        val dir = pasta(ctx)
        val hoje = File(dir, nomeDoDia(agora))
        if (hoje.exists()) return null
        val texto = BackupConfig.exportar(ctx)
        // Igual à última cópia (tirando a data)? Não guarda: 7 cópias iguais não serviriam para voltar no tempo.
        val ultima = listar(ctx).firstOrNull()
        if (ultima != null && semData(ultima.readText()) == semData(texto)) return null
        dir.mkdirs()
        // Grava num temporário e renomeia: uma queda de energia no meio não deixa um backup pela metade com nome válido.
        val tmp = File(dir, hoje.name + ".tmp")
        tmp.writeText(texto)
        if (!tmp.renameTo(hoje)) { tmp.delete(); return null }
        podar(dir, MANTER)
        Log.i(TAG, "backup automático gravado: ${hoje.name} (${hoje.length()} bytes)")
        return hoje
    }

    fun nomeDoDia(quando: Long) = "backup-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(quando)) + ".json"

    fun nomeValido(nome: String) = NOME.matches(nome)

    /** O JSON sem o campo "criadoEm", para comparar duas cópias pelo conteúdo. */
    fun semData(texto: String): String = texto.replace(Regex(""""criadoEm"\s*:\s*\d+,?"""), "")

    /** As cópias guardadas, da mais nova para a mais velha (o nome tem a data em ordem). */
    fun listar(ctx: Context): List<File> =
        pasta(ctx).listFiles { f -> nomeValido(f.name) }?.sortedByDescending { it.name } ?: emptyList()

    fun podar(dir: File, manter: Int) {
        dir.listFiles { f -> nomeValido(f.name) }?.sortedByDescending { it.name }?.drop(manter)?.forEach { it.delete() }
        dir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
    }

    /** Uma cópia pelo nome, só se o nome for de uma cópia (nada de "../" vindo do painel). */
    fun arquivo(ctx: Context, nome: String): File? =
        if (nomeValido(nome)) File(pasta(ctx), nome).takeIf { it.isFile } else null
}
