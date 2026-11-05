package br.paulo.tvlauncher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * Aviso de chuva (05/11/2026): um cartão por cima de qualquer tela (ver [AvisoTv]) quando a chuva está para chegar,
 * ex.: "70% de chance de chuva por volta das 15h". Serve para quem está vendo TV e não está olhando a HOME.
 *
 * Fonte: a MESMA chance de chuva da linha do widget de clima — a mediana dos modelos ECMWF, ICON e GFS da
 * Open-Meteo (ver MainActivity.chuvaMediana: o ECMWF sozinho exagera a chuva aqui). E o "chovendo agora" do aeroporto
 * (METAR SBRP), que a HOME grava em prefs "clima": se já está chovendo, não há o que avisar.
 *
 * Quando avisa ([decidir]): a chance de agora está abaixo de [LIMIAR] e a de uma das próximas [HORAS_A_FRENTE] horas
 * está em [LIMIAR] ou mais. Depois de um aviso, fica [SILENCIO_H] horas quieto (uma chuva = um aviso, não um a cada
 * meia hora). Só entre [HORA_INICIO] e [HORA_FIM] e só com a tela acesa (TV desligada não tem quem leia).
 *
 * Custo: uma chamada HTTP pequena a cada [INTERVALO_MIN] min, só com a tela acesa e o aviso ligado; nada de alarme
 * nem de acordar a TV. O relógio roda no processo do launcher, que o serviço de acessibilidade mantém vivo.
 */
object AvisoChuva {
    private const val TAG = "TvLauncher.Chuva"
    private const val PREFS = "aviso_chuva"
    const val LIMIAR = 60            // % de chance (mediana dos 3 modelos)
    const val HORAS_A_FRENTE = 2
    const val SILENCIO_H = 3
    const val HORA_INICIO = 7        // não avisa de madrugada
    const val HORA_FIM = 23          // até 22:59
    private const val INTERVALO_MIN = 30L
    private const val SEGUNDOS_NA_TELA = 20
    private val MODELOS = listOf("ecmwf_ifs025", "icon_global", "gfs_seamless")

    // lazy: o teste no PC (AvisoChuvaTest) usa só a lógica pura, e lá o Looper do Android não existe.
    private val principal by lazy { Handler(Looper.getMainLooper()) }
    private val rede by lazy { Executors.newSingleThreadExecutor() }
    private var vigiando = false

    /** O que avisar: [hora] = "15h", [chance] em %. */
    data class Previsao(val hora: String, val chance: Int)

    fun ligado(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("ligado", true)
    fun definirLigado(ctx: Context, sim: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("ligado", sim).apply()
    }

    /** Liga o relógio de [INTERVALO_MIN] min. Uma vez por processo; chamado quando o serviço de acessibilidade liga. */
    fun vigiar(ctx: Context) {
        if (vigiando) return
        vigiando = true
        val app = ctx.applicationContext
        val relogio = object : Runnable {
            override fun run() {
                rede.execute { verificar(app) }
                principal.postDelayed(this, INTERVALO_MIN * 60_000L)
            }
        }
        // 2 min depois de subir: no boot a rede e a localização ainda não estão prontas.
        principal.postDelayed(relogio, 2 * 60_000L)
    }

    /** Uma verificação completa. Chamar fora da thread da tela. Devolve o que fez (para o log e o comando adb). */
    fun verificar(ctx: Context): String {
        val app = ctx.applicationContext
        if (!ligado(app)) return "desligado nas Configurações"
        val pm = app.getSystemService(android.os.PowerManager::class.java)
        if (pm?.isInteractive == false) return "tela apagada"
        val agora = Calendar.getInstance()
        val hora = agora.get(Calendar.HOUR_OF_DAY)
        if (hora < HORA_INICIO || hora >= HORA_FIM) return "fora do horário ($HORA_INICIO h–$HORA_FIM h)"
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ultimo = p.getLong("ultimo_aviso", 0)
        if (System.currentTimeMillis() - ultimo < SILENCIO_H * 3_600_000L) return "já avisou há menos de $SILENCIO_H h"
        // Já chovendo pelo aeroporto (o que a HOME viu nos últimos 40 min): não há o que avisar.
        val clima = app.getSharedPreferences("clima", Context.MODE_PRIVATE)
        if (clima.getBoolean("chovendo", false) && System.currentTimeMillis() - clima.getLong("em", 0) < 40 * 60_000L)
            return "já está chovendo"

        val previsao = try { buscar(app, agora) } catch (e: Exception) {
            Log.w(TAG, "previsão falhou: $e"); return "previsão falhou: ${e.javaClass.simpleName}"
        } ?: return "sem chuva à vista"
        val r = AvisoTv.mostrar(app, "☔  Aviso de chuva", "${previsao.chance}% de chance de chuva por volta das ${previsao.hora}",
            SEGUNDOS_NA_TELA, som = false, guardarSeApagada = false)
        if (r == AvisoTv.Resultado.MOSTRADO) p.edit().putLong("ultimo_aviso", System.currentTimeMillis()).apply()
        Log.i(TAG, "chuva ${previsao.chance}% às ${previsao.hora}: $r")
        return "avisou: ${previsao.chance}% às ${previsao.hora} ($r)"
    }

    /** Só para teste (comando adb CHUVA --ez simular true): mostra o cartão sem consultar nada nem mexer no silêncio. */
    fun simular(ctx: Context): AvisoTv.Resultado =
        AvisoTv.mostrar(ctx, "☔  Aviso de chuva (teste)", "70% de chance de chuva por volta das 15h",
            SEGUNDOS_NA_TELA, som = false, guardarSeApagada = false)

    private fun buscar(app: Context, agora: Calendar): Previsao? {
        val ponto = Localizacao.atual(app) { httpGet(it) }
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${ponto.lat}&longitude=${ponto.lon}" +
            "&hourly=precipitation_probability&forecast_hours=${HORAS_A_FRENTE + 2}&timezone=auto&models=${MODELOS.joinToString(",")}"
        val h = JSONObject(httpGet(url) ?: throw IllegalStateException("sem resposta")).getJSONObject("hourly")
        val tempo = h.getJSONArray("time")
        val horas = (0 until tempo.length()).map { tempo.getString(it) }
        val porModelo = MODELOS.mapNotNull { m ->
            h.optJSONArray("precipitation_probability_$m")?.let { a ->
                (0 until a.length()).map { i -> if (a.isNull(i)) null else a.optInt(i, -1).takeIf { it >= 0 } }
            }
        }
        if (porModelo.isEmpty()) throw IllegalStateException("sem modelos")
        val agoraTexto = String.format(java.util.Locale.US, "%04d-%02d-%02dT%02d",
            agora.get(Calendar.YEAR), agora.get(Calendar.MONTH) + 1, agora.get(Calendar.DAY_OF_MONTH), agora.get(Calendar.HOUR_OF_DAY))
        return decidir(horas, mediana(porModelo), agoraTexto)
    }

    // ---------------------------------------------------------------- lógica pura (testada em AvisoChuvaTest)

    /** Mediana hora a hora de vários modelos; hora sem nenhum valor = 0. Média dos dois do meio quando o número é par. */
    fun mediana(porModelo: List<List<Int?>>): List<Int> {
        val n = porModelo.maxOfOrNull { it.size } ?: 0
        return (0 until n).map { i ->
            val v = porModelo.mapNotNull { it.getOrNull(i) }.sorted()
            when {
                v.isEmpty() -> 0
                v.size % 2 == 1 -> v[v.size / 2]
                else -> (v[v.size / 2 - 1] + v[v.size / 2]) / 2
            }
        }
    }

    /**
     * [horas] no formato da Open-Meteo ("2026-11-05T14:00"), [chances] alinhadas com elas, [agora] = "2026-11-05T14"
     * (a hora cheia de agora, na hora local, a mesma do timezone=auto). null = não avisar.
     */
    fun decidir(horas: List<String>, chances: List<Int>, agora: String): Previsao? {
        val i0 = horas.indexOfFirst { it.startsWith(agora) }
        if (i0 < 0 || i0 >= chances.size) return null
        if (chances[i0] >= LIMIAR) return null   // a chuva é "agora": a HOME já diz, e um aviso chegaria tarde
        for (k in 1..HORAS_A_FRENTE) {
            val i = i0 + k
            if (i >= horas.size || i >= chances.size) break
            if (chances[i] >= LIMIAR) {
                val hh = horas[i].substringAfter('T').take(2).toIntOrNull() ?: return null
                return Previsao("${hh}h", chances[i])
            }
        }
        return null
    }

    private fun httpGet(endereco: String): String? {
        val c = URL(endereco).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "tv-launcher/0.1")
            if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null
        } finally { c.disconnect() }
    }
}
