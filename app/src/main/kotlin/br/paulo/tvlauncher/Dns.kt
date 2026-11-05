package br.paulo.tvlauncher

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import android.provider.Settings
import java.net.UnknownHostException

/**
 * DNS privado do Android (DNS sobre TLS, "DoT"): o sistema criptografa as consultas de nome para um servidor
 * que você escolhe. Tudo fica em duas configurações do sistema:
 *   private_dns_mode      = "opportunistic" (Automático) | "hostname" (um servidor fixo) | "off"
 *   private_dns_specifier = o nome do servidor, só usado no modo "hostname"
 * O Android só aceita NOME (dns.exemplo.com), nunca número de IP.
 *
 * Gravar essas configurações exige a permissão WRITE_SECURE_SETTINGS, que um app comum não pode pedir pela tela.
 * Ela é dada uma única vez pelo computador:  adb shell pm grant br.paulo.tvlauncher android.permission.WRITE_SECURE_SETTINGS
 * (continua valendo depois de reinstalar o app).
 */
object Dns {
    private const val CONFIG_MODO = "private_dns_mode"
    private const val CONFIG_HOST = "private_dns_specifier"
    private const val PREFS = "dns"
    private const val CHAVE_SALVOS = "salvos"

    class Servidor(val nome: String, val host: String)

    enum class Modo { AUTOMATICO, DESLIGADO, HOST }

    /** Como o sistema está configurado agora. [host] só vale quando [modo] é HOST. */
    class Estado(val modo: Modo, val host: String?)

    /** Servidores públicos conhecidos. Os nomes foram conferidos no DNS, mas o TLS de cada um não foi testado aqui. */
    val PRESETS = listOf(
        Servidor("AdGuard", "dns.adguard-dns.com"),
        Servidor("AdGuard Família (bloqueia adultos)", "family.adguard-dns.com"),
        Servidor("Cloudflare", "one.one.one.one"),
        Servidor("Cloudflare (bloqueia malware)", "security.cloudflare-dns.com"),
        Servidor("Google", "dns.google"),
        Servidor("Quad9 (bloqueia malware)", "dns.quad9.net"),
    )

    // ---------------------------------------------------------------- ler e gravar no sistema

    fun atual(ctx: Context): Estado {
        val cr = ctx.contentResolver
        val modo = when (Settings.Global.getString(cr, CONFIG_MODO)) {
            "off" -> Modo.DESLIGADO
            "hostname" -> Modo.HOST
            else -> Modo.AUTOMATICO   // vazio/nulo também significa "opportunistic", o padrão do Android
        }
        val host = Settings.Global.getString(cr, CONFIG_HOST)?.trim()?.ifEmpty { null }
        return Estado(modo, if (modo == Modo.HOST) host else null)
    }

    /** Grava no sistema. Devolve false se o app ainda não recebeu WRITE_SECURE_SETTINGS. */
    fun aplicar(ctx: Context, modo: Modo, host: String? = null): Boolean = try {
        val cr = ctx.contentResolver
        // Primeiro o servidor, depois o modo: assim o sistema nunca vê "hostname" sem servidor.
        val servidorOk = if (modo == Modo.HOST) Settings.Global.putString(cr, CONFIG_HOST, host) else true
        val valorModo = when (modo) {
            Modo.AUTOMATICO -> "opportunistic"
            Modo.DESLIGADO -> "off"
            Modo.HOST -> "hostname"
        }
        servidorOk && Settings.Global.putString(cr, CONFIG_MODO, valorModo)
    } catch (e: SecurityException) {
        false
    }

    // ---------------------------------------------------------------- endereço digitado

    private val REGEX_NOME = Regex("^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,}$")

    /**
     * Limpa o que a pessoa digitou/colou: aceita "tls://dns.exemplo.com", tira porta e caminho.
     * Devolve null se não for um nome de servidor válido (número de IP também é recusado: o Android não aceita).
     */
    fun normalizar(entrada: String): String? {
        var s = entrada.trim().lowercase()
        for (prefixo in listOf("tls://", "https://", "http://")) s = s.removePrefix(prefixo)
        s = s.substringBefore('/').substringBefore(':').trimEnd('.')
        return if (REGEX_NOME.matches(s)) s else null
    }

    // ---------------------------------------------------------------- servidores salvos (SharedPreferences)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Cada servidor salvo vira um texto "nome<TAB>host" dentro de um conjunto de textos. */
    fun salvos(ctx: Context): List<Servidor> =
        (prefs(ctx).getStringSet(CHAVE_SALVOS, emptySet()) ?: emptySet())
            .mapNotNull { linha ->
                val partes = linha.split('\t', limit = 2)
                if (partes.size == 2) Servidor(partes[0], partes[1]) else null
            }
            .sortedBy { it.nome.lowercase() }

    fun salvar(ctx: Context, servidor: Servidor) {
        // O conjunto devolvido pelo Android não pode ser alterado direto: copiamos antes.
        val novo = HashSet(prefs(ctx).getStringSet(CHAVE_SALVOS, emptySet()) ?: emptySet())
        novo.removeAll { it.endsWith("\t" + servidor.host) }   // mesmo servidor com outro nome: substitui
        novo.add(servidor.nome + "\t" + servidor.host)
        prefs(ctx).edit().putStringSet(CHAVE_SALVOS, novo).apply()
    }

    fun remover(ctx: Context, host: String) {
        val novo = HashSet(prefs(ctx).getStringSet(CHAVE_SALVOS, emptySet()) ?: emptySet())
        novo.removeAll { it.endsWith("\t" + host) }
        prefs(ctx).edit().putStringSet(CHAVE_SALVOS, novo).apply()
    }

    /**
     * Se o sistema usa um servidor que não é um dos pré-definidos nem está salvo (ex.: o seu DNS pessoal configurado
     * por fora), guarda na lista. Assim, ao trocar para outro, você não perde o endereço e volta com um toque.
     */
    fun guardarAtualSeDesconhecido(ctx: Context) {
        val e = atual(ctx)
        val host = e.host ?: return
        if (nomeDe(ctx, host) != null) return
        // Endereços "<código>.d.adguard-dns.com" são os perfis pessoais do AdGuard DNS: damos um nome legível.
        val nome = if (host.endsWith(".d.adguard-dns.com")) ctx.getString(R.string.dns_adguard_pessoal) else ctx.getString(R.string.dns_personalizado, host.substringBefore('.'))
        salvar(ctx, Servidor(nome, host))
    }

    fun nomeDe(ctx: Context, host: String): String? =
        (PRESETS + salvos(ctx)).firstOrNull { it.host.equals(host, ignoreCase = true) }?.nome

    /** Texto curto para o rodapé da tela inicial. */
    fun rotulo(ctx: Context): String {
        guardarAtualSeDesconhecido(ctx)   // dá nome ao servidor configurado por fora; senão o rodapé mostraria só o endereço
        val e = atual(ctx)
        return when (e.modo) {
            Modo.AUTOMATICO -> "DNS: " + ctx.getString(R.string.dns_rot_auto)
            Modo.DESLIGADO -> "DNS: " + ctx.getString(R.string.dns_rot_desl)
            Modo.HOST -> "DNS: " + (e.host?.let { nomeDe(ctx, it) ?: it } ?: "?")
        }
    }

    // ---------------------------------------------------------------- o que a rede está fazendo de verdade

    /** [tlsAtivo] = o Android confirma que está usando DNS sobre TLS na rede atual; [servidor] = com qual nome. */
    class Situacao(val tlsAtivo: Boolean, val servidor: String?)

    fun situacaoDaRede(ctx: Context): Situacao {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val lp = cm.getLinkProperties(cm.activeNetwork)
        return Situacao(lp?.isPrivateDnsActive == true, lp?.privateDnsServerName)
    }

    // ---------------------------------------------------------------- volta automática (revisão de 04/11/2026)

    private const val CHAVE_CAIU = "caiu_de"   // servidor fixo que falhou e foi trocado pelo automático

    /**
     * Se o DNS fixo parou de funcionar, volta para o Automático — numa TV da família, "DNS fora do ar" = "internet
     * quebrada", e o Android não volta sozinho (ver [testarResolucao]). Só age quando as TRÊS coisas valem: modo é
     * servidor fixo, a rede Wi-Fi perdeu a validação, e um teste real de resolução falhou (por ~9 s). Guarda qual
     * servidor caiu ([servidorQueCaiu]) para a tela de Configurações avisar e para dar para voltar a ele.
     * Chamar fora da thread da tela. Devolve true se trocou.
     */
    fun voltarAoAutomaticoSeQuebrado(ctx: Context): Boolean {
        val e = atual(ctx)
        if (e.modo != Modo.HOST) return false
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        // A rede Wi-Fi, não a "ativa": com o AdGuard VPN ligado a ativa é a VPN, e quem perde a validação quando o DNS
        // fixo cai é o Wi-Fi por baixo.
        @Suppress("DEPRECATION")
        val wifi = cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return false
        val caps = cm.getNetworkCapabilities(wifi) ?: return false
        if (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false
        // E o que os apps sentem: resolução na rede ativa (pela VPN, se houver). Se ainda resolve, não mexe.
        if (testarResolucao(ctx) != null) return false
        val trocou = aplicar(ctx, Modo.AUTOMATICO)
        android.util.Log.w("TvLauncher.Dns", "DNS fixo ${e.host} sem resposta; voltei para o automatico (gravou=$trocou)")
        if (trocou) prefs(ctx).edit().putString(CHAVE_CAIU, e.host).apply()
        return trocou
    }

    /** O servidor fixo que falhou e foi trocado pelo automático (null = nenhum). Some quando se escolhe outro DNS. */
    fun servidorQueCaiu(ctx: Context): String? = prefs(ctx).getString(CHAVE_CAIU, null)
    fun esquecerQueCaiu(ctx: Context) = prefs(ctx).edit().remove(CHAVE_CAIU).apply()

    /**
     * Tenta resolver um nome pela rede ativa e devolve quantos ms levou, ou null se não conseguiu.
     * Repete por até ~9 s, porque logo após trocar o servidor o Android ainda está validando o TLS.
     * Num servidor fixo que não responde, o Android NÃO volta para o DNS normal: os nomes simplesmente param de resolver.
     * Chamar fora da thread da tela (faz rede).
     */
    fun testarResolucao(ctx: Context): Long? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val rede = cm.activeNetwork ?: return null
        repeat(6) {
            val inicio = SystemClock.elapsedRealtime()
            try {
                rede.getAllByName("www.google.com")
                return SystemClock.elapsedRealtime() - inicio
            } catch (e: UnknownHostException) {
                Thread.sleep(1500)
            }
        }
        return null
    }
}
