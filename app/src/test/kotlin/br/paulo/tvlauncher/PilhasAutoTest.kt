package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class PilhasAutoTest {
    private val VIDEO = 2; private val AUDIO = 1; private val JOGO = 0

    @Test fun agrupaSoComDoisOuMais() {
        val g = Organizacao.agruparPorCategoria(listOf(
            "netflix" to VIDEO, "spotify" to AUDIO, "youtube" to VIDEO, "loja" to -1, "xadrez" to JOGO, "prime" to VIDEO,
        ), emptySet())
        assertEquals(mapOf("auto-2" to listOf("netflix", "youtube", "prime")), g)
    }

    @Test fun respeitaOsTiradosEOsSemCategoria() {
        val g = Organizacao.agruparPorCategoria(listOf("a" to VIDEO, "b" to VIDEO, "c" to -1, "d" to -1), setOf("b"))
        assertEquals(emptyMap<String, List<String>>(), g)
    }

    @Test fun idFixoPorCategoria() {
        val g1 = Organizacao.agruparPorCategoria(listOf("a" to AUDIO, "b" to AUDIO), emptySet())
        val g2 = Organizacao.agruparPorCategoria(listOf("z" to AUDIO, "a" to AUDIO, "b" to AUDIO), emptySet())
        assertEquals(g1.keys, g2.keys)   // app novo entrando não troca o token do card (o foco não se perde)
    }
}
