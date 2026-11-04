package br.paulo.tvlauncher

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Serviço de acessibilidade do launcher. Hoje só trata teclas do controle remoto (ver [onKeyEvent]) — chegou a
 * aprovar sozinho os diálogos de permissão do Shizuku, mas esse app foi desinstalado em 03/11 (o launcher migrou
 * para o Device Owner para a maior parte do que precisava, ver [PrivilegioDO]) e esse código foi removido.
 */
class AutorizaShizukuService : AccessibilityService() {
    companion object {
        /** O serviço ligado, para quem precisa de uma janela de acessibilidade (ver PareamentoNaTv). null = desligado. */
        @Volatile var instancia: AutorizaShizukuService? = null
    }

    override fun onServiceConnected() { super.onServiceConnected(); instancia = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instancia = null; return super.onUnbind(intent) }
    override fun onDestroy() { instancia = null; super.onDestroy() }

    // Só TYPE_WINDOW_STATE_CHANGED (ver autoriza_acessibilidade.xml): poucas por minuto, quando uma tela abre.
    override fun onAccessibilityEvent(evento: AccessibilityEvent?) {
        if (evento?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            Home.janelaApareceu(this, evento.packageName, evento.className)
    }

    /**
     * Teclas do controle: o serviço de acessibilidade vê cada tecla ANTES do app da frente e antes dos botões
     * "globais" (Netflix, YouTube), que o sistema manda direto para outro app sem passar pelo launcher.
     * Os botões Netflix e YouTube vão para BotoesControle (true = o sistema não os vê); o resto segue normal (false).
     * Para descobrir o código de um botão novo: adb logcat -s TvLauncher.Tecla (registra só em modo depuração do log, nível D).
     */
    override fun onKeyEvent(e: KeyEvent): Boolean {
        // Só registra com o log da tag ligado (adb shell setprop log.tag.TvLauncher.Tecla DEBUG). Antes saía sempre:
        // com um teclado Bluetooth, o que se digitava (senhas inclusive) ia parar no logcat (revisão de 04/11/2026).
        if (Log.isLoggable("TvLauncher.Tecla", Log.DEBUG))
            Log.d("TvLauncher.Tecla", "tecla=${KeyEvent.keyCodeToString(e.keyCode)} (${e.keyCode}) scan=${e.scanCode} " +
                "acao=${if (e.action == KeyEvent.ACTION_DOWN) "desce" else "sobe"} repete=${e.repeatCount}")
        return BotoesControle.tratar(this, e)
    }

    override fun onInterrupt() {}
}