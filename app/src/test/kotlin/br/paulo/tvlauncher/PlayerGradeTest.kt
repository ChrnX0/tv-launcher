package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Guia em grade: régua, cursor por instante e rolagem. Horários inventados (minutos desde um começo qualquer). */
class PlayerGradeTest {
    private val m = 60_000L
    private val base = 1_000L * 30 * m   // múltiplo de 30 min
    private fun p(ini: Int, fim: Int, t: String) = Programa("c", base + ini * m, base + fim * m, t)
    private val ps = listOf(p(0, 50, "A"), p(50, 60, "B"), p(60, 150, "C"), p(180, 240, "D"))   // buraco 150-180

    @Test fun faixa() {
        assertEquals(base, PlayerGrade.inicioFaixa(base + 29 * m))
        assertEquals(base + 30 * m, PlayerGrade.inicioFaixa(base + 30 * m))
    }

    @Test fun programaEJanela() {
        assertEquals("C", PlayerGrade.programaEm(ps, base + 61 * m)?.titulo)
        assertNull(PlayerGrade.programaEm(ps, base + 160 * m))
        assertEquals(listOf("C", "D"), PlayerGrade.naJanela(ps, base + 120 * m).map { it.titulo })
    }

    @Test fun moveDeProgramaEmPrograma() {
        val agora = base + 10 * m
        assertEquals(base + 50 * m, PlayerGrade.mover(ps, agora, +1, agora))           // A -> B
        assertEquals(base + 60 * m, PlayerGrade.mover(ps, base + 50 * m, +1, agora))   // B -> C
        assertEquals(base + 180 * m, PlayerGrade.mover(ps, base + 70 * m, +1, agora))  // C -> D, pulando o buraco
        assertEquals(base + 50 * m, PlayerGrade.mover(ps, base + 70 * m, -1, agora))   // C -> B
        assertEquals(agora, PlayerGrade.mover(ps, base + 55 * m, -1, agora))           // B -> A, que começou antes de agora
        assertEquals(agora, PlayerGrade.mover(ps, agora, -1, agora))                   // não vai para o passado
        // Linha sem guia: anda de 30 em 30 min.
        assertEquals(base + 30 * m, PlayerGrade.mover(emptyList(), agora, +1, agora))
        assertEquals(base + 30 * m, PlayerGrade.mover(emptyList(), base + 60 * m, -1, agora))
    }

    @Test fun janelaSoAndaQuandoOCursorSai() {
        val agora = base + 10 * m
        assertEquals(base, PlayerGrade.ajustarJanela(base, base + 100 * m, agora))
        assertEquals(base + 60 * m, PlayerGrade.ajustarJanela(base, base + 200 * m, agora))   // 180 fica a 2 h do começo: 1 h de folga
        assertEquals(base, PlayerGrade.ajustarJanela(base + 90 * m, base + 20 * m, agora))
        assertEquals(base, PlayerGrade.ajustarJanela(base, base - 60 * m, agora))             // nunca antes da faixa de agora
    }

    @Test fun linhasETopo() {
        assertEquals(0, PlayerGrade.moverLinha(0, -1, 10)); assertEquals(9, PlayerGrade.moverLinha(9, +1, 10))
        assertEquals(0, PlayerGrade.ajustarTopo(0, 5, 8, 20))
        assertEquals(1, PlayerGrade.ajustarTopo(0, 8, 8, 20))
        assertEquals(3, PlayerGrade.ajustarTopo(5, 3, 8, 20))
        assertEquals(12, PlayerGrade.ajustarTopo(12, 19, 8, 20))
        assertEquals(0, PlayerGrade.ajustarTopo(3, 4, 8, 5))
    }
}
