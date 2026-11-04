package br.paulo.tvlauncher

import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * O que a aba "Aplicativos" do painel no navegador faz, tudo por API em processo (somos Device Owner), sem shell:
 * listar com nome e ícone, ocultar/reexibir e desinstalar. Ver PainelWeb.kt.
 */
object PainelApps {
    private const val TAG = "TvLauncher.PainelApps"
    private const val ICONE_PX = 96

    /**
     * Apps que o painel NUNCA oculta nem desinstala, porque sem eles a TV quebra ou o painel some:
     * o próprio launcher (HOME + Device Owner), o sistema, as Configurações, o instalador e o controle de permissões,
     * o launcher do Google (o Gemini, a página de filme e o painel do Google dependem dele, ver Config.APPS_OCULTAR)
     * e os serviços do Google. Mais dois que mudam em tempo de execução: o teclado ativo e o app da VPN fixa.
     */
    private val PROTEGIDOS_FIXOS = setOf(
        "br.paulo.tvlauncher", "android", "com.android.systemui", "com.android.tv.settings", "com.android.settings",
        "com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.google.android.apps.tv.launcherx", "com.google.android.gms", "com.google.android.gsf",
        "com.android.providers.settings", "com.android.shell",
    )

    fun protegido(ctx: Context, pacote: String): Boolean {
        if (pacote in PROTEGIDOS_FIXOS || pacote == ctx.packageName) return true
        if (Config.VPN_FIXA.isNotEmpty() && pacote == Config.VPN_FIXA) return true
        // Teclado ativo: "pacote/.Classe". Ocultar o teclado deixaria a TV sem como digitar.
        val teclado = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        return teclado != null && teclado.substringBefore('/') == pacote
    }

    // ---------------------------------------------------------------- lista

    @Suppress("DEPRECATION")
    private fun flagsTodos(): Int = PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS

    /** Nome do app, guardado por (pacote, pasta do APK): a pasta muda a cada atualização, então o cache se renova sozinho. */
    private val nomes = ConcurrentHashMap<String, Pair<String, String>>()

    private fun nome(pm: PackageManager, ai: ApplicationInfo): String {
        val chave = ai.sourceDir ?: ""
        nomes[ai.packageName]?.let { (dir, nome) -> if (dir == chave) return nome }
        val nome = try { ai.loadLabel(pm).toString() } catch (e: Exception) { ai.packageName }
        nomes[ai.packageName] = chave to nome
        return nome
    }

    /** Pacotes com tela de abrir (menu de apps da TV ou de celular), numa consulta só para todos. */
    private fun abriveis(pm: PackageManager): Set<String> {
        val saida = HashSet<String>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val i = Intent(Intent.ACTION_MAIN).addCategory(cat)
            pm.queryIntentActivities(i, 0).forEach { saida.add(it.activityInfo.packageName) }
        }
        return saida
    }

    private fun sistema(ai: ApplicationInfo) = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0

    /**
     * Pode ocultar? Só app de usuário, ou de sistema que tem tela de abrir (YouTube, Play Store...). Um app de sistema
     * sem tela costuma ser serviço ou provedor de que outros dependem (ocultar um desses pode quebrar a TV), então fica
     * de fora. Reexibir é sempre permitido, para desfazer.
     */
    private fun podeOcultar(ctx: Context, ai: ApplicationInfo, abrivel: Boolean) =
        !protegido(ctx, ai.packageName) && (!sistema(ai) || abrivel)

    /** Desinstalar: só app instalado pelo usuário (sem FLAG_SYSTEM). App de sistema só se oculta. */
    private fun podeDesinstalar(ctx: Context, ai: ApplicationInfo) = !protegido(ctx, ai.packageName) && !sistema(ai)

    fun listar(ctx: Context): JSONArray {
        val pm = ctx.packageManager
        val abriveis = abriveis(pm)
        val pacotes: List<PackageInfo> = pm.getInstalledPackages(flagsTodos())
        val saida = JSONArray()
        for (pi in pacotes) {
            val ai = pi.applicationInfo ?: continue
            // MATCH_UNINSTALLED_PACKAGES também traz app desinstalado que deixou dados ("-k"): esse não está instalado.
            if (ai.flags and ApplicationInfo.FLAG_INSTALLED == 0) continue
            val oculto = PrivilegioDO.estaOculto(ctx, pi.packageName)
            // Oculto, o app some da consulta de "abríveis"; por isso "podeOcultar" abaixo vale sempre para quem já está
            // oculto (reexibir é sempre permitido).
            val abrivel = pi.packageName in abriveis
            saida.put(JSONObject()
                .put("pacote", pi.packageName)
                .put("nome", nome(pm, ai))
                .put("versao", pi.versionName ?: "")
                .put("sistema", sistema(ai))
                .put("abrivel", abrivel)
                .put("oculto", oculto)
                .put("desativado", !ai.enabled)
                .put("ocultoPeloLauncher", pi.packageName in Config.APPS_OCULTAR)
                .put("podeOcultar", oculto || podeOcultar(ctx, ai, abrivel))
                .put("podeDesinstalar", podeDesinstalar(ctx, ai))
                .put("protegido", protegido(ctx, pi.packageName)))
        }
        return saida
    }

    private fun infoApp(ctx: Context, pacote: String): ApplicationInfo? = try {
        ctx.packageManager.getApplicationInfo(pacote, flagsTodos())
    } catch (e: PackageManager.NameNotFoundException) { null }

    // ---------------------------------------------------------------- ícone

    /** PNGs prontos, ~1 MB no máximo (cerca de 150 ícones). Chave = pacote + pasta do APK. */
    private val icones = object : LruCache<String, ByteArray>(1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }

    /** O ícone em PNG 96x96 e uma "etiqueta" (ETag) que muda quando o app atualiza; null se o app não existe. */
    fun icone(ctx: Context, pacote: String): Pair<ByteArray, String>? {
        val ai = infoApp(ctx, pacote) ?: return null
        val etiqueta = "\"" + Integer.toHexString((pacote + ai.sourceDir).hashCode()) + "\""
        icones.get(etiqueta)?.let { return it to etiqueta }
        val pm = ctx.packageManager
        // O ícone do app; se o Android não conseguir carregar, o ícone genérico.
        val d = try { ai.loadIcon(pm) } catch (e: Exception) { pm.defaultActivityIcon }
        val bmp = Bitmap.createBitmap(ICONE_PX, ICONE_PX, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, ICONE_PX, ICONE_PX)
        d.draw(Canvas(bmp))
        val png = ByteArrayOutputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        bmp.recycle()
        icones.put(etiqueta, png)
        return png to etiqueta
    }

    // ---------------------------------------------------------------- ocultar

    /** Devolve null se deu certo, ou o motivo de não ter feito. */
    fun ocultar(ctx: Context, pacote: String, oculto: Boolean): String? {
        val ai = infoApp(ctx, pacote) ?: return "app não encontrado"
        if (oculto) {
            val abrivel = pacote in abriveis(ctx.packageManager)
            if (!podeOcultar(ctx, ai, abrivel)) return "este app não pode ser ocultado (o sistema ou o launcher dependem dele)"
        }
        if (!PrivilegioDO.somosDonos(ctx)) return "o launcher não é mais o dono do aparelho (Device Owner)"
        PrivilegioDO.ocultar(ctx, pacote, oculto)
        return if (PrivilegioDO.estaOculto(ctx, pacote) == oculto) null else "o Android recusou"
    }

    // ---------------------------------------------------------------- desinstalar

    private val proximoId = AtomicInteger(1)
    private class Espera { val pronto = CountDownLatch(1); @Volatile var status = PackageInstaller.STATUS_FAILURE; @Volatile var mensagem: String? = null }
    private val esperas = ConcurrentHashMap<Int, Espera>()
    const val ACAO_RESULTADO = "br.paulo.tvlauncher.painel.RESULTADO_DESINSTALAR"

    /**
     * Desinstala em silêncio (somos Device Owner, então o Android não pede confirmação: medido na fase 0) e ESPERA o
     * resultado de verdade, que chega pelo [ResultadoDesinstalarReceiver]. Antes, o desinstalar() antigo devolvia
     * "ok" ao pedir, não ao concluir. Devolve null se desinstalou, ou o motivo. Bloqueia até 60 s: fora da thread da tela.
     */
    fun desinstalar(ctx: Context, pacote: String): String? {
        val ai = infoApp(ctx, pacote) ?: return "app não encontrado"
        if (!podeDesinstalar(ctx, ai)) return "este app não pode ser desinstalado pelo painel (é do sistema ou protegido)"
        if (!PrivilegioDO.somosDonos(ctx)) return "o launcher não é mais o dono do aparelho (Device Owner)"
        val id = proximoId.getAndIncrement()
        val espera = Espera()
        esperas[id] = espera
        try {
            // Intent EXPLÍCITO para o nosso receiver (não exportado). MUTABLE porque o instalador acrescenta o
            // resultado (EXTRA_STATUS) no intent; o Android 14 só aceita PendingIntent mutável se for explícito.
            val intent = Intent(ctx, ResultadoDesinstalarReceiver::class.java).setAction(ACAO_RESULTADO).putExtra("id", id)
            val pi = PendingIntent.getBroadcast(ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            ctx.packageManager.packageInstaller.uninstall(pacote, pi.intentSender)
            if (!espera.pronto.await(60, TimeUnit.SECONDS)) return "o Android não respondeu em 60 s"
            return when (espera.status) {
                PackageInstaller.STATUS_SUCCESS -> null
                PackageInstaller.STATUS_PENDING_USER_ACTION -> "o Android pediu confirmação na TV (o launcher deixou de ser Device Owner?)"
                else -> "o Android recusou: ${espera.mensagem ?: "status ${espera.status}"}"
            }
        } catch (e: Exception) {
            Log.w(TAG, "desinstalar $pacote falhou: $e")
            return "erro: ${e.javaClass.simpleName}"
        } finally {
            esperas.remove(id)
        }
    }

    // ---------------------------------------------------------------- instalar

    const val LIMITE_APK = 1L shl 30   // 1 GB: nenhum APK de TV chega perto; acima disso é engano de arquivo

    /** Resultado de [instalar]: [motivo] null = instalou. */
    class Instalado(val pacote: String?, val nome: String?, val versao: String?, val motivo: String?)

    /**
     * Instala o APK que chega em [entrada] ([tamanho] bytes), em silêncio (Device Owner). Primeiro grava num arquivo
     * temporário para LER o APK antes de instalar: pacote, nome e versão vão para o registro, e o próprio launcher
     * é recusado (atualizar o launcher por aqui mataria o painel no meio; isso fica com o build3.ps1). Depois manda
     * para o PackageInstaller e espera o resultado de verdade, como no [desinstalar]. Bloqueia: fora da tela.
     */
    fun instalar(ctx: Context, entrada: java.io.InputStream, tamanho: Long): Instalado {
        if (tamanho <= 0 || tamanho > LIMITE_APK) return Instalado(null, null, null, "tamanho de APK inválido")
        if (!PrivilegioDO.somosDonos(ctx)) return Instalado(null, null, null, "o launcher não é mais o dono do aparelho (Device Owner)")
        if (ctx.cacheDir.usableSpace < tamanho * 2 + (200L shl 20)) return Instalado(null, null, null, "falta espaço na TV para instalar")
        // Nome único: duas instalações ao mesmo tempo (duas abas) não escrevem no mesmo arquivo.
        val tmp = java.io.File.createTempFile("painel-instalar", ".apk", ctx.cacheDir)
        try {
            java.io.FileOutputStream(tmp).use { saida -> entrada.copyTo(saida, 64 * 1024); saida.fd.sync() }
            if (tmp.length() != tamanho) return Instalado(null, null, null, "o arquivo chegou incompleto")
            val pm = ctx.packageManager
            val info = pm.getPackageArchiveInfo(tmp.path, 0)
                ?: return Instalado(null, null, null, "não é um APK que o Android entenda (arquivos .apks/.xapk, com várias partes, ainda não são aceitos)")
            val pacote = info.packageName
            info.applicationInfo?.let { it.sourceDir = tmp.path; it.publicSourceDir = tmp.path }
            val nome = try { info.applicationInfo?.loadLabel(pm)?.toString() } catch (e: Exception) { null } ?: pacote
            val versao = info.versionName ?: ""
            if (pacote == ctx.packageName) return Instalado(pacote, nome, versao, "o próprio launcher não se atualiza pelo painel (use o build3.ps1 no PC)")

            val instalador = pm.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(pacote)
                setSize(tamanho)
            }
            val sessaoId = instalador.createSession(params)
            val id = proximoId.getAndIncrement()
            val espera = Espera()
            esperas[id] = espera
            try {
                instalador.openSession(sessaoId).use { sessao ->
                    sessao.openWrite("base.apk", 0, tamanho).use { saida ->
                        java.io.FileInputStream(tmp).use { it.copyTo(saida, 64 * 1024) }
                        sessao.fsync(saida)
                    }
                    val intent = Intent(ctx, ResultadoDesinstalarReceiver::class.java).setAction(ACAO_RESULTADO).putExtra("id", id)
                    val pi = PendingIntent.getBroadcast(ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                    sessao.commit(pi.intentSender)
                }
                if (!espera.pronto.await(120, TimeUnit.SECONDS)) return Instalado(pacote, nome, versao, "o Android não respondeu em 2 min")
                val motivo = when (espera.status) {
                    PackageInstaller.STATUS_SUCCESS -> null
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> "o Android pediu confirmação na TV (o launcher deixou de ser Device Owner?)"
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> "já existe uma versão deste app com outra assinatura (desinstale a antiga antes)"
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "o APK não é compatível com esta TV (ex.: feito só para 64 bits)"
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "falta espaço na TV"
                    else -> "o Android recusou: ${espera.mensagem ?: "status ${espera.status}"}"
                }
                return Instalado(pacote, nome, versao, motivo)
            } finally {
                esperas.remove(id)
            }
        } catch (e: Exception) {
            Log.w(TAG, "instalar falhou: $e")
            return Instalado(null, null, null, "erro: ${e.javaClass.simpleName}")
        } finally {
            tmp.delete()
        }
    }

    internal fun resultado(id: Int, status: Int, mensagem: String?) {
        val e = esperas[id] ?: return
        e.status = status; e.mensagem = mensagem
        e.pronto.countDown()
    }

    // ---------------------------------------------------------------- o que está na TV agora

    /**
     * O que a família está vendo: "em espera" (tela apagada), "na tela inicial" ou o nome do app na frente.
     * Pelo histórico de uso (PACKAGE_USAGE_STATS, já liberada para o "ordenar por uso"): o último app que veio para
     * a frente nas últimas 6 h. Sem a permissão, devolve só ligada/em espera.
     */
    fun naTela(ctx: Context): String {
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (pm != null && !pm.isInteractive) return "em espera"
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val agora = System.currentTimeMillis()
            val eventos = usm.queryEvents(agora - 6 * 3600_000L, agora)
            val e = UsageEvents.Event()
            var ultimo: String? = null
            while (eventos.hasNextEvent()) {
                eventos.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) ultimo = e.packageName
            }
            when (ultimo) {
                null -> "ligada"
                ctx.packageName -> "na tela inicial"
                else -> infoApp(ctx, ultimo)?.let { nome(ctx.packageManager, it) } ?: ultimo
            }
        } catch (e: Exception) { "ligada" }
    }
}

/** Recebe o resultado do PackageInstaller (desinstalar e instalar, ver PainelApps). Não exportado: só o nosso PendingIntent chega. */
class ResultadoDesinstalarReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PainelApps.ACAO_RESULTADO) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val mensagem = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i("TvLauncher.PainelApps", "resultado desinstalar id=${intent.getIntExtra("id", -1)} status=$status $mensagem")
        PainelApps.resultado(intent.getIntExtra("id", -1), status, mensagem)
    }
}
