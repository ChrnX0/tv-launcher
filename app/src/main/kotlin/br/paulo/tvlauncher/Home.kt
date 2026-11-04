package br.paulo.tvlauncher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log

/**
 * Manter a NOSSA HOME com o launcher do Google (launcherx) ativo (04/11/2026).
 *
 * Por que o launcherx fica ativo: o Gemini mostra a resposta da voz em launcherx/.gemini.chat.ChatActivity, e a página
 * de um filme da busca também é dele. Com ele desativado ou oculto, a voz dava "o Google Assistente está com problema".
 *
 * Por que precisamos disto: a HomeActivity dele declara priority=2 (app de sistema privilegiado), e no resolvedor do
 * Android a prioridade maior vence antes de qualquer preferência — a persistente do Device Owner e o papel de HOME
 * não seguram. Então:
 *  - o botão HOME é tratado pelo serviço de acessibilidade (BotoesControle.tratarHome);
 *  - quem mais abrir a HOME (boot, um app, o sistema) cai na HomeActivity dele, e o serviço de acessibilidade vê a
 *    janela aparecer e traz a nossa por cima (AutorizaShizukuService.onAccessibilityEvent).
 * Com o launcherx desativado nada disto age: a HOME do sistema já é a nossa.
 */
object Home {
    private const val TAG = "TvLauncher.Home"
    const val LAUNCHERX = "com.google.android.apps.tv.launcherx"
    private const val HOME_DELE = "$LAUNCHERX.home.HomeActivity"
    const val SEGURAR_HOME_MS = 700L

    private var ultimoDesvio = 0L

    fun launcherxAtivo(ctx: Context): Boolean = try {
        val ai = ctx.packageManager.getApplicationInfo(LAUNCHERX, 0)
        ai.enabled && ctx.packageManager.getApplicationEnabledSetting(LAUNCHERX) !in
            setOf(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)
    } catch (e: Exception) { false }   // não instalado ou oculto

    /** Abre a nossa HOME como o sistema abriria (onNewIntent com CATEGORY_HOME volta o foco ao primeiro app). */
    fun irParaHome(ctx: Context) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setClass(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        } catch (e: Exception) { Log.w(TAG, "não abriu a nossa HOME: $e") }
    }

    /** HOME segurado: o painel do Google TV (notificações, atalhos, Google Home), o mesmo pedido do sistema. */
    fun abrirPainelGoogle(ctx: Context) {
        try {
            ctx.startActivity(Intent("android.app.action.TOGGLE_NOTIFICATION_HANDLER_PANEL").setPackage(LAUNCHERX)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { Log.w(TAG, "não abriu o painel do Google: $e") }
    }

    /** Chamado quando uma janela aparece: se for a HOME do launcherx, traz a nossa por cima. */
    fun janelaApareceu(ctx: Context, pacote: CharSequence?, classe: CharSequence?) {
        if (pacote?.toString() != LAUNCHERX || classe?.toString() != HOME_DELE) return
        val agora = SystemClock.elapsedRealtime()
        if (agora - ultimoDesvio < 1500) return   // trava contra laço, se algo insistir em reabrir a dele
        ultimoDesvio = agora
        Log.i(TAG, "a HOME do launcherx apareceu: trazendo a nossa")
        irParaHome(ctx)
    }
}
