package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Filmes do Xtream: leitura do catálogo e da ficha, endereço, cache sem senha e "continuar". Dados inventados. */
class PlayerVodTest {
    private val conta = Xtream.Conta("http://servidor.invalid:8080", "ana", "s&nh@")
    private val json = """[
        {"num":1,"name":"BR: O Filme (2023)","stream_id":10,"stream_icon":"http://img.invalid/1.jpg","rating":"7.5","added":"1700000000","category_id":"3","container_extension":"mkv"},
        {"num":2,"name":"Outro","stream_id":"11","stream_icon":"javascript:x","rating":0,"added":1600000000,"category_id":"4","container_extension":"../x"},
        {"name":"sem id"}, {"stream_id":12,"name":""}
    ]"""
    private val cats = listOf(Xtream.Categoria("3", "Ação"), Xtream.Categoria("4", "Drama"))

    @Test fun leCatalogo() {
        val f = Vod.lerFilmes(json, "a1", cats)
        assertEquals(2, f.size)
        assertEquals("Ação", f[0].grupo); assertEquals(7.5f, f[0].nota); assertEquals(2023, f[0].ano); assertEquals("mkv", f[0].extensao)
        assertEquals("", f[1].capa)            // endereço que não é http(s) é recusado
        assertEquals("mp4", f[1].extensao)     // extensão estranha vira mp4 (entra no endereço)
        assertNull(f[1].nota)
        assertEquals("a1:f10", f[0].chave)
        assertEquals("O Filme", Vod.nomeLimpo(f[0].nome))
    }

    @Test fun enderecoECacheSemSenha() {
        val f = Vod.lerFilmes(json, "a1", cats)
        assertEquals("http://servidor.invalid:8080/movie/ana/s%26nh%40/10.mkv", Vod.urlFilme(conta, f[0]))
        val cache = Vod.filmesParaJson(f)
        assertFalse(cache.contains("nh%40")); assertFalse(cache.contains("ana"))
        assertEquals(f, Vod.filmesDeJson(cache, "a1"))
    }

    @Test fun leFicha() {
        val ficha = Vod.lerFicha("""{"info":{"plot":"Uma história.","cast":"A, B","director":"C","genre":"Drama","duration_secs":6720,
            "releasedate":"2023-05-01","backdrop_path":["ftp://x","http://img.invalid/f.jpg"]},"movie_data":{"stream_id":10}}""")!!
        assertEquals("Uma história.", ficha.sinopse); assertEquals(112, ficha.duracaoMin); assertEquals("http://img.invalid/f.jpg", ficha.fundo)
        assertEquals(112, Vod.lerFicha("""{"info":{"duration":"01:52:00"}}""")!!.duracaoMin)
        assertNull(Vod.lerFicha("<html>"))
    }

    @Test fun continuarDeOndeParou() {
        assertFalse(Vod.deveRetomar(Posicao(30_000, 6_000_000, 1)))       // menos de 1 min
        assertTrue(Vod.deveRetomar(Posicao(600_000, 6_000_000, 1)))
        assertFalse(Vod.deveRetomar(Posicao(5_800_000, 6_000_000, 1)))    // passou de 95%
        assertEquals(595_000, Vod.inicio(Posicao(600_000, 6_000_000, 1)))
        assertEquals(0, Vod.inicio(null))
        val f = Vod.lerFilmes(json, "a1", cats)
        val pos = mapOf("a1:f10" to Posicao(600_000, 6_000_000, 5), "a1:f11" to Posicao(900_000, 6_000_000, 9))
        assertEquals(listOf("11", "10"), Vod.continuar(f, pos).map { it.id })
        assertEquals(pos, Vod.posicoesDeJson(Vod.posicoesParaJson(pos)))
        val muitas = (1..Vod.MAX_POSICOES + 5).associate { "k$it" to Posicao(1, 1, it.toLong()) }
        val depois = Vod.registrar(muitas, "novo", Posicao(1, 1, 9_999))
        assertEquals(Vod.MAX_POSICOES, depois.size); assertTrue("novo" in depois); assertFalse("k1" in depois)
    }

    @Test fun ordemEBusca() {
        val f = Vod.lerFilmes(json, "a1", cats)
        assertEquals(listOf("10", "11"), Vod.ordenar(f, Vod.Ordem.RECENTES).map { it.id })
        assertEquals(listOf("10", "11"), Vod.ordenar(f, Vod.Ordem.NOTA).map { it.id })
        assertEquals(listOf("10", "11"), Vod.ordenar(f, Vod.Ordem.NOME).map { it.id })   // "O Filme" < "Outro"
        assertEquals(listOf("10"), Vod.buscar(f, "filme").map { it.id })
        assertEquals(listOf("Ação" to 1, "Drama" to 1), Vod.grupos(f))
        assertEquals(112, Vod.duracaoTexto("112 min"))
    }

    @Test fun assistidosEPercentual() {
        assertNull(Vod.progresso(null))
        assertNull(Vod.progresso(Posicao(30_000, 6_000_000, 1)))          // menos de 1 min: nem conta
        assertEquals(10, Vod.progresso(Posicao(600_000, 6_000_000, 1)))
        assertEquals(1, Vod.progresso(Posicao(61_000, 100_000_000, 1)))   // nunca "0%" para quem viu
        assertEquals(94, Vod.progresso(Posicao(5_690_000, 6_000_000, 1))) // 94,8%: ainda não terminou (arredonda para baixo)
        assertEquals(100, Vod.progresso(Posicao(5_700_000, 6_000_000, 1)))// 95%: Finalizado
        assertEquals(100, Vod.progresso(Posicao(6_000_000, 6_000_000, 1)))// a VodActivity grava o fim assim
        assertEquals(0, Vod.progresso(Posicao(600_000, 0, 1)))            // sem duração: só "Assistido"
        val f = Vod.lerFilmes(json, "a1", cats)
        val pos = mapOf("a1:f10" to Posicao(6_000_000, 6_000_000, 5), "a1:f11" to Posicao(900_000, 6_000_000, 9))
        assertEquals(listOf("11", "10"), Vod.assistidos(f, pos).map { it.id })   // terminado também fica, mais recente primeiro
        assertEquals(listOf("11"), Vod.continuar(f, pos).map { it.id })           // ... mas sai do "continuar"
    }

    @Test fun interesses() {
        val f = Vod.lerFilmes(json, "a1", cats)
        var i = Vod.alternarInteresse(emptySet(), "a1:f10")
        i = Vod.alternarInteresse(i, "a1:f11")
        i = Vod.alternarInteresse(i, "a1:fsumiu")                              // saiu do catálogo
        assertEquals(listOf("11", "10"), Vod.interesses(f, i) { it.chave }.map { it.id })   // último marcado primeiro
        i = Vod.alternarInteresse(i, "a1:f11")                                 // de novo = tira
        assertEquals(listOf("10"), Vod.interesses(f, i) { it.chave }.map { it.id })
        assertTrue("a1:fsumiu" in i)                                           // guardado, caso volte
        val cheio = (1..Vod.MAX_INTERESSES).map { "k$it" }.toSet()
        assertTrue(Vod.alternarInteresse(cheio, "novo") === cheio)             // cheio: não muda (a tela avisa)
        // A ordem sobrevive ao arquivo (mesmo JSON dos favoritos de canais).
        val volta = PlayerLista.idsDeJson(PlayerLista.idsParaJson(i), Vod.MAX_INTERESSES)
        assertEquals(i.toList(), volta)
    }
}
