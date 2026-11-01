package br.paulo.tvlauncher

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Aperta sozinho "Permitir o tempo todo" quando um app pede acesso ao Shizuku.
 *
 * Como funciona: um serviço de acessibilidade recebe eventos da tela de um app (aqui, só do Shizuku:
 * o filtro está em res/xml/autoriza_acessibilidade.xml) e pode "tocar" nos botões como se fosse você.
 *
 * O diálogo do Shizuku é um AlertDialog comum, então os ids são os do próprio Android (android:id/...),
 * e não os do app dele. Descobri isso com `adb shell uiautomator dump`: title = "Permitir que o app X acessar Shizuku?",
 * button1 = "Permitir o tempo todo", button3 = "Negar". Só clicamos quando título (com "Shizuku") e os DOIS botões
 * existem na tela, para não apertar botões de outros diálogos do Shizuku.
 *
 * ATENÇÃO: isto autoriza QUALQUER app que pedir, e cada app autorizado passa a ter shell.
 * Para desligar: Config.SHIZUKU_AUTORIZAR_TUDO = false, ou desative o serviço em Configurações > Acessibilidade.
 */
class AutorizaShizukuService : AccessibilityService() {
    private companion object {
        const val TAG = "TvLauncher.Autoriza"
        const val SHIZUKU = "moe.shizuku.privileged.api"
        const val ID_TITULO = "android:id/title"
        const val ID_PERMITIR = "android:id/button1"
        const val ID_NEGAR = "android:id/button3"
    }

    private var ultimoClique = 0L

    // Se o diálogo já estiver na tela quando o serviço liga, nenhum evento novo chega: olhamos uma vez ao conectar.
    override fun onServiceConnected() {
        tentarAprovar()
    }

    override fun onAccessibilityEvent(evento: AccessibilityEvent?) {
        if (evento?.packageName?.toString() != SHIZUKU) return
        tentarAprovar()
    }

    private fun tentarAprovar() {
        if (!Config.SHIZUKU_AUTORIZAR_TUDO) return
        val raiz = rootInActiveWindow ?: return
        if (raiz.packageName?.toString() != SHIZUKU) return

        val titulo = raiz.findAccessibilityNodeInfosByViewId(ID_TITULO).firstOrNull()?.text?.toString() ?: return
        if (!titulo.contains("Shizuku", ignoreCase = true)) return
        val permitir = raiz.findAccessibilityNodeInfosByViewId(ID_PERMITIR)
        val negar = raiz.findAccessibilityNodeInfosByViewId(ID_NEGAR)
        if (permitir.isEmpty() || negar.isEmpty()) return

        // Vários eventos chegam seguidos enquanto o diálogo fecha: clica no máximo 1 vez a cada 2 s.
        val agora = System.currentTimeMillis()
        if (agora - ultimoClique < 2000) return
        ultimoClique = agora

        // Registro para auditoria (adb logcat -s TvLauncher.Autoriza): o título diz qual app pediu.
        Log.i(TAG, "autorizando pedido: $titulo")
        permitir[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /**
     * Teclas do controle: o serviço de acessibilidade vê cada tecla ANTES do app da frente e antes dos botões
     * "globais" (Netflix, YouTube), que o sistema manda direto para outro app sem passar pelo launcher.
     * Os botões Netflix e YouTube vão para BotoesControle (true = o sistema não os vê); o resto segue normal (false).
     * Para descobrir o código de um botão novo: adb logcat -s TvLauncher.Tecla (registra só em modo depuração do log, nível D).
     */
    override fun onKeyEvent(e: KeyEvent): Boolean {
        Log.d("TvLauncher.Tecla", "tecla=${KeyEvent.keyCodeToString(e.keyCode)} (${e.keyCode}) scan=${e.scanCode} " +
            "acao=${if (e.action == KeyEvent.ACTION_DOWN) "desce" else "sobe"} repete=${e.repeatCount}")
        return BotoesControle.tratar(this, e)
    }

    override fun onInterrupt() {}
}