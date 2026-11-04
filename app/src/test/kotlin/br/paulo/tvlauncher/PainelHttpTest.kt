package br.paulo.tvlauncher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Testes do HTTP mínimo do painel (sem Android: roda no computador). */
class PainelHttpTest {
    private fun ler(texto: String, limite: Int = 1024) =
        PainelHttp.ler(ByteArrayInputStream(texto.toByteArray(Charsets.ISO_8859_1)), limite)

    private fun codigoDoErro(texto: String, limite: Int = 1024): Int = try {
        ler(texto, limite); fail("devia ter dado erro"); 0
    } catch (e: ErroHttp) { e.codigo }

    @Test fun getSimples() {
        val r = ler("GET /api/apps?x=1&nome=a%20b HTTP/1.1\r\nHost: 192.168.1.153:8080\r\nCookie: a=1; painel_sessao=abc\r\n\r\n")
        assertEquals("GET", r.metodo)
        assertEquals("/api/apps", r.caminho)
        assertEquals("1", r.consulta["x"])
        assertEquals("a b", r.consulta["nome"])
        assertEquals("192.168.1.153:8080", r.cabecalho("Host"))
        assertEquals("abc", PainelHttp.cookie(r.cabecalho("cookie"), "painel_sessao"))
        assertEquals(0, r.corpo.size)
    }

    @Test fun postComCorpo() {
        val corpo = """{"pin":"123456"}"""
        val r = ler("POST /api/entrar HTTP/1.1\r\nHost: x\r\nContent-Length: ${corpo.length}\r\n\r\n$corpo")
        assertEquals("POST", r.metodo)
        assertEquals(corpo, r.corpoTexto())
    }

    @Test fun corpoNaoEngoleOQueVemDepois() {
        val entrada = ByteArrayInputStream("POST / HTTP/1.1\r\nContent-Length: 3\r\n\r\nabcEXTRA".toByteArray())
        val r = PainelHttp.ler(entrada, 100)
        assertArrayEquals("abc".toByteArray(), r.corpo)
    }

    @Test fun aceitaSoLF() {
        val r = ler("GET / HTTP/1.1\nHost: x\n\n")
        assertEquals("/", r.caminho)
        assertEquals("x", r.cabecalho("host"))
    }

    @Test fun ignoraLinhaVaziaAntesDoPedido() {
        assertEquals("/", ler("\r\nGET / HTTP/1.1\r\n\r\n").caminho)
    }

    @Test fun corpoGrandeDemais413() = assertEquals(413, codigoDoErro("POST / HTTP/1.1\r\nContent-Length: 2000\r\n\r\n", 1024))

    @Test fun cabecalhosGrandesDemais431() =
        assertEquals(431, codigoDoErro("GET / HTTP/1.1\r\nX: ${"a".repeat(9000)}\r\n\r\n"))

    @Test fun chunkedRecusado() =
        assertEquals(411, codigoDoErro("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n"))

    @Test fun hostRepetidoRecusado() =
        assertEquals(400, codigoDoErro("GET / HTTP/1.1\r\nHost: a\r\nHost: b\r\n\r\n"))

    @Test fun contentLengthInvalido() {
        assertEquals(400, codigoDoErro("POST / HTTP/1.1\r\nContent-Length: -1\r\n\r\n"))
        assertEquals(400, codigoDoErro("POST / HTTP/1.1\r\nContent-Length: abc\r\n\r\n"))
        assertEquals(400, codigoDoErro("POST / HTTP/1.1\r\nContent-Length: 99999999999\r\n\r\n"))
    }

    @Test fun pedidoMalformado() {
        assertEquals(400, codigoDoErro("GET\r\n\r\n"))
        assertEquals(400, codigoDoErro("GET http://evil/ HTTP/1.1\r\n\r\n"))
        assertEquals(400, codigoDoErro("GET / FTP/1.0\r\n\r\n"))
        assertEquals(400, codigoDoErro("GET / HTTP/1.1\r\nsemdoispontos\r\n\r\n"))
        assertEquals(400, codigoDoErro("GET /?a=%zz HTTP/1.1\r\n\r\n"))
    }

    @Test fun corpoIncompletoEConexaoVazia() {
        try { ler("POST / HTTP/1.1\r\nContent-Length: 10\r\n\r\nabc"); fail() } catch (e: ErroHttp) { fail("não é erro HTTP") } catch (e: IOException) {}
        try { ler(""); fail() } catch (e: ErroHttp) { fail("não é erro HTTP") } catch (e: IOException) {}
        assertEquals(400, codigoDoErro("GET / HTTP/1.1\r\nHost: x"))   // cabeçalhos cortados
    }

    @Test fun cookie() {
        assertEquals("v", PainelHttp.cookie("painel_sessao=v", "painel_sessao"))
        assertEquals("v", PainelHttp.cookie("x=1;  painel_sessao=v ; y=2", "painel_sessao"))
        assertNull(PainelHttp.cookie("xpainel_sessao=v", "painel_sessao"))
        assertNull(PainelHttp.cookie(null, "painel_sessao"))
        assertNull(PainelHttp.cookie("", "painel_sessao"))
    }

    @Test fun respostaTemCabecalhosDeSeguranca() {
        val saida = ByteArrayOutputStream()
        PainelHttp.escrever(saida, 200, "text/plain", "oi".toByteArray(), mapOf("Cache-Control" to "private, max-age=60"))
        val texto = saida.toString("ISO-8859-1")
        assertTrue(texto.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue("Content-Security-Policy: default-src 'self'" in texto)
        assertTrue("frame-ancestors 'none'" in texto)
        assertTrue("X-Content-Type-Options: nosniff\r\n" in texto)
        assertTrue("Cache-Control: private, max-age=60\r\n" in texto)   // extras sobrescrevem
        assertTrue("Content-Length: 2\r\n" in texto)
        assertTrue("Connection: close\r\n" in texto)
        assertTrue(texto.endsWith("\r\n\r\noi"))
    }

    @Test fun recusaQuebraDeLinhaEmCabecalho() {
        try {
            PainelHttp.escrever(ByteArrayOutputStream(), 200, null, ByteArray(0), mapOf("X" to "a\r\nSet-Cookie: x=1"))
            fail()
        } catch (e: IllegalArgumentException) {}
    }
}
