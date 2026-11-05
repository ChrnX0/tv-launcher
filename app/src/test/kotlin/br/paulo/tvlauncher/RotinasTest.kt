package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RotinasTest {
    @Test fun idaEVoltaPeloJson() {
        val l = listOf(
            Rotinas.Rotina(2, 23, 0, 0b1111111, Rotinas.Acao.DESLIGAR, ocioso = 60),
            Rotinas.Rotina(1, 7, 30, 0b0111110, Rotinas.Acao.ABRIR, pacote = "com.exemplo.jornal"),
        )
        assertEquals(l.sortedBy { it.hora }, Rotinas.deJson(Rotinas.paraJson(l)))
    }

    @Test fun itemEstragadoSaiSozinho() {
        val l = Rotinas.deJson("""[{"id":1,"hora":7,"minuto":0,"dias":62,"acao":"LIGAR"},{"id":2,"hora":8,"acao":"VOAR"},"lixo"]""")
        assertEquals(1, l.size)
        assertEquals(Rotinas.Acao.LIGAR, l[0].acao)
    }

    @Test fun naoQuebraComTextoInvalido() = assertEquals(emptyList<Rotinas.Rotina>(), Rotinas.deJson("{nada"))

    @Test fun desligarComOcio() {
        assertTrue(Rotinas.podeDesligar(0, 0, false))          // sem condição: sempre
        assertFalse(Rotinas.podeDesligar(60, 120, false))      // sem ver as teclas não arrisca
        assertFalse(Rotinas.podeDesligar(60, 10, true))        // alguém mexeu há 10 min
        assertTrue(Rotinas.podeDesligar(60, 61, true))
    }
}
