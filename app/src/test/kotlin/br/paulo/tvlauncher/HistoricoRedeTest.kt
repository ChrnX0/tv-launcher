package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** As contas do histórico de rede (ResumoRede): quedas, buracos sem dados, tempo fora e leitura do arquivo. */
class HistoricoRedeTest {
    private val min = 60_000L
    private fun ok(t: Long, lat: Int = 20) = ResumoRede.Amostra(t, true, lat, 10, -60, false, true)
    private fun semNet(t: Long) = ResumoRede.Amostra(t, false, -1, -1, -60, false, false)
    private fun semDns(t: Long) = ResumoRede.Amostra(t, true, 30, -1, -60, false, true)

    @Test fun tudoOkNaoTemQueda() {
        val a = (0..12).map { ok(it * 5 * min) }
        val r = ResumoRede.resumir(a, 0, 60 * min)
        assertEquals(0, r.quedas.size)
        assertEquals(60 * min, r.coberturaMs)
        assertEquals(0L, r.foraMs)
        assertEquals(20, r.latMedia)
    }

    @Test fun quedaVaiDaPrimeiraFalhaAtePrimeiraBoa() {
        // 5 min ok, cai às 10, mede a cada 30 s, volta às 11:30.
        val a = listOf(ok(0), ok(5 * min), semNet(10 * min), semNet(10 * min + 30_000), semNet(11 * min), ok(11 * min + 30_000), ok(16 * min))
        val r = ResumoRede.resumir(a, 0, 20 * min)
        assertEquals(1, r.quedas.size)
        val q = r.quedas[0]
        assertEquals("internet", q.tipo)
        assertEquals(10 * min, q.inicio)
        assertEquals(11 * min + 30_000, q.fim)
        assertEquals(90_000L, r.foraMs)
        assertEquals(90_000L, r.maiorQuedaMs)
    }

    @Test fun soDnsContaSeparado() {
        val a = listOf(ok(0), semDns(5 * min), semDns(10 * min), ok(15 * min))
        val r = ResumoRede.resumir(a, 0, 20 * min)
        assertEquals(1, r.quedas.size)
        assertEquals("dns", r.quedas[0].tipo)
        assertEquals(0L, r.foraMs)   // internet estava no ar
    }

    @Test fun mudarDeTipoFechaUmaEAbreOutra() {
        val a = listOf(ok(0), semDns(5 * min), semNet(6 * min), ok(7 * min))
        val r = ResumoRede.resumir(a, 0, 10 * min)
        assertEquals(listOf("dns", "internet"), r.quedas.map { it.tipo })
        assertEquals(6 * min, r.quedas[0].fim)
        assertEquals(min, r.foraMs)
    }

    @Test fun buracoSemDadosNaoViraQueda() {
        // TV fora da tomada das 10 às 14 h: nenhuma amostra. Não é queda e não conta como tempo medido.
        val a = listOf(ok(0), ok(5 * min), ok(4 * 60 * min), ok(4 * 60 * min + 5 * min))
        val r = ResumoRede.resumir(a, 0, 4 * 60 * min + 10 * min)
        assertEquals(0, r.quedas.size)
        assertEquals(5 * min + 15 * min + 10 * min, r.coberturaMs)
    }

    @Test fun quedaAntesDeUmBuracoTerminaOndeOsDadosAcabam() {
        val a = listOf(ok(0), semNet(5 * min), ok(3 * 60 * min))
        val r = ResumoRede.resumir(a, 0, 3 * 60 * min + min)
        assertEquals(1, r.quedas.size)
        assertEquals(5 * min, r.quedas[0].inicio)
        assertEquals(5 * min + ResumoRede.BURACO_MS, r.quedas[0].fim)
        assertTrue(!r.quedas[0].aberta)
    }

    @Test fun quedaEmAndamentoFicaAberta() {
        val a = listOf(ok(0), semNet(5 * min), semNet(5 * min + 30_000))
        val r = ResumoRede.resumir(a, 0, 6 * min)
        assertEquals(1, r.quedas.size)
        assertTrue(r.quedas[0].aberta)
        assertEquals(6 * min, r.quedas[0].fim)
    }

    @Test fun semAmostrasNaoQuebra() {
        val r = ResumoRede.resumir(emptyList(), 0, 60 * min)
        assertEquals(0, r.medicoes)
        assertNull(r.latMedia)
        assertNull(r.dbmMedio)
    }

    @Test fun lerIdaEVoltaEPulaLinhaEstragada() {
        val linhas = listOf(
            ResumoRede.linhaAmostra(ok(2 * min, 33)),
            "lixo",
            "123\tA\t1\tx\t1\t1\t1\t1",
            ResumoRede.linhaEvento(min, "rede\tcaiu\n"),
            ResumoRede.linhaAmostra(semNet(0)),   // antes de "desde": fica de fora
        )
        val (a, e) = ResumoRede.ler(linhas, 30_000)
        assertEquals(1, a.size)
        assertEquals(33, a[0].latMs)
        assertTrue(a[0].internet && a[0].validado)
        assertEquals(listOf(min to "rede caiu "), e)
    }

    @Test fun p95PegaOsPiores() {
        val a = (1..100).map { ok(it * min, lat = it) }
        val r = ResumoRede.resumir(a, 0, 101 * min)
        assertEquals(95, r.latP95)
    }

    @Test fun p95ComPoucasAmostrasNaoFicaAbaixoDaMedia() {
        val r = ResumoRede.resumir(listOf(ok(0, 15), ok(min, 17)), 0, 2 * min)
        assertEquals(16, r.latMedia)
        assertEquals(17, r.latP95)
    }
}
