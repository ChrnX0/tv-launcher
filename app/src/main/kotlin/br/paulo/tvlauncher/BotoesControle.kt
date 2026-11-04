package br.paulo.tvlauncher

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent

/**
 * Os dois botões de app do controle (Netflix e YouTube) passam a abrir o app que você escolher.
 *  - toque: abre o app salvo para aquele botão;
 *  - segurar 3 s: abre a lista de apps (EscolherAppActivity) para trocar; a escolha fica salva.
 *
 * Como chega aqui: o serviço de acessibilidade (AutorizaShizukuService.onKeyEvent) vê a tecla ANTES do sistema.
 * Devolvendo true, o sistema nunca a recebe: o Netflix não abre sozinho e o YouTube (que o Home do Google tratava) passa a funcionar.
 * Códigos medidos no controle da Sala em 01/11/2026 (log TvLauncher.Tecla): Netflix = BUTTON_4, YouTube = BUTTON_3.
 */
object BotoesControle {
    private const val TAG = "TvLauncher.Botao"
    private const val SEGURAR_MS = 3000L

    /** Cada botão: código da tecla, nome mostrado na lista e o app padrão (antes de você escolher). */
    class Botao(val tecla: Int, val nome: String, val padrao: String)

    val BOTOES = listOf(
        Botao(KeyEvent.KEYCODE_BUTTON_4, "Netflix", "com.onestream.player"),
        Botao(KeyEvent.KEYCODE_BUTTON_3, "YouTube", "com.google.android.youtube.tv"),
    )

    private val principal = Handler(Looper.getMainLooper())
    private var abrirLista: Runnable? = null   // o "segurou 3 s" agendado; null = nenhum botão apertado
    private var listaAberta = false             // true = a lista já abriu neste aperto: soltar não abre app
    private var apertado: Int? = null           // tecla cujo DOWN vimos neste aperto

    fun botao(tecla: Int) = BOTOES.firstOrNull { it.tecla == tecla }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    fun appDo(ctx: Context, b: Botao): String = prefs(ctx).getString("botao_${b.tecla}", b.padrao) ?: b.padrao

    fun salvar(ctx: Context, b: Botao, pacote: String) {
        prefs(ctx).edit().putString("botao_${b.tecla}", pacote).apply()
        Log.i(TAG, "${b.nome} agora abre $pacote")
    }

    /** Chamado para cada tecla. true = a tecla é nossa (o sistema não a vê). */
    fun tratar(ctx: Context, e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_HOME && Home.launcherxAtivo(ctx)) return tratarHome(ctx, e)
        val b = botao(e.keyCode) ?: return false
        when (e.action) {
            KeyEvent.ACTION_DOWN -> if (e.repeatCount == 0) {
                // Aperto novo: agenda a lista para daqui a 3 s. Se soltar antes, o agendamento é cancelado.
                listaAberta = false
                abrirLista?.let { principal.removeCallbacks(it) }
                apertado = b.tecla
                abrirLista = Runnable {
                    listaAberta = true
                    abrirLista = null
                    // try: este código roda no processo da HOME; uma falha aqui não pode derrubar o launcher.
                    try {
                        ctx.startActivity(Intent(ctx, EscolherAppActivity::class.java)
                            .putExtra(EscolherAppActivity.EXTRA_TECLA, b.tecla)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) { Log.w(TAG, "não abriu a lista de apps: $e") }
                }.also { principal.postDelayed(it, SEGURAR_MS) }
            }
            KeyEvent.ACTION_UP -> {
                abrirLista?.let { principal.removeCallbacks(it) }
                abrirLista = null
                // Só abre o app se o DOWN deste aperto passou por aqui (ex.: serviço religado no meio do aperto
                // recebe só o UP — antes isso abria o app sozinho).
                if (!listaAberta && apertado == b.tecla) abrirApp(ctx, appDo(ctx, b))
                apertado = null
            }
        }
        return true
    }

    private var homeLongo: Runnable? = null
    private var homeLongoDisparou = false
    private var homeApertado = false

    /**
     * Botão HOME com o launcherx do Google ATIVO (04/11/2026). Ele declara a HOME com priority=2 (app de sistema
     * privilegiado), e no resolvedor do Android a prioridade vence QUALQUER preferência, até a persistente do Device
     * Owner e o papel de HOME (medido: role HOME = nosso, preferred = nosso, e o HOME abria launcherx/.home.HomeActivity).
     * Então a tecla é tratada aqui, antes do sistema: aperto curto = a nossa HOME; segurado = o painel do Google
     * (notificações, Google Home), pelo mesmo pedido que o sistema faz no HOME longo.
     */
    private fun tratarHome(ctx: Context, e: KeyEvent): Boolean {
        when (e.action) {
            KeyEvent.ACTION_DOWN -> if (e.repeatCount == 0) {
                homeApertado = true
                homeLongoDisparou = false
                homeLongo?.let { principal.removeCallbacks(it) }
                homeLongo = Runnable {
                    homeLongoDisparou = true
                    homeLongo = null
                    Home.abrirPainelGoogle(ctx)
                }.also { principal.postDelayed(it, Home.SEGURAR_HOME_MS) }
            }
            KeyEvent.ACTION_UP -> {
                homeLongo?.let { principal.removeCallbacks(it) }
                homeLongo = null
                if (homeApertado && !homeLongoDisparou) Home.irParaHome(ctx)
                homeApertado = false
            }
        }
        return true
    }

    /** Abre um app pelo pacote. Na TV o certo é o atalho "leanback"; se o app não tiver, tenta o comum. */
    fun abrirApp(ctx: Context, pacote: String) {
        val pm = ctx.packageManager
        val abrir = pm.getLeanbackLaunchIntentForPackage(pacote) ?: pm.getLaunchIntentForPackage(pacote)
        if (abrir == null) {
            Log.w(TAG, "app $pacote não encontrado")
            return
        }
        try { ctx.startActivity(abrir.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { Log.w(TAG, "não abriu $pacote: $e") }
    }
}
