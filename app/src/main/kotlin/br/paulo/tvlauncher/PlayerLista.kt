package br.paulo.tvlauncher

import org.json.JSONArray

/**
 * Player universal, lista completa (06/11/2026): categorias, favoritos e recentes. Só lógica pura (sem Android), testada
 * em PlayerListaTest. Quem desenha é a PlayerListaTela; quem grava os arquivos é o PlayerArmazem.
 *
 * As categorias são o "group-title" da M3U ou a categoria do Xtream (Canal.grupo), na ordem em que aparecem na lista
 * (é a ordem que o dono da lista escolheu: costuma pôr as principais primeiro). Na frente vêm três fixas:
 * Todos, Favoritos e Recentes.
 */
data class GrupoCanais(
    val chave: String,   // TODOS, FAVORITOS, RECENTES ou "g:<nome do grupo>"
    val nome: String,    // o que aparece na tela (as fixas são traduzidas pela tela, pela chave)
    val quantos: Int,
)

object PlayerLista {
    const val TODOS = "*todos"
    const val FAVORITOS = "*favoritos"
    const val RECENTES = "*recentes"
    const val MAX_RECENTES = 30
    const val MAX_FAVORITOS = 500

    /** Nome usado para canal sem grupo na lista (a tela mostra "Outros"). */
    const val SEM_GRUPO = ""

    fun chaveDoGrupo(nome: String) = "g:$nome"

    /** As categorias com a contagem de canais. Favoritos e Recentes aparecem sempre (mesmo vazias: é onde o usuário aprende que existem). */
    fun grupos(canais: List<Canal>, favoritos: Set<String>, recentes: List<String>): List<GrupoCanais> {
        val ids = canais.mapTo(HashSet()) { it.id }
        val contagem = LinkedHashMap<String, Int>()   // LinkedHashMap guarda a ordem da 1ª aparição
        for (c in canais) contagem[c.grupo.trim()] = (contagem[c.grupo.trim()] ?: 0) + 1
        val saida = ArrayList<GrupoCanais>(contagem.size + 3)
        saida += GrupoCanais(TODOS, "", canais.size)
        saida += GrupoCanais(FAVORITOS, "", favoritos.count { it in ids })
        saida += GrupoCanais(RECENTES, "", recentes.count { it in ids })
        // Lista sem grupo nenhum: a categoria "Outros" sozinha seria igual a "Todos". Não mostra.
        if (contagem.size == 1 && contagem.containsKey(SEM_GRUPO)) return saida
        for ((nome, n) in contagem) if (nome != SEM_GRUPO) saida += GrupoCanais(chaveDoGrupo(nome), nome, n)
        contagem[SEM_GRUPO]?.let { saida += GrupoCanais(chaveDoGrupo(SEM_GRUPO), SEM_GRUPO, it) }   // "Outros" vai por último
        return saida
    }

    /**
     * Os canais de uma categoria. Todos e os grupos ficam na ordem do número; Favoritos também (como o usuário conhece);
     * Recentes, do mais novo para o mais velho. Chave desconhecida (categoria que sumiu da lista) = Todos.
     */
    fun filtrar(canais: List<Canal>, chave: String, favoritos: Set<String>, recentes: List<String>): List<Canal> = when {
        chave == TODOS -> canais
        chave == FAVORITOS -> canais.filter { it.id in favoritos }
        chave == RECENTES -> {
            val porId = canais.associateBy { it.id }
            recentes.mapNotNull { porId[it] }
        }
        chave.startsWith("g:") -> {
            val nome = chave.removePrefix("g:")
            canais.filter { it.grupo.trim() == nome }
        }
        else -> canais
    }

    /** A categoria salva ainda existe nesta lista? Se não (a lista mudou), volta para Todos. */
    fun chaveValida(grupos: List<GrupoCanais>, chave: String?): String =
        chave?.takeIf { c -> grupos.any { it.chave == c } } ?: TODOS

    /**
     * Onde o ▲▼ zapeia: dentro da categoria escolhida, se o canal atual estiver nela (é o que o usuário espera depois de
     * escolher "Esportes"); senão, na lista inteira. Recentes não vale para zapear: a ordem muda a cada troca e o ▲
     * ficaria pulando entre os mesmos dois canais.
     */
    fun listaDeZapping(canais: List<Canal>, chave: String, favoritos: Set<String>, recentes: List<String>, atual: Canal?): List<Canal> {
        if (chave == TODOS || chave == RECENTES || atual == null) return canais
        val f = filtrar(canais, chave, favoritos, recentes)
        return if (f.size > 1 && f.any { it.id == atual.id }) f else canais
    }

    fun alternarFavorito(favoritos: Set<String>, id: String): Set<String> =
        if (id in favoritos) favoritos - id
        else if (favoritos.size >= MAX_FAVORITOS) favoritos
        else favoritos + id

    /** Põe o canal no topo dos recentes (sem repetir) e corta no máximo. */
    fun registrarRecente(recentes: List<String>, id: String): List<String> =
        (listOf(id) + recentes.filter { it != id }).take(MAX_RECENTES)

    /** Posição do canal na lista (para a lista abrir já no canal que está passando), ou 0. */
    fun posicao(lista: List<Canal>, id: String?): Int = lista.indexOfFirst { it.id == id }.coerceAtLeast(0)

    // ---------------------------------------------------------------- arquivo (lista de ids em JSON)

    fun idsParaJson(ids: Collection<String>): String = JSONArray().apply { ids.forEach { put(it) } }.toString()

    fun idsDeJson(texto: String?, max: Int): List<String> {
        val a = runCatching { JSONArray(texto ?: return emptyList()) }.getOrNull() ?: return emptyList()
        val saida = LinkedHashSet<String>()
        for (i in 0 until a.length()) {
            val s = a.optString(i).trim()
            if (s.isNotEmpty() && s.length <= 300) saida += s
            if (saida.size >= max) break
        }
        return saida.toList()
    }
}
