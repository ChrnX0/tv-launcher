package br.paulo.tvlauncher

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * As defesas do painel no navegador (ver PainelWeb.kt e o plano em painel-no-launcher.md, seção 3).
 * Nada aqui usa o Android: os testes rodam no computador (app/src/test, PainelSegurancaTest).
 */

/**
 * PIN guardado como PBKDF2-HMAC-SHA256: o arquivo de preferências nunca tem o PIN, só um "resumo" lento de calcular.
 * Lento de propósito: cada tentativa custa ~100 mil voltas de SHA-256, o que torna inútil adivinhar o PIN a partir
 * do arquivo. O sal (aleatório, por PIN) impede tabelas prontas. O número de voltas fica gravado junto, para poder
 * mudar no futuro sem invalidar o PIN já salvo.
 */
object PinSeguro {
    const val ITERACOES = 100_000
    const val MIN_DIGITOS = 6
    const val MAX_DIGITOS = 12
    private val aleatorio = SecureRandom()

    fun valido(pin: String): Boolean = pin.length in MIN_DIGITOS..MAX_DIGITOS && pin.all { it in '0'..'9' }

    fun novoSal(): ByteArray = ByteArray(16).also { aleatorio.nextBytes(it) }

    fun resumo(pin: String, sal: ByteArray, iteracoes: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), sal, iteracoes, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Compara em tempo constante (MessageDigest.isEqual): o tempo da resposta não revela quantos bytes bateram. */
    fun confere(pin: String, sal: ByteArray, iteracoes: Int, esperado: ByteArray): Boolean =
        valido(pin) && MessageDigest.isEqual(resumo(pin, sal, iteracoes), esperado)
}

/**
 * Contador GLOBAL de PIN errado (não por IP: na rede de casa o atacante troca de IP à vontade).
 * A cada [POR_RODADA] erros seguidos o login fica bloqueado: 1 min, depois 2, 4... (dobra a cada rodada).
 * Com [LIMITE_DESLIGAR] erros acumulados, devolve [Resultado.Desligar]: quem chama desliga o painel, que só volta
 * pelas Configurações da TV. Acertar zera tudo.
 *
 * [falhasIniciais] e [aoMudarFalhas] existem para o total sobreviver a um reinício do launcher (gravado nas
 * preferências por quem chama). O bloqueio por tempo fica só na memória: reiniciar o launcher exige estar na TV.
 */
class ControleTentativas(
    private val relogio: () -> Long,
    falhasIniciais: Int = 0,
    private val aoMudarFalhas: (Int) -> Unit = {},
) {
    companion object {
        const val POR_RODADA = 5
        const val LIMITE_DESLIGAR = 20
        const val BLOQUEIO_BASE_MS = 60_000L
    }

    sealed class Resultado {
        /** Errou, mas ainda pode tentar: faltam [restantes] tentativas até o próximo bloqueio. */
        data class Errou(val restantes: Int) : Resultado()
        /** Errou e o login ficou bloqueado por [ms]. */
        data class Bloqueou(val ms: Long) : Resultado()
        /** Errou demais: desligar o painel. */
        object Desligar : Resultado()
    }

    var falhas: Int = falhasIniciais
        private set
    private var bloqueadoAte = 0L

    /** Quanto falta de bloqueio, em ms (0 = pode tentar). Long.MAX_VALUE = esgotado (ver [esgotado]). */
    @Synchronized fun esperaMs(): Long =
        if (esgotado()) Long.MAX_VALUE else (bloqueadoAte - relogio()).coerceAtLeast(0L)

    /** Errou demais: nada mais é conferido até [zerar]. */
    @Synchronized fun esgotado(): Boolean = falhas >= LIMITE_DESLIGAR

    @Synchronized fun registrarFalha(): Resultado {
        falhas++
        aoMudarFalhas(falhas)
        if (falhas >= LIMITE_DESLIGAR) {
            // Estado final: nenhuma tentativa a mais é conferida (nem as que já estavam na fila) até zerar() nas
            // Configurações. Sem isto, pedidos já aceitos antes de o painel fechar continuavam testando PINs.
            bloqueadoAte = Long.MAX_VALUE
            return Resultado.Desligar
        }
        if (falhas % POR_RODADA == 0) {
            val rodada = falhas / POR_RODADA   // 1, 2, 3
            val ms = BLOQUEIO_BASE_MS shl (rodada - 1)   // 1 min, 2 min, 4 min
            bloqueadoAte = relogio() + ms
            return Resultado.Bloqueou(ms)
        }
        return Resultado.Errou(POR_RODADA - falhas % POR_RODADA)
    }

    @Synchronized fun registrarSucesso() {
        if (falhas != 0) { falhas = 0; aoMudarFalhas(0) }
        bloqueadoAte = 0L
    }

    /** Volta ao zero (PIN trocado ou painel religado nas Configurações). */
    @Synchronized fun zerar() = registrarSucesso()
}

/**
 * Sessões de quem entrou com o PIN. O navegador guarda só um token aleatório de 128 bits (cookie HttpOnly); aqui
 * guardamos o SHA-256 dele (se um dia este mapa vazar num log, o token em si não vaza). Só na memória: reiniciar o
 * launcher derruba todo mundo, de propósito.
 *
 * Expira com [OCIOSO_MS] sem uso ou [MAXIMO_MS] desde o login. "Elevada" = o PIN foi redigitado há menos de
 * [ELEVADA_MS]: exigido nas ações que não têm volta (desinstalar, restaurar backup), para limitar o estrago de um
 * cookie roubado na Wi-Fi (o painel é HTTP).
 */
class Sessoes(private val relogio: () -> Long, private val maximoSessoes: Int = 8) {
    companion object {
        const val OCIOSO_MS = 30 * 60_000L
        const val MAXIMO_MS = 12 * 3600_000L
        const val ELEVADA_MS = 5 * 60_000L
    }

    private class Sessao(val criada: Long, var ultimoUso: Long, var elevadaAte: Long)

    private val aleatorio = SecureRandom()
    private val mapa = LinkedHashMap<String, Sessao>()

    /** Cria a sessão e devolve o token (32 caracteres hex) para o cookie. A sessão já nasce elevada (acabou de digitar o PIN). */
    @Synchronized fun criar(): String {
        limpar()
        while (mapa.size >= maximoSessoes) mapa.remove(mapa.keys.first())   // tira a mais antiga
        val bytes = ByteArray(16).also { aleatorio.nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        val agora = relogio()
        mapa[chave(token)] = Sessao(agora, agora, agora + ELEVADA_MS)
        return token
    }

    /** O token vale? Se sim, conta como uso (renova o prazo de ociosidade). */
    @Synchronized fun valida(token: String?): Boolean {
        val s = buscar(token) ?: return false
        s.ultimoUso = relogio()
        return true
    }

    /** O token vale? Sem contar como uso: para consultas automáticas da página (que não podem manter a sessão viva). */
    @Synchronized fun existe(token: String?): Boolean = buscar(token) != null

    @Synchronized fun elevada(token: String?): Boolean {
        val s = buscar(token) ?: return false
        return relogio() < s.elevadaAte
    }

    @Synchronized fun elevar(token: String?): Boolean {
        val s = buscar(token) ?: return false
        s.elevadaAte = relogio() + ELEVADA_MS
        return true
    }

    @Synchronized fun encerrar(token: String?) { if (token != null) mapa.remove(chave(token)) }

    @Synchronized fun encerrarTodas() = mapa.clear()

    @Synchronized fun quantas(): Int { limpar(); return mapa.size }

    private fun buscar(token: String?): Sessao? {
        if (token == null || token.length != 32) return null
        val k = chave(token)
        val s = mapa[k] ?: return null
        if (expirou(s)) { mapa.remove(k); return null }
        return s
    }

    private fun expirou(s: Sessao): Boolean {
        val agora = relogio()
        return agora - s.ultimoUso > OCIOSO_MS || agora - s.criada > MAXIMO_MS
    }

    private fun limpar() { mapa.values.removeAll { expirou(it) } }

    private fun chave(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** Checagens de cada requisição: Host, Origin e nomes de pacote. */
object ChecagensPainel {
    /** Nome de pacote Android válido. Validado antes de qualquer chamada ao sistema. */
    private val PACOTE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    fun pacoteValido(p: String?): Boolean = p != null && p.length <= 255 && PACOTE.matches(p)

    /**
     * O cabeçalho Host é um nome que conhecemos? Isto barra "DNS rebinding": um site malicioso aberto no celular de
     * alguém da casa faz o próprio domínio apontar para 192.168.1.153 e tenta falar com o painel; o navegador manda
     * o Host com o domínio DELE, que não está na lista. [nomes] = IPs da TV na rede + os nomes de Config.PAINEL_NOMES.
     * A porta, se vier, tem de ser a nossa.
     */
    fun hostPermitido(host: String?, nomes: Set<String>, porta: Int): Boolean {
        if (host.isNullOrEmpty()) return false
        val h = host.lowercase()
        val doisPontos = h.lastIndexOf(':')
        val (nome, portaTexto) = if (doisPontos >= 0) h.substring(0, doisPontos) to h.substring(doisPontos + 1) else h to null
        if (portaTexto != null && portaTexto != porta.toString()) return false
        return nome in nomes
    }

    /**
     * Em POST, o Origin tem de ser exatamente o próprio painel (http://<host>). Junto com o cabeçalho X-Painel
     * (que um site de fora não consegue mandar sem a nossa permissão de CORS, que não damos), isto barra CSRF:
     * um site de fora não consegue clicar "desinstalar" pelo navegador de quem já entrou no painel.
     */
    fun origemPermitida(origem: String?, host: String?): Boolean =
        origem != null && host != null && origem.lowercase() == "http://${host.lowercase()}"

    /**
     * [remoto] está na mesma sub-rede que [local] (prefixo /[prefixo], ex.: 24 para 192.168.1.x)? Endereços IPv4 em
     * bytes. Recusa aparelho de outra VLAN ou de rede roteada (convidados, IoT, o lado de lá da VPN), mesmo privado.
     */
    fun mesmaRede(local: ByteArray, remoto: ByteArray, prefixo: Int): Boolean {
        if (local.size != 4 || remoto.size != 4 || prefixo !in 1..32) return false
        val a = java.nio.ByteBuffer.wrap(local).int
        val b = java.nio.ByteBuffer.wrap(remoto).int
        val mascara = if (prefixo == 32) -1 else ((-1L shl (32 - prefixo)).toInt())
        return (a and mascara) == (b and mascara)
    }

    /**
     * O texto JSON tem no máximo [max] níveis de [ ou { aninhados? Varredura linear e barata, que ignora o que está
     * dentro de strings. Necessária porque o leitor de JSON do Android é recursivo: um arquivo "[[[[..." de 512 KB
     * estoura a pilha (StackOverflowError, que derruba o processo inteiro do launcher).
     */
    fun jsonRaso(texto: String, max: Int = 16): Boolean {
        var nivel = 0
        var emString = false
        var escapado = false
        for (c in texto) {
            if (emString) {
                when {
                    escapado -> escapado = false
                    c == '\\' -> escapado = true
                    c == '"' -> emString = false
                }
                continue
            }
            when (c) {
                '"' -> emString = true
                '[', '{' -> if (++nivel > max) return false
                ']', '}' -> nivel--
            }
        }
        return true
    }

    // ------------------------------------------------------------ arquivos (aba Arquivos do painel)

    /** Pastas de topo que o painel não mostra nem deixa mexer: dados de apps (o Android já barra, mas não mostra). */
    val PASTAS_FECHADAS = setOf("android")

    /**
     * Um nome de arquivo ou pasta (um pedaço só do caminho). Barra o que permitiria sair da pasta ("..", barra),
     * caracteres de controle (quebram listagem e cabeçalho) e nomes enormes.
     */
    fun nomeArquivoValido(nome: String?): Boolean {
        if (nome.isNullOrEmpty() || nome.length > 200 || nome == "." || nome == "..") return false
        if (nome.trim().isEmpty()) return false
        return nome.none { it == '/' || it == '\\' || it.code < 0x20 || it.code == 0x7f }
    }

    /**
     * Caminho relativo à memória interna ("" = a raiz; "Download/Filmes"). Cada pedaço tem de ser um nome válido, e o
     * primeiro não pode ser uma pasta fechada. A conferência final (o caminho real, com links resolvidos, ficar dentro
     * da raiz) é feita em PainelArquivos com o sistema de arquivos de verdade.
     */
    fun caminhoRelativoValido(rel: String?): Boolean {
        if (rel == null || rel.length > 1000) return false
        if (rel.isEmpty()) return true
        val partes = rel.split('/')
        if (partes.any { !nomeArquivoValido(it) }) return false
        return partes[0].lowercase() !in PASTAS_FECHADAS
    }

    /** [canonico] (caminho real) está dentro de [raiz] (ou é ela)? Comparação por pedaço: "/a/bc" não está em "/a/b". */
    fun dentroDe(canonico: String, raiz: String): Boolean =
        canonico == raiz || canonico.startsWith(raiz.trimEnd('/') + "/")
}
