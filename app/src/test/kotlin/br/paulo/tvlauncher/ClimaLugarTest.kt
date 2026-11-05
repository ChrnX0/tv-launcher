package br.paulo.tvlauncher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** Testes do lugar do clima (LocalClima) e da faixa de 9 dias (FaixaDias): só a lógica, sem Android. */
class ClimaLugarTest {
    // ---- coordenadas coladas do Google Maps

    @Test fun leOFormatoDoGoogleMaps() {
        assertEquals(-21.1795 to -47.8105, LocalClima.lerCoordenadas("-21.1795, -47.8105"))
    }

    @Test fun aceitaParentesesEspacoEPontoEVirgula() {
        assertEquals(-21.1795 to -47.8105, LocalClima.lerCoordenadas(" (-21.1795,-47.8105) "))
        assertEquals(-21.1795 to -47.8105, LocalClima.lerCoordenadas("-21.1795 -47.8105"))
        assertEquals(-21.1795 to -47.8105, LocalClima.lerCoordenadas("-21,1795; -47,8105"))
    }

    @Test fun recusaOQueNaoECoordenada() {
        assertNull(LocalClima.lerCoordenadas(""))
        assertNull(LocalClima.lerCoordenadas("Higienópolis"))
        assertNull(LocalClima.lerCoordenadas("-21,1795, -47,8105"))   // vírgula decimal E separadora: ambíguo
        assertNull(LocalClima.lerCoordenadas("95, 10"))               // latitude fora de -90..90
        assertNull(LocalClima.lerCoordenadas("10, 190"))              // longitude fora de -180..180
        assertNull(LocalClima.lerCoordenadas("0, 0"))                 // o padrão "sem lugar" do build
        assertNull(LocalClima.lerCoordenadas("NaN, 1"))
    }

    @Test fun nomeLimpo() {
        assertEquals("Centro", LocalClima.limparNome("  Centro \n"))
        assertEquals("Local escolhido", LocalClima.limparNome(""))
        assertEquals("Local escolhido", LocalClima.limparNome(null))
        assertEquals(LocalClima.MAX_NOME, LocalClima.limparNome("x".repeat(100)).length)
    }

    // ---- faixa de 9 dias

    private fun daily(n: Int = 9) = JSONObject()
        .put("time", JSONArray((0 until n).map { "2026-11-%02d".format(5 + it) }))
        .put("weather_code", JSONArray((0 until n).map { if (it % 2 == 0) 61 else 2 }))
        .put("temperature_2m_max", JSONArray((0 until n).map { 29.4 + it }))
        .put("temperature_2m_min", JSONArray((0 until n).map { 20.6 }))
        .put("precipitation_probability_max", JSONArray((0 until n).map { 80 }))

    @Test fun leNoveDias() {
        val dias = FaixaDias.ler(daily(), tempAgora = 25)
        assertEquals(9, dias.size)
        assertEquals(FaixaDias.Dia("2026-11-05", 61, 29, 21, 80), dias[0])
        assertEquals(FaixaDias.Dia("2026-11-13", 61, 37, 21, 80), dias[8])
    }

    @Test fun hojeSeAjustaATemperaturaMedida() {
        // O aeroporto mediu 32° com o modelo dizendo máxima 29°: a de hoje vira 32 (igual ao topo), as outras não.
        val dias = FaixaDias.ler(daily(), tempAgora = 32)
        assertEquals(32, dias[0].max)
        assertEquals(30, dias[1].max)
        assertEquals(18, FaixaDias.ler(daily(), tempAgora = 18)[0].min)
    }

    @Test fun chanceDeChuvaPelaMedianaQuandoHa() {
        val mediana = listOf(55, 15, 55, 40, 10, 10, 15, 50, 15)
        assertEquals(mediana, FaixaDias.ler(daily(), null, diasChuva = mediana).map { it.chuva })
        // Mediana mais curta que os dias: o resto cai no best_match.
        assertEquals(listOf(55, 80, 80), FaixaDias.ler(daily(3), null, diasChuva = listOf(55)).map { it.chuva })
    }

    @Test fun chanceDoDiaEOMeioTermoEntrePicoEMedia() {
        // Números de 05/11/2026 (mediana ECMWF/ICON/GFS): pico 76/43/71, média 21/13/27 -> 49/28/49 (Google: 55/15/55).
        assertEquals(listOf(49, 28, 49), FaixaDias.chanceDoDia(listOf(76, 43, 71), listOf(21, 13, 27)))
        assertEquals(listOf(76, 43), FaixaDias.chanceDoDia(listOf(76, 43), null))          // sem média: o pico
        assertEquals(listOf(49, 43), FaixaDias.chanceDoDia(listOf(76, 43), listOf(21)))    // média mais curta
        assertNull(FaixaDias.chanceDoDia(null, listOf(21)))
    }

    @Test fun pulaDiaSemDadoENaoPassaDoPedido() {
        val d = daily().put("temperature_2m_max", JSONArray(listOf(30.0, JSONObject.NULL, 31.0)))
        assertEquals(listOf("2026-11-05", "2026-11-07"), FaixaDias.ler(d, null).map { it.data })
        assertEquals(3, FaixaDias.ler(daily(), null, dias = 3).size)
        assertEquals(emptyList<FaixaDias.Dia>(), FaixaDias.ler(JSONObject(), null))
    }

    @Test fun nomesDosDias() {
        val d = FaixaDias.Dia("2026-11-06", 2, 33, 23, 15)   // sexta-feira
        assertEquals("Hoje", FaixaDias.rotulo(d, 0, "Hoje"))
        assertEquals("sex.", FaixaDias.rotulo(d, 1, "Hoje", Locale.forLanguageTag("pt-BR")))
        assertEquals("Fri", FaixaDias.rotulo(d, 1, "Today", Locale.US))
    }
}
