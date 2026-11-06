package br.paulo.tvlauncher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.PowerManager
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Atualização automática pelo GitHub Releases (05/11/2026).
 *
 * O GitHub publica um Release a cada push na main (.github/workflows/release.yml), com o APK e o SHA-256 dele.
 * O launcher, a cada [INTERVALO_MS], pergunta qual é o último Release e, se o versionCode for maior que o instalado:
 *  1. baixa o APK e o .sha256 e confere um contra o outro (download corrompido = não instala);
 *  2. confere que o APK é do MESMO pacote e assinado com a MESMA chave do launcher instalado. Isso é o que protege a
 *     TV: o Android já recusaria chave diferente, mas assim nem tentamos. Na TV da Sala do Paulo (chave do Dell,
 *     opção A de 04/11/2026) o Release público é SEMPRE recusado aqui, de propósito;
 *  3. instala sozinho pelo PackageInstaller. Como o launcher é Device Owner, o Android instala sem perguntar.
 *     Só instala com a tela APAGADA, para não reiniciar o launcher na frente de quem está usando a TV.
 *
 * Não usa token nem servidor próprio: só a API pública do GitHub (60 pedidos/hora por IP; usamos 1 a cada 6 h).
 * Quem fizer um fork troca [Config.ATUALIZACAO_REPO].
 */
@android.annotation.TargetApi(28)   // longVersionCode e signingInfo são do Android 9; as entradas conferem o SDK_INT
object Atualizador {
    private const val TAG = "TvLauncher.Atualiza"
    private const val PREFS = "atualizador"
    private const val ULTIMA = "ultima_verificacao"
    private const val ESTADO = "estado"
    private const val ESTADO_VERSAO = "estado_versao"   // versão instalada quando o ESTADO foi escrito
    private const val PENDENTE = "pendente"             // Release conferido (sha, pacote, chave) e ainda não instalado
    private const val RECUSADA = "recusada"             // Release recusado por chave diferente: não baixa de novo
    private const val INTERVALO_MS = 6 * 60 * 60_000L
    private const val NOME_APK = "tv-launcher.apk"
    private const val ACAO_RESULTADO = "br.paulo.tvlauncher.ATUALIZACAO_RESULTADO"

    @Volatile private var ocupado = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Texto curto para a tela de Configurações: o que aconteceu na última verificação. */
    fun estado(ctx: Context): String {
        val p = prefs(ctx)
        val t = p.getString(ESTADO, null) ?: return txt(R.string.atu_nunca)
        // 06/11/2026: o texto velho ("Release X assinado com outra chave") ficava na tela depois de o PC instalar uma
        // versão mais nova, e parecia que havia atualização. Se a versão instalada mudou, o texto não vale mais.
        val v = versaoAtual(ctx)
        return if (v != 0L && p.getLong(ESTADO_VERSAO, v) != v) txt(R.string.atu_trocou) else t
    }

    /** versionCode do Release já conferido e pronto para instalar, ou null. Só vale se for maior que o instalado. */
    fun pendente(ctx: Context): Long? {
        val c = prefs(ctx).getLong(PENDENTE, 0L)
        return if (c > versaoAtual(ctx)) c else null
    }

    private fun versaoAtual(ctx: Context): Long = try {
        if (android.os.Build.VERSION.SDK_INT >= 28) instalado(ctx.applicationContext).longVersionCode else 0L
    } catch (e: Exception) { 0L }

    private fun anotar(ctx: Context, t: String) {
        Log.i(TAG, t)
        val hora = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        prefs(ctx).edit().putString(ESTADO, "$hora · $t").putLong(ESTADO_VERSAO, versaoAtual(ctx)).apply()
    }

    /** Chamado pelo relógio de 10 min do GuardiaoShizuku. Só faz algo a cada 6 h e com a tela apagada. */
    fun talvezVerificar(ctx: Context) {
        if (!Config.ATUALIZACAO_AUTOMATICA || android.os.Build.VERSION.SDK_INT < 28) return
        val app = ctx.applicationContext
        if (System.currentTimeMillis() - prefs(app).getLong(ULTIMA, 0) < INTERVALO_MS) return
        if (telaAcesa(app)) return   // espera a tela apagar: instalar reinicia o launcher
        verificar(app, instalar = true)
    }

    /**
     * Verifica agora (botão das Configurações, com [instalar] = false só mostra o que acharia). Roda numa thread própria;
     * [fim] é chamado nela com o texto do resultado.
     */
    fun verificar(ctx: Context, instalar: Boolean, fim: ((String) -> Unit)? = null) {
        val app = ctx.applicationContext
        if (android.os.Build.VERSION.SDK_INT < 28) { fim?.invoke(txt(R.string.atu_android9)); return }
        if (ocupado) { fim?.invoke(txt(R.string.atu_ocupado)); return }
        ocupado = true
        Thread {
            val r = try { verificarAgora(app, instalar) } catch (e: Exception) { txt(R.string.atu_erro, e.javaClass.simpleName, e.message.orEmpty()) }
            ocupado = false
            prefs(app).edit().putLong(ULTIMA, System.currentTimeMillis()).apply()
            anotar(app, r)
            fim?.invoke(r)
        }.start()
    }

    private fun verificarAgora(app: Context, instalar: Boolean): String {
        val json = JSONObject(baixarTexto("https://api.github.com/repos/${Config.ATUALIZACAO_REPO}/releases/latest"))
        // A tag é "v<versionCode>" (o workflow cria assim).
        val codigo = json.optString("tag_name").removePrefix("v").toLongOrNull() ?: return "Release sem versão na tag"
        val meu = instalado(app).longVersionCode
        if (codigo <= meu) { prefs(app).edit().remove(PENDENTE).apply(); return txt(R.string.atu_em_dia, meu, codigo) }
        // Este mesmo Release já foi recusado pela chave: o APK não muda, então não baixa 12 MB de novo a cada 6 h.
        if (prefs(app).getLong(RECUSADA, 0L) == codigo) return txt(R.string.atu_chave, codigo)

        var urlApk: String? = null
        var urlSha: String? = null
        val assets = json.optJSONArray("assets")
        for (i in 0 until (assets?.length() ?: 0)) {
            val a = assets!!.getJSONObject(i)
            when (a.optString("name")) {
                NOME_APK -> urlApk = a.optString("browser_download_url")
                "$NOME_APK.sha256" -> urlSha = a.optString("browser_download_url")
            }
        }
        if (urlApk == null || urlSha == null) return txt(R.string.atu_sem_apk, codigo)

        val esperado = baixarTexto(urlSha).trim().split(Regex("\\s+")).first().lowercase()
        val arquivo = File(app.cacheDir, "atualizacao.apk")
        baixarArquivo(urlApk, arquivo)
        var entregue = false   // true = o PackageInstaller ficou com o arquivo (o Resultado apaga depois)
        try {
            if (sha256(arquivo) != esperado) return txt(R.string.atu_sha, codigo)

            val novo = app.packageManager.getPackageArchiveInfo(arquivo.path, PackageManager.GET_SIGNING_CERTIFICATES)
                ?: return txt(R.string.atu_ilegivel, codigo)
            if (novo.packageName != app.packageName) return txt(R.string.atu_pacote, codigo, novo.packageName)
            if (novo.longVersionCode != codigo) return txt(R.string.atu_versao, codigo, novo.longVersionCode)
            if (!mesmaChave(instalado(app), novo)) {
                prefs(app).edit().putLong(RECUSADA, codigo).remove(PENDENTE).apply()
                return txt(R.string.atu_chave, codigo)
            }

            if (!instalar) { prefs(app).edit().putLong(PENDENTE, codigo).apply(); return txt(R.string.atu_disponivel, codigo, meu) }
            instalarApk(app, arquivo)
            entregue = true
            return txt(R.string.atu_instalando, codigo, meu)
        } finally {
            if (!entregue) arquivo.delete()
        }
    }

    private fun instalado(app: Context): PackageInfo =
        app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)

    /** Mesma chave = o conjunto de certificados de quem assinou é igual. Sem signingInfo, recusa. */
    private fun mesmaChave(a: PackageInfo, b: PackageInfo): Boolean {
        val sa = a.signingInfo?.apkContentsSigners?.map { it.toByteArray().toList() }?.toSet() ?: return false
        val sb = b.signingInfo?.apkContentsSigners?.map { it.toByteArray().toList() }?.toSet() ?: return false
        return sa.isNotEmpty() && sa == sb
    }

    private fun telaAcesa(app: Context) = app.getSystemService(PowerManager::class.java)?.isInteractive ?: true

    private fun instalarApk(app: Context, arquivo: File) {
        val instalador = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(arquivo.length())
        }
        val id = instalador.createSession(params)
        instalador.openSession(id).use { sessao ->
            sessao.openWrite("launcher", 0, arquivo.length()).use { saida ->
                arquivo.inputStream().use { it.copyTo(saida) }
                sessao.fsync(saida)
            }
            val aviso = Intent(app, Resultado::class.java).setAction(ACAO_RESULTADO)
            val pi = PendingIntent.getBroadcast(app, id, aviso,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)   // MUTABLE: o sistema preenche o status
            // Device Owner: o Android instala sem perguntar. Se o processo morrer aqui, é a atualização em si.
            sessao.commit(pi.intentSender)
        }
    }

    private fun conexao(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "tv-launcher")
        setRequestProperty("Accept", "application/vnd.github+json")
    }

    private fun baixarTexto(url: String): String {
        val c = conexao(url)
        try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode} em $url")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun baixarArquivo(url: String, destino: File) {
        val c = conexao(url)
        try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode} em $url")
            c.inputStream.use { entrada -> destino.outputStream().use { entrada.copyTo(it) } }
        } finally { c.disconnect() }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { e -> val b = ByteArray(64 * 1024); while (true) { val n = e.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Resposta do PackageInstaller. Sucesso numa autoatualização quase nunca chega aqui: o processo é trocado antes. */
    class Resultado : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                // Sem Device Owner o Android pede confirmação: mostra a tela dele.
                @Suppress("DEPRECATION")
                val confirmar = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                ctx.startActivity(confirmar.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
            File(ctx.cacheDir, "atualizacao.apk").delete()
            val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            anotar(ctx, if (status == PackageInstaller.STATUS_SUCCESS) txt(R.string.atu_ok) else txt(R.string.atu_falhou, status, msg.toString()))
        }
    }
}
