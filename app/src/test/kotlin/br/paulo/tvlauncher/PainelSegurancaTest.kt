package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Testes da parte de segurança do painel no navegador (sem Android: roda no computador). */
class PainelSegurancaTest {

    // ---------------------------------------------------------------- PIN

    @Test fun pinValidoSoComSeisADozeDigitos() {
        assertTrue(PinSeguro.valido("123456"))
        assertTrue(PinSeguro.valido("123456789012"))
        assertFalse(PinSeguro.valido("12345"))
        assertFalse(PinSeguro.valido("1234567890123"))
        assertFalse(PinSeguro.valido("12345a"))
        assertFalse(PinSeguro.valido("12 456"))
        assertFalse(PinSeguro.valido("١٢٣٤٥٦"))   // dígitos arábicos: isDigit() aceitaria, o PIN não
        assertFalse(PinSeguro.valido(""))
    }

    @Test fun resumoConfereSoComOPinCerto() {
        val sal = PinSeguro.novoSal()
        val resumo = PinSeguro.resumo("246810", sal, 1000)
        assertTrue(PinSeguro.confere("246810", sal, 1000, resumo))
        assertFalse(PinSeguro.confere("246811", sal, 1000, resumo))
        assertFalse(PinSeguro.confere("246810", sal, 1001, resumo))   // outro número de voltas
        assertFalse(PinSeguro.confere("246810", PinSeguro.novoSal(), 1000, resumo))   // outro sal
        assertFalse(PinSeguro.confere("", sal, 1000, resumo))
    }

    @Test fun salAleatorioMudaOResumo() {
        val a = PinSeguro.resumo("135790", PinSeguro.novoSal(), 1000)
        val b = PinSeguro.resumo("135790", PinSeguro.novoSal(), 1000)
        assertEquals(32, a.size)
        assertFalse(a.contentEquals(b))
    }

    // ---------------------------------------------------------------- tentativas

    @Test fun cincoErrosBloqueiamUmMinutoDepoisDobra() {
        var agora = 0L
        val c = ControleTentativas({ agora })
        repeat(4) { assertTrue(c.registrarFalha() is ControleTentativas.Resultado.Errou) }
        assertEquals(ControleTentativas.Resultado.Bloqueou(60_000), c.registrarFalha())
        assertEquals(60_000, c.esperaMs())
        agora = 59_999
        assertEquals(1, c.esperaMs())
        agora = 60_000
        assertEquals(0, c.esperaMs())
        repeat(4) { c.registrarFalha() }
        assertEquals(ControleTentativas.Resultado.Bloqueou(120_000), c.registrarFalha())
        agora += 120_000
        repeat(4) { c.registrarFalha() }
        assertEquals(ControleTentativas.Resultado.Bloqueou(240_000), c.registrarFalha())
    }

    @Test fun vinteErrosDesligam() {
        val c = ControleTentativas({ 0L })
        repeat(19) { assertNotEquals(ControleTentativas.Resultado.Desligar, c.registrarFalha()) }
        assertEquals(ControleTentativas.Resultado.Desligar, c.registrarFalha())
    }

    @Test fun errouInformaQuantasFaltam() {
        val c = ControleTentativas({ 0L })
        assertEquals(ControleTentativas.Resultado.Errou(4), c.registrarFalha())
        assertEquals(ControleTentativas.Resultado.Errou(3), c.registrarFalha())
    }

    @Test fun acertarZeraEGravaZero() {
        val gravados = ArrayList<Int>()
        val c = ControleTentativas({ 0L }, falhasIniciais = 3) { gravados.add(it) }
        c.registrarFalha()
        c.registrarSucesso()
        assertEquals(listOf(4, 0), gravados)
        assertEquals(0, c.falhas)
        assertEquals(0, c.esperaMs())
    }

    @Test fun falhasGravadasSobrevivemAoReinicio() {
        // 18 erros antes de reiniciar: faltam 2 para desligar, não 20.
        val c = ControleTentativas({ 0L }, falhasIniciais = 18)
        assertTrue(c.registrarFalha() is ControleTentativas.Resultado.Errou)
        assertEquals(ControleTentativas.Resultado.Desligar, c.registrarFalha())
    }

    // ---------------------------------------------------------------- sessões

    @Test fun sessaoValeEExpiraPorOciosidade() {
        var agora = 1_000L
        val s = Sessoes({ agora })
        val t = s.criar()
        assertEquals(32, t.length)
        assertTrue(s.valida(t))
        agora += Sessoes.OCIOSO_MS - 1
        assertTrue(s.valida(t))   // usar renova
        agora += Sessoes.OCIOSO_MS - 1
        assertTrue(s.valida(t))
        agora += Sessoes.OCIOSO_MS + 1
        assertFalse(s.valida(t))
    }

    @Test fun sessaoExpiraNoMaximoMesmoEmUso() {
        var agora = 0L
        val s = Sessoes({ agora })
        val t = s.criar()
        while (agora < Sessoes.MAXIMO_MS - 60_000) { agora += 60_000; assertTrue(s.valida(t)) }
        agora += 120_000
        assertFalse(s.valida(t))
    }

    @Test fun tokenErradoOuNuloNaoVale() {
        val s = Sessoes({ 0L })
        s.criar()
        assertFalse(s.valida(null))
        assertFalse(s.valida(""))
        assertFalse(s.valida("0".repeat(32)))
        assertFalse(s.valida("x"))
    }

    @Test fun elevadaPorCincoMinutos() {
        var agora = 0L
        val s = Sessoes({ agora })
        val t = s.criar()
        assertTrue(s.elevada(t))   // acabou de digitar o PIN
        agora = Sessoes.ELEVADA_MS
        assertFalse(s.elevada(t))
        assertTrue(s.elevar(t))
        assertTrue(s.elevada(t))
        assertFalse(s.elevar("f".repeat(32)))
    }

    @Test fun encerrarDerruba() {
        val s = Sessoes({ 0L })
        val a = s.criar(); val b = s.criar()
        s.encerrar(a)
        assertFalse(s.valida(a)); assertTrue(s.valida(b))
        s.encerrarTodas()
        assertFalse(s.valida(b))
    }

    @Test fun limiteDeSessoesTiraAMaisAntiga() {
        val s = Sessoes({ 0L }, maximoSessoes = 2)
        val a = s.criar(); val b = s.criar(); val c = s.criar()
        assertFalse(s.valida(a)); assertTrue(s.valida(b)); assertTrue(s.valida(c))
        assertEquals(2, s.quantas())
    }

    @Test fun tokensSaoDiferentes() {
        val s = Sessoes({ 0L }, maximoSessoes = 100)
        val tokens = (1..50).map { s.criar() }.toSet()
        assertEquals(50, tokens.size)
    }

    // ---------------------------------------------------------------- Host / Origin / pacote

    private val nomes = setOf("192.168.1.153", "chromecast")

    @Test fun hostAceitaIpENomeComOuSemPorta() {
        assertTrue(ChecagensPainel.hostPermitido("192.168.1.153:8080", nomes, 8080))
        assertTrue(ChecagensPainel.hostPermitido("192.168.1.153", nomes, 8080))
        assertTrue(ChecagensPainel.hostPermitido("Chromecast:8080", nomes, 8080))
    }

    @Test fun hostRecusaRebindingEPortaErrada() {
        assertFalse(ChecagensPainel.hostPermitido("evil.example:8080", nomes, 8080))
        assertFalse(ChecagensPainel.hostPermitido("chromecast.evil.example:8080", nomes, 8080))
        assertFalse(ChecagensPainel.hostPermitido("192.168.1.153:8081", nomes, 8080))
        assertFalse(ChecagensPainel.hostPermitido("192.168.1.153:", nomes, 8080))
        assertFalse(ChecagensPainel.hostPermitido("", nomes, 8080))
        assertFalse(ChecagensPainel.hostPermitido(null, nomes, 8080))
        assertFalse(ChecagensPainel.hostPermitido("[::1]:8080", nomes, 8080))
    }

    @Test fun origemTemDeSerOProprioPainel() {
        assertTrue(ChecagensPainel.origemPermitida("http://192.168.1.153:8080", "192.168.1.153:8080"))
        assertTrue(ChecagensPainel.origemPermitida("http://chromecast:8080", "Chromecast:8080"))
        assertFalse(ChecagensPainel.origemPermitida("http://evil.example", "192.168.1.153:8080"))
        assertFalse(ChecagensPainel.origemPermitida("https://192.168.1.153:8080", "192.168.1.153:8080"))
        assertFalse(ChecagensPainel.origemPermitida("null", "192.168.1.153:8080"))
        assertFalse(ChecagensPainel.origemPermitida(null, "192.168.1.153:8080"))
        assertFalse(ChecagensPainel.origemPermitida("http://192.168.1.153:8080", null))
    }

    @Test fun pacoteValido() {
        assertTrue(ChecagensPainel.pacoteValido("com.netflix.ninja"))
        assertTrue(ChecagensPainel.pacoteValido("br.paulo.tvlauncher"))
        assertTrue(ChecagensPainel.pacoteValido("com.google.android.youtube.tv"))
        assertFalse(ChecagensPainel.pacoteValido("android"))   // sem ponto
        assertFalse(ChecagensPainel.pacoteValido("com..x"))
        assertFalse(ChecagensPainel.pacoteValido("../etc"))
        assertFalse(ChecagensPainel.pacoteValido("com.x;rm"))
        assertFalse(ChecagensPainel.pacoteValido("com.x/y"))
        assertFalse(ChecagensPainel.pacoteValido("1com.x"))
        assertFalse(ChecagensPainel.pacoteValido(null))
        assertFalse(ChecagensPainel.pacoteValido("a." + "b".repeat(300)))
    }
}
