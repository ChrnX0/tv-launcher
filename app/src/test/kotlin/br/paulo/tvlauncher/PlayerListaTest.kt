package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lista completa do player: categorias, favoritos, recentes e zapping dentro da categoria. Dados inventados. */
class PlayerListaTest {
    private fun c(id: String, n: Int, g: String) = Canal(id, "Canal $id", n, g, url = "http://e.invalid/$id")

    private val canais = listOf(
        c("a", 1, "Notícias"), c("b", 2, "Esportes"), c("c", 3, "Notícias"),
        c("d", 4, ""), c("e", 5, " Esportes "), c("f", 6, "Filmes"),
    )

    @Test fun gruposNaOrdemDaListaComFixasNaFrenteEOutrosNoFim() {
        val g = PlayerLista.grupos(canais, setOf("b", "sumiu"), listOf("f", "a"))
        assertEquals(listOf(PlayerLista.TODOS, PlayerLista.FAVORITOS, PlayerLista.RECENTES, "g:Notícias", "g:Esportes", "g:Filmes", "g:"),
            g.map { it.chave })
        assertEquals(listOf(6, 1, 2, 2, 2, 1, 1), g.map { it.quantos })   // favorito que sumiu da lista não conta
    }

    @Test fun listaSemGrupoNaoMostraOutros() {
        val semGrupo = listOf(c("a", 1, ""), c("b", 2, ""))
        assertEquals(3, PlayerLista.grupos(semGrupo, emptySet(), emptyList()).size)
    }

    @Test fun filtra() {
        assertEquals(listOf("b", "e"), PlayerLista.filtrar(canais, "g:Esportes", emptySet(), emptyList()).map { it.id })
        assertEquals(listOf("d"), PlayerLista.filtrar(canais, "g:", emptySet(), emptyList()).map { it.id })
        assertEquals(listOf("b", "f"), PlayerLista.filtrar(canais, PlayerLista.FAVORITOS, setOf("f", "b"), emptyList()).map { it.id })
        // Recentes: do mais novo para o mais velho, ignorando o que sumiu.
        assertEquals(listOf("f", "a"), PlayerLista.filtrar(canais, PlayerLista.RECENTES, emptySet(), listOf("f", "x", "a")).map { it.id })
        assertEquals(6, PlayerLista.filtrar(canais, "desconhecida", emptySet(), emptyList()).size)
    }

    @Test fun categoriaQueSumiuVoltaParaTodos() {
        val g = PlayerLista.grupos(canais, emptySet(), emptyList())
        assertEquals("g:Filmes", PlayerLista.chaveValida(g, "g:Filmes"))
        assertEquals(PlayerLista.TODOS, PlayerLista.chaveValida(g, "g:Novelas"))
        assertEquals(PlayerLista.TODOS, PlayerLista.chaveValida(g, null))
    }

    @Test fun zapeiaDentroDaCategoria() {
        val noticias = PlayerLista.listaDeZapping(canais, "g:Notícias", emptySet(), emptyList(), canais[0])
        assertEquals(listOf("a", "c"), noticias.map { it.id })
        assertEquals("c", PlayerDados.vizinho(noticias, canais[0], +1)?.id)
        // Canal atual fora da categoria (veio pelo número): zapeia na lista toda.
        assertEquals(6, PlayerLista.listaDeZapping(canais, "g:Notícias", emptySet(), emptyList(), canais[1]).size)
        // Categoria de 1 canal só: ▲▼ ficaria parado. Usa a lista toda.
        assertEquals(6, PlayerLista.listaDeZapping(canais, "g:Filmes", emptySet(), emptyList(), canais[5]).size)
        // Recentes nunca: a ordem muda a cada troca.
        assertEquals(6, PlayerLista.listaDeZapping(canais, PlayerLista.RECENTES, emptySet(), listOf("a", "b"), canais[0]).size)
    }

    @Test fun buscaViraCategoriaEAcaoNuncaEhEscolhida() {
        val g = PlayerLista.grupos(canais, emptySet(), emptyList(), "esp" to 2)
        assertEquals(PlayerLista.BUSCA, g[3].chave); assertEquals("esp", g[3].nome); assertEquals(2, g[3].quantos)
        assertEquals(PlayerLista.BUSCA, PlayerLista.chaveValida(g, PlayerLista.BUSCA))
        assertEquals(PlayerLista.TODOS, PlayerLista.chaveValida(PlayerLista.grupos(canais, emptySet(), emptyList()), PlayerLista.BUSCA))
        assertEquals(PlayerLista.TODOS, PlayerLista.chaveValida(g, PlayerLista.ACAO_FILTROS))
        assertEquals(6, PlayerLista.listaDeZapping(canais, PlayerLista.BUSCA, emptySet(), emptyList(), canais[0]).size)
    }

    @Test fun favoritosERecentes() {
        assertEquals(setOf("a"), PlayerLista.alternarFavorito(emptySet(), "a"))
        assertEquals(emptySet<String>(), PlayerLista.alternarFavorito(setOf("a"), "a"))
        val cheio = (1..PlayerLista.MAX_FAVORITOS).map { "x$it" }.toSet()
        assertSame(cheio, PlayerLista.alternarFavorito(cheio, "novo"))
        assertEquals(listOf("b", "a", "c"), PlayerLista.registrarRecente(listOf("a", "b", "c"), "b"))
        val muitos = (1..40).map { "r$it" }
        assertEquals(PlayerLista.MAX_RECENTES, PlayerLista.registrarRecente(muitos, "novo").size)
    }

    @Test fun arquivoIdaEVolta() {
        val ids = listOf("f1:x12", "f2:http://e.invalid/a,b")
        assertEquals(ids, PlayerLista.idsDeJson(PlayerLista.idsParaJson(ids), 10))
        assertEquals(listOf("a"), PlayerLista.idsDeJson("""["a","a",""," "]""", 10))
        assertTrue(PlayerLista.idsDeJson("lixo", 10).isEmpty())
        assertEquals(2, PlayerLista.idsDeJson("""["a","b","c"]""", 2).size)
    }

    @Test fun nomeExibidoTiraOPrefixoDoTipo() {
        assertEquals("Jogos de Hoje", PlayerLista.nomeExibido("Canais | Jogos de Hoje"))
        assertEquals("Ação", PlayerLista.nomeExibido("FILMES: Ação"))
        assertEquals("Drama", PlayerLista.nomeExibido("Séries - Drama"))
        assertEquals("HBO Max", PlayerLista.nomeExibido("Séries / HBO Max"))
        assertEquals("Esportes", PlayerLista.nomeExibido("Esportes"))
        assertEquals("Canal Brasil", PlayerLista.nomeExibido("Canal Brasil"))   // sem separador: é nome, não prefixo
        assertEquals("Canais |", PlayerLista.nomeExibido("Canais |"))           // só o prefixo: mantém o original
    }
}
