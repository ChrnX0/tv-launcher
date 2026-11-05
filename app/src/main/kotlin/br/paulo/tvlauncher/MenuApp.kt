package br.paulo.tvlauncher

/**
 * O que aparece ao segurar OK num app da tela inicial (05/11/2026). Fica separado da MainActivity para dar para testar
 * no computador, sem TV: aqui é só a regra, a tela monta o menu a partir de [acoes].
 *
 * Antes, segurar OK num app solto ia direto para o modo mover. Agora abre um menu, como no Google TV:
 *  - Mover (o mesmo modo de antes, um OK a mais);
 *  - Ocultar da tela inicial (o mesmo que ▲ no modo mover; o app continua instalado);
 *  - Desinstalar, só quando [podeDesinstalar] deixa.
 * Dentro da pasta "Ocultos" o menu troca "Mover/Ocultar" por "Mostrar na tela inicial".
 */
object MenuApp {
    /** Ver PainelApps.protegido: nunca ocultar nem desinstalar (sem eles a TV quebra ou o painel some). */
    val PROTEGIDOS_FIXOS = setOf(
        "br.paulo.tvlauncher", "android", "com.android.systemui", "com.android.tv.settings", "com.android.settings",
        "com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.google.android.apps.tv.launcherx", "com.google.android.gms", "com.google.android.gsf",
        "com.android.providers.settings", "com.android.shell",
    )

    enum class Acao { MOVER, OCULTAR, MOSTRAR, DESINSTALAR }

    fun acoes(naPastaOcultos: Boolean, desinstalavel: Boolean): List<Acao> {
        val base = if (naPastaOcultos) listOf(Acao.MOSTRAR) else listOf(Acao.MOVER, Acao.OCULTAR)
        return if (desinstalavel) base + Acao.DESINSTALAR else base
    }

    /**
     * Desinstalar só app que o usuário instalou ([sistema] = false: app de fábrica do Chromecast não sai, o Android
     * recusa; para esses fica o "Ocultar") e que não está [protegido] (ver PainelApps.protegido). O próprio launcher é
     * barrado aqui também, mesmo que alguém o tire da lista de protegidos: ele carrega a HOME, o Device Owner (perder é
     * irreversível sem reset de fábrica), o ADB embutido e o serviço de acessibilidade.
     */
    fun podeDesinstalar(pacote: String, nosso: String, sistema: Boolean, protegido: Boolean): Boolean =
        pacote != nosso && !sistema && !protegido
}
