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

    /**
     * Uma pilha: um id, os pacotes dentro dela e [frente], o pacote que está na frente (o que o OK abre).
     * frente == null (ou um app que saiu) significa "o primeiro da lista".
     */
    class Pilha(val id: String, val apps: MutableList<String>, var frente: String? = null) {
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
                lista.add(
                    Pilha(
                        o.getString("id"),
                        MutableList(apps.length()) { apps.getString(it) },
                        o.optString("frente", "").ifEmpty { null }
                    )
                )
            }
        } catch (e: Exception) {
            lista.clear()   // dado estragado: começa sem pilhas em vez de travar a tela inicial
        }
        return lista
    }

    private fun salvarPilhas(ctx: Context, pilhas: List<Pilha>) {
        val vetor = JSONArray()
        for (p in pilhas) {
            vetor.put(JSONObject().put("id", p.id).put("apps", JSONArray(p.apps)).put("frente", p.frente ?: ""))
        }
        prefs(ctx).edit().putString("pilhas", vetor.toString()).apply()
    }

    /**
     * Junta o app [origem] (solto) com [destino]: outro app solto (cria uma pilha nova) ou uma pilha existente.
     * O app que chegou fica na frente. Devolve o token da pilha resultante.
     */
    fun empilhar(ctx: Context, origem: String, destino: String): String {
        val todas = pilhas(ctx)
        val existente = todas.firstOrNull { it.token == destino }
        val pilha = existente ?: Pilha(System.currentTimeMillis().toString(36), mutableListOf(destino)).also { todas.add(it) }
        if (origem !in pilha.apps) pilha.apps.add(origem)
        pilha.frente = origem
        salvarPilhas(ctx, todas)
        val token = pilha.token
        // Na ordem salva: a pilha ocupa o lugar do destino e a origem sai da posição antiga.
        editarOrdem(ctx) { o -> o.filter { it != origem }.map { if (it == destino) token else it } }
        return token
    }

    /** Escolhe qual app da pilha [id] fica na frente. */
    fun definirFrente(ctx: Context, id: String, pacote: String) {
        if (id.startsWith(PREFIXO_AUTO)) { definirFrenteAuto(ctx, id, pacote); return }
        val todas = pilhas(ctx)
        val pilha = todas.firstOrNull { it.id == id } ?: return
        if (pacote !in pilha.apps) return
        pilha.frente = pacote
        salvarPilhas(ctx, todas)
    }

    /** Tira [pacote] da pilha onde está. Uma pilha que ficar com menos de 2 apps se desfaz. */
    fun tirarDaPilha(ctx: Context, pacote: String) {
        val todas = pilhas(ctx)
        // Fora das pilhas manuais: se estava numa pilha automática, passa a ficar solto (lista "auto_fora").
        val pilha = todas.firstOrNull { pacote in it.apps } ?: run { tirarDaAuto(ctx, pacote); return }
        pilha.apps.remove(pacote)
        if (pilha.frente == pacote) pilha.frente = null
        val novos = if (pilha.apps.size < 2) {
            todas.remove(pilha)
            pilha.apps + pacote   // quem sobrou e quem saiu viram cards soltos
        } else {
            listOf(pilha.token, pacote)   // o app que saiu fica logo depois da pilha
        }
        salvarPilhas(ctx, todas)
        editarOrdem(ctx) { o -> o.flatMap { if (it == pilha.token) novos else listOf(it) } }
    }

    /**
     * Cria uma pilha manual com [apps] (o [frente] na frente). Usado quando alguém solta um app em cima de uma pilha
     * AUTOMÁTICA: ela vira manual (com o app novo), e a manual sempre vence a automática. Devolve o token.
     */
    fun criarPilha(ctx: Context, apps: List<String>, frente: String?): String {
        val todas = pilhas(ctx)
        apps.forEach { p -> todas.forEach { it.apps.remove(p) } }
        todas.removeAll { it.apps.size < 2 }
        val nova = Pilha(System.currentTimeMillis().toString(36), apps.distinct().toMutableList(), frente)
        todas.add(nova)
        salvarPilhas(ctx, todas)
        return nova.token
    }

    // ---------------------------------------------------------------- pilhas automáticas (05/11/2026)
    // Liga em Configurações do launcher. Os apps que não estão numa pilha manual são agrupados pela categoria que o
    // PRÓPRIO app declara no manifesto (android:appCategory: vídeo, áudio, jogo, notícias...). App sem categoria fica
    // solto. Nada é gravado: a pilha automática é refeita a cada desenho, com id fixo por categoria ("auto-<n>"), então
    // o card não muda de lugar nem de token quando um app novo entra (o foco do controle não se perde). Só se grava a
    // frente escolhida (frente_auto) e os apps que a pessoa tirou de uma pilha automática (auto_fora).

    const val PREFIXO_AUTO = "auto-"

    fun pilhasAutomaticas(ctx: Context) = prefs(ctx).getBoolean("pilhas_auto", false)
    fun definirPilhasAutomaticas(ctx: Context, ligado: Boolean) = prefs(ctx).edit().putBoolean("pilhas_auto", ligado).apply()

    fun foraDaAuto(ctx: Context): Set<String> = HashSet(prefs(ctx).getStringSet("auto_fora", emptySet()) ?: emptySet())
    private fun tirarDaAuto(ctx: Context, pacote: String) =
        prefs(ctx).edit().putStringSet("auto_fora", HashSet(foraDaAuto(ctx) + pacote)).apply()

    fun frentesAuto(ctx: Context): Map<String, String> = try {
        val o = JSONObject(prefs(ctx).getString("frente_auto", "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.getString(it) }
    } catch (e: Exception) { emptyMap() }

    private fun definirFrenteAuto(ctx: Context, id: String, pacote: String) {
        val o = JSONObject(frentesAuto(ctx)).put(id, pacote)
        prefs(ctx).edit().putString("frente_auto", o.toString()).apply()
    }

    /**
     * Agrupa [categorias] (pacote -> categoria do Android, na ordem em que os apps aparecem) em pilhas automáticas:
     * só categorias conhecidas (>= 0) com 2 ou mais apps, tirando os de [fora]. Devolve id -> pacotes, na ordem de entrada.
     */
    fun agruparPorCategoria(categorias: List<Pair<String, Int>>, fora: Set<String>): Map<String, List<String>> =
        categorias.filter { it.second >= 0 && it.first !in fora }
            .groupBy({ PREFIXO_AUTO + it.second }, { it.first })
            .filterValues { it.size >= 2 }

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
