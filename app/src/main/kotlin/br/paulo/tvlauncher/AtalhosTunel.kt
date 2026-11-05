package br.paulo.tvlauncher

/**
 * Atalhos de porta pela rede Tailscale (05/11/2026): "nas:8096" faz a TV abrir 127.0.0.1:8096 e repassar para a porta 8096
 * do aparelho "nas" da conta Tailscale. Serve para app de mídia (Jellyfin, Emby, Kodi) alcançar um servidor que está
 * em OUTRO lugar: no app, o servidor vira http://127.0.0.1:8096. (Na mesma rede de casa não precisa disto.)
 *
 * Puro (sem Android) para ter teste JVM. Só vale com a opção "Rede Tailscale" ligada.
 */
object AtalhosTunel {
    const val MAXIMO = 5

    class Atalho(val aparelho: String, val porta: Int) {
        /** Porta local: a mesma, ou 18000+porta se for abaixo de 1024 (app comum não abre porta baixa). */
        val portaLocal: Int get() = if (porta >= 1024) porta else 18000 + porta
        override fun toString() = "$aparelho:$porta"
    }

    /** "nas:8096, servidor.tail1234.ts.net:32400" -> lista; null se algum item for inválido. Vazio = nenhum atalho. */
    fun ler(texto: String?): List<Atalho>? {
        val itens = texto.orEmpty().split(',', '\n', ' ').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (itens.size > MAXIMO) return null
        val lista = itens.map { item ->
            val aparelho = item.substringBeforeLast(':', "")
            val porta = item.substringAfterLast(':', "").toIntOrNull() ?: return null
            if (porta !in 1..65535 || !nomeValido(aparelho)) return null
            Atalho(aparelho, porta)
        }
        // Duas entradas não podem disputar a mesma porta local, nem as portas que a própria TV usa.
        val locais = lista.map { it.portaLocal }
        if (locais.toSet().size != locais.size || locais.any { it in PORTAS_DA_TV }) return null
        return lista
    }

    /** Nome do aparelho na conta (MagicDNS): letras, números, hífen e ponto, como um nome de host. */
    fun nomeValido(n: String): Boolean = n.length in 1..120 && !n.startsWith('.') && !n.startsWith('-') &&
        n.all { it in 'a'..'z' || it in '0'..'9' || it in ".-" }

    /** Para o programa do túnel (variável TUNEL_ATALHOS): "local=aparelho:porta,..." */
    fun paraAmbiente(l: List<Atalho>): String = l.joinToString(",") { "${it.portaLocal}=${it.aparelho}:${it.porta}" }

    private val PORTAS_DA_TV = setOf(Config.PAINEL_PORTA, Config.MCP_PORTA, 5555)
}
