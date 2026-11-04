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
                fixarHome(ctx)
                if (Config.OTIMIZAR_MEMORIA) Config.APPS_OCULTAR.forEach { ocultar(ctx, it, true) }
                // VPN sempre ativa = app AdGuard VPN (04/11/2026). Vazio = o launcher não mexe na VPN.
                if (Config.VPN_FIXA.isNotEmpty()) vpnSempreAtiva(ctx, Config.VPN_FIXA)
            }
        }
    }

    /**
     * Trava a nossa MainActivity como HOME, pelo Device Owner (04/11/2026). Até aqui a HOME era só "o único launcher
     * ativo": se o launcherx do Google voltar a ser ativado (ou uma atualização trouxer outro launcher), o Android
     * podia abrir o dele no botão HOME. A preferência persistente do DO vence qualquer escolha do usuário ou do sistema
     * e sobrevive a reinícios. Idempotente: o PackageManager ignora a mesma preferência já gravada.
     * Para desfazer: clearPackagePersistentPreferredActivities(admin, nosso pacote).
     */
    private fun fixarHome(ctx: Context) {
        try {
            val filtro = android.content.IntentFilter(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_HOME)
                addCategory(android.content.Intent.CATEGORY_DEFAULT)
            }
            val nossa = ComponentName(ctx, MainActivity::class.java)
            dpm(ctx).addPersistentPreferredActivity(admin(ctx), filtro, nossa)
            val resolvida = ctx.packageManager.resolveActivity(
                android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), 0
            )?.activityInfo
            Log.i(TAG, "HOME persistente => ${nossa.flattenToShortString()} (resolve hoje para ${resolvida?.packageName}/${resolvida?.name})")
        } catch (e: Exception) {
            Log.w(TAG, "fixarHome falhou: $e")
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

    /**
     * VPN "sempre ativa" apontando para o NOSSO pacote (o perfil IKEv2 que o [Vpn] cadastrou): o Android religa o túnel
     * sozinho, inclusive depois de reiniciar. [ligada] = false tira. lockdown = false: se a VPN cair, a internet continua
     * funcionando sem ela (com true, a TV ficaria sem internet até a VPN voltar — ruim numa TV que os pais usam).
     */
    /**
     * Fixa [pacote] como "VPN sempre ativa" (null = nenhuma). Idempotente: só grava se mudou. lockdown = false de
     * propósito: se o app da VPN cair, a TV continua com internet (sem VPN) em vez de ficar sem rede nenhuma.
     * Desde 04/11/2026 é o app AdGuard VPN (Config.VPN_FIXA); antes era a VPN própria do launcher, aposentada.
     */
    fun vpnSempreAtiva(ctx: Context, pacote: String?): Boolean = try {
        if (!somosDonos(ctx)) false
        else {
            val atual = dpm(ctx).getAlwaysOnVpnPackage(admin(ctx))
            if (atual != pacote) {
                dpm(ctx).setAlwaysOnVpnPackage(admin(ctx), pacote, false)
                Log.i(TAG, "VPN sempre ativa: $atual => $pacote")
            }
            true
        }
    } catch (e: Exception) {
        Log.w(TAG, "vpnSempreAtiva($pacote) falhou: $e"); false
    }

    /**
     * Empurrão na VPN sempre ativa: se o app fixado ([Config.VPN_FIXA]) não está com túnel de pé, tira e põe de novo o
     * "sempre ativa" — o Android então reinicia o serviço de VPN do app.
     *
     * Por quê (medido em 04/11/2026): no boot o sistema inicia o AdGuard VPN ANTES de o Wi-Fi estar pronto; ele termina
     * "Disconnected cause=ConnectionLost" e não tenta de novo — a TV fica sem VPN até alguém abrir o app. Já quando o
     * "sempre ativa" é (re)aplicado com a rede de pé, ele conecta na hora. Chamado quando o Wi-Fi conecta
     * (Privilegio.vigiarDepuracaoSemFio) e no relógio do guardião. Não faz nada se já há VPN de pé.
     */
    fun reforcarVpn(ctx: Context) {
        val pacote = Config.VPN_FIXA
        if (pacote.isEmpty() || !somosDonos(ctx) || vpnDePe(ctx)) return
        try {
            dpm(ctx).setAlwaysOnVpnPackage(admin(ctx), null, false)
            dpm(ctx).setAlwaysOnVpnPackage(admin(ctx), pacote, false)
            Log.i(TAG, "VPN sem túnel: 'sempre ativa' reaplicado para $pacote")
        } catch (e: Exception) { Log.w(TAG, "reforcarVpn falhou: $e") }
    }

    private fun vpnDePe(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true }
    } catch (e: Exception) { true }   // na dúvida, não mexe

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

    // 04/11/2026 (faxina, item 28 da revisão): saíram suspender(), desinstalar() e restricao(), que ninguém chamava.
    // O desinstalar() era também o poder mais perigoso do arquivo (remover app em silêncio). Se um dia precisar, as
    // chamadas do DPM são setPackagesSuspended, PackageInstaller.uninstall e add/clearUserRestriction (ver o git).
}
