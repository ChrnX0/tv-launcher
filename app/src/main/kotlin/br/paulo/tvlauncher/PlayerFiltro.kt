package br.paulo.tvlauncher

import java.text.Normalizer

/**
 * Player universal, busca e filtros (06/11/2026). Só lógica pura (sem Android), testada em PlayerFiltroTest.
 *
 * As listas de IPTV não trazem qualidade nem país em campo próprio: vêm no NOME, por convenção ("BR: Globo FHD",
 * "[PT] RTP 1 HD", "ESPN 4K"). Por isso tudo aqui é leitura de nome. Canal sem marca fica como "desconhecida" e só
 * some quando o usuário escolhe uma qualidade específica.
 */
object PlayerFiltro {
    enum class Qualidade { SD, HD, FHD, UHD }

    /** Filtro em vigor. `ocultas` = nomes de grupo escondidos; `mostrarOcultas` só fica true depois do PIN. */
    data class Filtro(
        val qualidade: Qualidade? = null,   // null = qualquer
        val pais: String? = null,           // null = qualquer
        val ocultas: Set<String> = emptySet(),
        val mostrarOcultas: Boolean = false,
    ) {
        val ativo: Boolean get() = qualidade != null || pais != null
    }

    // Palavras soltas (\b) para "HD" não casar com "HDTV Esportes" de outro jeito, e "4K" não casar dentro de "S4K".
    private val reUhd = Regex("""\b(4K|UHD|2160P?)\b""", RegexOption.IGNORE_CASE)
    private val reFhd = Regex("""\b(FHD|FULL ?HD|1080[PI]?)\b""", RegexOption.IGNORE_CASE)
    private val reHd = Regex("""\b(HD|HDTV|720P?)\b""", RegexOption.IGNORE_CASE)
    private val reSd = Regex("""\b(SD|480P?|576P?)\b""", RegexOption.IGNORE_CASE)

    /** Qualidade pelo nome do canal; null = o nome não diz. A mais alta que aparecer vence ("FHD/HD" = FHD). */
    fun qualidade(nome: String): Qualidade? = when {
        reUhd.containsMatchIn(nome) -> Qualidade.UHD
        reFhd.containsMatchIn(nome) -> Qualidade.FHD
        reHd.containsMatchIn(nome) -> Qualidade.HD
        reSd.containsMatchIn(nome) -> Qualidade.SD
        else -> null
    }

    // "BR: x", "BR | x", "BR - x", "[BR] x", "(BR) x", "|BR| x": 2 ou 3 letras maiúsculas no começo.
    private val rePais = Regex("""^\s*[\[(|]?\s*([A-Z]{2,3})\s*(?:[\])|]\s*[:|\-]?|[:|]|\s-\s)\s*\S""")
    // Siglas que aparecem no começo do nome e não são país.
    private val naoPais = setOf("HD", "SD", "FHD", "UHD", "TV", "VIP", "PPV", "VOD", "EPG", "NEW", "XXX", "LIVE", "24H", "ESPN", "HBO", "FOX")

    /** País/idioma pelo prefixo do nome do canal, senão pelo do grupo. null = sem prefixo. */
    fun pais(c: Canal): String? = prefixo(c.nome) ?: prefixo(c.grupo)

    private fun prefixo(s: String): String? = rePais.find(s)?.groupValues?.get(1)?.takeIf { it !in naoPais }

    /** Países presentes, do mais comum ao menos. Menos de 2 países = o filtro não serve para nada (a tela nem mostra). */
    fun paises(canais: List<Canal>): List<Pair<String, Int>> =
        canais.mapNotNull { pais(it) }.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key to it.value }

    /** Qualidades presentes (só as que existem viram opção), da melhor para a pior. */
    fun qualidades(canais: List<Canal>): List<Pair<Qualidade, Int>> =
        canais.mapNotNull { qualidade(it.nome) }.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.key.ordinal }.map { it.key to it.value }

    // ---------------------------------------------------------------- categorias ocultas

    private val reAdulto = Regex("""(adult|adulto|xxx|\+ ?18|18 ?\+|porn|erotic|er[oó]tico|hot ?club|sexy)""", RegexOption.IGNORE_CASE)

    /** Grupo de conteúdo adulto pelo nome: começa oculto, sem o usuário ter de achar e esconder. */
    fun ehAdulto(grupo: String): Boolean = reAdulto.containsMatchIn(grupo)

    /** Ocultas de fato = as que o usuário escondeu + as adultas, menos as adultas que ele liberou (com PIN). */
    fun ocultasEfetivas(canais: List<Canal>, escondidas: Set<String>, liberadas: Set<String>): Set<String> {
        val adultas = canais.asSequence().map { it.grupo.trim() }.filter { it.isNotEmpty() && ehAdulto(it) }.toSet()
        return escondidas + (adultas - liberadas)
    }

    /** Aplica o filtro. Grupo oculto some de tudo (lista, ▲▼ e número digitado) até o PIN mostrar. */
    fun aplicar(canais: List<Canal>, f: Filtro): List<Canal> {
        if (!f.ativo && (f.ocultas.isEmpty() || f.mostrarOcultas)) return canais
        return canais.filter { c ->
            (f.mostrarOcultas || c.grupo.trim() !in f.ocultas) &&
                (f.qualidade == null || qualidade(c.nome) == f.qualidade) &&
                (f.pais == null || pais(c) == f.pais)
        }
    }

    // ---------------------------------------------------------------- busca

    /** Minúsculas, sem acento e só letras/números separados por um espaço: "Esporte Interativo HD" ~ "esporte inter". */
    fun normalizar(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("""\p{Mn}+"""), "").lowercase()
            .replace(Regex("""[^a-z0-9]+"""), " ").trim()

    /**
     * Busca por nome do canal e pelo programa que está passando (`programa` devolve o título de agora, ou null).
     * Todas as palavras do termo precisam aparecer. Ordem: nome que começa com o termo, nome que contém, depois
     * quem só bateu pelo programa. Número digitado busca também o número do canal.
     */
    fun buscar(canais: List<Canal>, termo: String, programa: (Canal) -> String? = { null }, max: Int = 500): List<Canal> {
        val t = normalizar(termo)
        if (t.isEmpty()) return emptyList()
        val palavras = t.split(' ')
        val comeca = ArrayList<Canal>(); val contem = ArrayList<Canal>(); val peloPrograma = ArrayList<Canal>()
        for (c in canais) {
            val n = normalizar(c.nome)
            when {
                n.startsWith(t) || c.numero.toString() == t -> comeca += c
                palavras.all { n.contains(it) } -> contem += c
                else -> programa(c)?.let { p -> val np = normalizar(p); if (palavras.all { np.contains(it) }) peloPrograma += c }
            }
            if (comeca.size >= max) break
        }
        return (comeca + contem + peloPrograma).take(max)
    }

    /** Resumo curto do filtro para o título da coluna ("4K · BR"); vazio = nenhum. */
    fun resumo(f: Filtro): String = listOfNotNull(f.qualidade?.let { rotulo(it) }, f.pais).joinToString(" · ")

    fun rotulo(q: Qualidade): String = when (q) { Qualidade.UHD -> "4K"; Qualidade.FHD -> "Full HD"; Qualidade.HD -> "HD"; Qualidade.SD -> "SD" }
}
