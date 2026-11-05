package br.paulo.tvlauncher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** O login OAuth do MCP (McpAutorizacao). Roda no computador: gradlew testReleaseUnitTest. */
class McpAutorizacaoTest {
    private var agora = 1_000_000L
    private var salvo: String? = null
    private fun nova(estado: String? = null) = McpAutorizacao({ agora }, estado, { salvo = it })

    private val volta = "https://claude.ai/api/mcp/auth_callback"
    private val verifier = "a".repeat(50)
    private val desafio = McpAutorizacao.desafioS256(verifier)

    private fun registrar(a: McpAutorizacao, redirect: String = volta): String =
        a.registrar(JSONObject().put("redirect_uris", JSONArray(listOf(redirect))).put("client_name", "Claude")).getString("client_id")

    private fun esperaErro(codigo: String, bloco: () -> Unit) {
        try { bloco(); fail("devia dar $codigo") } catch (e: McpAutorizacao.Erro) { assertEquals(codigo, e.codigo) }
    }

    private fun login(a: McpAutorizacao, cliente: String): McpAutorizacao.Par {
        a.conferirPedido(cliente, volta, "code", desafio, "S256")
        val codigo = a.criarCodigo(cliente, volta, desafio)
        return a.trocarCodigo(codigo, cliente, volta, verifier)
    }

    @Test fun soAceitaVoltaParaOClaudeOuLocal() {
        assertTrue(McpAutorizacao.redirectPermitido("https://claude.ai/api/mcp/auth_callback"))
        assertTrue(McpAutorizacao.redirectPermitido("https://claude.com/api/mcp/auth_callback"))
        assertTrue(McpAutorizacao.redirectPermitido("http://localhost:33418/callback"))
        assertTrue(McpAutorizacao.redirectPermitido("http://127.0.0.1:5000/cb"))
        assertFalse(McpAutorizacao.redirectPermitido("http://claude.ai/cb"))            // sem TLS
        assertFalse(McpAutorizacao.redirectPermitido("https://claude.ai.mal.com/cb"))
        assertFalse(McpAutorizacao.redirectPermitido("https://mal.com/cb"))
        assertFalse(McpAutorizacao.redirectPermitido("https://user@claude.ai/cb"))
        assertFalse(McpAutorizacao.redirectPermitido("https://claude.ai/cb#x"))
        assertFalse(McpAutorizacao.redirectPermitido("javascript:alert(1)"))
        assertFalse(McpAutorizacao.redirectPermitido(null))
    }

    @Test fun registroComVoltaEstranhaFalha() {
        esperaErro("invalid_redirect_uri") { registrar(nova(), "https://mal.com/cb") }
    }

    @Test fun fluxoCompletoDaToken() {
        val a = nova()
        val par = login(a, registrar(a))
        assertTrue(a.tokenValido(par.acesso))
        assertFalse(a.tokenValido(par.renovar))   // refresh não serve como acesso
        assertFalse(a.tokenValido("outro"))
        assertEquals(1, a.quantosLogins())
    }

    @Test fun pedidoSemPkceOuVoltaDiferenteFalha() {
        val a = nova(); val c = registrar(a)
        esperaErro("invalid_request") { a.conferirPedido(c, volta, "code", desafio, "plain") }
        esperaErro("invalid_request") { a.conferirPedido(c, "https://claude.ai/outra", "code", desafio, "S256") }
        esperaErro("unsupported_response_type") { a.conferirPedido(c, volta, "token", desafio, "S256") }
        esperaErro("invalid_client") { a.conferirPedido("c_nao_existe", volta, "code", desafio, "S256") }
    }

    @Test fun codigoValeUmaVezSoEVence() {
        val a = nova(); val c = registrar(a)
        val codigo = a.criarCodigo(c, volta, desafio)
        a.trocarCodigo(codigo, c, volta, verifier)
        esperaErro("invalid_grant") { a.trocarCodigo(codigo, c, volta, verifier) }
        val codigo2 = a.criarCodigo(c, volta, desafio)
        agora += McpAutorizacao.CODIGO_MS + 1
        esperaErro("invalid_grant") { a.trocarCodigo(codigo2, c, volta, verifier) }
    }

    @Test fun verifierErradoFalhaEQueimaOCodigo() {
        val a = nova(); val c = registrar(a)
        val codigo = a.criarCodigo(c, volta, desafio)
        esperaErro("invalid_grant") { a.trocarCodigo(codigo, c, volta, "b".repeat(50)) }
        esperaErro("invalid_grant") { a.trocarCodigo(codigo, c, volta, verifier) }
    }

    @Test fun acessoVence() {
        val a = nova(); val par = login(a, registrar(a))
        agora += McpAutorizacao.ACESSO_MS + 1
        assertFalse(a.tokenValido(par.acesso))
    }

    @Test fun renovarGiraEReusoDerrubaAFamilia() {
        val a = nova(); val c = registrar(a)
        val p1 = login(a, c)
        val p2 = a.renovar(p1.renovar, c)
        assertNotEquals(p1.acesso, p2.acesso)
        assertFalse(a.tokenValido(p1.acesso))
        assertTrue(a.tokenValido(p2.acesso))
        esperaErro("invalid_grant") { a.renovar(p1.renovar, c) }   // reuso do antigo
        esperaErro("invalid_grant") { a.renovar(p2.renovar, "c_outro") }
    }

    @Test fun guardaSoResumoEVoltaDoEstadoSalvo() {
        val a = nova(); val c = registrar(a)
        val par = login(a, c)
        assertFalse(salvo!!.contains(par.acesso))
        assertFalse(salvo!!.contains(par.renovar))
        val b = nova(salvo)
        assertTrue(b.tokenValido(par.acesso))
        assertEquals("Claude", b.nomeDoCliente(c))
    }

    @Test fun revogarTudoApaga() {
        val a = nova(); val par = login(a, registrar(a))
        a.revogarTudo()
        assertFalse(a.tokenValido(par.acesso))
        assertEquals(0, a.quantosLogins())
    }

    @Test fun desafioS256DaRfc7636() {
        // Exemplo do apêndice B da RFC 7636.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            McpAutorizacao.desafioS256("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }
}
