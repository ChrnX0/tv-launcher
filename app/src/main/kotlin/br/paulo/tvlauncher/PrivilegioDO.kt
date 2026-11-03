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
            if (Config.OTIMIZAR_MEMORIA && somosDonos(ctx)) {
                Config.APPS_OCULTAR.forEach { ocultar(ctx, it, true) }
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

    fun estaOculto(ctx: Context, pacote: String): Boolean = try {
        somosDonos(ctx) && dpm(ctx).isApplicationHidden(admin(ctx), pacote)
    } catch (e: Exception) { false }
}
