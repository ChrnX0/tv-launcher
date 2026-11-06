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
}
