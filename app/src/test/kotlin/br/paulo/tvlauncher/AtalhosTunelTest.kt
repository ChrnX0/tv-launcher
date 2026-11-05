package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Atalhos de porta do túnel (AtalhosTunel). Roda no computador: gradlew testReleaseUnitTest. */
class AtalhosTunelTest {
    @Test fun lePortaEAparelho() {
        val l = AtalhosTunel.ler("nas:8096, Servidor.tail12.ts.net:32400")!!
        assertEquals(2, l.size)
        assertEquals("servidor.tail12.ts.net", l[1].aparelho)
        assertEquals("8096=nas:8096,32400=servidor.tail12.ts.net:32400", AtalhosTunel.paraAmbiente(l))
    }

    @Test fun portaBaixaVaiPara18000() {
        assertEquals(18080, AtalhosTunel.ler("nas:80")!![0].portaLocal)
    }

    @Test fun vazioEhNenhum() { assertEquals(0, AtalhosTunel.ler("  ")!!.size) }

    @Test fun recusaInvalidos() {
        assertNull(AtalhosTunel.ler("nas"))
        assertNull(AtalhosTunel.ler("nas:0"))
        assertNull(AtalhosTunel.ler("nas:70000"))
        assertNull(AtalhosTunel.ler("n\$as:80"))
        assertNull(AtalhosTunel.ler("-nas:80"))
        assertNull(AtalhosTunel.ler("nas:8096,outro:8096"))   // mesma porta local
        assertNull(AtalhosTunel.ler("nas:8080"))              // porta do painel
        assertNull(AtalhosTunel.ler("a:1,b:2,c:3,d:4,e:5,f:6"))
    }
}
