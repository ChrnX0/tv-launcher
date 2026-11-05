package br.paulo.tvlauncher

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Painel das duas TVs (05/11/2026, fila longa do brainstorm). Cada TV com o painel ligado se anuncia na rede local
 * por mDNS (o mesmo "Bonjour" que impressoras usam), serviço [TIPO], com o nome amigável da TV e um ID curto. As
 * outras escutam e o painel mostra no topo "outras TVs da casa", com link para o painel de cada uma.
 *
 * Por que nome + ID: o nome ("Sala") pode repetir ou mudar; o ID não muda e separa duas TVs com o mesmo nome. A
 * mesma TV pode aparecer duas vezes (Wi-Fi e cabo, IPv4 e IPv6); [juntar] deduplica pelo ID.
 *
 * O ID é um resumo (SHA-256, 8 letras) do ANDROID_ID, não o ANDROID_ID em si: basta para distinguir as TVs da casa
 * e não entrega o identificador do aparelho para quem estiver na rede.
 *
 * Cada TV continua com o PRÓPRIO PIN e a própria sessão: o link só leva até a tela de entrar da outra.
 */
object PainelVizinhos {
    private const val TAG = "TvLauncher.Vizinhos"
    const val TIPO = "_tvlauncher._tcp."

    class Tv(val nome: String, val id: String, val host: String, val porta: Int, val vistoEm: Long)

    private var nsd: NsdManager? = null
    private var registro: NsdManager.RegistrationListener? = null
    private var busca: NsdManager.DiscoveryListener? = null
    private val achadas = mutableMapOf<String, Tv>()   // chave = nome do serviço anunciado
    private val filaResolver = ArrayDeque<NsdServiceInfo>()
    private var resolvendo = false

    fun nomeDaTv(ctx: Context): String = Settings.Global.getString(ctx.contentResolver, "device_name") ?: "TV"

    fun idDaTv(ctx: Context): String = idCurto(Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "")

    fun idCurto(bruto: String): String =
        MessageDigest.getInstance("SHA-256").digest(bruto.toByteArray()).joinToString("") { "%02x".format(it) }.take(8)

    /** Uma entrada por ID (a mais recente), sem a própria TV, ordenadas por nome. */
    fun juntar(lista: Collection<Tv>, meuId: String): List<Tv> =
        lista.filter { it.id.isNotEmpty() && it.id != meuId }
            .groupBy { it.id }.values.map { g -> g.maxByOrNull { it.vistoEm }!! }
            .sortedBy { it.nome.lowercase() }

    @Synchronized fun ligar(ctx: Context) {
        if (registro != null) return
        val app = ctx.applicationContext
        val m = app.getSystemService(NsdManager::class.java) ?: return
        nsd = m
        val meu = NsdServiceInfo().apply {
            // O nome do serviço tem de ser único na rede: o Android acrescenta " (2)" se repetir; o ID vai junto.
            serviceName = "${nomeDaTv(app)} ${idDaTv(app)}".take(60)
            serviceType = TIPO
            port = Config.PAINEL_PORTA
            setAttribute("nome", nomeDaTv(app))
            setAttribute("id", idDaTv(app))
        }
        val r = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { Log.i(TAG, "anunciado: ${i.serviceName}") }
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) { Log.w(TAG, "anúncio falhou: $e") }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }
        val b = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) { Log.w(TAG, "busca falhou: $e") }
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceFound(i: NsdServiceInfo) { resolver(i) }
            override fun onServiceLost(i: NsdServiceInfo) { synchronized(this@PainelVizinhos) { achadas.remove(i.serviceName) } }
        }
        try {
            m.registerService(meu, NsdManager.PROTOCOL_DNS_SD, r); registro = r
            m.discoverServices(TIPO, NsdManager.PROTOCOL_DNS_SD, b); busca = b
        } catch (e: Exception) { Log.w(TAG, "mDNS indisponível: $e") }
    }

    @Synchronized fun desligar() {
        val m = nsd ?: return
        try { registro?.let { m.unregisterService(it) } } catch (_: Exception) {}
        try { busca?.let { m.stopServiceDiscovery(it) } } catch (_: Exception) {}
        registro = null; busca = null; achadas.clear(); filaResolver.clear(); resolvendo = false
    }

    /** O NsdManager só resolve um serviço por vez (FAILURE_ALREADY_ACTIVE): fila. */
    @Synchronized private fun resolver(i: NsdServiceInfo) {
        filaResolver.addLast(i)
        if (!resolvendo) proximo()
    }

    @Synchronized private fun proximo() {
        val i = filaResolver.removeFirstOrNull() ?: run { resolvendo = false; return }
        val m = nsd ?: run { resolvendo = false; return }
        resolvendo = true
        @Suppress("DEPRECATION")   // a troca (registerServiceInfoCallback) só existe do Android 14 em diante
        m.resolveService(i, object : NsdManager.ResolveListener {
            override fun onResolveFailed(s: NsdServiceInfo, e: Int) { proximo() }
            override fun onServiceResolved(s: NsdServiceInfo) {
                val attrs = s.attributes
                fun attr(k: String) = attrs[k]?.toString(Charsets.UTF_8) ?: ""
                @Suppress("DEPRECATION") val host = s.host?.hostAddress ?: ""
                if (host.isNotEmpty()) synchronized(this@PainelVizinhos) {
                    achadas[s.serviceName] = Tv(attr("nome").ifEmpty { s.serviceName }, attr("id"), host, s.port, System.currentTimeMillis())
                }
                proximo()
            }
        })
    }

    /** Para /api/tvs: esta TV e as outras achadas. */
    fun json(ctx: Context): JSONObject {
        val meuId = idDaTv(ctx)
        val outras = synchronized(this) { juntar(achadas.values.toList(), meuId) }
        val arr = JSONArray()
        for (t in outras) {
            // IPv6 vai entre colchetes na URL.
            val h = if (t.host.contains(':')) "[${t.host.substringBefore('%')}]" else t.host
            arr.put(JSONObject().put("nome", t.nome).put("id", t.id).put("url", "http://$h:${t.porta}/"))
        }
        return JSONObject().put("este", JSONObject().put("nome", nomeDaTv(ctx)).put("id", meuId)).put("outras", arr)
    }
}
