package br.paulo.tvlauncher

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class PainelShizukuTest {
    @Test fun historicoNovoNoComecoECortado() {
        var h = "[]"
        for (i in 1..5) h = PainelShizuku.juntarHistorico(h, "app$i", i.toLong(), 3)
        val a = JSONArray(h)
        assertEquals(3, a.length())
        assertEquals("app5", a.getJSONObject(0).getString("p"))
        assertEquals("app3", a.getJSONObject(2).getString("p"))
    }

    @Test fun textoEstragadoRecomeca() =
        assertEquals(1, JSONArray(PainelShizuku.juntarHistorico("{lixo", "a", 1, 50)).length())
}
