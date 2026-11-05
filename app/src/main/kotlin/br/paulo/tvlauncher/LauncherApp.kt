package br.paulo.tvlauncher

import android.app.Application

/**
 * Classe do app (05/11/2026). Roda uma vez em cada processo do launcher, antes de qualquer tela ou receiver.
 * Hoje só liga o registro local de erros (RegistroErros.kt), para que um crash em QUALQUER ponto (HOME, boot,
 * acessibilidade, painel) fique gravado. Mantenha leve: tudo aqui atrasa a abertura da HOME.
 */
class LauncherApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RegistroErros.instalar(this)
    }
}
