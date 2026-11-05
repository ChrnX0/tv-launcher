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
        // Formato novo do AI Studio (05/11/2026): "AQ." + base64url. Chave FALSA, só o formato.
        assertTrue(ComandosIa.chaveValida("AQ.Ab8RN6Lxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx-_123"))
        assertEquals("AQ.abc", ComandosIa.limparChave(" AQ.a\nb c\t"))
        assertFalse(ComandosIa.chaveValida("AQ.abc"))
        assertFalse(ComandosIa.chaveValida("AQ.Ab8RN6L/xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"))
        assertFalse(ComandosIa.chaveValida("curta"))
        assertFalse(ComandosIa.chaveValida("AIzaSy 1234567890abcdefghijklmnop"))
        assertFalse(ComandosIa.chaveValida("AIzaSyÁ1234567890abcdefghijklmnopq"))
        assertTrue(ComandosIa.modeloValido("gemini-flash-latest"))
        assertFalse(ComandosIa.modeloValido("../models/x"))
        assertFalse(ComandosIa.modeloValido("gemini:generateContent?x"))
    }

    @Test fun detectaProvedorPelaChave() {
        // Chaves FALSAS: só o começo importa.
        assertEquals(ComandosIa.Provedor.CLAUDE, ComandosIa.Provedor.detectar("sk-ant-api03-xxxxxxxxxxxxxxxx"))
        assertEquals(ComandosIa.Provedor.OPENAI, ComandosIa.Provedor.detectar("sk-proj-xxxxxxxxxxxxxxxxxxxx"))
        assertEquals(ComandosIa.Provedor.PERPLEXITY, ComandosIa.Provedor.detectar("pplx-xxxxxxxxxxxxxxxxxxxx"))
        assertEquals(ComandosIa.Provedor.GEMINI, ComandosIa.Provedor.detectar("AQ.xxxxxxxxxxxxxxxxxxxx"))
        assertEquals(ComandosIa.Provedor.GEMINI, ComandosIa.Provedor.detectar("AIzaSyxxxxxxxxxxxxxxxx"))
        assertNull(ComandosIa.Provedor.detectar("xyz-desconhecida-1234567890"))
        assertEquals(ComandosIa.Provedor.OPENAI, ComandosIa.Provedor.porId("openai"))
        assertNull(ComandosIa.Provedor.porId("../gemini"))
        assertNull(ComandosIa.Provedor.porId(null))
        // Chave de formato desconhecido continua aceita (o formato muda; a 1ª chamada é o teste real).
        assertTrue(ComandosIa.chaveValida("xyz-desconhecida-1234567890"))
        assertTrue(ComandosIa.chaveValida("sk-ant-api03-" + "x".repeat(95)))
        assertTrue(ComandosIa.modeloValido("claude-haiku-5-5"))
        assertTrue(ComandosIa.modeloValido("gpt-5.4-mini"))
        assertTrue(ComandosIa.modeloValido("sonar"))
        for (p in ComandosIa.Provedor.values()) assertTrue(p.id, ComandosIa.modeloValido(p.modeloPadrao))
    }

    @Test fun esquemaNosDoisFormatos() {
        // Gemini (OpenAPI): tipos em MAIÚSCULAS, sem additionalProperties.
        val g = ComandosIa.esquema(openApi = true)
        assertEquals("OBJECT", g.getString("type"))
        assertFalse(g.has("additionalProperties"))
        // JSON Schema (Claude, OpenAI, Perplexity): minúsculas e additionalProperties=false em todo objeto.
        val j = ComandosIa.esquema(openApi = false)
        assertEquals("object", j.getString("type"))
        assertEquals(false, j.get("additionalProperties"))
        val acao = j.getJSONObject("properties").getJSONObject("acoes").getJSONObject("items")
        assertEquals("object", acao.getString("type"))
        assertEquals(false, acao.get("additionalProperties"))
        assertFalse(j.toString().contains("\"OBJECT\"") || j.toString().contains("\"STRING\""))
    }

    @Test fun jsonDentroDeTextoAindaConfere() {
        // Alguns modelos embrulham o JSON em ```json … ``` ou em uma frase; o plano B pega o trecho entre { e }.
        val p = ComandosIa.conferir("Claro!\n```json\n{\"acoes\":[{\"tipo\":\"midia\",\"acao\":\"pausar\"}],\"resposta\":\"ok\"}\n```", apps)
        assertEquals(1, p.acoes.size)
    }

    @Test fun esquemaEInstrucoesSaemSemErro() {
        val e = ComandosIa.esquema()
        assertTrue(e.getJSONObject("properties").has("acoes"))
        val i = ComandosIa.instrucoes(apps, "quinta, 05/11/2026 08:00")
        assertTrue(i.contains("com.netflix.ninja = Netflix"))
    }
}
