package br.paulo.tvlauncher

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder

/**
 * HTTP mínimo do painel no navegador (ver PainelWeb.kt). Sem biblioteca de propósito: o NanoHTTPD custava 30-60 KB e
 * gravava upload em arquivo temporário (plano do painel, seção 6). Aqui só o que o painel usa: GET e POST, corpo com
 * Content-Length, sempre "Connection: close" (uma requisição por conexão, sem keep-alive para administrar).
 *
 * Este arquivo não usa NADA do Android: assim os testes rodam no computador (app/src/test, PainelHttpTest).
 */

/** Requisição já lida. [cabecalhos] tem os nomes em minúsculas. [caminho] vem cru (sem decodificar %xx). */
class Requisicao(
    val metodo: String,
    val caminho: String,
    val consulta: Map<String, String>,
    val cabecalhos: Map<String, String>,
    val corpo: ByteArray,
) {
    fun cabecalho(nome: String): String? = cabecalhos[nome.lowercase()]
    fun corpoTexto(): String = String(corpo, Charsets.UTF_8)
}

/** Erro que vira uma resposta HTTP com este código (ex.: 413 corpo grande demais). */
class ErroHttp(val codigo: Int, mensagem: String) : IOException(mensagem)

object PainelHttp {
    const val LIMITE_CABECALHOS = 8 * 1024   // linha de pedido + cabeçalhos juntos

    /**
     * Lê uma requisição de [entrada]. [limiteCorpo] é o maior corpo aceito (bytes) — passado por quem chama porque a
     * restauração de backup aceita mais do que um login. Lança [ErroHttp] para pedido malformado ou grande demais,
     * e IOException se a conexão cair no meio.
     */
    fun ler(entrada: InputStream, limiteCorpo: Int): Requisicao {
        val linhas = lerCabecalhos(entrada)
        val pedido = linhas.first().split(' ')
        if (pedido.size != 3 || !pedido[2].startsWith("HTTP/1.")) throw ErroHttp(400, "linha de pedido inválida")
        val metodo = pedido[0]
        val alvo = pedido[1]
        if (!alvo.startsWith("/")) throw ErroHttp(400, "alvo inválido")

        val cabecalhos = HashMap<String, String>()
        for (linha in linhas.drop(1)) {
            val doisPontos = linha.indexOf(':')
            if (doisPontos <= 0) throw ErroHttp(400, "cabeçalho inválido")
            val nome = linha.substring(0, doisPontos).trim().lowercase()
            val valor = linha.substring(doisPontos + 1).trim()
            // Host repetido é o truque clássico para enganar a checagem de Host: recusa.
            if (nome == "host" && cabecalhos.containsKey("host")) throw ErroHttp(400, "Host repetido")
            cabecalhos[nome] = valor
        }
        // Corpo "chunked" não é suportado (o navegador só usa com stream; o painel sempre manda tamanho conhecido).
        if (cabecalhos.containsKey("transfer-encoding")) throw ErroHttp(411, "use Content-Length")

        val tamanho = cabecalhos["content-length"]?.let {
            it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: throw ErroHttp(400, "Content-Length inválido")
        } ?: 0
        if (tamanho > limiteCorpo) throw ErroHttp(413, "corpo grande demais")
        val corpo = lerExato(entrada, tamanho)

        val interrogacao = alvo.indexOf('?')
        val caminho = if (interrogacao < 0) alvo else alvo.substring(0, interrogacao)
        val consulta = if (interrogacao < 0) emptyMap() else lerConsulta(alvo.substring(interrogacao + 1))
        return Requisicao(metodo, caminho, consulta, cabecalhos, corpo)
    }

    /** Linhas até a linha vazia, sem o \r\n. Lê byte a byte (só os cabeçalhos; poucos KB) para não engolir o corpo. */
    private fun lerCabecalhos(entrada: InputStream): List<String> {
        val linhas = ArrayList<String>()
        val atual = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val b = entrada.read()
            if (b < 0) {
                if (total == 0) throw IOException("conexão fechada sem pedido")
                throw ErroHttp(400, "cabeçalhos incompletos")
            }
            if (++total > LIMITE_CABECALHOS) throw ErroHttp(431, "cabeçalhos grandes demais")
            if (b == '\n'.code) {
                var linha = atual.toString("ISO-8859-1")
                if (linha.endsWith("\r")) linha = linha.dropLast(1)
                atual.reset()
                if (linha.isEmpty()) {
                    if (linhas.isEmpty()) continue   // linhas vazias antes do pedido: a RFC manda ignorar
                    return linhas
                }
                linhas.add(linha)
            } else {
                atual.write(b)
            }
        }
    }

    private fun lerExato(entrada: InputStream, n: Int): ByteArray {
        val corpo = ByteArray(n)
        var lidos = 0
        while (lidos < n) {
            val r = entrada.read(corpo, lidos, n - lidos)
            if (r < 0) throw IOException("corpo incompleto ($lidos de $n bytes)")
            lidos += r
        }
        return corpo
    }

    private fun lerConsulta(texto: String): Map<String, String> {
        val mapa = HashMap<String, String>()
        for (par in texto.split('&')) {
            if (par.isEmpty()) continue
            val igual = par.indexOf('=')
            try {
                val nome = URLDecoder.decode(if (igual < 0) par else par.substring(0, igual), "UTF-8")
                val valor = if (igual < 0) "" else URLDecoder.decode(par.substring(igual + 1), "UTF-8")
                mapa[nome] = valor
            } catch (e: IllegalArgumentException) {
                throw ErroHttp(400, "consulta inválida")
            }
        }
        return mapa
    }

    /** Valor do cookie [nome] num cabeçalho Cookie ("a=1; b=2"), ou null. */
    fun cookie(cabecalho: String?, nome: String): String? {
        if (cabecalho == null) return null
        for (parte in cabecalho.split(';')) {
            val igual = parte.indexOf('=')
            if (igual > 0 && parte.substring(0, igual).trim() == nome) return parte.substring(igual + 1).trim()
        }
        return null
    }

    fun textoStatus(codigo: Int): String = when (codigo) {
        200 -> "OK"; 204 -> "No Content"; 304 -> "Not Modified"
        400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"
        405 -> "Method Not Allowed"; 409 -> "Conflict"; 411 -> "Length Required"; 413 -> "Payload Too Large"
        421 -> "Misdirected Request"; 429 -> "Too Many Requests"; 431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"; 503 -> "Service Unavailable"
        else -> "Status"
    }

    /**
     * Cabeçalhos de segurança que vão em TODA resposta. A CSP só deixa a página carregar arquivos do próprio painel
     * (nada de script de fora, nada de script inline) e proíbe abrir o painel dentro de iframe de outro site
     * (clickjacking). no-store: nada do painel fica no cache do navegador, exceto o que a rota liberar (ícones).
     */
    val CABECALHOS_SEGURANCA = linkedMapOf(
        "Content-Security-Policy" to "default-src 'self'; img-src 'self'; style-src 'self'; script-src 'self'; " +
            "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
        "X-Content-Type-Options" to "nosniff",
        "X-Frame-Options" to "DENY",
        "Referrer-Policy" to "no-referrer",
        "Cache-Control" to "no-store",
    )

    /** Escreve a resposta inteira. [extras] pode sobrescrever os de segurança (ex.: Cache-Control dos ícones). */
    fun escrever(saida: OutputStream, codigo: Int, tipo: String?, corpo: ByteArray, extras: Map<String, String> = emptyMap()) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(codigo).append(' ').append(textoStatus(codigo)).append("\r\n")
        val todos = LinkedHashMap(CABECALHOS_SEGURANCA)
        todos.putAll(extras)
        if (tipo != null) todos["Content-Type"] = tipo
        todos["Content-Length"] = corpo.size.toString()
        todos["Connection"] = "close"
        for ((nome, valor) in todos) {
            // Um \r ou \n num valor permitiria injetar cabeçalhos. Nenhum valor nosso tem, mas a barreira fica aqui.
            require('\r' !in valor && '\n' !in valor) { "quebra de linha no cabeçalho $nome" }
            sb.append(nome).append(": ").append(valor).append("\r\n")
        }
        sb.append("\r\n")
        saida.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        saida.write(corpo)
        saida.flush()
    }
}
