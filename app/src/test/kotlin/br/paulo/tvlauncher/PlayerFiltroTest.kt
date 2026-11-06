package br.paulo.tvlauncher

import br.paulo.tvlauncher.PlayerFiltro.Qualidade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Busca e filtros do player. Nomes inventados, no formato que as listas costumam usar. */
class PlayerFiltroTest {
    private fun c(id: String, nome: String, g: String = "", n: Int = 0) = Canal(id, nome, n, g, url = "http://e.invalid/$id")

    @Test fun qualidadePeloNome() {
        assertEquals(Qualidade.UHD, PlayerFiltro.qualidade("ESPN 4K"))
        assertEquals(Qualidade.UHD, PlayerFiltro.qualidade("Filmes UHD"))
        assertEquals(Qualidade.FHD, PlayerFiltro.qualidade("BR: Canal Um FHD"))
        assertEquals(Qualidade.FHD, PlayerFiltro.qualidade("Canal Um 1080p"))
        assertEquals(Qualidade.FHD, PlayerFiltro.qualidade("Canal FHD/HD"))   // a mais alta vence
        assertEquals(Qualidade.HD, PlayerFiltro.qualidade("Canal Dois HD"))
        assertEquals(Qualidade.SD, PlayerFiltro.qualidade("Canal Três SD"))
        assertNull(PlayerFiltro.qualidade("Canal Quatro"))
        assertNull(PlayerFiltro.qualidade("HDMI Notícias"))   // "HD" só como palavra solta
    }

    @Test fun paisPeloPrefixo() {
        assertEquals("BR", PlayerFiltro.pais(c("1", "BR: Canal")))
        assertEquals("PT", PlayerFiltro.pais(c("2", "[PT] Canal")))
        assertEquals("US", PlayerFiltro.pais(c("3", "US | Canal")))
        assertEquals("ES", PlayerFiltro.pais(c("4", "ES - Canal")))
        assertEquals("BR", PlayerFiltro.pais(c("5", "Canal", g = "BR | Esportes")))   // sem prefixo no nome: vale o grupo
        assertNull(PlayerFiltro.pais(c("6", "HD: Canal")))   // sigla que não é país
        assertNull(PlayerFiltro.pais(c("7", "Canal Brasil")))
        assertNull(PlayerFiltro.pais(c("8", "TV Cultura")))
    }

    @Test fun paisesEQualidadesPresentes() {
        val l = listOf(c("1", "BR: A HD"), c("2", "BR: B 4K"), c("3", "PT: C HD"), c("4", "D"))
        assertEquals(listOf("BR" to 2, "PT" to 1), PlayerFiltro.paises(l))
        assertEquals(listOf(Qualidade.UHD to 1, Qualidade.HD to 2), PlayerFiltro.qualidades(l))
    }

    @Test fun ocultasEAdulto() {
        assertTrue(PlayerFiltro.ehAdulto("Adultos +18")); assertTrue(PlayerFiltro.ehAdulto("XXX")); assertFalse(PlayerFiltro.ehAdulto("Esportes"))
        val l = listOf(c("1", "A", "Esportes"), c("2", "B", "Adulto"), c("3", "C", "Notícias"))
        assertEquals(setOf("Adulto"), PlayerFiltro.ocultasEfetivas(l, emptySet(), emptySet()))
        assertEquals(setOf("Notícias"), PlayerFiltro.ocultasEfetivas(l, setOf("Notícias"), setOf("Adulto")))
        val f = PlayerFiltro.Filtro(ocultas = setOf("Adulto"))
        assertEquals(listOf("1", "3"), PlayerFiltro.aplicar(l, f).map { it.id })
        assertEquals(3, PlayerFiltro.aplicar(l, f.copy(mostrarOcultas = true)).size)
    }

    @Test fun aplicaQualidadeEPais() {
        val l = listOf(c("1", "BR: A HD"), c("2", "BR: B 4K"), c("3", "PT: C HD"), c("4", "D"))
        assertEquals(listOf("1", "3"), PlayerFiltro.aplicar(l, PlayerFiltro.Filtro(qualidade = Qualidade.HD)).map { it.id })
        assertEquals(listOf("1"), PlayerFiltro.aplicar(l, PlayerFiltro.Filtro(qualidade = Qualidade.HD, pais = "BR")).map { it.id })
        assertEquals(4, PlayerFiltro.aplicar(l, PlayerFiltro.Filtro()).size)
        assertEquals("4K · BR", PlayerFiltro.resumo(PlayerFiltro.Filtro(Qualidade.UHD, "BR")))
    }

    @Test fun busca() {
        val l = listOf(c("1", "Esporte Interativo HD", n = 30), c("2", "Canal do Esporte", n = 31), c("3", "Notícias Já", n = 7), c("4", "Filmes", n = 8))
        assertEquals(listOf("1", "2"), PlayerFiltro.buscar(l, "esporte").map { it.id })
        assertEquals(listOf("3"), PlayerFiltro.buscar(l, "noticias").map { it.id })   // sem acento acha com acento
        assertEquals(listOf("1"), PlayerFiltro.buscar(l, "interativo esporte").map { it.id })   // todas as palavras, qualquer ordem
        assertEquals(listOf("4"), PlayerFiltro.buscar(l, "8").map { it.id })
        // Pelo programa que está passando.
        val prog = mapOf("3" to "Jornal da Noite")
        assertEquals(listOf("3"), PlayerFiltro.buscar(l, "jornal", { prog[it.id] }).map { it.id })
        assertTrue(PlayerFiltro.buscar(l, "  ").isEmpty())
    }
}
