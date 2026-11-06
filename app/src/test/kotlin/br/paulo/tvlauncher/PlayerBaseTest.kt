package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** Player universal, passo 1: M3U, Xtream e guia XMLTV. Só lógica, sem Android e sem rede (dados inventados). */
class PlayerBaseTest {
    // ---- M3U

    private val lista = "﻿#EXTM3U x-tvg-url=\"http://exemplo.invalid/guia.xml\"\r\n" +
        "#EXTINF:-1 tvg-id=\"c1\" tvg-chno=\"7\" tvg-logo=\"http://exemplo.invalid/1.png\" group-title=\"Notícias\",Canal Um\r\n" +
        "http://exemplo.invalid/1.m3u8\r\n" +
        "#EXTINF:-1 tvg-name=\"Dois, o canal\" group-title=\"Filmes\",Dois, o canal\n" +
        "#EXTVLCOPT:http-user-agent=PlayerTeste/1.0\n" +
        "https://exemplo.invalid/2.ts\n" +
        "\n" +
        "#EXTINF:-1 tvg-id=\"c1\" tvg-chno=\"7\",Canal Um SD\n" +
        "#EXTGRP:Notícias SD\n" +
        "http://exemplo.invalid/1sd.ts\n" +
        "#EXTINF:-1,Arquivo local\n" +
        "file:///sdcard/segredo.txt\n" +
        "http://url-solta-sem-extinf.invalid/x\n" +
        "#EXTINF:-1 tvg-logo=\"javascript:alert(1)\",Sem logo\n" +
        "http://exemplo.invalid/4.ts\n"

    @Test fun m3uLeCanais() {
        val c = M3u.ler(lista)
        assertEquals(listOf("Canal Um", "Dois, o canal", "Canal Um SD", "Sem logo"), c.map { it.nome })
        assertEquals(7, c[0].numero)                       // tvg-chno respeitado
        assertEquals("Notícias", c[0].grupo)
        assertEquals("c1", c[0].guiaId)
        assertEquals("http://exemplo.invalid/1.png", c[0].logo)
        assertEquals("PlayerTeste/1.0", c[1].agenteUsuario)
        assertEquals("Filmes", c[1].grupo)
        assertEquals("Notícias SD", c[2].grupo)            // #EXTGRP
        assertEquals("", c[3].logo)                        // logo que não é http some
    }

    @Test fun m3uNumerosEIdsNaoRepetem() {
        val c = M3u.ler(lista)
        assertEquals(c.size, c.map { it.numero }.toSet().size)
        assertEquals(c.size, c.map { it.id }.toSet().size)
        assertEquals("c1#2", c[2].id)                      // mesmo tvg-id do Canal Um
        assertTrue(c[2].numero != 7)                       // tvg-chno 7 já usado
    }

    @Test fun m3uRecusaUrlPerigosa() {
        assertFalse(M3u.ler(lista).any { it.url.startsWith("file:") })
        assertFalse(M3u.urlAceita("rtmp://x.invalid/a"))
        assertFalse(M3u.urlAceita("http://a b.invalid/x"))
        assertTrue(M3u.urlAceita("HTTPS://exemplo.invalid/a.m3u8"))
    }

    @Test fun m3uExtinfComVirgulaNasAspas() {
        val (attrs, nome) = M3u.lerExtinf("#EXTINF:-1 tvg-name=\"A, B\" group-title=\"X\",Nome final")
        assertEquals("A, B", attrs["tvg-name"])
        assertEquals("X", attrs["group-title"])
        assertEquals("Nome final", nome)
    }

    @Test fun m3uVazioOuLixo() {
        assertTrue(M3u.ler("").isEmpty())
        assertTrue(M3u.ler("<html>erro 404</html>").isEmpty())
    }

    // ---- Xtream

    private val conta = Xtream.Conta("http://servidor.invalid:8080", "ana", "s&nh@ 1")

    @Test fun xtreamServidor() {
        assertEquals("http://servidor.invalid:8080", Xtream.normalizarServidor(" servidor.invalid:8080/ "))
        assertEquals("https://s.invalid", Xtream.normalizarServidor("https://s.invalid/player_api.php?username=a"))
        assertEquals("http://s.invalid/sub", Xtream.normalizarServidor("http://s.invalid/sub/get.php"))
        assertNull(Xtream.normalizarServidor("ftp://s.invalid"))
        assertNull(Xtream.normalizarServidor(""))
        assertNull(Xtream.normalizarServidor("http://"))
        assertNull(Xtream.normalizarServidor("a b"))
    }

    @Test fun xtreamEnderecos() {
        assertEquals("http://servidor.invalid:8080/player_api.php?username=ana&password=s%26nh%40+1&action=get_live_streams",
            Xtream.urlCanaisAoVivo(conta))
        assertEquals("http://servidor.invalid:8080/live/ana/s%26nh%40+1/42.m3u8", Xtream.urlCanal(conta, "42", "m3u8"))
        assertEquals("http://servidor.invalid:8080/live/ana/s%26nh%40+1/42.ts", Xtream.urlCanal(conta, "42", "qualquer"))
        assertTrue(Xtream.urlGuiaCurto(conta, "42").endsWith("&action=get_short_epg&stream_id=42&limit=4"))
    }

    @Test fun xtreamSituacao() {
        val ok = Xtream.lerSituacao("""{"user_info":{"auth":1,"status":"Active","exp_date":"1798761600","max_connections":"2","active_cons":0}}""")!!
        assertTrue(ok.ativa); assertEquals(1798761600L, ok.expiraEm); assertEquals(2, ok.telasMax); assertEquals(0, ok.telasEmUso)
        val vencida = Xtream.lerSituacao("""{"user_info":{"auth":1,"status":"Expired","exp_date":null}}""")!!
        assertFalse(vencida.ativa); assertNull(vencida.expiraEm)
        assertFalse(Xtream.lerSituacao("""{"user_info":{"auth":0}}""")!!.ativa)
        assertNull(Xtream.lerSituacao("<html>"))
        assertNull(Xtream.lerSituacao("""{"outra":1}"""))
    }

    @Test fun xtreamCanais() {
        val cats = Xtream.lerCategorias("""[{"category_id":"5","category_name":" Esportes "},{"category_name":"sem id"}]""")
        assertEquals(listOf(Xtream.Categoria("5", "Esportes")), cats)
        val json = """[
            {"num":3,"name":"Esporte","stream_id":101,"stream_icon":"http://exemplo.invalid/e.png","epg_channel_id":"esp","category_id":"5"},
            {"num":3,"name":"Repetido","stream_id":"102","stream_icon":"","epg_channel_id":null,"category_id":"9"},
            {"name":"Sem id"},
            {"name":"","stream_id":103}
        ]"""
        val c = Xtream.lerCanais(json, conta, cats)
        assertEquals(3, c.size)
        assertEquals("x101", c[0].id); assertEquals(3, c[0].numero); assertEquals("Esportes", c[0].grupo); assertEquals("esp", c[0].guiaId)
        assertEquals("http://servidor.invalid:8080/live/ana/s%26nh%40+1/101.ts", c[0].url)
        assertTrue(c[1].numero != 3); assertEquals("", c[1].guiaId); assertEquals("", c[1].grupo)
        assertEquals("Canal ${c[2].numero}", c[2].nome)
        assertTrue(Xtream.lerCanais("{}", conta).isEmpty())
    }

    // ---- guia XMLTV

    private val xml = """<?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <channel id="esp"><display-name>Esporte</display-name></channel>
          <programme start="20261105215500 -0300" stop="20261105235500 -0300" channel="esp">
            <title lang="pt">Futebol</title><title lang="en">Soccer</title>
            <desc>Jogo da noite.</desc><category>Esporte</category>
            <rating><value>L</value></rating>
          </programme>
          <programme start="20261105235500 -0300" stop="20261106010000 -0300" channel="esp"><title>Notícias</title></programme>
          <programme start="20261105200000 +0000" stop="20261105210000 +0000" channel="outro"><title>Fora da lista</title></programme>
          <programme start="20261101000000" stop="20261101010000" channel="esp"><title>Antigo</title></programme>
          <programme start="ruim" stop="20261106010000" channel="esp"><title>Data ruim</title></programme>
        </tv>"""

    // 05/11/2026 22:00 em Brasília (-03:00) = 06/11/2026 01:00 UTC
    private val agora = Xmltv.data("20261105220000 -0300")!!

    @Test fun xmltvDatas() {
        assertEquals(0L, Xmltv.data("19700101000000 +0000"))
        assertEquals(Xmltv.data("20261106010000 +0000"), agora)
        assertEquals(Xmltv.data("20261106010000"), agora)           // sem fuso = UTC
        assertEquals(951782400000L, Xmltv.data("20000229000000 +0000"))  // ano bissexto
        assertNull(Xmltv.data("2026110522")); assertNull(Xmltv.data("20261305220000")); assertNull(Xmltv.data(null))
    }

    @Test fun xmltvFiltraCanalEJanela() {
        val p = Xmltv.ler(KXmlParser(), ByteArrayInputStream(xml.toByteArray()), setOf("esp"), desde = agora - 3_600_000)
        assertEquals(listOf("Futebol", "Notícias"), p.map { it.titulo })
        assertEquals("Jogo da noite.", p[0].descricao)
        assertEquals("Esporte", p[0].categoria)
    }

    @Test fun xmltvGzipEGuia() {
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(xml.toByteArray()) } }.toByteArray()
        val p = Xmltv.ler(KXmlParser(), Xmltv.abrir(ByteArrayInputStream(gz)))
        assertEquals(4, p.size)                                     // "Data ruim" fica de fora
        val g = Guia(p)
        val atual = g.agora("esp", agora)
        assertNotNull(atual); assertEquals("Futebol", atual!!.titulo)
        assertEquals(listOf("Futebol", "Notícias"), g.proximos("esp", agora).map { it.titulo })
        assertEquals(5f / 120f, g.progresso(atual, agora), 0.001f)
        assertNull(g.agora("nenhum", agora))
    }

    @Test fun xmltvNaoQuebraComLixo() {
        val p = runCatching { Xmltv.ler(KXmlParser(), ByteArrayInputStream("não é xml".toByteArray())) }
        assertTrue(p.isFailure || p.getOrThrow().isEmpty())
    }
}
