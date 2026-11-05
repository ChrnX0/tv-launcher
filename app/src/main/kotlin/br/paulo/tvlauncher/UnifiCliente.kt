package br.paulo.tvlauncher

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.X509TrustManager

/**
 * A parte de REDE da integração com o roteador UniFi (só leitura). Lógica pura em [Unifi]; chave em [UnifiArmazem].
 *
 * Certificado: o UX7 usa um certificado AUTOASSINADO (nenhuma autoridade o assinou), então a verificação normal do
 * Android recusa. Em vez de desligar a verificação, a TV FIXA o certificado (certificate pinning):
 *  1. [buscarCertificado] abre a conexão TLS, anota o certificado e CORTA o aperto de mão antes de mandar qualquer
 *     coisa (a chave nunca vai nessa conexão).
 *  2. O dono confere a impressão digital no painel (é a mesma que o navegador mostra ao abrir https://<ip> do
 *     roteador) e confirma.
 *  3. Dali em diante [ConfiaSoNoFixado] só aceita aquele certificado, byte a byte. Outro qualquer (outro aparelho
 *     fingindo ser o roteador, ou o roteador depois de trocar o certificado) é recusado e a chave não sai.
 * O nome no certificado não é conferido (o UniFi o emite para "unifi.local", e falamos com um IP): quem garante a
 * identidade é a impressão digital, que é mais forte que o nome.
 *
 * Custo: nada com a integração desligada (o padrão). Ligada, uma leitura pedida pelo painel (com 15 s de cache) e,
 * com o aviso de aparelho novo ligado, uma leitura a cada [INTERVALO_MIN] min numa thread de fundo.
 */
object UnifiCliente {
    private const val TAG = "TvLauncher.Unifi"
    private const val INTERVALO_MIN = 5L
    private const val LIMITE_RESPOSTA = 2 * 1024 * 1024
    private const val CACHE_MS = 15_000L

    /** Falha com uma frase pronta para o painel (sem detalhe técnico nem a chave). */
    class Falha(mensagem: String) : Exception(mensagem)

    data class Leitura(val versao: String?, val respostaMs: Long, val aparelhos: List<Unifi.Aparelho>, val em: Long)

    @Volatile private var ultima: Leitura? = null
    @Volatile private var site: Pair<String, String>? = null   // (endereço, id do site): refaz se o endereço mudar

    /** Esquece o que está em memória (o painel chama ao trocar endereço, chave ou certificado). */
    fun limparCache() { ultima = null; site = null }

    // ---------------------------------------------------------------- certificado

    /**
     * Confia SÓ no certificado de impressão [esperada]. Com [esperada] null (o passo 1), anota o certificado em
     * [visto] e recusa: o aperto de mão para ali e nada é enviado.
     */
    private class ConfiaSoNoFixado(val esperada: String?, private val visto: (X509Certificate) -> Unit = {}) : X509TrustManager {
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val folha = chain?.firstOrNull() ?: throw CertificateException("sem certificado")
            visto(folha)
            if (esperada == null) throw CertificateException("só lendo o certificado")
            if (Unifi.impressao(folha.encoded) != esperada) throw CertificateException("certificado diferente do confirmado")
        }
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("não somos servidor")
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    data class Certificado(val impressao: String, val emitidoPara: String, val validoAte: Long)

    /** Passo 1: lê o certificado do roteador em [endereco] SEM mandar nada. Chamar fora da thread da tela. */
    fun buscarCertificado(ctx: Context, endereco: String): Certificado {
        var visto: X509Certificate? = null
        try {
            val c = abrir(ctx, URL("https://$endereco/"), ConfiaSoNoFixado(null) { visto = it })
            try { c.connect() } finally { c.disconnect() }
        } catch (e: Exception) {
            if (visto == null) throw Falha(traduzir(e))
        }
        val cert = visto ?: throw Falha("o roteador não mandou certificado")
        return Certificado(Unifi.impressao(cert.encoded), cert.subjectX500Principal.name.take(200), cert.notAfter.time)
    }

    // ---------------------------------------------------------------- leitura

    /** Lê versão, tempo de resposta e aparelhos. [forcar] ignora o cache de 15 s. Lança [Falha]. */
    fun ler(ctx: Context, forcar: Boolean = false): Leitura {
        val app = ctx.applicationContext
        if (!UnifiArmazem.ligado(app)) throw Falha("a integração com o roteador está desligada")
        val endereco = UnifiArmazem.endereco(app) ?: throw Falha("falta o endereço do roteador")
        val cert = UnifiArmazem.certificado(app) ?: throw Falha("confirme o certificado do roteador primeiro")
        val chave = UnifiArmazem.chave(app) ?: throw Falha("falta a chave de API do roteador")
        if (!forcar) ultima?.let { if (System.currentTimeMillis() - it.em < CACHE_MS) return it }

        val t0 = System.nanoTime()
        val info = pegar(app, endereco, cert, chave, "/info")
        val respostaMs = (System.nanoTime() - t0) / 1_000_000
        val versao = try { org.json.JSONObject(info).optString("applicationVersion").takeIf { it.isNotEmpty() } } catch (e: Exception) { null }

        val idSite = site?.takeIf { it.first == endereco }?.second
            ?: (Unifi.lerSite(pegar(app, endereco, cert, chave, "/sites")) ?: throw Falha("o roteador não tem nenhum site"))
                .also { site = endereco to it }

        val todos = ArrayList<Unifi.Aparelho>()
        var offset = 0
        while (offset < Unifi.MAX_APARELHOS) {
            val (pagina, total) = Unifi.lerPaginaAparelhos(pegar(app, endereco, cert, chave, "/sites/$idSite/clients?offset=$offset&limit=200"))
            todos.addAll(pagina)
            offset += 200
            if (pagina.isEmpty() || offset >= total) break
        }
        return Leitura(versao, respostaMs, todos.sortedBy { it.nome.lowercase() }, System.currentTimeMillis()).also { ultima = it }
    }

    /** O Wi-Fi da TV medido pelo Android, junto com o ponto de acesso dela no roteador e as dicas. */
    data class WifiDaTv(val conectadaNoWifi: Boolean, val dbm: Int, val mhz: Int, val mbps: Int, val ip: String?,
                        val ap: Unifi.PontoDeAcesso?, val achadaNoRoteador: Boolean, val dicas: List<String>)

    /**
     * Diagnóstico do Wi-Fi da TV (só leitura). Sinal, banda e velocidade do link vêm do Android (a API de integração
     * do UniFi não dá isso por aparelho); o ponto de acesso, os canais e as retransmissões vêm do roteador.
     * A TV é achada na lista do roteador pelo IP (o Android esconde o MAC verdadeiro dos apps).
     */
    @Suppress("DEPRECATION")
    fun wifiDaTv(ctx: Context): WifiDaTv {
        val app = ctx.applicationContext
        val info = (app.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager).connectionInfo
        val dbm = info?.rssi ?: -127
        val noWifi = dbm in -126..-1
        val ipInt = info?.ipAddress ?: 0
        val ip = if (ipInt == 0) null else "${ipInt and 255}.${ipInt shr 8 and 255}.${ipInt shr 16 and 255}.${ipInt shr 24 and 255}"
        if (!noWifi) return WifiDaTv(false, dbm, 0, 0, null, null, false, emptyList())
        val mhz = info?.frequency ?: 0
        val mbps = info?.linkSpeed ?: 0

        val leitura = ler(app)
        val euNoRoteador = ip?.let { meu -> leitura.aparelhos.firstOrNull { it.ip == meu } }
        val ap = euNoRoteador?.pontoDeAcesso?.let { id ->
            val endereco = UnifiArmazem.endereco(app) ?: return@let null
            val cert = UnifiArmazem.certificado(app) ?: return@let null
            val chave = UnifiArmazem.chave(app) ?: return@let null
            val idSite = site?.second ?: return@let null
            try {
                val detalhes = pegar(app, endereco, cert, chave, "/sites/$idSite/devices/$id")
                val estat = try { pegar(app, endereco, cert, chave, "/sites/$idSite/devices/$id/statistics/latest") } catch (e: Falha) { null }
                Unifi.lerPontoDeAcesso(detalhes, estat)
            } catch (e: Falha) { null } catch (e: org.json.JSONException) { null }
        }
        return WifiDaTv(true, dbm, mhz, mbps, ip, ap, euNoRoteador != null, Unifi.dicas(dbm, mhz, mbps, ap))
    }

    private fun pegar(ctx: Context, endereco: String, cert: String, chave: String, caminho: String): String {
        val c = abrir(ctx, URL("https://$endereco${Unifi.BASE}$caminho"), ConfiaSoNoFixado(cert))
        try {
            c.setRequestProperty("X-API-Key", chave)
            c.setRequestProperty("Accept", "application/json")
            val codigo = try { c.responseCode } catch (e: Exception) { throw Falha(traduzir(e)) }
            when (codigo) {
                200 -> {}
                401, 403 -> throw Falha("o roteador recusou a chave de API (confira em Configurações → Integrações do UniFi)")
                404 -> throw Falha("essa API não existe no roteador (precisa do UniFi Network 9 ou mais novo)")
                else -> throw Falha("o roteador respondeu com erro $codigo")
            }
            val texto = lerLimitado(c)
            if (!ChecagensPainel.jsonRaso(texto)) throw Falha("resposta estranha do roteador")
            return texto
        } finally { c.disconnect() }
    }

    private fun lerLimitado(c: HttpURLConnection): String {
        val saida = ByteArrayOutputStream()
        c.inputStream.use { ent ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = ent.read(buf)
                if (n < 0) break
                saida.write(buf, 0, n)
                if (saida.size() > LIMITE_RESPOSTA) throw Falha("resposta grande demais do roteador")
            }
        }
        return saida.toString("UTF-8")
    }

    /**
     * Uma conexão HTTPS que só confia em [confia]. Vai pela rede Wi-Fi/cabo da casa quando existe (o tráfego da VPN
     * sempre ativa da TV não deve passar por ela para falar com o roteador ao lado); senão, pela rede padrão.
     */
    private fun abrir(ctx: Context, url: URL, confia: ConfiaSoNoFixado): HttpsURLConnection {
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(confia), null) }
        val c = (redeDeCasa(ctx)?.openConnection(url) ?: url.openConnection()) as HttpsURLConnection
        c.sslSocketFactory = tls.socketFactory
        // No lugar do nome (ver o topo), confere DE NOVO a impressão do certificado desta sessão. Não é "aceitar
        // qualquer um": sem a impressão confirmada, a conexão é recusada aqui também.
        val esperada = (confia as? ConfiaSoNoFixado)?.esperada
        c.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, sessao ->
            esperada != null && try {
                Unifi.impressao(sessao.peerCertificates.first().encoded) == esperada
            } catch (e: Exception) { false }
        }
        c.connectTimeout = 5_000
        c.readTimeout = 8_000
        c.useCaches = false
        c.instanceFollowRedirects = false   // redirecionar mandaria a chave para outro lugar
        return c
    }

    private fun redeDeCasa(ctx: Context): Network? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull { n ->
            val cap = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            !cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
    }

    private fun traduzir(e: Exception): String = when {
        e is Falha -> e.message ?: "falhou"
        e is SocketTimeoutException -> "o roteador não respondeu a tempo"
        e is SSLHandshakeException && e.message?.contains("diferente do confirmado") == true ->
            "o certificado do roteador MUDOU: confira e confirme de novo (pode ser uma atualização do UniFi)"
        e is SSLHandshakeException -> "falha no TLS com o roteador"
        e is java.net.ConnectException -> "nada respondeu nesse endereço"
        else -> "não consegui falar com o roteador (${e.javaClass.simpleName})"
    }.also { if (e !is Falha) Log.w(TAG, "falha: ${e.javaClass.simpleName}") }

    // ---------------------------------------------------------------- aviso de aparelho novo

    private val principal by lazy { Handler(Looper.getMainLooper()) }
    private val rede by lazy { Executors.newSingleThreadExecutor() }
    private var vigiando = false

    /** Relógio de [INTERVALO_MIN] min. Uma vez por processo; chamado quando o serviço de acessibilidade liga. */
    fun vigiar(ctx: Context) {
        if (vigiando) return
        vigiando = true
        val app = ctx.applicationContext
        val relogio = object : Runnable {
            override fun run() {
                rede.execute { try { verificarNovos(app) } catch (e: Exception) { Log.w(TAG, "vigia: ${e.javaClass.simpleName}") } }
                principal.postDelayed(this, INTERVALO_MIN * 60_000L)
            }
        }
        principal.postDelayed(relogio, 2 * 60_000L)   // no boot a rede ainda não está pronta
    }

    /** Uma verificação. Chamar fora da thread da tela. Devolve o que fez (para o log e o comando adb). */
    fun verificarNovos(ctx: Context): String {
        val app = ctx.applicationContext
        if (!UnifiArmazem.avisoNovos(app)) return "aviso de aparelho novo desligado"
        if (!UnifiArmazem.pronto(app)) return "integração incompleta ou desligada"
        val leitura = try { ler(app, forcar = true) } catch (e: Falha) { return "leitura falhou: ${e.message}" }
        val antigos = UnifiArmazem.conhecidos(app)
        val semeado = UnifiArmazem.semeado(app)
        val novos = if (semeado) Unifi.novos(antigos.toSet(), leitura.aparelhos) else emptyList()
        UnifiArmazem.definirConhecidos(app, Unifi.juntarConhecidos(antigos, leitura.aparelhos))
        if (!semeado) return "primeira leitura: ${leitura.aparelhos.size} aparelho(s) anotados, sem aviso"
        if (novos.isEmpty()) return "nenhum aparelho novo (${leitura.aparelhos.size} conectados)"
        val titulo = if (novos.size == 1) "📶  Aparelho novo na rede" else "📶  ${novos.size} aparelhos novos na rede"
        val r = AvisoTv.mostrar(app, titulo, Unifi.textoNovos(novos), 30, som = false, guardarSeApagada = true)
        Log.i(TAG, "${novos.size} aparelho(s) novo(s): $r")
        return "avisou ${novos.size} aparelho(s) novo(s) (${r.name.lowercase()})"
    }
}
