package br.paulo.tvlauncher

import android.util.Log
import br.paulo.tvlauncher.ComandosIa.Provedor
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * A chamada à IA na nuvem, com a chave DO DONO (bloco 6, 05/11/2026). Quatro provedores, um pedido igual para todos:
 * as instruções fixas (ComandosIa.instrucoes) + a frase, e a resposta forçada ao JSON do catálogo (ComandosIa.esquema).
 *
 *  - Gemini: generateContent com responseSchema. Chave no cabeçalho x-goog-api-key (aceita "AQ." e "AIza").
 *  - Claude: Messages API (POST /v1/messages) com output_config.format = json_schema. Cabeçalhos x-api-key e
 *    anthropic-version. Sem temperature (a versão 2026-10-21 recusa).
 *  - OpenAI: chat/completions com response_format = json_schema. Authorization: Bearer.
 *  - Perplexity: chat/completions (mesmo formato do OpenAI) com response_format = json_schema.
 *
 * Feito na mão (HttpsURLConnection), sem SDK: são quatro provedores e o APK é pequeno; um SDK por provedor dobraria o
 * tamanho. Mesmo assim, o que volta é conferido de novo (ComandosIa.conferir) e só roda com OK na TV.
 *
 * O que sai da TV: a frase, a data/hora e os nomes dos apps. HTTPS com a verificação padrão do Android (nunca
 * desligar). A chave nunca entra em log nem em mensagem de erro.
 */
object IaNuvem {
    private const val TAG = "TvLauncher.Ia"

    class Falha(mensagem: String) : Exception(mensagem)

    /** Devolve o texto JSON que a IA gerou. Lança [Falha] com uma mensagem para mostrar. Rede: fora da thread da tela. */
    fun perguntar(p: Provedor, chave: String, modelo: String, instrucoes: String, frase: String): String {
        require(ComandosIa.modeloValido(modelo))
        return when (p) {
            Provedor.GEMINI -> gemini(chave, modelo, instrucoes, frase)
            Provedor.CLAUDE -> claude(chave, modelo, instrucoes, frase)
            Provedor.OPENAI -> chat(p, "https://api.openai.com/v1/chat/completions", chave, modelo, instrucoes, frase)
            Provedor.PERPLEXITY -> chat(p, "https://api.perplexity.ai/chat/completions", chave, modelo, instrucoes, frase)
        }
    }

    // ---------------------------------------------------------------- Gemini

    private fun gemini(chave: String, modelo: String, instrucoes: String, frase: String): String {
        val corpo = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", instrucoes))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", frase)))))
            .put("generationConfig", JSONObject()
                .put("temperature", 0)
                .put("maxOutputTokens", 1024)
                .put("responseMimeType", "application/json")
                .put("responseSchema", ComandosIa.esquema(openApi = true)))
        val raiz = postar(Provedor.GEMINI, "https://generativelanguage.googleapis.com/v1beta/models/$modelo:generateContent",
            mapOf("x-goog-api-key" to chave), corpo, modelo)
        val cand = raiz.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw Falha(if (raiz.optJSONObject("promptFeedback")?.has("blockReason") == true) "o Google bloqueou a frase" else "o Google não respondeu nada")
        val partes = cand.optJSONObject("content")?.optJSONArray("parts") ?: throw Falha("resposta vazia (${cand.optString("finishReason")})")
        return (0 until partes.length()).joinToString("") { partes.optJSONObject(it)?.optString("text").orEmpty() }
    }

    // ---------------------------------------------------------------- Claude (Anthropic Messages API)

    private fun claude(chave: String, modelo: String, instrucoes: String, frase: String): String {
        val corpo = JSONObject()
            .put("model", modelo)
            .put("max_tokens", 4096)   // o raciocínio (thinking) também conta aqui
            .put("system", instrucoes)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", frase)))
            .put("output_config", JSONObject().apply {
                put("format", JSONObject().put("type", "json_schema").put("schema", ComandosIa.esquema(openApi = false)))
                // Traduzir uma frase curta é tarefa simples: esforço baixo = resposta mais rápida e mais barata.
                // Só nos modelos que aceitam "effort" (geração 5 em diante e Opus 4.5+); nos outros, sem o campo.
                if (Regex("""claude-(opus|sonnet|haiku|fable)-(5|4-[5-9])""").containsMatchIn(modelo)) put("effort", "low")
            })
        val raiz = postar(Provedor.CLAUDE, "https://api.anthropic.com/v1/messages",
            mapOf("x-api-key" to chave, "anthropic-version" to "2026-10-21"), corpo, modelo)
        // Recusa dos filtros de segurança: HTTP 200 com stop_reason "refusal".
        if (raiz.optString("stop_reason") == "refusal") throw Falha("o Claude recusou este pedido")
        if (raiz.optString("stop_reason") == "max_tokens") throw Falha("a resposta do Claude veio cortada; tente uma frase mais curta")
        val blocos = raiz.optJSONArray("content") ?: throw Falha("o Claude não respondeu nada")
        for (i in 0 until blocos.length()) {
            val b = blocos.optJSONObject(i) ?: continue
            if (b.optString("type") == "text") return b.optString("text")
        }
        throw Falha("o Claude não respondeu texto (${raiz.optString("stop_reason")})")
    }

    // ---------------------------------------------------------------- OpenAI e Perplexity (chat/completions)

    private fun chat(p: Provedor, url: String, chave: String, modelo: String, instrucoes: String, frase: String): String {
        val esquema = JSONObject().put("name", "acoes_tv").put("schema", ComandosIa.esquema(openApi = false))
        // strict=false: o esquema tem campos opcionais (cada tipo de ação usa só alguns), e o strict do OpenAI exige
        // todos obrigatórios. A conferência na TV cobre o resto.
        if (p == Provedor.OPENAI) esquema.put("strict", false)
        val corpo = JSONObject()
            .put("model", modelo)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", instrucoes))
                .put(JSONObject().put("role", "user").put("content", frase)))
            .put("response_format", JSONObject().put("type", "json_schema").put("json_schema", esquema))
        val raiz = postar(p, url, mapOf("Authorization" to "Bearer $chave"), corpo, modelo)
        val msg = raiz.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: throw Falha("o ${p.nome} não respondeu nada")
        if (msg.optString("refusal").isNotEmpty() && !msg.isNull("refusal")) throw Falha("o ${p.nome} recusou este pedido")
        return msg.optString("content")
    }

    // ---------------------------------------------------------------- HTTP comum

    /** POST JSON, devolve o JSON da resposta. Erro HTTP vira [Falha] com mensagem para a pessoa (sem a chave). */
    private fun postar(p: Provedor, url: String, cabecalhos: Map<String, String>, corpo: JSONObject, modelo: String): JSONObject {
        val bytes = corpo.toString().toByteArray()
        val c = URL(url).openConnection() as HttpsURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            cabecalhos.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            val codigo = c.responseCode
            val texto = (if (codigo in 200..299) c.inputStream else c.errorStream)
                ?.use { String(it.readBytes(), Charsets.UTF_8) }?.take(256 * 1024) ?: ""
            if (codigo !in 200..299) {
                Log.w(TAG, "${p.id} respondeu HTTP $codigo")   // só o código: a mensagem do provedor fica fora do log
                throw Falha(mensagemDeErro(p, codigo, texto, modelo))
            }
            return try { JSONObject(texto) } catch (e: Exception) { throw Falha("resposta do ${p.nome} ilegível") }
        } catch (e: SocketTimeoutException) {
            throw Falha("o ${p.nome} demorou demais para responder")
        } catch (e: IOException) {
            throw Falha("sem conexão com o ${p.nome} (${e.javaClass.simpleName})")
        } finally {
            c.disconnect()
        }
    }

    /** Traduz o erro HTTP do provedor para uma frase. Público para os testes. */
    fun mensagemDeErro(p: Provedor, codigo: Int, texto: String, modelo: String): String {
        val t = texto.lowercase()
        return when {
            p == Provedor.GEMINI && texto.contains("ACCESS_TOKEN_TYPE_UNSUPPORTED") ->
                "o Google não aceitou esta chave \"AQ.\" na Gemini API (ACCESS_TOKEN_TYPE_UNSUPPORTED); gere outra no AI Studio"
            codigo == 400 && (texto.contains("API_KEY_INVALID") || t.contains("api key not valid")) -> "a chave do ${p.nome} não é válida"
            t.contains("credit balance") || t.contains("insufficient_quota") || codigo == 402 ->
                "a conta do ${p.nome} está sem crédito ou cota"
            codigo == 401 -> "o ${p.nome} recusou a chave (HTTP 401): confira se colou a chave certa"
            codigo == 403 -> "a chave não tem acesso à API do ${p.nome} (HTTP 403)"
            codigo == 404 || t.contains("model_not_found") || (codigo == 400 && t.contains("model") && t.contains("not")) ->
                "o modelo \"$modelo\" não existe no ${p.nome} (ou a chave não tem acesso a ele); troque no painel"
            codigo == 429 -> "limite de uso do ${p.nome} atingido agora (HTTP 429); tente mais tarde"
            codigo in 500..599 -> "o ${p.nome} está com problema agora (HTTP $codigo)"
            else -> "o ${p.nome} recusou o pedido (HTTP $codigo)"
        }
    }
}
