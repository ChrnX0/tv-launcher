package br.paulo.tvlauncher

import android.app.Application

/**
 * Classe do app (05/11/2026). Roda uma vez em cada processo do launcher, antes de qualquer tela ou receiver.
 * Hoje só liga o registro local de erros (RegistroErros.kt), para que um crash em QUALQUER ponto (HOME, boot,
 * acessibilidade, painel) fique gravado. Mantenha leve: tudo aqui atrasa a abertura da HOME.
 */
class LauncherApp : Application() {
    companion object {
        /** O app, para as frases traduzidas de quem não tem Context (Textos.kt). Vale desde o onCreate do processo. */
        lateinit var instancia: LauncherApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instancia = this
        RegistroErros.instalar(this)
        // A IA na nuvem saiu em 05/11/2026: apaga a chave cifrada que ela tenha deixado (fora da thread da tela).
        Thread({ PedidoIa.apagarRestosDaNuvem(this) }, "limpa-ia").start()
    }
}
