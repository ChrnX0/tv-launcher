package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Player universal, passo 2: contas, cache sem senha, junção de várias listas e navegação. Dados inventados. */
class PlayerDadosTest {
    private val m3u = PlayerDados.criar("a1", " Casa ", FonteTv.TIPO_M3U, " http://exemplo.invalid/lista.m3u ").getOrThrow()
    private val xt = PlayerDados.criar("b2", "", FonteTv.TIPO_XTREAM, "servidor.invalid:8080", "ana", "s&nh@").getOrThrow()

    @Test fun criaEValida() {
        assertEquals("Casa", m3u.nome); assertEquals("http://exemplo.invalid/lista.m3u", m3u.endereco)
        assertEquals("http://servidor.invalid:8080", xt.endereco); assertEquals("Conta", xt.nome)
        assertTrue(PlayerDados.criar("c", "", FonteTv.TIPO_M3U, "file:///sdcard/x.m3u").isFailure)
        assertTrue(PlayerDados.criar("c", "", FonteTv.TIPO_XTREAM, "servidor.invalid", "ana", "").isFailure)
        assertTrue(PlayerDados.criar("c", "", FonteTv.TIPO_M3U, "http://e.invalid/l", guia = "ftp://g").isFailure)
        assertTrue(PlayerDados.criar("c", "", "outro", "http://e.invalid/l").isFailure)
    }

    @Test fun servidorVisivelSemSegredo() {
        assertEquals("servidor.invalid:8080", xt.servidorVisivel)
        val comSenhaNoLink = PlayerDados.criar("d", "", FonteTv.TIPO_M3U, "http://u:p@lista.invalid/get.php?username=u&password=p").getOrThrow()
        assertEquals("lista.invalid", comSenhaNoLink.servidorVisivel)
    }

    @Test fun contasIdaEVolta() {
        val volta = PlayerDados.fontesDeJson(PlayerDados.fontesParaJson(listOf(m3u, xt)))
        assertEquals(listOf(m3u, xt), volta)
        assertTrue(PlayerDados.fontesDeJson("lixo").isEmpty())
        assertTrue(PlayerDados.fontesDeJson(null).isEmpty())
    }

    @Test fun cacheDoXtreamNaoGuardaSenha() {
        val canais = Xtream.lerCanais("""[{"num":1,"name":"Um","stream_id":7,"epg_channel_id":"um"}]""", xt.xtream!!)
        assertTrue(canais[0].url.contains("s%26nh%40"))
        val json = PlayerDados.canaisParaJson(canais, xt)
        assertFalse(json.contains("nh%40")); assertFalse(json.contains("ana/"))
        assertEquals(canais, PlayerDados.canaisDeJson(json, xt))   // a URL volta remontada com a conta
    }

    @Test fun cacheDaListaM3u() {
        val canais = M3u.ler("#EXTM3U\n#EXTINF:-1 tvg-id=\"g\",Um\nhttp://exemplo.invalid/1.ts\n")
        assertEquals(canais, PlayerDados.canaisDeJson(PlayerDados.canaisParaJson(canais, m3u), m3u))
    }

    @Test fun programasIdaEVolta() {
        val p = listOf(Programa("g", 1000, 2000, "Jornal", "desc", "Notícias"), Programa("g", 2000, 1500, "ruim"))
        assertEquals(p.take(1), PlayerDados.programasDeJson(PlayerDados.programasParaJson(p)))
    }

    @Test fun juntaSemNumeroRepetido() {
        val a = M3u.ler("#EXTINF:-1,A1\nhttp://e.invalid/1\n#EXTINF:-1,A2\nhttp://e.invalid/2\n")
        val b = M3u.ler("#EXTINF:-1,B1\nhttp://f.invalid/1\n#EXTINF:-1 tvg-chno=\"50\",B50\nhttp://f.invalid/50\n")
        val j = PlayerDados.juntar(listOf(m3u to a, xt to b))
        assertEquals(listOf(1, 2, 3, 50), j.map { it.numero })
        assertEquals(listOf("A1", "A2", "B1", "B50"), j.map { it.nome })
        assertTrue(j.all { it.id.startsWith("a1:") || it.id.startsWith("b2:") })
        assertEquals("B50", PlayerDados.porNumero(j, 50)?.nome)
        assertNull(PlayerDados.porNumero(j, 4))
    }

    @Test fun vizinhoDaAVolta() {
        val c = M3u.ler("#EXTINF:-1,A\nhttp://e.invalid/1\n#EXTINF:-1,B\nhttp://e.invalid/2\n#EXTINF:-1,C\nhttp://e.invalid/3\n")
        assertEquals("B", PlayerDados.vizinho(c, c[0], 1)?.nome)
        assertEquals("C", PlayerDados.vizinho(c, c[0], -1)?.nome)
        assertEquals("A", PlayerDados.vizinho(c, c[2], 1)?.nome)
        assertEquals("B", PlayerDados.vizinho(c, null, 1)?.nome)
        assertNull(PlayerDados.vizinho(emptyList(), null, 1))
    }

    @Test fun guiaAnunciadoPelaLista() {
        assertEquals("http://e.invalid/g.xml.gz", M3u.guiaDaLista("#EXTM3U x-tvg-url=\"http://e.invalid/g.xml.gz,http://e.invalid/2.xml\"\n"))
        assertEquals("https://e.invalid/g.xml", M3u.guiaDaLista("﻿#EXTM3U url-tvg=\"https://e.invalid/g.xml\""))
        assertNull(M3u.guiaDaLista("#EXTM3U\n#EXTINF:-1,A\n"))
        assertNull(M3u.guiaDaLista("#EXTM3U x-tvg-url=\"file:///x\""))
        assertNull(M3u.guiaDaLista("lixo"))
    }
}
