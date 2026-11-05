package br.paulo.tvlauncher

import org.json.JSONObject
import java.security.MessageDigest

/**
 * Roteador UniFi (UX7 e outros com UniFi OS), integração SÓ DE LEITURA (05/11/2026). Aqui fica só a lógica pura, sem
 * nada do Android, para o teste rodar no PC (UnifiTest). A rede está em [UnifiCliente]; a chave, em [UnifiArmazem].
 *
 * A API usada é a "Integration v1" do UniFi Network (9.x ou mais novo): a chave é criada no próprio UniFi, em
 * Configurações → Integrações, e vai no cabeçalho X-API-Key. Caminho no UniFi OS: /proxy/network/integration/v1/…
 * Rotas usadas: /info (versão), /sites (o "site" da casa) e /sites/{id}/clients (aparelhos conectados).
 * Fonte: OpenAPI da comunidade github.com/tmcpro/unifi-network-api (engenharia reversa; a documentação oficial da
 * Ubiquiti fica em developer.ui.com). Nenhuma rota de AÇÃO (bloquear, reiniciar) é chamada.
 */
object Unifi {
    const val BASE = "/proxy/network/integration/v1"
    const val MAX_APARELHOS = 1000          // casa nenhuma tem mais; protege a memória se o roteador responder lixo
    const val MAX_CONHECIDOS = 1000

    /** Um aparelho conectado no roteador. [tipo]: "WIRED", "WIRELESS", "VPN" ou "TELEPORT" (o que a API manda). */
    data class Aparelho(val id: String, val nome: String, val ip: String?, val mac: String?, val tipo: String, val desde: String?) {
        /** O que identifica o aparelho entre uma leitura e outra: o MAC (o id da API muda se o roteador for refeito). */
        val chave: String get() = mac?.lowercase() ?: "id:$id"
    }

    /**
     * Endereço do roteador: só um IPv4 de rede PRIVADA (10.x, 172.16–31.x, 192.168.x), com porta opcional.
     * Por quê: a chave de API vai junto em cada pedido. Aceitar um nome (ex.: "unifi.lan") exigiria DNS, e um DNS
     * envenenado mandaria a chave para fora de casa; aceitar IP público mandaria a chave pela internet.
     * Devolve "ip" ou "ip:porta" normalizado, ou null se não serve.
     */
    fun enderecoValido(bruto: String?): String? {
        val t = bruto?.trim()?.removePrefix("https://")?.trimEnd('/') ?: return null
        val m = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(?::(\d{1,5}))?$""").matchEntire(t) ?: return null
        val n = (1..4).map { m.groupValues[it].toInt() }
        if (n.any { it > 255 }) return null
        val privado = n[0] == 10 || (n[0] == 172 && n[1] in 16..31) || (n[0] == 192 && n[1] == 168)
        if (!privado) return null
        val porta = m.groups[5]?.value?.toInt()
        if (porta != null && porta !in 1..65535) return null
        val ip = n.joinToString(".")
        return if (porta == null || porta == 443) ip else "$ip:$porta"
    }

    /**
     * Chave de API: o UniFi gera uma sequência de letras, números, "-" e "_" (uns 32 caracteres). Aceito de 16 a 128
     * desses caracteres; tira espaços e quebras que vêm junto ao colar. Null se não serve.
     */
    fun chaveValida(bruta: String?): String? {
        val t = bruta?.filterNot { it.isWhitespace() } ?: return null
        return t.takeIf { it.length in 16..128 && it.all { c -> c.isLetterOrDigit() && c.code < 128 || c == '-' || c == '_' } }
    }

    /** Impressão digital SHA-256 do certificado, como o navegador mostra: "AB:CD:…" (32 pares). */
    fun impressao(certificadoDer: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(certificadoDer).joinToString(":") { "%02X".format(it) }

    /** Lê uma página de /sites/{id}/clients. Devolve os aparelhos e o totalCount (para saber se há mais páginas). */
    fun lerPaginaAparelhos(texto: String): Pair<List<Aparelho>, Int> {
        val o = JSONObject(texto)
        val dados = o.getJSONArray("data")
        val lista = ArrayList<Aparelho>(dados.length())
        for (i in 0 until dados.length()) {
            val a = dados.optJSONObject(i) ?: continue
            val id = a.optString("id").takeIf { it.isNotEmpty() } ?: continue
            val mac = a.optString("macAddress").takeIf { it.isNotEmpty() }
            val nome = a.optString("name").trim().takeIf { it.isNotEmpty() } ?: mac ?: "sem nome"
            lista.add(Aparelho(id, nome.take(80), a.optString("ipAddress").takeIf { it.isNotEmpty() }, mac,
                a.optString("type", "?"), a.optString("connectedAt").takeIf { it.isNotEmpty() }))
        }
        return lista to o.optInt("totalCount", lista.size)
    }

    /** O id do site da casa em /sites: o de internalReference "default" ou, se não houver, o primeiro. */
    fun lerSite(texto: String): String? {
        val dados = JSONObject(texto).getJSONArray("data")
        var primeiro: String? = null
        for (i in 0 until dados.length()) {
            val s = dados.optJSONObject(i) ?: continue
            val id = s.optString("id").takeIf { it.isNotEmpty() } ?: continue
            if (s.optString("internalReference") == "default") return id
            if (primeiro == null) primeiro = id
        }
        return primeiro
    }

    /** Os aparelhos de [atuais] que não estão em [conhecidos] (comparando por [Aparelho.chave]). */
    fun novos(conhecidos: Set<String>, atuais: List<Aparelho>): List<Aparelho> =
        atuais.filter { it.chave !in conhecidos }.distinctBy { it.chave }

    /**
     * A lista de conhecidos depois de uma leitura: os antigos + os novos, no máximo [MAX_CONHECIDOS] (sai o mais
     * antigo). Ordem importa: o fim da lista é o mais recente.
     */
    fun juntarConhecidos(antigos: List<String>, atuais: List<Aparelho>): List<String> {
        val r = LinkedHashSet(antigos)
        for (a in atuais) r.add(a.chave)
        return r.toList().takeLast(MAX_CONHECIDOS)
    }

    /** Texto do recado na TV: até 3 nomes e "+N". Cabe no limite do [Recado] (200 caracteres, 3 linhas). */
    fun textoNovos(novos: List<Aparelho>): String {
        val nomes = novos.take(3).map { a -> a.nome.take(40) + (a.ip?.let { " ($it)" } ?: "") }
        val resto = novos.size - nomes.size
        return nomes.joinToString("\n") + if (resto > 0) " e mais $resto" else ""
    }
}
