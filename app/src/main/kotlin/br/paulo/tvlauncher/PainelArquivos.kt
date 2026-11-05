package br.paulo.tvlauncher

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

/**
 * Aba "Arquivos" do painel no navegador: a memória interna da TV (/storage/emulated/0, onde ficam Download, Movies,
 * Pictures...) para trocar arquivos entre o PC/celular e o Chromecast. Ver PainelWeb.kt.
 *
 * Acesso: um app comum só enxerga as próprias pastas. Para ver a memória interna inteira o Android exige a permissão
 * especial "acesso a todos os arquivos" (MANAGE_EXTERNAL_STORAGE), que NÃO dá para conceder pelo Device Owner, só pelo
 * shell (appops). Por isso [garantirAcesso] pede ao nosso próprio shell (GuardiaoShizuku.comando), uma vez.
 *
 * Segurança: todo caminho vem do navegador como texto relativo e passa por duas barreiras: os pedaços (sem "..", sem
 * barra, ChecagensPainel.caminhoRelativoValido) e o caminho REAL, com links resolvidos, tem de ficar dentro da raiz
 * ([resolver]). A pasta "Android" (dados dos apps) fica de fora.
 */
object PainelArquivos {
    private const val TAG = "TvLauncher.PainelArq"
    private const val MAX_ITENS = 2000            // pasta com mais que isso: mostra os 2000 primeiros e avisa
    private const val FOLGA_BYTES = 200L shl 20   // não enche a memória: sempre sobram 200 MB para o sistema

    fun raiz(): File = Environment.getExternalStorageDirectory()

    /** Erro com código HTTP, para o PainelWeb responder direto. */
    class Erro(val codigo: Int, mensagem: String) : Exception(mensagem)

    // ---------------------------------------------------------------- acesso

    fun temAcesso(): Boolean = Environment.isExternalStorageManager()

    /**
     * Sem a permissão, pede ao nosso shell (adb embutido) para conceder. Bloqueia até ~40 s na primeira vez (fora da
     * thread da tela). Devolve true se agora tem acesso.
     */
    fun garantirAcesso(ctx: Context): Boolean {
        if (temAcesso()) return true
        val r = GuardiaoShizuku.comando(ctx, "appops set --uid ${ctx.packageName} MANAGE_EXTERNAL_STORAGE allow")
        Log.i(TAG, "concedendo acesso a todos os arquivos pelo shell: ${r ?: "sem resposta do shell"}")
        return temAcesso()
    }

    // ---------------------------------------------------------------- caminhos

    /** O arquivo de verdade para o caminho relativo [rel], ou Erro 400/404. [deveExistir]=false para criar. */
    fun resolver(rel: String?, deveExistir: Boolean = true): File {
        val limpo = (rel ?: "").trim('/')
        if (!ChecagensPainel.caminhoRelativoValido(limpo)) throw Erro(400, "caminho inválido")
        val raiz = raiz().canonicalFile
        val f = if (limpo.isEmpty()) raiz else File(raiz, limpo)
        // canonicalPath resolve links simbólicos: um link dentro de Download apontando para fora não passa.
        val real = f.canonicalPath
        if (!ChecagensPainel.dentroDe(real, raiz.path)) throw Erro(400, "caminho fora da memória interna")
        if (deveExistir && !f.exists()) throw Erro(404, "não existe (talvez apagado na TV)")
        return f
    }

    private fun relativo(f: File): String = f.canonicalPath.removePrefix(raiz().canonicalPath).trim('/')

    // ---------------------------------------------------------------- listar

    fun listar(rel: String?): JSONObject {
        val pasta = resolver(rel)
        if (!pasta.isDirectory) throw Erro(400, "não é uma pasta")
        val naRaiz = relativo(pasta).isEmpty()
        val itens = (pasta.listFiles() ?: throw Erro(403, "o Android não deixou ler esta pasta"))
            .filter { !(naRaiz && it.name.lowercase() in ChecagensPainel.PASTAS_FECHADAS) }
            .filter { ChecagensPainel.nomeArquivoValido(it.name) }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
        val lista = JSONArray()
        for (f in itens.take(MAX_ITENS)) {
            lista.put(JSONObject()
                .put("nome", f.name)
                .put("pasta", f.isDirectory)
                .put("tamanho", if (f.isFile) f.length() else 0)
                .put("modificado", f.lastModified()))
        }
        return JSONObject()
            .put("caminho", relativo(pasta))
            .put("itens", lista)
            .put("cortado", itens.size > MAX_ITENS)
            .put("livre", livre())
            .put("total", raiz().totalSpace)
    }

    /** Espaço que o painel deixa usar: o livre menos a folga do sistema. */
    fun livre(): Long = maxOf(0L, raiz().usableSpace - FOLGA_BYTES)

    // ---------------------------------------------------------------- baixar

    fun paraBaixar(rel: String?): File {
        val f = resolver(rel)
        if (!f.isFile) throw Erro(400, "só dá para baixar arquivo, não pasta")
        if (!f.canRead()) throw Erro(403, "o Android não deixou ler este arquivo")
        return f
    }

    // ---------------------------------------------------------------- enviar

    /**
     * Grava [tamanho] bytes de [entrada] como [nome] na pasta [rel]. Grava primeiro num arquivo ".parcial" escondido
     * e só renomeia no fim: se a conexão cair no meio, não fica um arquivo pela metade com o nome certo.
     */
    fun receber(ctx: Context, rel: String?, nome: String?, tamanho: Long, substituir: Boolean, entrada: InputStream): File {
        if (!ChecagensPainel.nomeArquivoValido(nome)) throw Erro(400, "nome de arquivo inválido")
        val pasta = resolver(rel)
        if (!pasta.isDirectory) throw Erro(400, "destino não é uma pasta")
        val final = File(pasta, nome!!)
        if (final.exists() && (!substituir || final.isDirectory)) throw Erro(409, "já existe \"$nome\" nesta pasta")
        if (tamanho > livre()) throw Erro(507, "não cabe: faltam ${mb(tamanho - livre())} MB na TV")
        val parcial = File(pasta, ".$nome.parcial")
        try {
            FileOutputStream(parcial).use { saida ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = entrada.read(buf)
                    if (n < 0) break
                    saida.write(buf, 0, n)
                    total += n
                }
                if (total != tamanho) throw IOException("chegaram $total de $tamanho bytes")
                saida.fd.sync()   // garante no disco antes de dizer "ok"
            }
            if (final.exists() && !final.delete()) throw Erro(409, "não consegui substituir \"$nome\"")
            if (!parcial.renameTo(final)) throw Erro(500, "não consegui dar o nome final ao arquivo recebido")
        } catch (e: Exception) {
            parcial.delete()
            throw e
        }
        avisarGaleria(ctx, final)
        return final
    }

    /** Foto, vídeo e música recebidos aparecem logo nos apps de mídia da TV (sem esperar o Android varrer sozinho). */
    private fun avisarGaleria(ctx: Context, f: File) {
        try { MediaScannerConnection.scanFile(ctx, arrayOf(f.path), null, null) } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- nova pasta e apagar

    fun criarPasta(rel: String?, nome: String?) {
        if (!ChecagensPainel.nomeArquivoValido(nome)) throw Erro(400, "nome de pasta inválido")
        val pasta = resolver(rel)
        val nova = File(pasta, nome!!)
        if (nova.exists()) throw Erro(409, "já existe \"$nome\"")
        if (!nova.mkdir()) throw Erro(500, "o Android não deixou criar a pasta")
    }

    /** Apaga um arquivo ou uma pasta com tudo dentro. Não deixa apagar a raiz nem as pastas padrão do topo. */
    fun apagar(ctx: Context, rel: String?): Int {
        val f = resolver(rel)
        val r = relativo(f)
        if (r.isEmpty()) throw Erro(400, "a raiz não se apaga")
        if (!r.contains('/') && f.isDirectory && r in PASTAS_PADRAO) throw Erro(400, "a pasta \"$r\" é do sistema: apague o que tem dentro dela")
        val n = apagarTudo(f)
        if (f.exists()) throw Erro(500, "apaguei $n item(ns), mas sobrou algo que o Android não deixou apagar")
        avisarGaleria(ctx, f)
        return n
    }

    /**
     * Primeiro o conteúdo, depois a pasta. Um link simbólico é apagado como arquivo, SEM entrar nele: o walkBottomUp()
     * do Kotlin entraria e apagaria o destino, que pode estar fora da pasta escolhida.
     */
    private fun apagarTudo(f: File): Int {
        var n = 0
        if (f.isDirectory && !Files.isSymbolicLink(f.toPath())) f.listFiles()?.forEach { n += apagarTudo(it) }
        if (f.delete()) n++
        return n
    }

    /** As pastas que o Android cria e alguns apps esperam encontrar. */
    private val PASTAS_PADRAO = setOf("Alarms", "Audiobooks", "DCIM", "Documents", "Download", "Movies", "Music",
        "Notifications", "Pictures", "Podcasts", "Recordings", "Ringtones")

    private fun mb(b: Long) = (b + (1 shl 20) - 1) shr 20
}
