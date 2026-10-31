package br.paulo.tvlauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Como a linha de apps está organizada, além da lista de apps instalados:
 * ordem manual, pilhas (vários apps num card só) e apps ocultos.
 * Tudo fica nas SharedPreferences "launcher". Esta classe só guarda e altera dados (não desenha nada).
 *
 * "Token" = o identificador de um card na linha principal: para um app solto é o pacote
 * (ex.: "com.google.android.youtube.tv"); para uma pilha é "pilha:<id>".
 * A ordem salva (chave "ordem") é uma lista de tokens.
 */
object Organizacao {
    private const val PREFS = "launcher"

    /** Uma pilha: um id e os pacotes dentro dela (o primeiro aparece na frente). */
    class Pilha(val id: String, val apps: MutableList<String>) {
        val token: String get() = tokenDaPilha(id)
    }

    fun tokenDaPilha(id: String): String = "pilha:$id"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- ordem

    fun ordem(ctx: Context): List<String> =
        prefs(ctx).getString("ordem", "").orEmpty().split(",").filter { it.isNotEmpty() }

    fun salvarOrdem(ctx: Context, tokens: List<String>) {
        prefs(ctx).edit().putString("ordem", tokens.joinToString(",")).apply()
    }

    fun limparOrdem(ctx: Context) {
        prefs(ctx).edit().remove("ordem").apply()
    }

    /**
     * Ajusta a ordem salva depois de uma mudança (empilhar, tirar...). Se o usuário nunca fixou uma ordem
     * (ordem automática), não há o que ajustar: a pilha aparece onde estaria o seu primeiro app.
     */
    private fun editarOrdem(ctx: Context, mudar: (List<String>) -> List<String>) {
        val atual = ordem(ctx)
        if (atual.isEmpty()) return
        salvarOrdem(ctx, mudar(atual).distinct())   // distinct(): sem repetidos, vale a primeira posição
    }

    // ---------------------------------------------------------------- pilhas

    fun pilhas(ctx: Context): MutableList<Pilha> {
        val lista = mutableListOf<Pilha>()
        val texto = prefs(ctx).getString("pilhas", null) ?: return lista
        try {
            val vetor = JSONArray(texto)
            for (i in 0 until vetor.length()) {
                val o = vetor.getJSONObject(i)
                val apps = o.getJSONArray("apps")
                lista.add(Pilha(o.getString("id"), MutableList(apps.length()) { apps.getString(it) }))
            }
        } catch (e: Exception) {
            lista.clear()   // dado estragado: começa sem pilhas em vez de travar a tela inicial
        }
        return lista
    }

    private fun salvarPilhas(ctx: Context, pilhas: List<Pilha>) {
        val vetor = JSONArray()
        for (p in pilhas) vetor.put(JSONObject().put("id", p.id).put("apps", JSONArray(p.apps)))
        prefs(ctx).edit().putString("pilhas", vetor.toString()).apply()
    }

    /**
     * Junta o app [origem] (solto) com [destino]: outro app solto (cria uma pilha nova) ou uma pilha existente.
     * Devolve o token da pilha resultante.
     */
    fun empilhar(ctx: Context, origem: String, destino: String): String {
        val todas = pilhas(ctx)
        val existente = todas.firstOrNull { it.token == destino }
        val pilha = existente ?: Pilha(System.currentTimeMillis().toString(36), mutableListOf(destino)).also { todas.add(it) }
        if (origem !in pilha.apps) pilha.apps.add(origem)
        salvarPilhas(ctx, todas)
        val token = pilha.token
        // Na ordem salva: a pilha ocupa o lugar do destino e a origem sai da posição antiga.
        editarOrdem(ctx) { o -> o.filter { it != origem }.map { if (it == destino) token else it } }
        return token
    }

    /** Tira [pacote] da pilha onde está. Uma pilha que ficar com menos de 2 apps se desfaz. */
    fun tirarDaPilha(ctx: Context, pacote: String) {
        val todas = pilhas(ctx)
        val pilha = todas.firstOrNull { pacote in it.apps } ?: return
        pilha.apps.remove(pacote)
        val novos = if (pilha.apps.size < 2) {
            todas.remove(pilha)
            pilha.apps + pacote   // quem sobrou e quem saiu viram cards soltos
        } else {
            listOf(pilha.token, pacote)   // o app que saiu fica logo depois da pilha
        }
        salvarPilhas(ctx, todas)
        editarOrdem(ctx) { o -> o.flatMap { if (it == pilha.token) novos else listOf(it) } }
    }

    /** Desfaz a pilha inteira: os apps viram cards soltos, na posição dela. */
    fun desfazerPilha(ctx: Context, token: String) {
        val todas = pilhas(ctx)
        val pilha = todas.firstOrNull { it.token == token } ?: return
        todas.remove(pilha)
        salvarPilhas(ctx, todas)
        editarOrdem(ctx) { o -> o.flatMap { if (it == token) pilha.apps else listOf(it) } }
    }

    // ---------------------------------------------------------------- ocultos

    fun ocultos(ctx: Context): Set<String> =
        HashSet(prefs(ctx).getStringSet("ocultos", emptySet()) ?: emptySet())   // cópia: o Android proíbe mexer no original

    private fun salvarOcultos(ctx: Context, pacotes: Set<String>) {
        prefs(ctx).edit().putStringSet("ocultos", HashSet(pacotes)).apply()
    }

    /** Oculta [pacote]; se estava numa pilha, sai dela primeiro. */
    fun ocultar(ctx: Context, pacote: String) {
        tirarDaPilha(ctx, pacote)
        salvarOcultos(ctx, ocultos(ctx) + pacote)
    }

    fun mostrar(ctx: Context, pacote: String) {
        salvarOcultos(ctx, ocultos(ctx) - pacote)
    }
}
