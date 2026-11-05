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
        assertEquals(Unifi.Site("b", "default"), Unifi.lerSite("""{"data":[{"id":"a","internalReference":"x","name":"X"},{"id":"b","internalReference":"default","name":"Default"}]}"""))
        assertEquals(Unifi.Site("a", "x1"), Unifi.lerSite("""{"data":[{"id":"a","internalReference":"x1","name":"X"}]}"""))
        // internalReference estranho não entra no caminho da API antiga.
        assertEquals(Unifi.Site("a", "default"), Unifi.lerSite("""{"data":[{"id":"a","internalReference":"../x","name":"X"}]}"""))
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

    private val detalhesAp = """{"id":"x","name":"Sala AP","model":"U7 Pro","interfaces":{"radios":[
        {"wlanStandard":"802.11ax","frequencyGHz":"2.4","channelWidthMHz":20,"channel":6},
        {"wlanStandard":"802.11be","frequencyGHz":"5","channelWidthMHz":80,"channel":36}]}}"""
    private val estatAp = """{"cpuUtilizationPct":12.5,"interfaces":{"radios":[{"frequencyGHz":"2.4","txRetriesPct":25.0},{"frequencyGHz":"5","txRetriesPct":4.0}]}}"""

    @Test fun pontoDeAcesso() {
        val ap = Unifi.lerPontoDeAcesso(detalhesAp, estatAp)
        assertEquals("Sala AP", ap.nome)
        assertEquals(listOf("2.4", "5"), ap.radios.map { it.ghz })
        assertEquals(6, ap.radios[0].canal)
        assertEquals(25.0, ap.radios[0].retransmissoesPct!!, 0.01)
        assertEquals(12.5, ap.cpuPct!!, 0.01)
        assertNull(Unifi.lerPontoDeAcesso(detalhesAp, null).radios[0].retransmissoesPct)
    }

    @Test fun dicasDoWifi() {
        val ap = Unifi.lerPontoDeAcesso(detalhesAp, estatAp)
        assertTrue(Unifi.dicas(-50, 5180, 866, ap).isEmpty())                  // 5 GHz, sinal ótimo, 4% de retransmissões
        val presa = Unifi.dicas(-55, 2437, 144, ap)                               // 2,4 GHz com sinal bom
        assertTrue(presa.any { it.contains("Band Steering") })
        assertTrue(presa.any { it.contains("canal 6") && it.contains("25%") })    // rádio de 2,4 congestionado
        assertTrue(Unifi.dicas(-80, 5180, 100, ap).first().startsWith("Sinal fraco"))
        assertTrue(Unifi.dicas(-70, 5180, 300, null).first().startsWith("Sinal razoável"))
        assertTrue(Unifi.dicas(-50, 5180, 24, null).any { it.contains("24 Mbps") })
        assertEquals("2.4", Unifi.banda(2412)); assertEquals("5", Unifi.banda(5745)); assertEquals("6", Unifi.banda(6115)); assertNull(Unifi.banda(0))
    }

    @Test fun pontoDeAcessoDoAparelho() {
        val (lista, _) = Unifi.lerPaginaAparelhos("""{"totalCount":1,"data":[{"type":"WIRELESS","id":"a","name":"TV","ipAddress":"192.168.0.9","uplinkDeviceId":"ap1"}]}""")
        assertEquals("ap1", lista[0].pontoDeAcesso)
    }

    @Test fun estacaoPelaApiAntiga() {
        val sta = """{"meta":{"rc":"ok"},"data":[
            {"mac":"aa:bb:cc:00:00:09","ip":"192.168.0.8","signal":-48,"radio":"na","channel":36,"tx_rate":866000,"rx_rate":780000,"satisfaction":97,"essid":"Casa"},
            {"mac":"AA:BB:CC:00:00:10","ip":"192.168.0.9","signal":-71,"radio":"ng","channel":6,"tx_rate":72000,"rx_rate":65000,"satisfaction":41}]}"""
        val tv = Unifi.lerEstacao(sta, "192.168.0.9")!!
        assertEquals("aa:bb:cc:00:00:10", tv.mac)
        assertEquals(-71, tv.sinalDbm)
        assertEquals("2.4", Unifi.bandaDoRadio(tv.radio))
        assertEquals(72, tv.txMbps)
        assertEquals(41, tv.satisfacao)
        assertTrue(Unifi.dicaSatisfacao(tv)!!.contains("nota 41"))
        assertNull(Unifi.dicaSatisfacao(Unifi.lerEstacao(sta, "192.168.0.8")))
        assertNull(Unifi.lerEstacao(sta, "192.168.0.99"))
        assertNull(Unifi.lerEstacao("""{"data":[{"mac":"x; rm -rf","ip":"192.168.0.9"}]}""", "192.168.0.9"))   // MAC estranho: ignora
    }

    @Test fun validacoesDoComando() {
        assertTrue(Unifi.macValida("aa:bb:cc:dd:ee:ff"))
        assertTrue(!Unifi.macValida("aa:bb:cc:dd:ee"))
        assertTrue(!Unifi.macValida("aa:bb:cc:dd:ee:ff\""))
        assertTrue(Unifi.refValida("default"))
        assertTrue(!Unifi.refValida("../api"))
        assertTrue(!Unifi.refValida(""))
    }
}
