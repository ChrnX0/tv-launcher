package br.paulo.tvlauncher

import android.content.Context

/**
 * Modo visita (04/11/2026, pedido do Paulo): um interruptor nas Configurações do launcher que, enquanto ligado,
 * esconde da HOME o que é pessoal:
 *  - o perfil: a bolinha mostra um boneco genérico, e o OK nela só oferece "sair do modo visita";
 *  - a agenda: o OK na data não abre o calendário;
 *  - os apps que o Paulo escolher (lista própria, separada dos "ocultos" de sempre) e o card "Apps ocultos".
 * Não é uma trava: é privacidade para quando tem visita. Quem sabe onde fica o botão consegue sair.
 * Tudo fica num arquivo de preferências próprio, então desligar volta a HOME exatamente como estava.
 */
object ModoVisita {
    private const val PREFS = "modo_visita"
    private const val ATIVO = "ativo"
    private const val APPS = "apps"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ativo(ctx: Context): Boolean = prefs(ctx).getBoolean(ATIVO, false)

    fun definir(ctx: Context, ligado: Boolean) {
        prefs(ctx).edit().putBoolean(ATIVO, ligado).apply()
    }

    /** Pacotes que somem da HOME enquanto o modo visita está ligado. */
    fun apps(ctx: Context): Set<String> =
        HashSet(prefs(ctx).getStringSet(APPS, emptySet()) ?: emptySet())   // cópia: o Android proíbe mexer no original

    fun escondeNaVisita(ctx: Context, pacote: String): Boolean = pacote in apps(ctx)

    /** Liga ou desliga [pacote] da lista da visita. Devolve o estado novo (true = escondido na visita). */
    fun alternar(ctx: Context, pacote: String): Boolean {
        val lista = apps(ctx)
        val escondido = if (pacote in lista) { lista.remove(pacote); false } else { lista.add(pacote); true }
        prefs(ctx).edit().putStringSet(APPS, lista).apply()
        return escondido
    }
}
