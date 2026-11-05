package br.paulo.tvlauncher

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * Limpeza de apps de fábrica, com lista curada e desfazer (05/11/2026, fila longa do brainstorm).
 *
 * Ocultar um app pelo Device Owner (setApplicationHidden) o tira do ar sem desinstalar: some do menu, não roda, não
 * ocupa RAM, e volta inteiro com um OK. A lição do launcherx (04/11/2026) é que ocultar o app ERRADO quebra coisa longe
 * dali (o Gemini e o seletor de contas). Por isso:
 *  - só aparece na TV quem está em [LISTA] com [Item.testadoEm] preenchido: testado nesta TV, um a um, ocultando e
 *    conferindo voz, busca, Play Store, transmitir (Cast) e as Configurações;
 *  - [NUNCA] barra os pacotes que sustentam a TV, mesmo se alguém puser na lista por engano;
 *  - o launcher guarda quais apps ELE ocultou por aqui ("debloat_ocultos"), para o "mostrar todos de volta" desfazer
 *    só o que foi feito pela limpeza (e não mexer no dreamx, que o Config.APPS_OCULTAR cuida).
 *
 * Para testar um candidato na TV (só adb): `am broadcast -a br.paulo.tvlauncher.OCULTAR --es pacote <pkg> --ez oculto true
 * -n br.paulo.tvlauncher/.ControleShizukuAdbReceiver`, conferir, e preencher [Item.testadoEm] aqui com o resultado.
 * `br.paulo.tvlauncher.DEBLOAT` (adb) lista os candidatos instalados e o estado de cada um.
 */
object Debloat {
    private const val CHAVE = "debloat_ocultos"

    /** [perde]: o que deixa de funcionar com o app oculto, em português simples. [testadoEm]: null = não testado. */
    data class Item(val pacote: String, val nome: String, val perde: String, val testadoEm: String? = null)

    /**
     * Candidatos. Só os com [Item.testadoEm] aparecem na TV. Para pôr um aqui: ocultar na TV, usar a TV normalmente
     * (voz, busca, Play Store, transmitir do celular, Configurações) e só então preencher a data e o que se perde.
     */
    val LISTA = listOf(
        // Testados na Sala em 05/11/2026: ocultos, a busca abriu, Play Store e Configurações abriram, o Cast respondeu.
        Item("com.google.android.marvin.talkback", "TalkBack", "o leitor de tela para quem não enxerga", "05/11/2026"),
        Item("com.google.android.videos", "Google TV (filmes)", "comprar e alugar filmes do Google", "05/11/2026"),
        // Não testados ainda.
        Item("com.google.android.youtube.tvkids", "YouTube Kids", "o app YouTube Kids"),
        // NÃO liberar (05/11/2026): o Device Owner não consegue ocultar o Play Games (fica visível; há a versão de fábrica e
        // a atualizada), e as Recomendações nem estão instaladas no usuário 0 da Sala.
        Item("com.google.android.play.games", "Google Play Games", "jogos que pedem login do Play Games"),
        Item("com.google.android.tvrecommendations", "Recomendações do Google TV", "sugestões de outros apps (nossa HOME não mostra)"),
    )

    /** Pacotes que NUNCA entram na limpeza (sustentam voz, contas, Cast, Configurações, a loja ou o próprio launcher). */
    val NUNCA = setOf(
        "android", "com.android.systemui", "com.android.tv.settings", "com.android.vending",
        "com.google.android.gms", "com.google.android.gsf", "com.google.android.katniss",
        "com.google.android.apps.tv.launcherx", "com.google.android.apps.mediashell", "com.google.android.tts",
        "com.google.android.tungsten.setupwraith", "com.google.android.apps.tv.setupwraith", "com.android.providers.tv",
        "com.google.android.inputmethod.latin", "com.google.android.tvkeyboard", "com.android.packageinstaller",
        "com.google.android.packageinstaller", "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "br.paulo.tvlauncher",
    )

    fun permitido(item: Item) = item.testadoEm != null && item.pacote !in NUNCA

    /** Os que podem aparecer na TV: testados, fora do [NUNCA] e instalados (ocultos também contam como instalados). */
    fun disponiveis(ctx: Context): List<Item> = LISTA.filter { permitido(it) && instalado(ctx, it.pacote) }

    fun instalado(ctx: Context, pacote: String): Boolean = try {
        // MATCH_UNINSTALLED_PACKAGES enxerga o app oculto (oculto = "não instalado" para o PackageManager comum), mas
        // também o que só existe para OUTRO usuário (visto na Sala: tvrecommendations). FLAG_INSTALLED separa os dois:
        // fica ligado no app oculto deste usuário e desligado no que não está instalado nele.
        @Suppress("DEPRECATION")
        val ai = ctx.packageManager.getApplicationInfo(pacote, PackageManager.MATCH_UNINSTALLED_PACKAGES)
        (ai.flags and ApplicationInfo.FLAG_INSTALLED) != 0
    } catch (e: Exception) { false }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    fun ocultosPorAqui(ctx: Context): Set<String> = prefs(ctx).getStringSet(CHAVE, emptySet()) ?: emptySet()

    /** Oculta (true) ou mostra de volta (false). Recusa o que não está liberado. Devolve se mudou. */
    fun definir(ctx: Context, pacote: String, ocultar: Boolean): Boolean {
        val item = LISTA.firstOrNull { it.pacote == pacote }
        if (ocultar && (item == null || !permitido(item))) return false
        // O retorno do setApplicationHidden não é confiável no Android 14 (visto na Sala: reexibir um app que não estava
        // oculto devolve true, e o Play Games "ocultava" sem ocultar). Vale o estado lido depois.
        PrivilegioDO.ocultar(ctx, pacote, ocultar)
        val mudou = PrivilegioDO.estaOculto(ctx, pacote) == ocultar
        if (mudou) {
            val novo = ocultosPorAqui(ctx).toMutableSet().apply { if (ocultar) add(pacote) else remove(pacote) }
            prefs(ctx).edit().putStringSet(CHAVE, novo).apply()
        }
        return mudou
    }

    /** Mostra de volta tudo o que a limpeza ocultou. Devolve quantos voltaram. */
    fun desfazerTudo(ctx: Context): Int = ocultosPorAqui(ctx).count { definir(ctx, it, false) }

    /** Para o adb: cada candidato instalado, com testado/oculto. */
    fun relatorio(ctx: Context): String = LISTA.filter { instalado(ctx, it.pacote) }.joinToString("\n") {
        "${it.pacote} testado=${it.testadoEm ?: "não"} oculto=${PrivilegioDO.estaOculto(ctx, it.pacote)} nunca=${it.pacote in NUNCA}"
    }.ifEmpty { "nenhum candidato instalado" }
}
