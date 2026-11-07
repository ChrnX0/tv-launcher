package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Séries do Xtream: catálogo, episódios nos dois formatos, próximo episódio e "continuar". Dados inventados. */
class PlayerSeriesTest {
    private val conta = Xtream.Conta("http://servidor.invalid", "ana", "s&nh@")
    private val ficha = Series.lerFicha("""{"info":{"plot":"Sinopse","genre":"Drama"},
        "episodes":{"2":[{"id":"21","episode_num":1,"title":"Volta","container_extension":"mkv","info":{"duration_secs":2700}}],
                    "1":[{"id":"12","episode_num":2,"title":"Dois"},{"id":"11","episode_num":1,"title":"Um","season":1},{"title":"sem id"}]}}""")!!

    @Test fun leCatalogo() {
        val s = Series.lerSeries("""[{"series_id":5,"name":"A Série (2021)","cover":"http://img.invalid/c.jpg","rating":"8","category_id":"1","last_modified":"1700000000"},
            {"series_id":6,"name":""},{"name":"sem id"}]""", "a1", listOf(Xtream.Categoria("1", "Drama")))
        assertEquals(1, s.size)
        assertEquals("Drama", s[0].grupo); assertEquals(2021, s[0].ano); assertEquals(8f, s[0].nota); assertEquals("a1:s5", s[0].chave)
        assertEquals(s, Series.seriesDeJson(Series.seriesParaJson(s), "a1"))
    }

    @Test fun leEpisodiosEmOrdem() {
        assertEquals(listOf(1, 2), ficha.temporadas.keys.toList())
        assertEquals(listOf("11", "12", "21"), Series.emOrdem(ficha).map { it.id })
        assertEquals(45, ficha.temporadas[2]!![0].duracaoMin)
        assertEquals("T2E1", ficha.temporadas[2]!![0].rotulo)
        // Formato em lista de listas.
        val l = Series.lerFicha("""{"info":{},"episodes":[[{"id":"1","season":1,"episode_num":1}],[{"id":"2","season":2,"episode_num":1}]]}""")!!
        assertEquals(listOf(1, 2), l.temporadas.keys.toList())
        assertNull(Series.lerFicha("<html>"))
    }

    @Test fun enderecoSemSenhaNoCache() {
        assertEquals("http://servidor.invalid/series/ana/s%26nh%40/21.mkv", Series.urlEpisodio(conta, "21", "mkv"))
        val u = mapOf("a1:s5" to UltimoEpisodio("21", 2, 1, "Volta", "mkv", 10))
        val json = Series.ultimosParaJson(u)
        assertFalse(json.contains("ana"))
        assertEquals(u, Series.ultimosDeJson(json))
    }

    @Test fun proximoESugestao() {
        val todos = Series.emOrdem(ficha)
        assertEquals("21", Series.proximo(ficha, todos[1])?.id)   // fim da temporada 1 -> T2E1
        assertNull(Series.proximo(ficha, todos[2]))
        assertEquals("11" to false, Series.sugestao(ficha, "a1", null, emptyMap())?.let { it.first.id to it.second })
        val ult = UltimoEpisodio("12", 1, 2, "Dois", "mp4", 5)
        val meio = mapOf("a1:e12" to Posicao(600_000, 2_700_000, 5))
        assertEquals("12" to true, Series.sugestao(ficha, "a1", ult, meio)?.let { it.first.id to it.second })
        val fim = mapOf("a1:e12" to Posicao(2_700_000, 2_700_000, 5))
        assertEquals("21" to false, Series.sugestao(ficha, "a1", ult, fim)?.let { it.first.id to it.second })
    }

    @Test fun continuarEOrdem() {
        val s = listOf(Serie("1", "a", "Um", atualizada = 5), Serie("2", "a", "Dois", atualizada = 9))
        val u = mapOf("a:s1" to UltimoEpisodio("x", 1, 1, "", "mp4", 50))
        assertEquals(listOf("1"), Series.continuar(s, u).map { it.id })
        assertEquals(listOf("2", "1"), Series.ordenar(s, Vod.Ordem.RECENTES).map { it.id })
        assertEquals(listOf("2"), Series.buscar(s, "dois").map { it.id })
        val muitas = (1..205).associate { "k$it" to UltimoEpisodio("e", 1, 1, "", "mp4", it.toLong()) }
        assertTrue(Series.registrarUltimo(muitas, "novo", UltimoEpisodio("e", 1, 1, "", "mp4", 999)).size == 200)
    }

    @Test fun assistidosEFinalizado() {
        val serie = Serie("5", "a1", "A Série", atualizada = 100)
        val vista = mapOf("a1:e11" to Posicao(2_700_000, 2_700_000, 1))                 // T1E1 até o fim
        val p1 = Series.progressoDaFicha(serie, ficha, vista, null)!!
        assertEquals(ProgressoSerie(1, 3, 100), p1)
        assertEquals(33, Series.percentual(serie, p1))
        assertNull(Series.percentual(serie, Series.progressoDaFicha(serie, ficha, emptyMap(), null)))   // nada visto
        val todas = vista + ("a1:e12" to Posicao(120_000, 2_700_000, 2)) + ("a1:e21" to Posicao(2_600_000, 2_700_000, 3))
        val p3 = Series.progressoDaFicha(serie, ficha, todas, p1)!!
        assertEquals(100, Series.percentual(serie, p3))                                  // 3 de 3: Finalizado
        // Posições velhas sumiram do arquivo: o visto continua visto (total igual).
        assertEquals(3, Series.progressoDaFicha(serie, ficha, emptyMap(), p3)!!.vistos)
        // Episódio novo no servidor depois da conta: deixa de ser Finalizado.
        assertEquals(99, Series.percentual(serie.copy(atualizada = 200), p3))
        // Finalizada sai do "continuar" e fica nos "assistidos".
        val u = mapOf(serie.chave to UltimoEpisodio("21", 2, 1, "", "mp4", 9))
        val prog = mapOf(serie.chave to p3)
        assertTrue(Series.continuar(listOf(serie), u, prog).isEmpty())
        assertEquals(listOf("5"), Series.assistidos(listOf(serie), u, todas, prog).map { it.id })
        // Sem conta ainda, mas com o último episódio visto: entra nos assistidos.
        assertEquals(listOf("5"), Series.assistidos(listOf(serie), u, todas, emptyMap()).map { it.id })
        assertEquals(prog, Series.progressoDeJson(Series.progressoParaJson(prog)))
    }
}
