package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Regras da ponte de saída do túnel (PonteRegras). Roda no computador: gradlew testReleaseUnitTest. */
class PonteRegrasTest {
    private val senha = "s3nha"
    private val auth = "Proxy-Authorization: Basic " + Base64.getEncoder().encodeToString("t:$senha".toByteArray())

    private fun codigo(vararg l: String) = (PonteRegras.conferir(l.toList(), senha) as PonteRegras.Resultado.Recusa).codigo

    @Test fun aceitaConnect443ComSenha() {
        val r = PonteRegras.conferir(listOf("CONNECT controlplane.tailscale.com:443 HTTP/1.1", "Host: x", auth), senha)
        assertTrue(r is PonteRegras.Resultado.Ok)
        assertEquals("controlplane.tailscale.com", (r as PonteRegras.Resultado.Ok).host)
    }

    @Test fun recusa() {
        assertEquals(407, codigo("CONNECT a.com:443 HTTP/1.1"))
        assertEquals(407, codigo("CONNECT a.com:443 HTTP/1.1", "Proxy-Authorization: Basic errado"))
        assertEquals(405, codigo("GET http://a.com/ HTTP/1.1", auth))
        assertEquals(403, codigo("CONNECT a.com:22 HTTP/1.1", auth))
        assertEquals(403, codigo("CONNECT 127.0.0.1:443 HTTP/1.1", auth))
        assertEquals(403, codigo("CONNECT 192.168.1.1:443 HTTP/1.1", auth))
        assertEquals(400, codigo("CONNECT a_b/c:443 HTTP/1.1", auth))
    }

    @Test fun cabecalhoGrandeOuCortado() {
        assertNull(PonteRegras.lerCabecalho("CONNECT a:443 HTTP/1.1\r\n".byteInputStream()))
        assertNull(PonteRegras.lerCabecalho(("X: " + "a".repeat(9000) + "\r\n\r\n").byteInputStream()))
        assertEquals(2, PonteRegras.lerCabecalho("CONNECT a:443 HTTP/1.1\r\nHost: a\r\n\r\n".byteInputStream())!!.size)
    }

    @Test fun dnsPelaPonte() {
        assertTrue(PonteRegras.conferir(listOf("CONNECT dns.ponte:53 HTTP/1.1", auth), senha) is PonteRegras.Resultado.Dns)
        assertEquals(407, codigo("CONNECT dns.ponte:53 HTTP/1.1"))
        assertEquals(403, codigo("CONNECT outro:53 HTTP/1.1", auth))
    }

    private fun pergunta(nome: String, tipo: Int): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        o.write(byteArrayOf(0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0))
        for (r in nome.split('.')) { o.write(r.length); o.write(r.toByteArray()) }
        o.write(byteArrayOf(0, 0, tipo.toByte(), 0, 1)); return o.toByteArray()
    }

    @Test fun respondeA() {
        val ip = java.net.InetAddress.getByAddress(byteArrayOf(1, 2, 3, 4))
        val r = DnsPonte.responder(pergunta("ControlPlane.tailscale.com", 1)) { n -> assertEquals("controlplane.tailscale.com", n); listOf(ip) }!!
        assertEquals(0x12, r[0].toInt()); assertEquals(1, r[7].toInt())          // mesmo id, 1 resposta
        assertEquals(0x80, r[3].toInt() and 0xff)                               // rcode 0
        assertEquals(listOf<Byte>(1, 2, 3, 4), r.takeLast(4))
    }

    @Test fun respostaVaziaOuErro() {
        val ip4 = java.net.InetAddress.getByAddress(byteArrayOf(1, 2, 3, 4))
        assertEquals(0, DnsPonte.responder(pergunta("a.com", 28)) { listOf(ip4) }!![7].toInt())   // AAAA sem IPv6
        assertEquals(3, DnsPonte.responder(pergunta("a.com", 1)) { throw java.net.UnknownHostException() }!![3].toInt() and 0x0f)
        assertEquals(3, DnsPonte.responder(pergunta("a\"b.com", 1)) { error("não deveria resolver") }!![3].toInt() and 0x0f)
        assertNull(DnsPonte.responder(ByteArray(5)) { emptyList() })
    }
}
