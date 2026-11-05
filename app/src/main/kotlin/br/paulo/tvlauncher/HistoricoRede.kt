package br.paulo.tvlauncher

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Histórico de rede (05/11/2026, ideia aprovada no brainstorm): responde "a internet caiu ontem à noite?" e "é a
 * internet ou é o app?" olhando para trás, sem ninguém ter deixado nada rodando.
 *
 * Como coleta, enquanto o processo do launcher viver (ele é a tela inicial: vive quase sempre):
 *  - uma AMOSTRA a cada [INTERVALO_MS] (5 min): abre uma conexão até 1.1.1.1:443 (internet sem depender de DNS, e o
 *    tempo dela é a latência), resolve www.google.com (DNS), lê o sinal do Wi-Fi e se há VPN. Custo: 2 pacotes
 *    pequenos a cada 5 min, ~1 ms de CPU. Se a internet falhar, passa a medir a cada [INTERVALO_FALHA_MS] (30 s) até
 *    voltar, para saber quanto a queda durou com precisão de 30 s, e volta aos 5 min;
 *  - um EVENTO sempre que o Android avisa que a rede caiu, voltou ou mudou de validação (NetworkCallback: o sistema
 *    chama a gente, custo zero parado).
 * Guarda 7 dias num arquivo de texto (uma linha por medição, ~45 bytes: ~100 KB no total) em files/. Sem amostra
 * por mais de 15 min (TV desligada da tomada, launcher reiniciando) vira "sem dados", nunca "internet fora".
 * As contas (quedas, tempo fora, latência) ficam em [ResumoRede], que não usa Android e tem teste no PC.
 */
object HistoricoRede {
    private const val TAG = "TvLauncher.HistRede"
    private const val ARQUIVO = "historico-rede.txt"
    const val INTERVALO_MS = 5 * 60_000L
    const val INTERVALO_FALHA_MS = 30_000L
    const val GUARDAR_MS = 7L * 24 * 60 * 60 * 1000
    /** Acima disso o arquivo é reescrito só com os últimos 7 dias. */
    private const val LIMITE_BYTES = 400 * 1024L

    @Volatile private var exec: ScheduledExecutorService? = null
    private val trava = Any()
    private var ultimoValidado: Boolean? = null

    /** Liga a coleta. Idempotente: chamar à vontade (HOME, boot, serviço de acessibilidade). */
    @Synchronized fun iniciar(ctx: Context) {
        if (exec != null) return
        val app = ctx.applicationContext
        val e = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "HistoricoRede").apply { isDaemon = true } }
        exec = e
        e.schedule({ amostrar(app) }, 20, TimeUnit.SECONDS)   // a 1ª depois de o boot assentar
        e.execute { aparar(app) }
        vigiarEventos(app)
        Log.i(TAG, "coletando a cada ${INTERVALO_MS / 60_000} min")
    }

    // ---------------------------------------------------------------- coleta

    private fun amostrar(app: Context) {
        val proximo = try {
            val a = medir(app)
            gravar(app, ResumoRede.linhaAmostra(a))
            if (a.internet) INTERVALO_MS else INTERVALO_FALHA_MS
        } catch (e: Throwable) {
            Log.w(TAG, "amostra falhou: $e"); INTERVALO_MS
        }
        exec?.schedule({ amostrar(app) }, proximo, TimeUnit.MILLISECONDS)
    }

    /** Uma medição. Rede: até ~3 s (conexão) + ~3 s (DNS) no pior caso, na thread própria. */
    private fun medir(app: Context): ResumoRede.Amostra {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val ativa = cm.activeNetwork
        val caps = ativa?.let { cm.getNetworkCapabilities(it) }
        val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val validado = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        @Suppress("DEPRECATION")
        val dbm = (app.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo?.rssi ?: -127
        val wifi = dbm in -126..-1

        // Internet sem DNS: conexão TCP até a Cloudflare. O tempo dela é a latência (ida e volta + aperto de mão).
        var lat = -1
        val ini = SystemClock.elapsedRealtime()
        try {
            Socket().use { it.connect(InetSocketAddress("1.1.1.1", 443), 3000) }
            lat = (SystemClock.elapsedRealtime() - ini).toInt()
        } catch (_: Exception) {}

        // DNS: o mesmo caminho dos apps (rede ativa, com o DNS privado se houver). Pode vir do cache do Android, e
        // isso é o que os apps também veem.
        var dns = -1
        if (ativa != null) {
            val ini2 = SystemClock.elapsedRealtime()
            dns = try { ativa.getAllByName("www.google.com"); (SystemClock.elapsedRealtime() - ini2).toInt() } catch (_: Exception) { -1 }
        }
        return ResumoRede.Amostra(System.currentTimeMillis(), lat >= 0, lat, dns, if (wifi) dbm else 0, vpn, validado)
    }

    /** Avisos do próprio Android: rede caiu, voltou, validou ou deixou de validar. */
    private fun vigiarEventos(app: Context) {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                // O Android chama onAvailable logo no registro com a rede que já estava de pé: esse não é "voltou".
                private var primeira = true
                override fun onAvailable(network: Network) {
                    ultimoValidado = null
                    if (primeira) { primeira = false; return }
                    evento(app, "rede conectada")
                }
                override fun onLost(network: Network) {
                    ultimoValidado = null
                    primeira = false
                    evento(app, "rede caiu")
                    // Mede já (e a cada 30 s, se continuar fora) em vez de esperar até 5 min.
                    exec?.schedule({ amostrarAvulsa(app) }, 5, TimeUnit.SECONDS)
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val v = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    val antes = ultimoValidado
                    ultimoValidado = v
                    val tipo = when {
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "cabo"
                        else -> "rede"
                    }
                    if (antes == null) { if (!v) evento(app, "$tipo conectado, sem internet confirmada") }
                    else if (antes != v) evento(app, if (v) "$tipo: internet confirmada pelo Android" else "$tipo: o Android perdeu a internet")
                }
            })
        } catch (e: Exception) { Log.w(TAG, "sem aviso de rede: $e") }
    }

    /** Medição fora do ciclo (depois de um "rede caiu"); não mexe no agendamento normal. */
    private fun amostrarAvulsa(app: Context) {
        try { val a = medir(app); gravar(app, ResumoRede.linhaAmostra(a)) } catch (_: Throwable) {}
    }

    private fun evento(app: Context, texto: String) {
        exec?.execute { gravar(app, ResumoRede.linhaEvento(System.currentTimeMillis(), texto)) }
    }

    // ---------------------------------------------------------------- arquivo

    private fun arquivo(app: Context) = File(app.filesDir, ARQUIVO)

    private fun gravar(app: Context, linha: String) = synchronized(trava) {
        try {
            val f = arquivo(app)
            f.appendText(linha + "\n")
            if (f.length() > LIMITE_BYTES) aparar(app)
        } catch (e: Exception) { Log.w(TAG, "não gravei: $e") }
    }

    /** Reescreve o arquivo só com os últimos 7 dias. */
    private fun aparar(app: Context) = synchronized(trava) {
        try {
            val f = arquivo(app)
            if (!f.exists()) return@synchronized
            val desde = System.currentTimeMillis() - GUARDAR_MS
            val manter = f.readLines().filter { (it.substringBefore('\t').toLongOrNull() ?: 0) >= desde }
            val tmp = File(app.filesDir, "$ARQUIVO.tmp")
            tmp.writeText(manter.joinToString("") { it + "\n" })
            tmp.renameTo(f)
        } catch (e: Exception) { Log.w(TAG, "não aparei: $e") }
    }

    /** Para o painel: amostras, eventos e o resumo das últimas [horas] horas (1 a 168). */
    fun json(ctx: Context, horas: Int): JSONObject {
        val agora = System.currentTimeMillis()
        val desde = agora - horas.coerceIn(1, 168) * 3_600_000L
        val linhas = synchronized(trava) {
            try { arquivo(ctx.applicationContext).takeIf { it.exists() }?.readLines() ?: emptyList() } catch (_: Exception) { emptyList() }
        }
        val (amostras, eventos) = ResumoRede.ler(linhas, desde)
        val r = ResumoRede.resumir(amostras, desde, agora)
        val oA = JSONArray()
        // Compacto: [t, internet 1/0, latência, dns, dBm, vpn 1/0]. 168 h = ~2.000 linhas = ~60 KB.
        for (a in amostras) oA.put(JSONArray().put(a.t).put(if (a.internet) 1 else 0).put(a.latMs).put(a.dnsMs).put(a.dbm).put(if (a.vpn) 1 else 0))
        val oE = JSONArray()
        for ((t, txt) in eventos.takeLast(300)) oE.put(JSONArray().put(t).put(txt))
        val quedas = JSONArray()
        for (q in r.quedas.takeLast(100)) quedas.put(JSONObject().put("inicio", q.inicio).put("fim", q.fim).put("tipo", q.tipo).put("aberta", q.aberta))
        return JSONObject()
            .put("desde", desde).put("agora", agora).put("intervaloMin", INTERVALO_MS / 60_000)
            .put("amostras", oA).put("eventos", oE).put("quedas", quedas)
            .put("resumo", JSONObject()
                .put("medicoes", r.medicoes)
                .put("coberturaMs", r.coberturaMs)
                .put("foraMs", r.foraMs)
                .put("quedasInternet", r.quedas.count { it.tipo == "internet" })
                .put("quedasDns", r.quedas.count { it.tipo == "dns" })
                .put("maiorQuedaMs", r.maiorQuedaMs)
                .put("latMedia", r.latMedia ?: JSONObject.NULL)
                .put("latP95", r.latP95 ?: JSONObject.NULL)
                .put("dbmMedio", r.dbmMedio ?: JSONObject.NULL))
    }
}

/**
 * As contas do histórico, sem nada de Android (testadas no PC em HistoricoRedeTest).
 * Formato de cada linha do arquivo, separado por TAB:
 *   amostra: `t  A  internet(1/0)  latênciaMs  dnsMs(-1 = falhou)  dBm(0 = sem Wi-Fi)  vpn(1/0)  validado(1/0)`
 *   evento:  `t  E  texto`
 */
object ResumoRede {
    /** Sem amostra por mais que isso = buraco (sem dados), não queda. 3 ciclos de 5 min. */
    const val BURACO_MS = 15 * 60_000L

    class Amostra(val t: Long, val internet: Boolean, val latMs: Int, val dnsMs: Int, val dbm: Int, val vpn: Boolean, val validado: Boolean)
    /** [tipo]: "internet" (nem a conexão sem DNS passou) ou "dns" (internet ok, nomes não resolvem). */
    class Queda(val inicio: Long, val fim: Long, val tipo: String, val aberta: Boolean) { val duracao get() = fim - inicio }
    class Resumo(val medicoes: Int, val coberturaMs: Long, val foraMs: Long, val quedas: List<Queda>, val maiorQuedaMs: Long,
                 val latMedia: Int?, val latP95: Int?, val dbmMedio: Int?)

    fun linhaAmostra(a: Amostra) = listOf(a.t, "A", if (a.internet) 1 else 0, a.latMs, a.dnsMs, a.dbm, if (a.vpn) 1 else 0, if (a.validado) 1 else 0).joinToString("\t")
    fun linhaEvento(t: Long, texto: String) = "$t\tE\t" + texto.replace('\t', ' ').replace('\n', ' ')

    /** Linhas do arquivo -> amostras e eventos a partir de [desde], em ordem de tempo. Linha estragada é pulada. */
    fun ler(linhas: List<String>, desde: Long): Pair<List<Amostra>, List<Pair<Long, String>>> {
        val amostras = mutableListOf<Amostra>()
        val eventos = mutableListOf<Pair<Long, String>>()
        for (l in linhas) {
            val p = l.split('\t')
            val t = p.getOrNull(0)?.toLongOrNull() ?: continue
            if (t < desde) continue
            when (p.getOrNull(1)) {
                "A" -> if (p.size >= 8) {
                    val n = p.drop(2).map { it.toIntOrNull() }
                    if (n.any { it == null }) continue
                    amostras += Amostra(t, n[0] == 1, n[1]!!, n[2]!!, n[3]!!, n[4] == 1, n[5] == 1)
                }
                "E" -> eventos += t to p.drop(2).joinToString(" ")
            }
        }
        amostras.sortBy { it.t }; eventos.sortBy { it.first }
        return amostras to eventos
    }

    /**
     * Quedas = sequências de amostras com falha. Uma queda começa na 1ª amostra com falha e termina na 1ª amostra boa
     * depois dela. Buraco de mais de [BURACO_MS] entre duas amostras corta a queda ali (não sabemos o que houve no
     * buraco). Cobertura = tempo coberto por amostras (cada uma vale até a próxima, no máximo [BURACO_MS]).
     */
    fun resumir(amostras: List<Amostra>, desde: Long, ate: Long): Resumo {
        val quedas = mutableListOf<Queda>()
        var cobertura = 0L
        var inicio: Long? = null; var tipo = ""
        fun tipoDe(a: Amostra) = when { !a.internet -> "internet"; a.dnsMs < 0 -> "dns"; else -> null }
        for ((i, a) in amostras.withIndex()) {
            val prox = amostras.getOrNull(i + 1)
            val fimTrecho = minOf(prox?.t ?: ate, a.t + BURACO_MS, ate)
            cobertura += (fimTrecho - a.t).coerceAtLeast(0)
            val tp = tipoDe(a)
            if (inicio != null && tp != tipo) {   // a queda acabou (voltou ou mudou de tipo) nesta amostra
                quedas += Queda(inicio, a.t, tipo, false); inicio = null
            }
            if (tp != null && inicio == null) { inicio = a.t; tipo = tp }
            // Buraco depois desta amostra: a queda aberta termina onde os dados acabam.
            if (inicio != null && (prox == null || prox.t - a.t > BURACO_MS)) {
                val aberta = prox == null && ate - a.t <= BURACO_MS
                quedas += Queda(inicio, if (aberta) ate else maxOf(fimTrecho, inicio), tipo, aberta); inicio = null
            }
        }
        val fora = quedas.filter { it.tipo == "internet" }.sumOf { it.duracao }
        val lats = amostras.filter { it.internet && it.latMs >= 0 }.map { it.latMs }.sorted()
        val dbms = amostras.filter { it.dbm != 0 }.map { it.dbm }
        return Resumo(
            medicoes = amostras.size, coberturaMs = cobertura, foraMs = fora, quedas = quedas,
            maiorQuedaMs = quedas.maxOfOrNull { it.duracao } ?: 0,
            latMedia = if (lats.isEmpty()) null else lats.average().toInt(),
            latP95 = if (lats.isEmpty()) null else lats[((lats.size * 95 + 99) / 100 - 1).coerceIn(0, lats.size - 1)],   // posto mais próximo
            dbmMedio = if (dbms.isEmpty()) null else dbms.average().toInt(),
        )
    }
}
