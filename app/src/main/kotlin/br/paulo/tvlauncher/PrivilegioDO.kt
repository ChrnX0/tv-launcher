package br.paulo.tvlauncher

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import kotlin.concurrent.thread

/**
 * O núcleo de privilégio por Device Owner (DPM), no MESMO processo do launcher — sem app intermediário.
 *
 * Diferente do [Privilegio] (ponte Shizuku, que fala no UID de shell): este usa os poderes de ADMINISTRADOR DO APARELHO
 * que já temos (ver AdminReceiver). O Dhizuku existe para EMPRESTAR esses poderes a outros apps por IPC; como nós somos
 * o dono E quem consome, chamamos o DevicePolicyManager direto, em processo. Mais integrado e sem copiar código de ninguém.
 *
 * Limite honesto: isto NÃO alcança o que é exclusivo do UID de shell (pm/cmd arbitrários, ler dados de outros apps) —
 * aquilo continua sendo Shizuku. Aqui ficam: ocultar/suspender apps, instalar/desinstalar calado, permissões, quiosque.
 */
object PrivilegioDO {
    private const val TAG = "TvLauncher.DO"

    private fun dpm(ctx: Context) = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private fun admin(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)

    fun somosDonos(ctx: Context): Boolean = try { dpm(ctx).isDeviceOwnerApp(ctx.packageName) } catch (e: Exception) { false }

    /** Chamado no onCreate (fora da thread da tela). Fixa o nosso protetor e aplica as otimizações. Idempotente e barato. */
    fun aplicar(ctx: Context) {
        thread(name = "PrivilegioDO") {
            if (Config.FIXAR_PROTETOR) fixarProtetor(ctx)
            if (somosDonos(ctx)) {
                // Autoconcede as nossas proprias permissoes perigosas, de forma FIXA: o usuario (ou um reset de permissoes)
                // nao consegue revogar. E o "nunca perder privilegios", pelo caminho permanente — nao depende de adb nem Shizuku.
                Config.PERMISSOES_FIXAS.forEach { conceder(ctx, ctx.packageName, it) }
                if (Config.OTIMIZAR_MEMORIA) Config.APPS_OCULTAR.forEach { ocultar(ctx, it, true) }
            }
        }
    }

    /**
     * Aponta o screensaver do sistema para o nosso [ProtetorTela], aposentando o de fábrica. Usa WRITE_SECURE_SETTINGS
     * (dada ao app uma vez por adb — a mesma que religa a acessibilidade). Assim o app se autoconfigura: não depende de adb.
     */
    private fun fixarProtetor(ctx: Context) {
        try {
            val cr = ctx.contentResolver
            val nosso = "${ctx.packageName}/.ProtetorTela"
            if (Settings.Secure.getString(cr, "screensaver_components") != nosso) {
                Settings.Secure.putString(cr, "screensaver_components", nosso)
                Settings.Secure.putString(cr, "screensaver_enabled", "1")
                Settings.Secure.putString(cr, "screensaver_activate_on_sleep", "1")
                Log.i(TAG, "protetor do sistema fixado em $nosso")
            }
        } catch (e: Exception) {
            Log.w(TAG, "fixarProtetor falhou (sem WRITE_SECURE_SETTINGS?): $e")
        }
    }

    /**
     * Oculta (ou mostra) um app pelo Device Owner. Ocultar = o app some e para de rodar: libera a memória que ele segurava,
     * sem desinstalar. Reversível a qualquer momento com [ocultar] (…, false). Devolve true se o estado mudou.
     */
    fun ocultar(ctx: Context, pacote: String, oculto: Boolean): Boolean = try {
        if (!somosDonos(ctx)) false
        else dpm(ctx).setApplicationHidden(admin(ctx), pacote, oculto).also {
            Log.i(TAG, "ocultar($pacote, $oculto) => mudou=$it")
        }
    } catch (e: Exception) {
        Log.w(TAG, "ocultar $pacote falhou: $e"); false
    }

    /**
     * Quanto tempo parada a TV leva para entrar no protetor de tela, em ms. Com screensaver_activate_on_sleep=1 (ver
     * [fixarProtetor]), o protetor entra quando o sistema "iria dormir", então este tempo é o SCREEN_OFF_TIMEOUT
     * (testado na TV: baixando para 15 s, o ProtetorTela entrou sozinho). Int.MAX_VALUE = nunca.
     */
    fun tempoDescanso(ctx: Context): Int =
        Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 600_000)

    /**
     * Grava o tempo pelo Device Owner (DevicePolicyManager.setSystemSetting): SCREEN_OFF_TIMEOUT é uma das chaves que o
     * dono do aparelho pode mudar sem a permissão WRITE_SETTINGS (que a TV nem tem tela para conceder). Devolve se gravou.
     */
    fun definirTempoDescanso(ctx: Context, ms: Int): Boolean = try {
        if (!somosDonos(ctx)) false
        else {
            dpm(ctx).setSystemSetting(admin(ctx), Settings.System.SCREEN_OFF_TIMEOUT, ms.toString())
            Log.i(TAG, "tempo do descanso de tela => $ms ms")
            tempoDescanso(ctx) == ms
        }
    } catch (e: Exception) {
        Log.w(TAG, "definirTempoDescanso falhou: $e"); false
    }

    fun estaOculto(ctx: Context, pacote: String): Boolean = try {
        somosDonos(ctx) && dpm(ctx).isApplicationHidden(admin(ctx), pacote)
    } catch (e: Exception) { false }

    /**
     * Concede uma permissao perigosa a [pacote] de forma FIXA (PERMISSION_GRANT_STATE_GRANTED): fica concedida e o usuario
     * nao pode revogar pela interface. So funciona para permissoes que o app declara no manifesto. Para o nosso proprio
     * pacote, e assim que garantimos READ_CALENDAR, localizacao etc. sem o `pm grant` manual por adb.
     */
    fun conceder(ctx: Context, pacote: String, permissao: String): Boolean = try {
        if (!somosDonos(ctx)) false
        else dpm(ctx).setPermissionGrantState(admin(ctx), pacote, permissao,
            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED).also {
            Log.i(TAG, "conceder($pacote, $permissao) => ok=$it")
        }
    } catch (e: Exception) { Log.w(TAG, "conceder $permissao falhou: $e"); false }

    /** Suspende (ou retoma) apps: o icone fica esmaecido e o app nao abre nem roda, sem desinstalar. Reversivel. */
    fun suspender(ctx: Context, pacotes: List<String>, suspenso: Boolean): Array<String> = try {
        if (!somosDonos(ctx)) emptyArray()
        else dpm(ctx).setPackagesSuspended(admin(ctx), pacotes.toTypedArray(), suspenso).also {
            Log.i(TAG, "suspender(${pacotes.joinToString()}, $suspenso) => falharam=${it.joinToString()}")
        }
    } catch (e: Exception) { Log.w(TAG, "suspender falhou: $e"); pacotes.toTypedArray() }

    /** Desinstala um app em silencio (sem a tela do sistema), pelo Device Owner. Use com cuidado: e de verdade. */
    fun desinstalar(ctx: Context, pacote: String): Boolean = try {
        if (!somosDonos(ctx)) false
        else {
            val pi = ctx.packageManager.packageInstaller
            val intent = android.content.Intent("br.paulo.tvlauncher.DESINSTALADO")
            val ps = android.app.PendingIntent.getBroadcast(ctx, 0, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE)
            pi.uninstall(pacote, ps.intentSender)
            Log.i(TAG, "desinstalar($pacote) solicitado")
            true
        }
    } catch (e: Exception) { Log.w(TAG, "desinstalar $pacote falhou: $e"); false }

    /** Liga/desliga uma restricao de usuario (ex.: UserManager.DISALLOW_SAFE_BOOT). Permanente ate reverter. */
    fun restricao(ctx: Context, chave: String, ligada: Boolean) {
        try {
            if (!somosDonos(ctx)) return
            if (ligada) dpm(ctx).addUserRestriction(admin(ctx), chave) else dpm(ctx).clearUserRestriction(admin(ctx), chave)
            Log.i(TAG, "restricao($chave, $ligada)")
        } catch (e: Exception) { Log.w(TAG, "restricao $chave falhou: $e") }
    }
}
