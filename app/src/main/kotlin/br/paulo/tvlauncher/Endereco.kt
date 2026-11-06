package br.paulo.tvlauncher

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Do CEP e do número da casa até as coordenadas (05/11/2026, pedido do Paulo: "pede o CEP e endereço e pronto",
 * nada de coordenada digitada à mão nem fixa no código).
 *
 *  1. ViaCEP (viacep.com.br, gratuito, sem chave): CEP -> rua, bairro, cidade, UF.
 *  2. Nominatim (OpenStreetMap, gratuito, sem chave; pede um User-Agent que identifique o app e no máximo 1 pedido por
 *     segundo — aqui é 1 a 3 pedidos, uma vez, quando alguém salva o endereço): endereço -> latitude/longitude.
 *     Tenta do mais preciso para o menos: rua + número, só a rua, o bairro, a cidade.
 *
 * As funções de texto/JSON são puras (testadas no computador); [buscar] faz a rede e roda fora da thread da tela.
 */
object Endereco {
    const val AGENTE = "tv-launcher/0.1 (github.com/ChrnX0/tv-launcher)"

    data class Cep(val cep: String, val rua: String, val bairro: String, val cidade: String, val uf: String)
    data class Achado(val nome: String, val lat: Double, val lon: Double, val precisao: String)

    /** Só os 8 dígitos ("14015-060" -> "14015060"); null se não for um CEP. */
    fun limparCep(texto: String?): String? = texto.orEmpty().filter { it.isDigit() }.takeIf { it.length == 8 }

    /** Número da casa: até 6 dígitos (aceita vazio = sem número). Null se tiver lixo. */
    fun limparNumero(texto: String?): String? {
        val t = texto.orEmpty().trim()
        return if (t.isEmpty() || (t.length <= 6 && t.all { it.isDigit() })) t else null
    }

    /** Resposta do ViaCEP -> Cep. Null quando o CEP não existe ({"erro": true}) ou falta a cidade. */
    fun lerViaCep(json: String): Cep? = try {
        val o = JSONObject(json)
        if (o.optBoolean("erro", false) || o.optString("erro") == "true") null
        else o.optString("localidade").takeIf { it.isNotBlank() }?.let { cidade ->
            Cep(o.optString("cep"), o.optString("logradouro"), o.optString("bairro"), cidade, o.optString("uf"))
        }
    } catch (e: Exception) { null }

    /** Primeiro resultado do Nominatim -> (lat, lon). Null se veio vazio ou estranho. */
    fun lerNominatim(json: String): Pair<Double, Double>? = try {
        val a = JSONArray(json)
        if (a.length() == 0) null
        else a.getJSONObject(0).let { r ->
            val lat = r.getString("lat").toDouble(); val lon = r.getString("lon").toDouble()
            if (LocalClima.coordenadaValida(lat, lon)) lat to lon else null
        }
    } catch (e: Exception) { null }

    private fun cod(s: String) = URLEncoder.encode(s, "UTF-8")

    /** As buscas no Nominatim, da mais precisa para a menos, com o rótulo de precisão de cada uma. */
    fun consultas(c: Cep, numero: String): List<Pair<String, String>> {
        val base = "https://nominatim.openstreetmap.org/search?format=json&limit=1&countrycodes=br"
        val cidade = "&city=${cod(c.cidade)}&state=${cod(c.uf)}"
        val lista = ArrayList<Pair<String, String>>()
        if (c.rua.isNotBlank() && numero.isNotEmpty()) lista += "$base&street=${cod("$numero ${c.rua}")}$cidade" to "número"
        if (c.rua.isNotBlank()) lista += "$base&street=${cod(c.rua)}$cidade" to "rua"
        if (c.bairro.isNotBlank()) lista += "$base&q=${cod("${c.bairro}, ${c.cidade}, ${c.uf}")}" to "bairro"
        lista += "$base$cidade" to "cidade"
        return lista
    }

    /** GET simples com o User-Agent do app (o Nominatim exige); null se não vier 200. Fora da thread da tela. */
    fun httpGet(endereco: String): String? = try {
        val c = java.net.URL(endereco).openConnection() as java.net.HttpURLConnection
        try {
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("User-Agent", AGENTE)
            if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null
        } finally { c.disconnect() }
    } catch (e: Exception) { null }

    /** Nome curto que aparece na previsão: o bairro (ou a rua, ou a cidade). */
    fun nome(c: Cep): String = LocalClima.limparNome(c.bairro.ifBlank { c.rua.ifBlank { c.cidade } })

    /**
     * CEP + número -> coordenadas. [httpGet] recebe a URL e o User-Agent. Devolve o achado ou a mensagem de erro
     * (para o painel e a tela de Configurações mostrarem).
     */
    fun buscar(cepTexto: String?, numeroTexto: String?, httpGet: (String) -> String?): Result<Achado> {
        val cep = limparCep(cepTexto) ?: return Result.failure(IllegalArgumentException("CEP inválido: são 8 números"))
        val numero = limparNumero(numeroTexto) ?: return Result.failure(IllegalArgumentException("número inválido"))
        val c = httpGet("https://viacep.com.br/ws/$cep/json/")?.let { lerViaCep(it) }
            ?: return Result.failure(IllegalStateException("CEP não encontrado (ou sem internet)"))
        for ((url, precisao) in consultas(c, numero)) {
            val ponto = httpGet(url)?.let { lerNominatim(it) } ?: continue
            return Result.success(Achado(nome(c), ponto.first, ponto.second, precisao))
        }
        return Result.failure(IllegalStateException("não achei esse endereço no mapa"))
    }
}
