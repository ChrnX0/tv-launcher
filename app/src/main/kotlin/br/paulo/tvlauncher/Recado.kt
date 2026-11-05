package br.paulo.tvlauncher

/**
 * Recado na TV (05/11/2026): uma mensagem mandada pelo painel no navegador (ou pelo adb) aparece por cima de qualquer
 * tela da TV, ex.: "O jantar está pronto!". Quem mostra é o [AvisoTv]; aqui só a validação, sem nada do Android,
 * para o teste rodar no PC (RecadoTest).
 */
object Recado {
    const val MAX_CARACTERES = 200
    val DURACOES = listOf(30, 60, 300)   // segundos que o recado fica na tela (as opções do painel)

    /**
     * Texto pronto para a tela, ou null se não serve. Tira caracteres de controle (exceto a quebra de linha),
     * junta espaços repetidos e no máximo 3 linhas: o cartão não pode virar uma parede que tapa o filme.
     */
    fun limpar(bruto: String?): String? {
        if (bruto == null) return null
        val semControle = bruto.replace("\r\n", "\n").filter { it == '\n' || !it.isISOControl() }
        val linhas = semControle.split('\n').map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }.take(3)
        val t = linhas.joinToString("\n")
        if (t.isEmpty() || t.length > MAX_CARACTERES) return null
        return t
    }

    /** Duração pedida, presa às opções do painel (qualquer outro número vira 1 min). */
    fun duracao(segundos: Int?): Int = if (segundos != null && segundos in DURACOES) segundos else 60
}
