package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A lógica pura da integração com o roteador UniFi (Unifi.kt). Endereços e chaves aqui são de exemplo. */
class UnifiTest {
    @Test fun enderecoSoPrivado() {
        assertEquals("192.168.0.1", Unifi.enderecoValido(" 192.168.0.1 "))
        assertEquals("10.0.0.1", Unifi.enderecoValido("https://10.0.0.1/"))
        assertEquals("172.16.5.4:8443", Unifi.enderecoValido("172.16.5.4:8443"))
        assertEquals("192.168.0.1", Unifi.enderecoValido("192.168.0.1:443"))   // porta padrão some
        assertNull(Unifi.enderecoValido("8.8.8.8"))           // público: a chave iria pela internet
        assertNull(Unifi.enderecoValido("172.32.0.1"))        // fora de 172.16–31
        assertNull(Unifi.enderecoValido("127.0.0.1"))         // a própria TV
        assertNull(Unifi.enderecoValido("unifi.lan"))         // nome exige DNS
        assertNull(Unifi.enderecoValido("192.168.0.256"))
        assertNull(Unifi.enderecoValido("192.168.0.1:0"))
        assertNull(Unifi.enderecoValido("192.168.0.1/../x"))
        assertNull(Unifi.enderecoValido(null))
    }

    @Test fun chave() {
        assertEquals("abcdEFGH1234-_xyzABCD", Unifi.chaveValida(" abcdEFGH1234-_xyz\nABCD "))
        assertNull(Unifi.chaveValida("curta"))
        assertNull(Unifi.chaveValida("abcdEFGH1234\"; x=1abcd"))
        assertNull(Unifi.chaveValida("açãoaçãoaçãoaçãoação"))
        assertNull(Unifi.chaveValida("a".repeat(129)))
    }

    @Test fun impressaoComoNoNavegador() {
        // SHA-256 de "abc" (vetor de teste conhecido do padrão).
        assertEquals("BA:78:16:BF:8F:01:CF:EA:41:41:40:DE:5D:AE:22:23:B0:03:61:A3:96:17:7A:9C:B4:10:FF:61:F2:00:15:AD",
            Unifi.impressao("abc".toByteArray()))
    }

    private val pagina = """{"offset":0,"limit":200,"count":3,"totalCount":3,"data":[
        {"type":"WIRELESS","id":"11111111-1111-1111-1111-111111111111","name":"Celular","ipAddress":"192.168.0.20",
         "macAddress":"AA:BB:CC:00:00:01","connectedAt":"2026-11-05T10:00:00Z","access":{"type":"DEFAULT"}},
        {"type":"WIRED","id":"22222222-2222-2222-2222-222222222222","name":"","macAddress":"aa:bb:cc:00:00:02","access":{}},
        {"type":"VPN","id":"","name":"sem id"}]}"""

    @Test fun lerAparelhos() {
        val (lista, total) = Unifi.lerPaginaAparelhos(pagina)
        assertEquals(3, total)
        assertEquals(2, lista.size)   // o sem id fica de fora
        assertEquals("Celular", lista[0].nome)
        assertEquals("192.168.0.20", lista[0].ip)
        assertEquals("aa:bb:cc:00:00:01", lista[0].chave)
        assertEquals("aa:bb:cc:00:00:02", lista[1].nome)   // sem nome: mostra o MAC
        assertNull(lista[1].ip)
        assertEquals("WIRED", lista[1].tipo)
    }

    @Test fun lerSite() {
        assertEquals("b", Unifi.lerSite("""{"data":[{"id":"a","internalReference":"x","name":"X"},{"id":"b","internalReference":"default","name":"Default"}]}"""))
        assertEquals("a", Unifi.lerSite("""{"data":[{"id":"a","internalReference":"x","name":"X"}]}"""))
        assertNull(Unifi.lerSite("""{"data":[]}"""))
    }

    @Test fun novosEConhecidos() {
        val (lista, _) = Unifi.lerPaginaAparelhos(pagina)
        val conhecidos = listOf("aa:bb:cc:00:00:01")
        val novos = Unifi.novos(conhecidos.toSet(), lista)
        assertEquals(listOf("aa:bb:cc:00:00:02"), novos.map { it.chave })
        assertEquals(listOf("aa:bb:cc:00:00:01", "aa:bb:cc:00:00:02"), Unifi.juntarConhecidos(conhecidos, lista))
        // Limite: o mais antigo sai.
        val muitos = (1..Unifi.MAX_CONHECIDOS).map { "m$it" }
        val juntos = Unifi.juntarConhecidos(muitos, lista)
        assertEquals(Unifi.MAX_CONHECIDOS, juntos.size)
        assertEquals("m3", juntos.first())
    }

    @Test fun textoDoRecadoCabeNoCartao() {
        val muitos = (1..6).map { Unifi.Aparelho("id$it", "Aparelho com um nome comprido demais para caber $it", "192.168.0.$it", null, "WIRELESS", null) }
        val t = Unifi.textoNovos(muitos)
        assertTrue(t.endsWith(" e mais 3"))
        assertEquals(t, Recado.limpar(t))   // passa pela mesma limpeza do recado sem cortar nada
    }
}
