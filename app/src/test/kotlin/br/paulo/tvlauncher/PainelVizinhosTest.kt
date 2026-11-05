package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class PainelVizinhosTest {
    private fun tv(nome: String, id: String, host: String, visto: Long) = PainelVizinhos.Tv(nome, id, host, 8080, visto)

    @Test fun juntaPeloIdTiraEstaTvEOrdena() {
        val l = listOf(
            tv("Sala", "aaaa1111", "192.168.1.10", 1),
            tv("Sala", "aaaa1111", "fe80::1", 5),      // a mesma TV por IPv6, vista depois
            tv("Quarto", "bbbb2222", "192.168.1.11", 2),
            tv("Eu", "cccc3333", "192.168.1.12", 3),   // esta TV
            tv("Sem id", "", "192.168.1.13", 4),       // anúncio sem ID: ignora
        )
        val r = PainelVizinhos.juntar(l, "cccc3333")
        assertEquals(listOf("Quarto", "Sala"), r.map { it.nome })
        assertEquals("fe80::1", r[1].host)
    }

    @Test fun idCurtoTemOitoLetrasEEstavel() {
        val a = PainelVizinhos.idCurto("1234567890abcdef")
        assertEquals(8, a.length)
        assertEquals(a, PainelVizinhos.idCurto("1234567890abcdef"))
    }
}
