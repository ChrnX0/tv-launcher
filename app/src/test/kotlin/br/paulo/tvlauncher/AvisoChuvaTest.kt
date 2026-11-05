package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Testes do aviso de chuva e do recado (só a lógica, sem Android: roda no computador). */
class AvisoChuvaTest {
    private val horas = listOf("2026-11-05T14:00", "2026-11-05T15:00", "2026-11-05T16:00", "2026-11-05T17:00")

    @Test fun avisaQuandoAChuvaChegaNaProximaHora() {
        assertEquals(AvisoChuva.Previsao("15h", 70), AvisoChuva.decidir(horas, listOf(10, 70, 80, 20), "2026-11-05T14"))
    }

    @Test fun avisaDuasHorasAntes() {
        assertEquals(AvisoChuva.Previsao("16h", 60), AvisoChuva.decidir(horas, listOf(10, 30, 60, 90), "2026-11-05T14"))
    }

    @Test fun naoAvisaTresHorasAntes() {
        assertNull(AvisoChuva.decidir(horas, listOf(10, 20, 30, 90), "2026-11-05T14"))
    }

    @Test fun naoAvisaSeAChuvaJaEAgora() {
        assertNull(AvisoChuva.decidir(horas, listOf(65, 90, 90, 90), "2026-11-05T14"))
    }

    @Test fun naoAvisaAbaixoDoLimiar() {
        assertNull(AvisoChuva.decidir(horas, listOf(10, 59, 59, 0), "2026-11-05T14"))
    }

    @Test fun usaAHoraDeAgoraMesmoQueAListaComeceAntes() {
        // A lista começa às 14h, mas agora são 15h: a "próxima hora" é 16h.
        assertEquals(AvisoChuva.Previsao("16h", 75), AvisoChuva.decidir(horas, listOf(90, 10, 75, 0), "2026-11-05T15"))
    }

    @Test fun horaFora() {
        assertNull(AvisoChuva.decidir(horas, listOf(0, 90, 90, 90), "2026-11-06T09"))
        assertNull(AvisoChuva.decidir(emptyList(), emptyList(), "2026-11-05T14"))
    }

    @Test fun medianaDeTresModelosDescartaOExagero() {
        // ECMWF 75, ICON 10, GFS 18 (caso medido no CSV de comparação): a mediana é 18.
        assertEquals(listOf(18, 50), AvisoChuva.mediana(listOf(listOf(75, 50), listOf(10, null), listOf(18, 50))))
    }

    @Test fun medianaParEVazia() {
        assertEquals(listOf(40, 0), AvisoChuva.mediana(listOf(listOf(30, null), listOf(50, null))))
        assertEquals(emptyList<Int>(), AvisoChuva.mediana(emptyList()))
    }

    @Test fun recadoLimpo() {
        assertEquals("Jantar pronto!", Recado.limpar("  Jantar   pronto! "))
        assertEquals("linha 1\nlinha 2", Recado.limpar("linha 1\r\n\n  linha 2\u0007"))
        assertEquals("a\nb\nc", Recado.limpar("a\nb\nc\nd"))
        assertNull(Recado.limpar("   "))
        assertNull(Recado.limpar(null))
        assertNull(Recado.limpar("x".repeat(Recado.MAX_CARACTERES + 1)))
    }

    @Test fun duracaoPresaAsOpcoes() {
        assertEquals(30, Recado.duracao(30))
        assertEquals(300, Recado.duracao(300))
        assertEquals(60, Recado.duracao(9999))
        assertEquals(60, Recado.duracao(null))
    }
}
