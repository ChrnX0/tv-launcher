package br.paulo.tvlauncher

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
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
    private lateinit var txtLua: TextView
    private lateinit var txtRede: TextView
    private lateinit var txtDns: TextView   // chip do rodapé: mostra o DNS em uso e abre a DnsActivity
    private lateinit var linhaApps: LinearLayout
    private lateinit var rolagem: HorizontalScrollView
    private var lista: MutableList<Item> = mutableListOf()   // apps na ordem em que aparecem na tela
    private var modoMover = false
    private var luaNoIcone = false   // true: o icone do clima ja e a lua, entao a linha da fase some
    private var pacoteMovendo: String? = null
    private var ordemAntesDeMover: List<String> = emptyList()
    private var ignorarProximoUp = false
    private var ultimoPacote: String? = null   // pacote com foco: a ordem pode mudar, então guardamos o pacote, não a posição

    // Tarefas que se repetem enquanto a tela está visível (e param em onPause, para não gastar rede à toa).
    private val tarefaClima = object : Runnable {
        override fun run() {
            buscarClima()
            ui.postDelayed(this, Config.CLIMA_A_CADA_MIN * 60_000L)
        }
    }
    private val tarefaLua = object : Runnable {
        override fun run() {
            atualizarLua()
            ui.postDelayed(this, Config.LUA_A_CADA_MIN * 60_000L)
        }
    }
    private val tarefaRede = object : Runnable {
        override fun run() {
            atualizarRede()
            ui.postDelayed(this, Config.REDE_A_CADA_SEG * 1000L)
        }
    }

    // ---------------------------------------------------------------- ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(montarTela())
        Privilegio.religarAcessibilidade(this)   // antes de falar com o Shizuku, para o serviço já estar de pé
        Privilegio.iniciar()   // liga a ponte com o Shizuku (ver Privilegio.kt)
    }

    override fun onResume() {
        super.onResume()
        preencherApps()
        ui.post(tarefaClima)
        ui.post(tarefaLua)
        ui.post(tarefaRede)
        txtDns.text = Dns.rotulo(this)   // pode ter mudado na tela de DNS
        Privilegio.religarAcessibilidade(this)
        Privilegio.verificar()   // se a autorização no Shizuku foi revogada, pede de novo
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tarefaClima)
        ui.removeCallbacks(tarefaLua)
        ui.removeCallbacks(tarefaRede)
        if (modoMover) sairDoModoMover(salvar = false)
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

        // ---- Topo: hora e data a esquerda; clima e fase da lua a direita.
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
        txtLua = texto(14f, textoSuave).apply { letterSpacing = 0.02f }
        blocoInfo.addView(linhaClima, LinearLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.END })
        blocoInfo.addView(txtLua, LinearLayout.LayoutParams(WRAP, WRAP).apply {
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

        // ---- Rodape: rede (nome, sinal, velocidade, latencia) a esquerda; botao de perfil/Configuracoes a direita.
        val base = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(56), 0, dp(56), 0)
        }
        txtRede = texto(14f, textoSuave).apply { letterSpacing = 0.01f; maxLines = 1 }
        base.addView(txtRede, LinearLayout.LayoutParams(0, WRAP, 1f))
        // (sem o botao Liberar RAM)
        base.addView(chipDns(), LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(20) })
        base.addView(botaoPerfil())
        raiz.addView(base, LinearLayout.LayoutParams(MATCH, WRAP))

        return raiz
    }

    /**
     * Fundo: a textura de res/drawable-nodpi/fundo.jpg (1920x1080, já no tamanho da tela).
     * "nodpi" manda o Android NÃO redimensionar a imagem conforme a densidade da tela.
     * Custa ~8 MB de RAM enquanto o launcher está aberto (1920 x 1080 x 4 bytes); a TV tem folga.
     */
    private fun montarFundo(): Drawable = resources.getDrawable(R.drawable.fundo, theme)
    /** Botão do rodapé: mostra o DNS em uso (ex.: "DNS: AdGuard") e abre a tela de DNS (DnsActivity). */
    private fun chipDns(): View {
        txtDns = texto(14f, textoClaro, fonteMedia).apply {
            maxLines = 1
            setPadding(dp(18), dp(10), dp(18), dp(10))
            isFocusable = true
            isClickable = true
            background = fundoVidro(false, dp(22))
            setOnClickListener { startActivity(Intent(this@MainActivity, DnsActivity::class.java)) }
            setOnFocusChangeListener { v, temFoco ->
                v.background = fundoVidro(temFoco, dp(22))
                val escala = if (temFoco) 1.08f else 1f
                v.animate().scaleX(escala).scaleY(escala).setDuration(140).start()
            }
        }
        return txtDns
    }

    private fun botaoPerfil(): View {
        val foto = ImageView(this).apply {
            setImageBitmap(Perfil.avatar(this@MainActivity, dp(56)))
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)   // recorte redondo
                }
            }
        }
        return FrameLayout(this).apply {
            contentDescription = "Configurações"
            isFocusable = true
            isClickable = true
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = anel(false)
            addView(foto, FrameLayout.LayoutParams(dp(56), dp(56)))
            setOnClickListener { abrir(Config.PACOTE_CONFIG) }
            setOnFocusChangeListener { v, temFoco ->
                v.background = anel(temFoco)
                val escala = if (temFoco) 1.12f else 1f
                v.animate().scaleX(escala).scaleY(escala).setDuration(140).start()
            }
        }
    }

    /** Anel ao redor da foto: quase invisível normalmente, branco quando o botão está focado. */
    private fun anel(focado: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.TRANSPARENT)
        setStroke(dp(3), if (focado) 0xD9FFFFFF.toInt() else 0x33FFFFFF)
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
        val manual = ordemSalva().withIndex().associate { it.value to it.index }
        return itens.values.sortedWith(
            compareBy<Item>({ manual[it.pacote] ?: Int.MAX_VALUE }, { fixo(it.pacote) }, { -(usados[it.pacote] ?: 0L) }, { posicao(it.pacote) }, { it.nome.lowercase() })
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
        lista = carregarApps().toMutableList()
        desenhar()
    }

    /** Desenha os cards na ordem de [lista] e devolve o foco ao ultimo app usado. */
    private fun desenhar() {
        linhaApps.removeAllViews()
        // (usa o campo "lista")
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
            tag = app.pacote   // identifica o card (usado no modo de mover)
            setOnClickListener { abrir(app.pacote) }
            setOnFocusChangeListener { v, temFoco ->
                estilizar(v, app.pacote, temFoco)
                
                
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

    // ---------------------------------------------------------------- mover apps (segure OK em um card)

    private val corMover = 0xFF7FD6C2.toInt()

    /** Visual de um card: normal, focado, ou "sendo movido" (borda verde-agua e maior). */
    private fun estilizar(card: View, pacote: String, focado: Boolean) {
        val movendo = modoMover && pacote == pacoteMovendo
        card.background = if (movendo) fundoMover() else fundoVidro(focado, dp(18))
        val escala = if (movendo) 1.12f else if (focado) 1.06f else 1f
        card.animate().scaleX(escala).scaleY(escala).setDuration(140).start()
    }

    private fun fundoMover(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(18).toFloat()
        setColor(0x2E7FD6C2)
        setStroke(dp(3), corMover)
    }

    /**
     * Segurar OK (ou ENTER) num card entra no modo de mover. Dentro do modo:
     * esquerda/direita troca de posicao, OK salva, Voltar cancela, para baixo volta a ordem automatica.
     * Detectamos o "segurar" pelo repeatCount: o Android repete a tecla enquanto ela esta pressionada.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val confirma = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER
        if (!modoMover) {
            val segurado = event.repeatCount >= 1 || (event.flags and KeyEvent.FLAG_LONG_PRESS) != 0
            if (confirma && event.action == KeyEvent.ACTION_DOWN && segurado) {
                val pacote = currentFocus?.tag as? String   // so os cards de app tem tag
                if (pacote != null) {
                    iniciarMover(pacote)
                    return true
                }
            }
            return super.dispatchKeyEvent(event)
        }
        val pressionou = event.action == KeyEvent.ACTION_DOWN
        val soltou = event.action == KeyEvent.ACTION_UP
        return when {
            event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> { if (pressionou) moverCartao(-1); true }
            event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> { if (pressionou) moverCartao(1); true }
            event.keyCode == KeyEvent.KEYCODE_DPAD_UP -> true
            event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (pressionou && event.repeatCount == 0) restaurarOrdemAutomatica()
                true
            }
            confirma -> {
                if (soltou) {
                    // O primeiro "soltar" e o fim do segurar que abriu o modo: ignora.
                    if (ignorarProximoUp) ignorarProximoUp = false else sairDoModoMover(salvar = true)
                }
                true
            }
            event.keyCode == KeyEvent.KEYCODE_BACK -> { if (soltou) sairDoModoMover(salvar = false); true }
            else -> super.dispatchKeyEvent(event)
        }
    }

    private fun iniciarMover(pacote: String) {
        modoMover = true
        pacoteMovendo = pacote
        ultimoPacote = pacote
        ordemAntesDeMover = lista.map { it.pacote }
        ignorarProximoUp = true
        mostrarDica(true)
        desenhar()
    }

    private fun moverCartao(delta: Int) {
        val pacote = pacoteMovendo ?: return
        val de = lista.indexOfFirst { it.pacote == pacote }
        val para = de + delta
        if (de < 0 || para !in lista.indices) return
        lista.add(para, lista.removeAt(de))
        desenhar()
    }

    private fun sairDoModoMover(salvar: Boolean) {
        if (salvar) salvarOrdem(lista.map { it.pacote })
        else lista = lista.sortedBy { ordemAntesDeMover.indexOf(it.pacote) }.toMutableList()
        modoMover = false
        pacoteMovendo = null
        mostrarDica(false)
        desenhar()
    }

    private fun restaurarOrdemAutomatica() {
        getSharedPreferences("launcher", MODE_PRIVATE).edit().remove("ordem").apply()
        modoMover = false
        pacoteMovendo = null
        mostrarDica(false)
        preencherApps()
    }

    private fun ordemSalva(): List<String> =
        getSharedPreferences("launcher", MODE_PRIVATE).getString("ordem", "").orEmpty()
            .split(",").filter { it.isNotEmpty() }

    private fun salvarOrdem(pacotes: List<String>) {
        getSharedPreferences("launcher", MODE_PRIVATE).edit().putString("ordem", pacotes.joinToString(",")).apply()
    }

    /** Durante o modo de mover, o rodape vira um guia de teclas. */
    private fun mostrarDica(ligada: Boolean) {
        if (ligada) {
            txtRede.setTextColor(corMover)
            txtRede.text = "◀ ▶ mover  ·  OK salva  ·  Voltar cancela  ·  ▼ ordem automática"
        } else {
            txtRede.setTextColor(textoSuave)
            atualizarRede()
        }
    }

    private fun abrir(pacote: String) {
        val intent = packageManager.getLeanbackLaunchIntentForPackage(pacote)
            ?: packageManager.getLaunchIntentForPackage(pacote)
            ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    // ---------------------------------------------------------------- clima, lua e rede

    private fun buscarClima() {
        rede.execute {
            try {
                val unidade = if (Config.USAR_FAHRENHEIT) "&temperature_unit=fahrenheit" else ""
                val url = "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=${Config.LATITUDE}&longitude=${Config.LONGITUDE}" +
                    "&current=temperature_2m,weather_code,precipitation,is_day" +
                    "&hourly=precipitation_probability&forecast_hours=4" +
                    "&daily=temperature_2m_max,temperature_2m_min" +
                    "&timezone=auto&forecast_days=1$unidade"
                val json = JSONObject(httpGet(url) ?: return@execute)
                val atual = json.getJSONObject("current")
                val dia = json.getJSONObject("daily")
                val temp = Math.round(atual.getDouble("temperature_2m")).toInt()
                val max = Math.round(dia.getJSONArray("temperature_2m_max").getDouble(0)).toInt()
                val min = Math.round(dia.getJSONArray("temperature_2m_min").getDouble(0)).toInt()
                // Chuva: so o que vale AGORA. O maximo do DIA inclui horas que ja passaram (dava 99% sem chover).
                val chovendoAgora = atual.optDouble("precipitation", 0.0) > 0.0
                val horas = json.getJSONObject("hourly").getJSONArray("precipitation_probability")
                var chance = -1   // maior probabilidade entre a hora atual e as proximas 2
                for (i in 0 until minOf(3, horas.length())) chance = maxOf(chance, horas.optInt(i, -1))
                val chuva = when {
                    chovendoAgora -> "chovendo agora"
                    chance >= 0 -> "chuva nas próx. 3 h: $chance%"
                    else -> ""
                }
                val codigo = atual.getInt("weather_code")
                val eDia = atual.optInt("is_day", 1) == 1   // a Open-Meteo diz se e dia no local (pelo nascer/por do sol)
                val (iconeDoTempo, descricao) = descricaoClima(codigo)
                // De noite, ceu limpo/poucas nuvens/parcial mostram a lua na fase de hoje no lugar do sol.
                // Chuva, neblina etc. (codigo 3 em diante) mantem o proprio icone.
                val luaVirouIcone = !eDia && codigo in 0..2
                val icone = if (luaVirouIcone) Lua.emoji(System.currentTimeMillis(), Config.LATITUDE) else iconeDoTempo
                val detalhe = "${Config.CIDADE}  ·  $min° – $max°" +
                    if (chuva.isNotEmpty()) "  ·  $chuva" else ""
                ui.post {
                    txtIcone.text = icone
                    txtTemp.text = "$temp°"
                    txtDescricao.text = descricao
                    txtDetalhe.text = detalhe
                    luaNoIcone = luaVirouIcone
                    atualizarLua()
                }
            } catch (e: Exception) {
                // Sem internet ou API fora do ar: mantem o ultimo valor mostrado.
            }
        }
    }

    /** A lua e calculada no aparelho (ver Lua.kt): nao usa internet. */
    private fun atualizarLua() {
        // Quando o icone do clima ja e a lua (noite, ceu visivel), a linha ficaria repetida: some.
        txtLua.visibility = if (luaNoIcone) View.GONE else View.VISIBLE
        txtLua.text = Lua.resumo(System.currentTimeMillis(), Config.LATITUDE)
    }

    private fun atualizarRede() {
        rede.execute {
            val r = Rede.medir(this)
            ui.post {
                if (modoMover) return@post
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
