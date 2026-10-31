package br.paulo.tvlauncher

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

/**
 * A tela inicial inteira. A interface é montada por código (sem arquivos XML de layout):
 * menos arquivos, e fica fácil de ler de cima para baixo.
 */
class MainActivity : Activity() {

    /** Um app na grade: pacote (identificador), nome, imagem e se a imagem é um banner (16:9) ou só um ícone. */
    private class Item(val pacote: String, val nome: String, val imagem: Drawable, val ehBanner: Boolean)

    private val ui = Handler(Looper.getMainLooper())          // roda código na thread da tela
    private val rede = Executors.newSingleThreadExecutor()    // roda chamadas de internet fora da thread da tela

    // Paleta: tons frios e quase neutros, para a tela não competir com os banners dos apps.
    private val textoClaro = 0xFFE8EEEA.toInt()
    private val textoSuave = 0xFF93A19A.toInt()
    private val fonteLeve = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val fonteMedia = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private lateinit var txtIcone: TextView
    private lateinit var txtTemp: TextView
    private lateinit var txtDescricao: TextView
    private lateinit var txtDetalhe: TextView
    private lateinit var txtCotacao: TextView
    private lateinit var txtSistema: TextView
    private lateinit var txtRede: TextView
    private lateinit var linhaApps: LinearLayout
    private lateinit var rolagem: HorizontalScrollView
    private var ultimoPacote: String? = null   // pacote com foco: a ordem pode mudar, então guardamos o pacote, não a posição

    // Tarefas que se repetem enquanto a tela está visível (e param em onPause, para não gastar rede à toa).
    private val tarefaClima = object : Runnable {
        override fun run() {
            buscarClima()
            ui.postDelayed(this, Config.CLIMA_A_CADA_MIN * 60_000L)
        }
    }
    private val tarefaCotacao = object : Runnable {
        override fun run() {
            buscarCotacao()
            ui.postDelayed(this, Config.COTACAO_A_CADA_MIN * 60_000L)
        }
    }
    private val tarefaRede = object : Runnable {
        override fun run() {
            atualizarRede()
            ui.postDelayed(this, Config.REDE_A_CADA_SEG * 1000L)
        }
    }
    private val tarefaSistema = object : Runnable {
        override fun run() {
            atualizarSistema()
            ui.postDelayed(this, Config.SISTEMA_A_CADA_SEG * 1000L)
        }
    }

    // ---------------------------------------------------------------- ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(montarTela())
    }

    override fun onResume() {
        super.onResume()
        preencherApps()
        ui.post(tarefaClima)
        ui.post(tarefaCotacao)
        ui.post(tarefaSistema)
        ui.post(tarefaRede)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tarefaClima)
        ui.removeCallbacks(tarefaCotacao)
        ui.removeCallbacks(tarefaSistema)
        ui.removeCallbacks(tarefaRede)
    }

    override fun onDestroy() {
        rede.shutdownNow()
        super.onDestroy()
    }

    // Apertar HOME de novo volta o foco para o primeiro app.
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.hasCategory(Intent.CATEGORY_HOME) == true) ultimoPacote = null
    }

    // Uma tela inicial não "fecha" com o botão Voltar.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {}

    // ---------------------------------------------------------------- montagem da tela

    private fun montarTela(): View {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Margem de segurança de TV só em cima/embaixo; as laterais ficam em cada bloco,
            // assim a linha de apps pode rolar até a borda da tela.
            setPadding(0, dp(30), 0, dp(26))
            background = montarFundo()
        }

        // ---- Topo: hora e data à esquerda; clima e cotação à direita.
        val topo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(56), 0, dp(56), 0)
        }

        val blocoHora = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        blocoHora.addView(TextClock(this).apply {
            format12Hour = "HH:mm"
            format24Hour = "HH:mm"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 72f)
            setTextColor(Color.WHITE)
            typeface = fonteLeve
            letterSpacing = -0.02f
            includeFontPadding = false
        })
        blocoHora.addView(TextClock(this).apply {
            format12Hour = "EEEE, d 'de' MMMM"
            format24Hour = "EEEE, d 'de' MMMM"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(textoSuave)
            letterSpacing = 0.03f
            setPadding(dp(4), dp(6), 0, 0)
        })
        topo.addView(blocoHora, LinearLayout.LayoutParams(0, WRAP, 1f))

        val blocoInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        val linhaClima = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        txtIcone = texto(34f, Color.WHITE)
        txtTemp = texto(54f, Color.WHITE, fonteLeve).apply {
            setPadding(dp(14), 0, dp(18), 0)
            includeFontPadding = false
            text = "…"
        }
        txtDescricao = texto(17f, textoClaro, fonteMedia)
        txtDetalhe = texto(13f, textoSuave).apply { setPadding(0, dp(2), 0, 0) }
        val colunaClima = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        colunaClima.addView(txtDescricao)
        colunaClima.addView(txtDetalhe)
        linhaClima.addView(txtIcone)
        linhaClima.addView(txtTemp)
        linhaClima.addView(colunaClima)
        txtCotacao = texto(14f, textoSuave).apply { letterSpacing = 0.02f }
        blocoInfo.addView(linhaClima, LinearLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.END })
        blocoInfo.addView(txtCotacao, LinearLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.END
            topMargin = dp(8)
        })
        topo.addView(blocoInfo, LinearLayout.LayoutParams(WRAP, WRAP))
        raiz.addView(topo, LinearLayout.LayoutParams(MATCH, WRAP))

        // Espaço elástico empurra a linha de apps para o meio da tela.
        raiz.addView(View(this), LinearLayout.LayoutParams(MATCH, 0, 1f))

        // ---- Linha de apps (rola na horizontal).
        rolagem = HorizontalScrollView(this).apply {
            clipChildren = false    // senão a rolagem corta o card focado quando ele cresce
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(56), dp(24), dp(56), dp(24))
        }
        linhaApps = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
        }
        rolagem.addView(linhaApps)
        raiz.addView(rolagem, LinearLayout.LayoutParams(MATCH, WRAP))

        raiz.addView(View(this), LinearLayout.LayoutParams(MATCH, 0, 1f))

        // ---- Rodapé: RAM/espaço livres à esquerda, botão de Configurações à direita.
        val base = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(56), 0, dp(56), 0)
        }
        txtSistema = texto(13f, textoSuave).apply { letterSpacing = 0.01f; maxLines = 1 }
        val colunaSistema = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        colunaSistema.addView(txtSistema)
        txtRede = texto(13f, textoSuave).apply { letterSpacing = 0.01f; maxLines = 1 }
        colunaSistema.addView(txtRede, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) })
        base.addView(colunaSistema, LinearLayout.LayoutParams(0, WRAP, 1f))
        base.addView(botaoLiberarRam(), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(14) })
        base.addView(botaoConfiguracoes())
        raiz.addView(base, LinearLayout.LayoutParams(MATCH, WRAP))

        return raiz
    }

    /**
     * Fundo escuro: degradê radial (centro levemente esverdeado, bordas quase pretas)
     * + uma textura fina de pontos, desenhada por código num bloquinho de 8x8 pixels que se repete.
     * Um bitmap de tela cheia (1920x1080x4 bytes) ocuparia ~8 MB; este ocupa 256 bytes.
     */
    private fun montarFundo(): Drawable {
        val base = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = resources.displayMetrics.widthPixels * 0.8f
            setGradientCenter(0.5f, 0.42f)
            colors = intArrayOf(0xFF111C18.toInt(), 0xFF070B0A.toInt(), 0xFF030404.toInt())
        }
        val lado = 8
        val bloco = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888)
        val ponto = Color.argb(30, 170, 210, 190)
        for ((x, y) in listOf(2 to 2, 3 to 2, 2 to 3, 3 to 3, 6 to 6, 7 to 6, 6 to 7, 7 to 7)) {
            bloco.setPixel(x, y, ponto)
        }
        bloco.density = Bitmap.DENSITY_NONE   // sem escalar o bloquinho
        val textura = BitmapDrawable(resources, bloco).apply {
            setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
        return LayerDrawable(arrayOf(base, textura))
    }

    private fun botaoLiberarRam(): View = texto(14f, textoClaro, fonteMedia).apply {
        text = "Liberar RAM"
        isFocusable = true
        isClickable = true
        letterSpacing = 0.02f
        setPadding(dp(20), dp(10), dp(22), dp(10))
        background = fundoVidro(false, dp(24))
        setOnClickListener { liberarRam() }
        setOnFocusChangeListener { v, temFoco -> v.background = fundoVidro(temFoco, dp(24)) }
    }

    private fun liberarRam() {
        txtSistema.text = "Liberando…"
        rede.execute {
            val liberadoMb = Memoria.liberar(this)
            ui.post {
                txtSistema.text = if (liberadoMb > 0) "Liberados $liberadoMb MB" else "Nada a liberar"
                ui.postDelayed({ atualizarSistema() }, 4000)
            }
        }
    }

    private fun botaoConfiguracoes(): View = texto(14f, textoClaro, fonteMedia).apply {
        text = "⚙   Configurações"
        isFocusable = true
        isClickable = true
        letterSpacing = 0.02f
        setPadding(dp(20), dp(10), dp(22), dp(10))
        background = fundoVidro(false, dp(24))
        setOnClickListener { abrir(Config.PACOTE_CONFIG) }
        setOnFocusChangeListener { v, temFoco -> v.background = fundoVidro(temFoco, dp(24)) }
    }

    // ---------------------------------------------------------------- apps

    private fun carregarApps(): List<Item> {
        val pm = packageManager
        // "Quais apps aparecem no menu de uma TV?" = os que declaram a categoria LEANBACK_LAUNCHER.
        val consulta = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val itens = LinkedHashMap<String, Item>()
        for (ri in pm.queryIntentActivities(consulta, 0)) {
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName || pkg == Config.PACOTE_CONFIG || pkg in itens) continue
            // FLAG_SYSTEM == 0 significa "instalado por você". Os de sistema só entram se estiverem em SEMPRE_MOSTRAR.
            val instaladoPorVoce = (ri.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0
            if (!instaladoPorVoce && pkg !in Config.SEMPRE_MOSTRAR) continue
            val banner = ri.activityInfo.loadBanner(pm)
            itens[pkg] = Item(pkg, ri.loadLabel(pm).toString(), banner ?: ri.loadIcon(pm), banner != null)
        }
        val usados = if (Config.ORDENAR_POR_USO_RECENTE) Uso.ultimoUso(this) else emptyMap()
        return itens.values.sortedWith(
            compareBy<Item>({ fixo(it.pacote) }, { -(usados[it.pacote] ?: 0L) }, { posicao(it.pacote) }, { it.nome.lowercase() })
        )
    }

    private fun fixo(pacote: String): Int {
        val i = Config.FIXOS.indexOf(pacote)
        return if (i >= 0) i else Int.MAX_VALUE
    }

    private fun posicao(pacote: String): Int {
        val i = Config.ORDEM.indexOf(pacote)
        return if (i >= 0) i else Int.MAX_VALUE
    }

    private fun preencherApps() {
        linhaApps.removeAllViews()
        val lista = carregarApps()
        lista.forEachIndexed { indice, app -> linhaApps.addView(criarCartao(app, indice)) }
        linhaApps.post {
            val alvo = lista.indexOfFirst { it.pacote == ultimoPacote }.coerceAtLeast(0)
            linhaApps.getChildAt(alvo)?.requestFocus()
        }
    }

    private fun criarCartao(app: Item, indice: Int): View {
        val cartao = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = true
            isClickable = true
            // sem transparência nos cards: banners brancos ficariam acinzentados
            background = fundoVidro(false, dp(18))
            setPadding(dp(8), dp(8), dp(8), dp(12))
            setOnClickListener { abrir(app.pacote) }
            setOnFocusChangeListener { v, temFoco ->
                v.background = fundoVidro(temFoco, dp(18))
                val escala = if (temFoco) 1.06f else 1f
                v.animate().scaleX(escala).scaleY(escala).setDuration(140).start()
                if (temFoco) {
                    ultimoPacote = app.pacote
                    centralizar(v)
                }
            }
        }
        // A imagem do app com cantos arredondados (clipToOutline recorta a imagem no formato do contorno).
        val imagem = ImageView(this).apply {
            setImageDrawable(app.imagem)
            scaleType = if (app.ehBanner) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                }
            }
        }
        cartao.addView(imagem, LinearLayout.LayoutParams(dp(184), dp(104)))
        cartao.addView(
            texto(14f, textoClaro, fonteMedia).apply {
                text = app.nome
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                letterSpacing = 0.01f
            },
            LinearLayout.LayoutParams(dp(184), WRAP).apply { topMargin = dp(10) }
        )
        return cartao.also {
            it.layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(16) }
        }
    }

    /** Rola a linha para deixar o card focado no centro (nas pontas, respeita a margem de 56dp). */
    private fun centralizar(card: View) {
        val alvo = card.left + card.width / 2 - rolagem.width / 2 + rolagem.paddingLeft
        rolagem.smoothScrollTo(alvo, 0)
    }

    private fun abrir(pacote: String) {
        val intent = packageManager.getLeanbackLaunchIntentForPackage(pacote)
            ?: packageManager.getLaunchIntentForPackage(pacote)
            ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    // ---------------------------------------------------------------- clima, cotação, sistema

    private fun buscarClima() {
        rede.execute {
            try {
                val unidade = if (Config.USAR_FAHRENHEIT) "&temperature_unit=fahrenheit" else ""
                val url = "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=${Config.LATITUDE}&longitude=${Config.LONGITUDE}" +
                    "&current=temperature_2m,weather_code" +
                    "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                    "&timezone=auto&forecast_days=1$unidade"
                val json = JSONObject(httpGet(url) ?: return@execute)
                val atual = json.getJSONObject("current")
                val dia = json.getJSONObject("daily")
                val temp = Math.round(atual.getDouble("temperature_2m")).toInt()
                val max = Math.round(dia.getJSONArray("temperature_2m_max").getDouble(0)).toInt()
                val min = Math.round(dia.getJSONArray("temperature_2m_min").getDouble(0)).toInt()
                val chuva = dia.getJSONArray("precipitation_probability_max").optInt(0, -1)
                val (icone, descricao) = descricaoClima(atual.getInt("weather_code"))
                val detalhe = "${Config.CIDADE}  ·  $min° – $max°" + if (chuva >= 0) "  ·  chuva $chuva%" else ""
                ui.post {
                    txtIcone.text = icone
                    txtTemp.text = "$temp°"
                    txtDescricao.text = descricao
                    txtDetalhe.text = detalhe
                }
            } catch (e: Exception) {
                // Sem internet ou API fora do ar: mantém o último valor mostrado.
            }
        }
    }

    private fun buscarCotacao() {
        rede.execute {
            val valor = runCatching { cotacaoAwesome() }.getOrNull()
                ?: runCatching { cotacaoEr() }.getOrNull()
            if (valor != null) {
                val texto = "US$ 1  =  R$ " + String.format(Locale("pt", "BR"), "%.2f", valor)
                ui.post { txtCotacao.text = texto }
            }
        }
    }

    private fun cotacaoAwesome(): Double? {
        val corpo = httpGet("https://economia.awesomeapi.com.br/json/last/USD-BRL") ?: return null
        return JSONObject(corpo).getJSONObject("USDBRL").getString("bid").toDouble()
    }

    private fun cotacaoEr(): Double? {
        val corpo = httpGet("https://open.er-api.com/v6/latest/USD") ?: return null
        return JSONObject(corpo).getJSONObject("rates").getDouble("BRL")
    }

    private fun atualizarRede() {
        rede.execute {
            val r = Rede.medir(this)
            ui.post {
                txtRede.text = r.texto
                txtRede.setTextColor(
                    when (r.nivel) {
                        0 -> textoSuave
                        1 -> 0xFFE8C66A.toInt()    // amarelo: atenção
                        else -> 0xFFE88A7A.toInt() // vermelho: ruim
                    }
                )
            }
        }
    }

    private fun atualizarSistema() {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val ramLivreMb = info.availMem / (1024L * 1024L)
        val disco = StatFs(Environment.getDataDirectory().path)
        val discoLivreGb = disco.availableBytes / 1_073_741_824.0
        txtSistema.text = String.format(
            Locale("pt", "BR"), "Livre: %d MB RAM  ·  %.1f GB disco", ramLivreMb, discoLivreGb
        )
    }

    /** Códigos de clima da Open-Meteo (padrão WMO) -> emoji + texto. */
    private fun descricaoClima(codigo: Int): Pair<String, String> = when (codigo) {
        0 -> "☀️" to "Céu limpo"
        1 -> "🌤️" to "Poucas nuvens"
        2 -> "⛅" to "Parcialmente nublado"
        3 -> "☁️" to "Nublado"
        45, 48 -> "🌫️" to "Neblina"
        in 51..57 -> "🌦️" to "Garoa"
        in 61..67 -> "🌧️" to "Chuva"
        in 71..77, 85, 86 -> "❄️" to "Neve"
        in 80..82 -> "🌧️" to "Pancadas de chuva"
        95, 96, 99 -> "⛈️" to "Tempestade"
        else -> "🌡️" to "—"
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

    // ---------------------------------------------------------------- utilidades

    private fun dp(valor: Int): Int = (valor * resources.displayMetrics.density + 0.5f).toInt()

    private fun texto(sp: Float, cor: Int, fonte: Typeface? = null): TextView = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(cor)
        if (fonte != null) typeface = fonte
    }

    /**
     * Fundo "vidro": branco bem translúcido com uma borda fina.
     * Quando focado, o vidro fica mais claro e a borda quase opaca.
     */
    private fun fundoVidro(focado: Boolean, raio: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = raio.toFloat()
        setColor(if (focado) 0x2EFFFFFF else 0x12FFFFFF)
        setStroke(dp(2), if (focado) 0xD9FFFFFF.toInt() else 0x1FFFFFFF)
    }
}
