package br.paulo.tvlauncher

import rikka.shizuku.ShizukuProvider

/**
 * Provider por onde o servidor do Shizuku embutido (processo `shizuku_server`, uid 2000) entrega o binder dele ao
 * launcher. O servidor chama `content://br.paulo.tvlauncher.shizuku` com o método "sendBinder"; a classe-mãe
 * (da API do Shizuku) guarda o binder e a partir daí `Shizuku.pingBinder()` passa a dar true no launcher.
 *
 * Igual ao `ShizukuManagerProvider` do app Shizuku original, só que sem o "sendUserService" (fica para depois).
 */
class ProvedorShizuku : ShizukuProvider() {
    override fun onCreate(): Boolean {
        // Sui é uma variante do Shizuku que roda com root (Magisk). Não existe nesta TV; desligar evita procurar por ela.
        disableAutomaticSuiInitialization()
        return super.onCreate()
    }
}
