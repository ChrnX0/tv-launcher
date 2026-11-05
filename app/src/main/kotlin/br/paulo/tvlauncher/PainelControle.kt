package br.paulo.tvlauncher

import android.accessibilityservice.AccessibilityService
import android.app.admin.DevicePolicyManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager
import android.util.Log
import android.view.Display
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Aba "Controle" do painel no navegador e as ações extras de cada app (ver PainelWeb.kt):
 *  - foto da tela pela ACESSIBILIDADE (takeScreenshot, medido na fase 0: 1920x1080 em 37 ms; não depende do adb);
 *  - teclas do controle remoto e texto: VOLTAR e INÍCIO pela acessibilidade (na hora); as demais pelo shell
 *    (`input keyevent`, cerca de 1 s cada, porque o comando sobe uma JVM);
 *  - abrir, parar e limpar os dados de um app; ligar/apagar a tela e reiniciar a TV (Device Owner);
 *  - tempo de uso de hoje e espaço ocupado por app.
 * Nenhuma rota recebe comando livre: tecla vem de uma tabela fixa, texto só com letras e números simples.
 */
object PainelControle {
    private const val TAG = "TvLauncher.PainelCtl"

    // ---------------------------------------------------------------- foto da tela

    private val trava = Any()
    @Volatile private var ultimaFoto: Pair<Long, ByteArray>? = null
    private val executor by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "Painel-foto") } }

    /**
     * A tela da TV em JPEG 1280x720. Reaproveita a última foto se tiver menos de 700 ms (o Android só deixa tirar
     * uma a cada 333 ms, e duas abas abertas não dobram o custo). Devolve null e o motivo se não deu.
     */
    fun foto(): Pair<ByteArray?, String?> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null to "Android antigo demais para foto da tela"
        val servico = AutorizaShizukuService.instancia ?: return null to "o serviço de acessibilidade do launcher está desligado"
        synchronized(trava) {
            ultimaFoto?.let { (quando, jpg) -> if (System.currentTimeMillis() - quando < 700) return jpg to null }
            val pronto = CountDownLatch(1)
            var jpg: ByteArray? = null
            var erro: String? = null
            servico.takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                    try {
                        val hw = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                        r.hardwareBuffer.close()
                        if (hw == null) { erro = "o Android não entregou a imagem"; return }
                        // HARDWARE -> memória comum já reduzida (1280x720 = 3,7 MB transitórios em vez de 8 MB).
                        val sw = hw.copy(Bitmap.Config.ARGB_8888, false)
                        hw.recycle()
                        val menor = if (sw.width > 1280) Bitmap.createScaledBitmap(sw, 1280, sw.height * 1280 / sw.width, true) else sw
                        if (menor !== sw) sw.recycle()
                        jpg = ByteArrayOutputStream().use { menor.compress(Bitmap.CompressFormat.JPEG, 70, it); it.toByteArray() }
                        menor.recycle()
                    } catch (e: Exception) { erro = "erro ao converter: ${e.javaClass.simpleName}" } finally { pronto.countDown() }
                }
                override fun onFailure(codigo: Int) {
                    // Código 3 = pediu de novo antes de 333 ms; 1 = o serviço não declarou canTakeScreenshot.
                    erro = "o Android recusou a foto (código $codigo)"; pronto.countDown()
                }
            })
            if (!pronto.await(5, TimeUnit.SECONDS)) return null to "a foto demorou demais"
            jpg?.let { ultimaFoto = System.currentTimeMillis() to it }
            return jpg to erro
        }
    }

    // ---------------------------------------------------------------- teclas e texto

    /** Nome no painel -> código da tecla (KeyEvent.KEYCODE_*). A lista fecha o que o navegador pode pedir. */
    val TECLAS = mapOf(
        "cima" to 19, "baixo" to 20, "esquerda" to 21, "direita" to 22, "ok" to 23,
        "voltar" to 4, "inicio" to 3, "menu" to 82, "play" to 85, "avancar" to 90, "retroceder" to 89,
        "volume+" to 24, "volume-" to 25, "mudo" to 164, "ligar" to 224, "apagar" to 223,
        "apagar_letra" to 67, "entrar" to 66,
    )

    /** Aperta uma tecla. Devolve null se deu certo, ou o motivo. */
    fun tecla(ctx: Context, nome: String): String? {
        val codigo = TECLAS[nome] ?: return "tecla desconhecida"
        AutoDesligar.atividade()   // tecla pelo painel também é alguém usando a TV
        val servico = AutorizaShizukuService.instancia
        // VOLTAR e INÍCIO pela acessibilidade: imediatos e sem shell.
        if (servico != null && nome == "voltar") return if (servico.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) null else "o Android recusou"
        if (servico != null && nome == "inicio") return if (servico.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)) null else "o Android recusou"
        return shell(ctx, "input keyevent $codigo")
    }

    /** Roda um comando montado aqui no nosso shell. null = rodou; senão o motivo (sem resposta do shell). */
    private fun shell(ctx: Context, cmd: String): String? =
        if (GuardiaoShizuku.comando(ctx, cmd) == null) "o shell do launcher não respondeu (a depuração sem fio está ligada?)" else null

    /** Texto aceito: ver ChecagensPainel.textoTvValido (o `input text` do Android não digita acento). */
    fun textoValido(t: String) = ChecagensPainel.textoTvValido(t)

    fun digitar(ctx: Context, texto: String): String? {
        AutoDesligar.atividade()
        if (!textoValido(texto)) return "só letras sem acento, números, espaço e . , @ _ - (até 100)"
        // %s é como o `input text` entende espaço; o texto vai entre aspas simples e não tem aspas (barrado acima).
        val arg = texto.replace(" ", "%s")
        return shell(ctx, "input text '$arg'")
    }

    // ---------------------------------------------------------------- apps: abrir, parar, limpar dados

    fun abrir(ctx: Context, pacote: String): String? {
        val pm = ctx.packageManager
        val i = pm.getLeanbackLaunchIntentForPackage(pacote) ?: pm.getLaunchIntentForPackage(pacote)
            ?: return "este app não tem tela para abrir"
        return try {
            // Abrir tela vindo de segundo plano: liberado porque o launcher tem a permissão de sobreposição.
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); null
        } catch (e: Exception) { "o Android não deixou abrir: ${e.javaClass.simpleName}" }
    }

    /** Força a parada (como "Forçar parada" nas Configurações). O app volta a funcionar ao ser aberto de novo. */
    fun parar(ctx: Context, pacote: String): String? {
        if (PainelApps.protegido(ctx, pacote)) return "este app não se para pelo painel (o sistema ou o launcher dependem dele)"
        if (!ChecagensPainel.pacoteValido(pacote)) return "pacote inválido"
        return shell(ctx, "am force-stop $pacote")
    }

    /** Apaga os dados do app (login, configurações, downloads dele), como "Limpar dados". Sem volta. Só app instalado. */
    fun limparDados(ctx: Context, pacote: String): String? {
        if (PainelApps.protegido(ctx, pacote)) return "este app é protegido"
        val ai = try { ctx.packageManager.getApplicationInfo(pacote, 0) } catch (e: Exception) { return "app não encontrado" }
        if (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0) return "app do sistema: limpe pelas Configurações da TV"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return "Android antigo demais"
        if (!PrivilegioDO.somosDonos(ctx)) return "o launcher não é mais o dono do aparelho (Device Owner)"
        val pronto = CountDownLatch(1)
        var ok = false
        try {
            ctx.getSystemService(DevicePolicyManager::class.java).clearApplicationUserData(
                ComponentName(ctx, AdminReceiver::class.java), pacote, executor
            ) { _, sucesso -> ok = sucesso; pronto.countDown() }
        } catch (e: Exception) { return "o Android recusou: ${e.javaClass.simpleName}" }
        if (!pronto.await(30, TimeUnit.SECONDS)) return "o Android não respondeu em 30 s"
        return if (ok) null else "o Android recusou"
    }

    // ---------------------------------------------------------------- a TV inteira

    /** Reinicia a TV (Device Owner). O painel volta sozinho depois do boot, se estiver ligado. */
    fun reiniciarTv(ctx: Context): String? {
        if (!PrivilegioDO.somosDonos(ctx)) return "o launcher não é mais o dono do aparelho (Device Owner)"
        return try {
            ctx.getSystemService(DevicePolicyManager::class.java).reboot(ComponentName(ctx, AdminReceiver::class.java)); null
        } catch (e: Exception) { Log.w(TAG, "reboot: $e"); "o Android recusou: ${e.javaClass.simpleName}" }
    }

    // ---------------------------------------------------------------- uso e espaço

    /** Minutos de uso HOJE (desde a meia-noite) por pacote, só os que foram usados. */
    fun usoHoje(ctx: Context): JSONObject {
        val o = JSONObject()
        try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val meiaNoite = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
            for ((p, s) in usm.queryAndAggregateUsageStats(meiaNoite, System.currentTimeMillis())) {
                val min = s.totalTimeInForeground / 60_000
                if (min > 0) o.put(p, min)
            }
        } catch (e: Exception) { Log.w(TAG, "uso: $e") }
        return o
    }

    /** Espaço do app em bytes: o app, os dados e o cache. null se o Android não informar. */
    fun espaco(ctx: Context, pacote: String): JSONObject? = try {
        val ssm = ctx.getSystemService(StorageStatsManager::class.java)
        val s = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, pacote, Process.myUserHandle())
        JSONObject().put("app", s.appBytes).put("dados", s.dataBytes).put("cache", s.cacheBytes)
    } catch (e: Exception) { null }
}
