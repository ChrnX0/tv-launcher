package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Confere a estrutura do index.html do painel. Motivo (05/11/2026): um merge local perdeu o </section> da aba Falar, e
 * o navegador pôs as sete abas seguintes DENTRO dela. Com a Falar escondida, todas apareciam vazias, sem nenhum erro.
 * Cada PR que mexe no painel junta abas no mesmo arquivo, então isso pode se repetir em qualquer merge.
 */
class PainelHtmlTest {
    // Os testes de unidade rodam com a pasta do módulo (app/) como diretório atual.
    private val html: String = listOf("src/main/assets/painel/index.html", "app/src/main/assets/painel/index.html")
        .map(::File).first { it.isFile }.readText()

    /** (id, profundidade) de cada <section>, contando só as <section> abertas e fechadas. */
    private fun secoes(): List<Pair<String?, Int>> {
        val r = ArrayList<Pair<String?, Int>>()
        var nivel = 0
        for (m in Regex("""<(/?)section\b([^>]*)>""", RegexOption.IGNORE_CASE).findAll(html)) {
            if (m.groupValues[1] == "/") { nivel--; continue }
            val id = Regex("""\bid="([^"]+)"""").find(m.groupValues[2])?.groupValues?.get(1)
            r.add(id to nivel)
            nivel++
        }
        assertEquals("cada <section> precisa do seu </section>", 0, nivel)
        return r
    }

    @Test fun nenhumaAbaDentroDeOutra() {
        val abas = secoes().filter { it.first?.startsWith("aba") == true }
        assertTrue("o painel tem abas", abas.isNotEmpty())
        val nivel = abas.first().second
        for ((id, n) in abas) assertEquals("a aba $id está dentro de outra seção (faltou um </section> antes dela?)", nivel, n)
    }

    @Test fun cadaBotaoDeAbaTemSuaSecao() {
        val ids = secoes().mapNotNull { it.first }.toSet()
        val botoes = Regex("""data-aba="([a-z]+)"""").findAll(html).map { it.groupValues[1] }.toList()
        assertTrue(botoes.isNotEmpty())
        // Convenção do app.js: data-aba="arquivos" mostra a <section id="abaArquivos">; "registro" é a de Acessos.
        for (b in botoes) assertTrue("o botão de aba \"$b\" não tem <section id=\"aba${b.replaceFirstChar { it.uppercase() }}\">",
            "aba" + b.replaceFirstChar { it.uppercase() } in ids)
    }
}
