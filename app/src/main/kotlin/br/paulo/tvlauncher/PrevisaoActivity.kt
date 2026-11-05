package br.paulo.tvlauncher

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A previsão do tempo em tela cheia (abre com OK no bloco do clima, no topo da tela inicial). Voltar fecha.
 *
 * Uma coluna que rola com as setas: cada cartão é um "ponto de parada" do foco (cima/baixo), e o cartão focado sobe para o
 * topo da tela. Na faixa de horas, esquerda/direita andam hora a hora. Fonte: Open-Meteo (previsão) e a API de qualidade do ar
 * da própria Open-Meteo, no ponto da Localizacao. Tudo é desenhado em código (Views), sem XML.
 */
class PrevisaoActivity : Activity() {
    companion object {
        const val EXTRA_TEMP = "temp"            // a temperatura mostrada no topo (Int.MIN_VALUE = sem medida)
        const val EXTRA_CHOVENDO = "chovendo"    // o topo diz que chove agora
        const val EXTRA_FALAR = "falar"          // ler a previsão em voz alta ao abrir
        const val EXTRA_BOM_DIA = "bom_dia"      // falar "Bom dia", a hora e a agenda de hoje junto (despertador)
    }

    // Voz: o TextToSpeech do Android (motor do Google, já instalado na TV). Liga só quando a tela foi aberta para falar
    // (pelo atalho "Boletim do Tempo", que o Google Assistant abre por voz, ou com EXTRA_FALAR).
    private var voz: TextToSpeech? = null
    private var vozPronta = false
    private var falaPendente: String? = null
    private var bomDia = false
    @Volatile private var agendaHoje: String? = null   // lida em segundo plano; quase sempre pronta antes da previsão

    private val textoSuave = 0xB3FFFFFF.toInt()
    private val azulChuva = 0xFF8EC5FF.toInt()
    private val fonteMedia = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val fonteLeve = Typeface.create("sans-serif-light", Typeface.NORMAL)

    private lateinit var raiz: FrameLayout
    private lateinit var rolagem: ScrollView
    private lateinit var coluna: LinearLayout
    private lateinit var fundoVivo: View   // o mesmo fundo do launcher (FundoEscuro/FundoGaleria, ver Fundos.kt), para as duas telas ficarem coerentes

    /** ScrollView que NÃO rola sozinho para o filho focado: quem rola é [focarCartao], de forma previsível. */
    private class Rolagem(ctx: Context) : ScrollView(ctx) {
        override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect?): Int = 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coluna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            setPadding(dp(Grade.MARGEM), dp(28), dp(Grade.MARGEM), dp(60))
        }
        rolagem = Rolagem(this).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(coluna, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // Mesmo fundo do launcher (criarFundo(), ver Fundos.kt): acompanha o tema configurado (Escuro/Galeria) em
        // vez de uma cor fixa só desta tela — pedido do Paulo para as duas ficarem coerentes.
        fundoVivo = criarFundo(this, Tema.atual(this), decoracao = false) { bloco -> Thread(bloco).start() }
        raiz = FrameLayout(this).apply {
            addView(fundoVivo, FrameLayout.LayoutParams(-1, -1))
            addView(rolagem, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(raiz)
        coluna.addView(texto(18f, textoSuave).apply { text = "Carregando a previsão…" })

        // Aberta pelo atalho "Boletim do Tempo" (action MAIN) ou com EXTRA_FALAR: prepara a voz enquanto busca os dados.
        // "Bom dia": o despertador (Parar) ou o atalho de voz "Bom dia" (alias .BomDia, ver o manifesto).
        bomDia = intent.getBooleanExtra(EXTRA_BOM_DIA, false) || intent.component?.className?.endsWith(".BomDia") == true
        if (bomDia) Thread { agendaHoje = try { Agenda.resumoHoje(this) } catch (e: Exception) { null } }.start()
        if (intent.action == Intent.ACTION_MAIN || intent.getBooleanExtra(EXTRA_FALAR, false) || bomDia) {
            voz = TextToSpeech(this) { status ->
                if (status != TextToSpeech.SUCCESS) return@TextToSpeech
                val r = voz?.setLanguage(Locale("pt", "BR")) ?: TextToSpeech.LANG_NOT_SUPPORTED
                vozPronta = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                android.util.Log.i("TvLauncher.Voz", "TTS pronto, idioma pt-BR: $r")
                falaPendente?.let { falar(it) }
            }
        }
        // A medida do topo vem no Intent (OK no clima) ou, aberta por voz, do que a tela inicial guardou (se tiver até 90 min).
        val guardado = getSharedPreferences("clima", MODE_PRIVATE)
        val recente = System.currentTimeMillis() - guardado.getLong("em", 0) < 90 * 60_000L
        val tempTopo = intent.getIntExtra(EXTRA_TEMP, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
            ?: if (recente && guardado.contains("temp")) guardado.getInt("temp", 0) else null
        val chovendo = if (intent.hasExtra(EXTRA_CHOVENDO)) intent.getBooleanExtra(EXTRA_CHOVENDO, false)
            else recente && guardado.getBoolean("chovendo", false)
        Thread {
            val dados = try { carregar() } catch (e: Exception) { null }
            runOnUiThread { if (!isFinishing) montar(dados, tempTopo, chovendo) }
        }.start()
    }

    private fun falar(frase: String) {
        if (!vozPronta) { falaPendente = frase; return }
        falaPendente = null
        voz?.speak(frase, TextToSpeech.QUEUE_FLUSH, null, "previsao")
    }

    override fun onDestroy() {
        voz?.stop(); voz?.shutdown(); voz = null
        super.onDestroy()
    }

    /** "Bom dia! São 7 horas e 5 minutos, quinta-feira, 5 de novembro." (com "horas" e "minutos" por extenso: o
     *  leitor de voz lê "7:05" de jeitos diferentes conforme o motor). */
    private fun saudacao(): String {
        val c = java.util.Calendar.getInstance()
        val h = c.get(java.util.Calendar.HOUR_OF_DAY); val m = c.get(java.util.Calendar.MINUTE)
        val ola = when { h < 12 -> "Bom dia!"; h < 18 -> "Boa tarde!"; else -> "Boa noite!" }
        val horas = if (h == 1) "1 hora" else "$h horas"
        val agora = when (m) { 0 -> horas; 1 -> "$horas e 1 minuto"; else -> "$horas e $m minutos" }
        val dia = java.text.SimpleDateFormat("EEEE, d 'de' MMMM", Locale("pt", "BR")).format(c.time)
        return "$ola ${if (h == 1) "É" else "São"} $agora, $dia."
    }

    /** "Agora faz 20 graus, chuva. Hoje, máxima de 27 e mínima de 20, com 86 por cento de chance de chuva. Amanhã, ..." */
    private fun boletim(d: Dados, tempAgora: Int, maxHoje: Int, minHoje: Int, chovendo: Boolean): String {
        val b = StringBuilder("Agora faz $tempAgora graus, ${IconesClima.texto(d.codigo, chovendo).lowercase()}. ")
        b.append("Hoje, máxima de $maxHoje e mínima de $minHoje")
        b.append(if (d.dChuva[0] >= 20) ", com ${d.dChuva[0]} por cento de chance de chuva. " else ", sem previsão de chuva. ")
        if (d.dData.size > 1) {
            b.append("Amanhã, ${IconesClima.texto(d.dCodigo[1]).lowercase()}, máxima de ${Math.round(d.dMax[1])} e mínima de ${Math.round(d.dMin[1])}")
            if (d.dChuva[1] >= 20) b.append(", chance de chuva de ${d.dChuva[1]} por cento")
            b.append(".")
        }
        return b.toString()
    }

    // ---------------------------------------------------------------- dados

    private class Dados(
        val ponto: Localizacao.Ponto,
        val agoraTexto: String,            // "2026-11-02T16:15" (hora local do lugar)
        val temp: Double, val sensacao: Double, val umidade: Int, val codigo: Int, val pressao: Double,
        val vento: Double, val ventoDir: Int, val eDia: Boolean,
        val hTempo: List<String>, val hTemp: List<Double>, val hChuva: List<Int>, val hCodigo: List<Int>,
        val hVento: List<Double>, val hVisib: List<Double>, val hOrvalho: List<Double>, val hUv: List<Double>,
        val dData: List<String>, val dCodigo: List<Int>, val dMax: List<Double>, val dMin: List<Double>, val dChuva: List<Int>,
        val dNascer: List<String>, val dPoente: List<String>, val dChuvaMm: Double,
        val aqi: Int?,
    )

    private fun carregar(): Dados? {
        val ponto = Localizacao.atual(this) { httpGet(it) }
        val unidade = if (Config.USAR_FAHRENHEIT) "&temperature_unit=fahrenheit" else ""
        val j = JSONObject(httpGet(
            "https://api.open-meteo.com/v1/forecast?latitude=${ponto.lat}&longitude=${ponto.lon}" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,pressure_msl,wind_speed_10m,wind_direction_10m,is_day" +
                "&hourly=temperature_2m,precipitation_probability,weather_code,wind_speed_10m,visibility,dew_point_2m,uv_index" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset,precipitation_sum" +
                "&timezone=auto&forecast_days=7$unidade"
        ) ?: return null)
        val c = j.getJSONObject("current"); val h = j.getJSONObject("hourly"); val d = j.getJSONObject("daily")
        fun <T> lista(o: JSONObject, k: String, f: (org.json.JSONArray, Int) -> T) = o.getJSONArray(k).let { a -> (0 until a.length()).map { f(a, it) } }
        val aqi = try {
            JSONObject(httpGet("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=${ponto.lat}&longitude=${ponto.lon}&current=us_aqi")
                ?: "{}").optJSONObject("current")?.optInt("us_aqi", -1)?.takeIf { it >= 0 }
        } catch (e: Exception) { null }
        return Dados(
            ponto, c.getString("time"),
            c.getDouble("temperature_2m"), c.getDouble("apparent_temperature"), c.getInt("relative_humidity_2m"), c.getInt("weather_code"),
            c.getDouble("pressure_msl"), c.getDouble("wind_speed_10m"), c.getInt("wind_direction_10m"),
            // Mesma regra da HOME (Astronomia.eDia, com o crepúsculo): antes era o is_day cru da API e as telas discordavam.
            Astronomia.eDia(System.currentTimeMillis(), ponto.lat, ponto.lon, c.getInt("is_day") == 1),
            lista(h, "time") { a, i -> a.getString(i) }, lista(h, "temperature_2m") { a, i -> a.optDouble(i) },
            lista(h, "precipitation_probability") { a, i -> a.optInt(i, 0) }, lista(h, "weather_code") { a, i -> a.optInt(i, 3) },
            lista(h, "wind_speed_10m") { a, i -> a.optDouble(i) }, lista(h, "visibility") { a, i -> a.optDouble(i) },
            lista(h, "dew_point_2m") { a, i -> a.optDouble(i) }, lista(h, "uv_index") { a, i -> a.optDouble(i, 0.0) },
            lista(d, "time") { a, i -> a.getString(i) }, lista(d, "weather_code") { a, i -> a.optInt(i, 3) },
            lista(d, "temperature_2m_max") { a, i -> a.optDouble(i) }, lista(d, "temperature_2m_min") { a, i -> a.optDouble(i) },
            lista(d, "precipitation_probability_max") { a, i -> a.optInt(i, 0) },
            lista(d, "sunrise") { a, i -> a.getString(i) }, lista(d, "sunset") { a, i -> a.getString(i) },
            d.getJSONArray("precipitation_sum").optDouble(0, 0.0),
            aqi,
        )
    }

    private fun httpGet(endereco: String): String? {
        val conexao = URL(endereco).openConnection() as HttpURLConnection
        return try {
            conexao.connectTimeout = 8000
            conexao.readTimeout = 8000
            conexao.setRequestProperty("User-Agent", "tv-launcher/0.1")
            if (conexao.responseCode == 200) conexao.inputStream.bufferedReader().use { it.readText() } else null
        } finally {
            conexao.disconnect()
        }
    }

    // ---------------------------------------------------------------- tela

    private fun montar(d: Dados?, tempTopo: Int?, chovendo: Boolean) {
        coluna.removeAllViews()
        if (d == null) {
            coluna.addView(texto(20f, Color.WHITE).apply { text = "Sem previsão agora (sem internet ou fonte fora do ar)." })
            return
        }
        val agoraMin = minutos(d.agoraTexto)
        val tempAgora = tempTopo ?: Math.round(d.temp).toInt()
        val i0 = max(0, d.hTempo.indexOfFirst { it >= d.agoraTexto.substring(0, 13) + ":00" })
        fun eDiaNaHora(i: Int): Boolean { val di = min(i / 24, d.dNascer.size - 1); return d.hTempo[i] >= d.dNascer[di] && d.hTempo[i] < d.dPoente[di] }

        (fundoVivo as? FundoVivo)?.atualizar(EstadoCeu(System.currentTimeMillis(), d.ponto.lat, d.ponto.lon, d.codigo, chovendo))

        // ---- cabeçalho
        val maxHoje = max(Math.round(d.dMax[0]).toInt(), tempAgora)
        val minHoje = min(Math.round(d.dMin[0]).toInt(), tempAgora)
        // O modelo erra a temperatura de agora (hoje: modelo 25°, aeroporto 20°). Corrige a sensação pela mesma diferença,
        // para "20°, sensação 28° mais quente" não aparecer.
        val sensacao = Math.round(d.sensacao + (tempAgora - d.temp)).toInt()
        val iconeAgora = IconesClima.chave(d.codigo, d.eDia, chovendo)
        val cabecalho = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        cabecalho.addView(IconesClima.imagem(this, iconeAgora), LinearLayout.LayoutParams(dp(132), dp(132)))
        val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), 0, 0, 0) }
        txt.addView(texto(84f, Color.WHITE, fonteLeve).apply { text = "$tempAgora°"; includeFontPadding = false })
        txt.addView(texto(24f, Color.WHITE, fonteMedia).apply { text = IconesClima.texto(d.codigo, chovendo) })
        txt.addView(texto(16f, textoSuave).apply {
            text = "máx $maxHoje°   mín $minHoje°   ·   sensação $sensacao°"
            setPadding(0, dp(4), 0, 0)
        })
        cabecalho.addView(txt)
        coluna.addView(cabecalho)

        // ---- hora a hora
        val horas = cartao("Próximas 24 horas", focavel = false)
        val faixa = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val fim = min(d.hTempo.size, i0 + 24)
        for (i in i0 until fim) {
            val hora = if (i == i0) "Agora" else "${d.hTempo[i].substring(11, 13).toInt()}h"
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(8), dp(4), dp(8))
                isFocusable = true
                background = itemFoco(false)
                setOnFocusChangeListener { v, f -> v.background = itemFoco(f); if (f) focarCartao(horas) }
            }
            item.addView(texto(14f, textoSuave).apply { text = hora; gravity = Gravity.CENTER })
            item.addView(IconesClima.imagem(this, if (i == i0) iconeAgora else IconesClima.chave(d.hCodigo[i], eDiaNaHora(i), false)),
                LinearLayout.LayoutParams(dp(40), dp(40)).apply { topMargin = dp(6); bottomMargin = dp(6) })
            item.addView(texto(18f, Color.WHITE, fonteMedia).apply { text = "${if (i == i0) tempAgora else Math.round(d.hTemp[i]).toInt()}°"; gravity = Gravity.CENTER })
            item.addView(texto(13f, azulChuva).apply { text = "${d.hChuva[i]}%"; gravity = Gravity.CENTER })
            item.addView(texto(12f, textoSuave).apply { text = "${Math.round(d.hVento[i]).toInt()} km/h"; gravity = Gravity.CENTER })
            faixa.addView(item, LinearLayout.LayoutParams(dp(66), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        horas.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(faixa)
        })
        coluna.addView(horas, lpCartao())

        // ---- gráfico de chuva
        val grafico = cartao("Chance de chuva")
        grafico.addView(GraficoChuva(this, (i0 until fim).map { d.hChuva[it] }, (i0 until fim).map { d.hTempo[it].substring(11, 13).toInt() }),
            LinearLayout.LayoutParams(-1, dp(120)).apply { topMargin = dp(6) })
        coluna.addView(grafico, lpCartao())

        // ---- 7 dias
        val gMin = d.dMin.minOrNull() ?: 0.0; val gMax = d.dMax.maxOrNull() ?: 1.0
        val dias = cartao("Próximos 7 dias")
        val entrada = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val saida = SimpleDateFormat("EEEE", Locale("pt", "BR"))
        for (i in d.dData.indices) {
            val nome = if (i == 0) "Hoje" else saida.format(entrada.parse(d.dData[i])!!).replaceFirstChar { it.uppercase() }.substringBefore("-feira")
            val mx = if (i == 0) maxHoje else Math.round(d.dMax[i]).toInt()
            val mn = if (i == 0) minHoje else Math.round(d.dMin[i]).toInt()
            dias.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, dp(3))
                addView(texto(17f, Color.WHITE, fonteMedia).apply { text = nome }, LinearLayout.LayoutParams(dp(110), -2))
                addView(IconesClima.imagem(this@PrevisaoActivity, IconesClima.chave(d.dCodigo[i], true, false)), LinearLayout.LayoutParams(dp(34), dp(34)))
                addView(texto(15f, azulChuva).apply { text = "${d.dChuva[i]}%"; gravity = Gravity.END }, LinearLayout.LayoutParams(dp(56), -2))
                addView(texto(17f, textoSuave).apply { text = "$mn°"; gravity = Gravity.END }, LinearLayout.LayoutParams(dp(48), -2))
                addView(BarraTemp(this@PrevisaoActivity, mn.toDouble(), mx.toDouble(), gMin, gMax), LinearLayout.LayoutParams(0, dp(8), 1f).apply { marginStart = dp(12); marginEnd = dp(12) })
                addView(texto(17f, Color.WHITE, fonteMedia).apply { text = "$mx°" }, LinearLayout.LayoutParams(dp(44), -2))
            })
        }
        coluna.addView(dias, lpCartao())

        // ---- detalhes (2 linhas x 4)
        val uv = d.hUv.getOrElse(i0) { 0.0 }
        val sensTexto = when { sensacao > tempAgora + 1 -> "Mais quente"; sensacao < tempAgora - 1 -> "Mais frio"; else -> "Igual à real" }
        val vis = d.hVisib.getOrElse(i0) { 10000.0 } / 1000
        val rosa = arrayOf("N", "NE", "L", "SE", "S", "SO", "O", "NO")[((d.ventoDir + 22.5) / 45).toInt() % 8]
        val detalhes = cartao("Detalhes")
        val linhas = listOf(
            listOf(
                Triple("Sensação", "$sensacao°", "thermometer" to sensTexto),
                Triple("Umidade", "${d.umidade}%", "droplet" to ""),
                Triple("Vento", "${Math.round(d.vento).toInt()} km/h", "wind_face" to rosa),
                Triple("Índice UV", "${Math.round(uv).toInt()}", "sun" to rotuloUv(uv)),
            ),
            listOf(
                Triple("Visibilidade", if (vis >= 10) "10+ km" else "${"%.1f".format(Locale.US, vis)} km", "eye" to ""),
                Triple("Ponto de orvalho", "${Math.round(d.hOrvalho.getOrElse(i0) { d.temp }).toInt()}°", "droplet" to ""),
                Triple("Pressão", "${Math.round(d.pressao).toInt()} hPa", "cloud" to ""),
                Triple("Chuva hoje", "${"%.1f".format(Locale.US, d.dChuvaMm)} mm", "cloud_with_rain" to ""),
            ),
        )
        linhas.forEach { l ->
            detalhes.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, dp(4))
                l.forEach { (rot, valor, extra) -> addView(tile(rot, valor, extra.first, extra.second), LinearLayout.LayoutParams(0, -2, 1f)) }
            })
        }
        coluna.addView(detalhes, lpCartao())

        // ---- ar, sol e lua (lado a lado)
        val trio = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        val ar = cartao("Qualidade do ar")
        if (d.aqi != null) {
            val (rot, cor) = rotuloAqi(d.aqi)
            ar.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6))
                addView(texto(44f, cor, fonteLeve).apply { text = "${d.aqi}"; includeFontPadding = false })
                addView(texto(18f, Color.WHITE, fonteMedia).apply { text = rot; setPadding(dp(12), 0, 0, 0) })
            })
            ar.addView(BarraAqi(this, d.aqi), LinearLayout.LayoutParams(-1, dp(8)))
        } else ar.addView(texto(15f, textoSuave).apply { text = "Sem dados agora"; setPadding(0, dp(10), 0, 0) })

        val sol = cartao("Sol")
        val nascerMin = minutos(d.dNascer[0]); val poenteMin = minutos(d.dPoente[0])
        sol.addView(ArcoSol(this, ((agoraMin - nascerMin).toFloat() / (poenteMin - nascerMin)).coerceIn(-0.05f, 1.05f)),
            LinearLayout.LayoutParams(-1, dp(70)).apply { topMargin = dp(4) })
        sol.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(texto(15f, textoSuave).apply { text = "↑ ${d.dNascer[0].substring(11)}" }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(texto(15f, textoSuave).apply { text = "↓ ${d.dPoente[0].substring(11)}"; gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f))
        })

        val lua = cartao("Lua")
        val agoraMs = System.currentTimeMillis()
        val partes = Lua.resumo(agoraMs, d.ponto.lat).split("  ·  ")
        lua.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0)
            addView(IconesClima.imagem(this@PrevisaoActivity, Lua.chaveIcone(agoraMs, d.ponto.lat)), LinearLayout.LayoutParams(dp(60), dp(60)))
            addView(LinearLayout(this@PrevisaoActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0)
                addView(texto(17f, Color.WHITE, fonteMedia).apply { text = partes[0].substringAfter("  ").trim() })
                addView(texto(14f, textoSuave).apply { text = "${partes.getOrElse(1) { "" }} · ${partes.getOrElse(2) { "" }}" })
            })
        })
        trio.addView(ar, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(Grade.VAO / 2) })
        trio.addView(sol, LinearLayout.LayoutParams(0, -1, 1.15f).apply { marginStart = dp(Grade.VAO / 2); marginEnd = dp(Grade.VAO / 2) })
        trio.addView(lua, LinearLayout.LayoutParams(0, -1, 1.15f).apply { marginStart = dp(Grade.VAO / 2) })
        coluna.addView(trio, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        if (voz != null) {
            val previsao = boletim(d, tempAgora, maxHoje, minHoje, chovendo)
            falar(if (bomDia) listOfNotNull(saudacao(), previsao, agendaHoje).joinToString(" ") else previsao)
        }

        // O primeiro ponto de parada: a faixa de horas (item "Agora").
        faixa.getChildAt(0)?.post { faixa.getChildAt(0).requestFocus() }
    }

    private fun tile(rotulo: String, valor: String, icone: String, extra: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, dp(10), 0)
        addView(LinearLayout(this@PrevisaoActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(IconesClima.imagem(this@PrevisaoActivity, IconesClima.PREFIXO + "3d/" + icone), LinearLayout.LayoutParams(dp(22), dp(22)))
            addView(texto(13f, textoSuave).apply { text = rotulo; setPadding(dp(6), 0, 0, 0); maxLines = 1 })
        })
        addView(texto(26f, Color.WHITE, fonteMedia).apply { text = valor; setPadding(0, dp(4), 0, 0); maxLines = 1 })
        addView(texto(13f, textoSuave).apply { text = extra.ifEmpty { " " } })
    }

    private fun rotuloUv(uv: Double) = when {
        uv < 3 -> "Baixo"; uv < 6 -> "Moderado"; uv < 8 -> "Alto"; uv < 11 -> "Muito alto"; else -> "Extremo"
    }

    private fun rotuloAqi(a: Int): Pair<String, Int> = when {
        a <= 50 -> "Boa" to 0xFF7BE0A0.toInt()
        a <= 100 -> "Moderada" to 0xFFF2E26B.toInt()
        a <= 150 -> "Ruim p/ sensíveis" to 0xFFFFB566.toInt()
        a <= 200 -> "Ruim" to 0xFFFF7A6B.toInt()
        a <= 300 -> "Muito ruim" to 0xFFD08CFF.toInt()
        else -> "Perigosa" to 0xFFFF5C7A.toInt()
    }

    /** "2026-11-02T16:15" -> minutos desde a meia-noite (só a hora do lugar; sem fuso, de propósito). */
    private fun minutos(t: String) = t.substring(11, 13).toInt() * 60 + t.substring(14, 16).toInt()

    // ---------------------------------------------------------------- cartões e foco

    private fun lpCartao() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }

    /** Um cartão com título. [focavel]: o cartão inteiro é um ponto de parada (a faixa de horas tem os próprios itens). */
    private fun cartao(titulo: String, focavel: Boolean = true) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(14), dp(20), dp(14))
        background = fundoCartao(false)
        if (focavel) {
            isFocusable = true
            setOnFocusChangeListener { v, f -> v.background = fundoCartao(f); if (f) focarCartao(v) }
        }
        addView(texto(14f, textoSuave, fonteMedia).apply { text = titulo.uppercase(); letterSpacing = 0.08f })
    }

    /**
     * Leva o cartão focado para perto do topo. A posição é medida em relação à coluna (os cartões de Ar/Sol/Lua ficam
     * dentro de uma linha, e o top deles é relativo a ela). Os primeiros cartões mostram a tela desde o topo (cabeçalho junto).
     */
    private fun focarCartao(c: View) {
        val r = Rect(0, 0, c.width, c.height)
        coluna.offsetDescendantRectToMyCoords(c, r)
        val alvo = if (r.top < dp(300)) 0 else r.top - dp(20)
        rolagem.smoothScrollTo(0, alvo)
    }

    private fun fundoCartao(foco: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(20).toFloat()
        setColor(if (foco) 0x40FFFFFF else 0x24000000)
        setStroke(dp(if (foco) 2 else 1), if (foco) 0xE6FFFFFF.toInt() else 0x22FFFFFF)
    }

    private fun itemFoco(foco: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(if (foco) 0x40FFFFFF else Color.TRANSPARENT)
        setStroke(dp(2), if (foco) 0xE6FFFFFF.toInt() else Color.TRANSPARENT)
    }

    private fun texto(sp: Float, cor: Int, fonte: Typeface? = null) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(cor)
        if (fonte != null) typeface = fonte
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    // ---------------------------------------------------------------- desenhos

    /** Área azul da chance de chuva (0-100%) nas próximas horas, com linhas em 0/50/100% e a hora a cada 4 pontos. */
    private class GraficoChuva(ctx: Context, val valores: List<Int>, val horas: List<Int>) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val d = resources.displayMetrics.density
            val esq = 34 * d; val dir = width - 6 * d; val topo = 6 * d; val base = height - 20 * d
            p.textSize = 11 * d; p.strokeWidth = d
            for (v in intArrayOf(0, 50, 100)) {
                val y = base - (base - topo) * v / 100f
                p.color = 0x33FFFFFF; p.style = Paint.Style.STROKE; c.drawLine(esq, y, dir, y, p)
                p.color = 0xB3FFFFFF.toInt(); p.style = Paint.Style.FILL; c.drawText("$v%", 0f, y + 4 * d, p)
            }
            if (valores.size < 2) return
            val passo = (dir - esq) / (valores.size - 1)
            val linha = Path(); val area = Path()
            valores.forEachIndexed { i, v ->
                val x = esq + passo * i; val y = base - (base - topo) * v / 100f
                if (i == 0) { linha.moveTo(x, y); area.moveTo(x, base); area.lineTo(x, y) } else { linha.lineTo(x, y); area.lineTo(x, y) }
            }
            area.lineTo(esq + passo * (valores.size - 1), base); area.close()
            p.style = Paint.Style.FILL
            p.shader = LinearGradient(0f, topo, 0f, base, 0xAA8EC5FF.toInt(), 0x108EC5FF, Shader.TileMode.CLAMP)
            c.drawPath(area, p); p.shader = null
            p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f * d; p.color = 0xFF8EC5FF.toInt(); c.drawPath(linha, p)
            p.style = Paint.Style.FILL; p.color = 0xB3FFFFFF.toInt(); p.textAlign = Paint.Align.CENTER
            for (i in valores.indices step 4) c.drawText("${horas[i]}h", esq + passo * i, height - 4 * d, p)
            p.textAlign = Paint.Align.LEFT
        }
    }

    /** Barra da faixa mín–máx do dia, na escala da semana toda (azul-frio na esquerda, laranja-quente na direita). */
    private class BarraTemp(ctx: Context, val mn: Double, val mx: Double, val gMin: Double, val gMax: Double) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val h = height.toFloat(); val esc = max(1.0, gMax - gMin)
            p.color = 0x28FFFFFF; c.drawRoundRect(RectF(0f, 0f, width.toFloat(), h), h / 2, h / 2, p)
            val x1 = (((mn - gMin) / esc) * width).toFloat(); val x2 = max(x1 + h, (((mx - gMin) / esc) * width).toFloat())
            p.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, intArrayOf(0xFF6FC3FF.toInt(), 0xFFFFD36B.toInt(), 0xFFFF8A4C.toInt()), null, Shader.TileMode.CLAMP)
            c.drawRoundRect(RectF(x1, 0f, x2, h), h / 2, h / 2, p); p.shader = null
        }
    }

    /** Escala de 0 a 300+ do índice de qualidade do ar (verde a vinho) com um marcador onde o valor está. */
    private class BarraAqi(ctx: Context, val aqi: Int) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val h = height.toFloat()
            p.shader = LinearGradient(0f, 0f, width.toFloat(), 0f,
                intArrayOf(0xFF7BE0A0.toInt(), 0xFFF2E26B.toInt(), 0xFFFFB566.toInt(), 0xFFFF7A6B.toInt(), 0xFFD08CFF.toInt()), null, Shader.TileMode.CLAMP)
            c.drawRoundRect(RectF(0f, 0f, width.toFloat(), h), h / 2, h / 2, p); p.shader = null
            p.color = Color.WHITE
            c.drawCircle(((aqi.coerceIn(0, 300) / 300f) * (width - h) + h / 2), h / 2, h * 0.9f, p)
        }
    }

    /** O arco do sol: tracejado do nascer ao pôr, com o sol (bolinha amarela) na posição de agora ([fracao] 0 = nascer, 1 = pôr). */
    private class ArcoSol(ctx: Context, val fracao: Float) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val d = resources.displayMetrics.density
            val raioX = width / 2f - 12 * d; val raioY = height - 10 * d; val cx = width / 2f; val cy = height - 4 * d
            p.style = Paint.Style.STROKE; p.strokeWidth = 2 * d; p.color = 0x55FFFFFF
            p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(5 * d, 5 * d), 0f)
            c.drawArc(RectF(cx - raioX, cy - raioY, cx + raioX, cy + raioY), 180f, 180f, false, p); p.pathEffect = null
            p.color = 0x55FFFFFF; c.drawLine(8 * d, cy, width - 8 * d, cy, p)
            val ang = PI * (1 - fracao.coerceIn(0f, 1f))
            val x = cx + raioX * cos(ang).toFloat(); val y = cy - raioY * sin(ang).toFloat()
            p.style = Paint.Style.FILL
            p.color = 0x55FFD36B; c.drawCircle(x, y, 11 * d, p)
            p.color = 0xFFFFD36B.toInt(); c.drawCircle(x, y, 6.5f * d, p)
        }
    }
}
