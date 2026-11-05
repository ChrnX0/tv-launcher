package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A conferência do que volta da IA na nuvem (ComandosIa). Roda no computador: gradlew testReleaseUnitTest. */
class ComandosIaTest {
    private val apps = mapOf("com.netflix.ninja" to "Netflix", "com.google.android.youtube.tv" to "YouTube")

    @Test fun abreAppDaLista() {
        val p = ComandosIa.conferir("""{"acoes":[{"tipo":"abrir_app","pacote":"com.netflix.ninja"}],"resposta":"ok"}""", apps)
        assertEquals(1, p.acoes.size)
        assertEquals("Abrir Netflix", p.acoes[0].descricao)
        assertTrue(p.recusadas.isEmpty())
    }

    @Test fun recusaAppForaDaLista() {
        val p = ComandosIa.conferir("""{"acoes":[{"tipo":"abrir_app","pacote":"com.android.settings"}],"resposta":""}""", apps)
        assertTrue(p.acoes.isEmpty())
        assertEquals(1, p.recusadas.size)
    }

    @Test fun recusaTipoForaDoCatalogo() {
        val p = ComandosIa.conferir("""{"acoes":[{"tipo":"shell","comando":"pm uninstall br.paulo.tvlauncher"},
            {"tipo":"desinstalar","pacote":"com.netflix.ninja"}],"resposta":""}""", apps)
        assertTrue(p.acoes.isEmpty())
        assertEquals(2, p.recusadas.size)
    }

    @Test fun despertadorConfereHoraEDias() {
        val ok = ComandosIa.conferir("""{"acoes":[{"tipo":"despertador","hora":7,"minuto":30,"dias":[1,2,3,4,5]}],"resposta":""}""", apps)
        val a = ok.acoes.single() as ComandosIa.Acao.Despertador
        assertEquals(0b0111110, a.dias)
        assertEquals("Criar despertador às 07:30 (seg a sex)", a.descricao)
        assertTrue(ComandosIa.conferir("""{"acoes":[{"tipo":"despertador","hora":24,"minuto":0}]}""", apps).acoes.isEmpty())
        assertTrue(ComandosIa.conferir("""{"acoes":[{"tipo":"despertador","hora":7.5}]}""", apps).acoes.isEmpty())
        assertTrue(ComandosIa.conferir("""{"acoes":[{"tipo":"despertador","hora":7,"dias":[9]}]}""", apps).acoes.isEmpty())
        assertTrue(ComandosIa.conferir("""{"acoes":[{"tipo":"despertador","hora":"7"}]}""", apps).acoes.isEmpty())
    }

    @Test fun nomeDoDespertadorSoTextoSimples() {
        val p = ComandosIa.conferir("""{"acoes":[{"tipo":"despertador","hora":6,"nome":"<b>remédio</b>\n!!"}]}""", apps)
        assertEquals("bremédiob", (p.acoes.single() as ComandosIa.Acao.Despertador).nome)
    }

    @Test fun volumeLimitaPassos() {
        assertEquals("Aumentar o volume (3 vezes)",
            ComandosIa.conferir("""{"acoes":[{"tipo":"volume","acao":"subir","vezes":3}]}""", apps).acoes.single().descricao)
        assertTrue(ComandosIa.conferir("""{"acoes":[{"tipo":"volume","acao":"subir","vezes":50}]}""", apps).acoes.isEmpty())
        assertTrue(ComandosIa.conferir("""{"acoes":[{"tipo":"volume","acao":"explodir"}]}""", apps).acoes.isEmpty())
    }

    @Test fun modoVisitaExigeBooleano() {
        assertTrue(ComandosIa.conferir("""{"acoes":[{"tipo":"modo_visita","ligado":"sim"}]}""", apps).acoes.isEmpty())
        assertEquals("Ligar o modo visita", ComandosIa.conferir("""{"acoes":[{"tipo":"modo_visita","ligado":true}]}""", apps).acoes.single().descricao)
    }

    @Test fun maximoDeAcoes() {
        val muitas = (1..9).joinToString(",") { """{"tipo":"midia","acao":"pausar"}""" }
        val p = ComandosIa.conferir("""{"acoes":[$muitas],"resposta":""}""", apps)
        assertEquals(ComandosIa.MAX_ACOES, p.acoes.size)
        assertEquals(1, p.recusadas.size)
    }

    @Test fun lixoNaoQuebra() {
        assertTrue(ComandosIa.conferir("isso não é json", apps).acoes.isEmpty())
        assertTrue(ComandosIa.conferir("""{"acoes":"abrir tudo"}""", apps).acoes.isEmpty())
        assertTrue(ComandosIa.conferir("""{"acoes":[1,null,"x"]}""", apps).acoes.isEmpty())
        assertTrue(ComandosIa.conferir("""{"acoes":[{"tipo":"abrir_tela","tela":"../../etc"}]}""", apps).acoes.isEmpty())
    }

    @Test fun frasesEChaves() {
        assertNull(ComandosIa.limparFrase("   "))
        assertNull(ComandosIa.limparFrase(null))
        assertEquals("abre a netflix", ComandosIa.limparFrase(" abre a\u0000 netflix "))
        assertEquals(ComandosIa.MAX_FRASE, ComandosIa.limparFrase("a".repeat(1000))!!.length)
        assertTrue(ComandosIa.chaveValida("AIzaSyA-1234567890abcdefghijklmnopqrs_t"))
        assertFalse(ComandosIa.chaveValida("curta"))
        assertFalse(ComandosIa.chaveValida("AIzaSy 1234567890abcdefghijklmnop"))
        assertFalse(ComandosIa.chaveValida("AIzaSyÁ1234567890abcdefghijklmnopq"))
        assertTrue(ComandosIa.modeloValido("gemini-flash-latest"))
        assertFalse(ComandosIa.modeloValido("../models/x"))
        assertFalse(ComandosIa.modeloValido("gemini:generateContent?x"))
    }

    @Test fun esquemaEInstrucoesSaemSemErro() {
        val e = ComandosIa.esquema()
        assertTrue(e.getJSONObject("properties").has("acoes"))
        val i = ComandosIa.instrucoes(apps, "quinta, 05/11/2026 08:00")
        assertTrue(i.contains("com.netflix.ninja = Netflix"))
    }
}
