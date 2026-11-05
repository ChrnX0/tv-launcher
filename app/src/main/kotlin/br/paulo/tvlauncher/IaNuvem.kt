package br.paulo.tvlauncher

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * A chamada à IA na nuvem: Gemini API (Google AI Studio), com a chave DO DONO (bloco 6, 05/11/2026).
 *
 * O que sai da TV: a frase pedida, a data/hora e a lista de apps (pacote e nome). Nada de conta, agenda, arquivo ou
 * rede. A resposta é forçada a JSON pelo esquema (ComandosIa.esquema) e mesmo assim conferida de novo depois.
 * HTTPS com a verificação de certificado padrão do Android (nunca desligar).
 * A chave vai no cabeçalho x-goog-api-key (não na URL), para não aparecer em log de proxy nem em exceção.
 */
object IaNuvem {
    private const val TAG = "TvLauncher.Ia"
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"

    class Falha(mensagem: String) : Exception(mensagem)

    /** Devolve o texto JSON que a IA gerou. Lança [Falha] com uma mensagem para mostrar. Rede: chamar fora da thread da tela. */
    fun perguntar(chave: String, modelo: String, instrucoes: String, frase: String): String {
        require(ComandosIa.modeloValido(modelo))
        val corpo = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", instrucoes))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", frase)))))
            .put("generationConfig", JSONObject()
                .put("temperature", 0)
                .put("maxOutputTokens", 1024)
                .put("responseMimeType", "application/json")
                .put("responseSchema", ComandosIa.esquema()))
            .toString().toByteArray()

        val c = URL("$BASE$modelo:generateContent").openConnection() as HttpsURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10_000
            c.readTimeout = 25_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setRequestProperty("x-goog-api-key", chave)
            c.setFixedLengthStreamingMode(corpo.size)
            c.outputStream.use { it.write(corpo) }
            val codigo = c.responseCode
            val texto = (if (codigo in 200..299) c.inputStream else c.errorStream)
                ?.use { it.readBytes().take(256 * 1024).toByteArray().toString(Charsets.UTF_8) } ?: ""
            if (codigo !in 200..299) {
                // A mensagem de erro do Google não traz a chave; mesmo assim, só o código vai para o log.
                Log.w(TAG, "Gemini respondeu HTTP $codigo")
                throw Falha(when (codigo) {
                    400 -> if (texto.contains("API_KEY_INVALID") || texto.contains("API key not valid")) "a chave de API não é válida" else "o Google recusou o pedido (400)"
                    401, 403 -> "a chave não tem acesso à Gemini API (HTTP $codigo)"
                    404 -> "o modelo \"$modelo\" não existe para esta chave"
                    429 -> "a cota da chave acabou por agora (HTTP 429); tente mais tarde"
                    in 500..599 -> "o Google está com problema agora (HTTP $codigo)"
                    else -> "o Google respondeu HTTP $codigo"
                })
            }
            val raiz = try { JSONObject(texto) } catch (e: Exception) { throw Falha("resposta do Google ilegível") }
            val cand = raiz.optJSONArray("candidates")?.optJSONObject(0)
                ?: throw Falha(if (raiz.optJSONObject("promptFeedback")?.has("blockReason") == true) "o Google bloqueou a frase" else "o Google não respondeu nada")
            val partes = cand.optJSONObject("content")?.optJSONArray("parts") ?: throw Falha("resposta vazia (${cand.optString("finishReason")})")
            return (0 until partes.length()).joinToString("") { partes.optJSONObject(it)?.optString("text").orEmpty() }
        } catch (e: SocketTimeoutException) {
            throw Falha("o Google demorou demais para responder")
        } catch (e: IOException) {
            throw Falha("sem conexão com o Google (${e.javaClass.simpleName})")
        } finally {
            c.disconnect()
        }
    }
}
