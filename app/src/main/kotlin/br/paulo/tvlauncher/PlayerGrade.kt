package br.paulo.tvlauncher

/**
 * Player universal, guia em grade (06/11/2026): a matemática da grade (canais × horários). Só lógica pura, testada em
 * PlayerGradeTest. Quem desenha é a PlayerGradeView.
 *
 * O cursor da grade é um INSTANTE (ms), não uma coluna: ◀ ▶ pulam de programa em programa da linha, e ▲ ▼ trocam de
 * canal mantendo o instante, caindo no programa que passa naquele horário no canal novo (como nas grades de TV a cabo).
 * O passado não interessa (não há "assistir de novo"): o cursor e a janela nunca vão antes de agora.
 */
object PlayerGrade {
    const val FAIXA = 30 * 60_000L           // largura de uma coluna da régua (30 min)
    const val JANELA = 3 * 60 * 60_000L      // quanto tempo cabe na tela (3 h)
    const val ALCANCE = 24 * 60 * 60_000L    // até onde dá para ir (o guia guarda ~26 h à frente)

    /** Começo da faixa de 30 min que contém o instante. Os fusos do Brasil são horas cheias: 21:47 -> 21:30 local. */
    fun inicioFaixa(instante: Long): Long = instante - Math.floorMod(instante, FAIXA)

    /** O programa no ar em `instante`, ou null (buraco no guia). */
    fun programaEm(ps: List<Programa>, instante: Long): Programa? = ps.firstOrNull { instante >= it.inicio && instante < it.fim }

    /** Programas que aparecem (mesmo que em parte) na janela [ini, ini + JANELA). */
    fun naJanela(ps: List<Programa>, ini: Long): List<Programa> = ps.filter { it.fim > ini && it.inicio < ini + JANELA }

    /**
     * ◀ ▶: o novo instante do cursor. Para a direita, o começo do próximo programa; para a esquerda, o do anterior
     * (mas nunca antes de `agora`). Linha sem guia, ou fim do guia: anda uma faixa de 30 min.
     */
    fun mover(ps: List<Programa>, foco: Long, passo: Int, agora: Long): Long {
        val atual = programaEm(ps, foco)
        val novo = if (passo > 0) {
            val limite = atual?.fim ?: (foco + 1)
            ps.firstOrNull { it.inicio >= limite }?.inicio ?: (inicioFaixa(foco) + FAIXA)
        } else {
            val limite = atual?.inicio ?: foco
            ps.lastOrNull { it.fim <= limite }?.inicio ?: (foco - FAIXA)
        }
        return novo.coerceIn(agora, agora + ALCANCE)
    }

    /** O começo da janela para o cursor ficar visível: só anda quando o cursor sai dela, e nunca antes da faixa de agora. */
    fun ajustarJanela(ini: Long, foco: Long, agora: Long): Long {
        val minimo = inicioFaixa(agora)
        val novo = when {
            foco < ini -> inicioFaixa(foco)
            foco >= ini + JANELA -> inicioFaixa(foco) - JANELA + FAIXA * 2   // o programa novo entra com folga à direita
            else -> ini
        }
        return novo.coerceAtLeast(minimo)
    }

    /** ▲ ▼: a linha nova (para no primeiro e no último canal, sem dar a volta: numa grade a volta desorienta). */
    fun moverLinha(linha: Int, passo: Int, total: Int): Int = if (total == 0) 0 else (linha + passo).coerceIn(0, total - 1)

    fun ajustarTopo(topo: Int, linha: Int, visiveis: Int, total: Int): Int {
        if (total <= visiveis) return 0
        val t = when {
            linha < topo -> linha
            linha >= topo + visiveis -> linha - visiveis + 1
            else -> topo
        }
        return t.coerceIn(0, total - visiveis)
    }
}
