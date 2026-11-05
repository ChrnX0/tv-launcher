package br.paulo.tvlauncher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** As mensagens JSON-RPC do MCP (McpProtocolo). Roda no computador: gradlew testReleaseUnitTest. */
class McpProtocoloTest {
    private val falsas = object : McpProtocolo.Ferramentas {
        override fun lista() = JSONArray().put(JSONObject().put("name", "eco")).put(JSONObject().put("name", "quebra"))
        override fun chamar(nome: String, argumentos: JSONObject): String = when (nome) {
            "eco" -> argumentos.optString("x")
            else -> throw McpProtocolo.ErroFerramenta("não deu")
        }
    }
    private fun tratar(s: String) = McpProtocolo.tratar(s, falsas)

    @Test fun initializeDevolveVersaoPedidaOuAMaisNova() {
        val r = tratar("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""")!!
        assertEquals("2025-06-18", r.getJSONObject("result").getString("protocolVersion"))
        val r2 = tratar("""{"jsonrpc":"2.0","id":2,"method":"initialize","params":{"protocolVersion":"1999-01-01"}}""")!!
        assertEquals(McpProtocolo.VERSOES.first(), r2.getJSONObject("result").getString("protocolVersion"))
        assertTrue(r2.getJSONObject("result").getJSONObject("capabilities").has("tools"))
    }

    @Test fun notificacaoNaoTemResposta() {
        assertNull(tratar("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
    }

    @Test fun listaEChama() {
        val l = tratar("""{"jsonrpc":"2.0","id":"a","method":"tools/list"}""")!!
        assertEquals(2, l.getJSONObject("result").getJSONArray("tools").length())
        val c = tratar("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"eco","arguments":{"x":"oi"}}}""")!!
        val res = c.getJSONObject("result")
        assertEquals(false, res.getBoolean("isError"))
        assertEquals("oi", res.getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test fun erroDaFerramentaVemComoIsError() {
        val c = tratar("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"quebra"}}""")!!
        assertEquals(true, c.getJSONObject("result").getBoolean("isError"))
    }

    @Test fun errosDoProtocolo() {
        assertEquals(-32700, tratar("{nao é json")!!.getJSONObject("error").getInt("code"))
        assertEquals(-32600, tratar("""{"id":1,"method":"ping"}""")!!.getJSONObject("error").getInt("code"))
        assertEquals(-32601, tratar("""{"jsonrpc":"2.0","id":1,"method":"resources/list"}""")!!.getJSONObject("error").getInt("code"))
        assertEquals(-32602, tratar("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"shell"}}""")!!.getJSONObject("error").getInt("code"))
    }

    @Test fun ping() {
        assertEquals(5, tratar("""{"jsonrpc":"2.0","id":5,"method":"ping"}""")!!.getInt("id"))
    }
}
