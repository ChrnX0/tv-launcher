package br.paulo.tvlauncher

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
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
private const val TOKEN_OCULTOS = "ocultos"   // identifica o card "Ocultos" (nenhum pacote tem esse nome)

/**
 * A tela inicial inteira. A interface é montada por código (sem arquivos XML de layout):
 * menos arquivos, e fica fácil de ler de cima para baixo.
 */
class MainActivity : Activity() {

    /** Um app na grade: pacote (identificador), nome, imagem e se a imagem é um banner (16:9) ou só um ícone. */
    private class Item(val pacote: String, val nome: String, val imagem: Drawable, val ehBanner: Boolean)

    /**
     * Um card da linha principal: um app, uma pilha de apps, ou o card "Ocultos" (sempre o ultimo).
     * [token] identifica o card: o pacote (app), "pilha:<id>" (pilha) ou TOKEN_OCULTOS.
     */
    private sealed class Entrada(val token: String, val nome: String) {
        class App(val item: Item) : Entrada(item.pacote, item.nome)
        class Pilha(val id: String, val itens: List<Item>) :
            Entrada(Organizacao.tokenDaPilha(id), itens.joinToString(" + ") { it.nome })
        class Ocultos(val itens: List<Item>) : Entrada(TOKEN_OCULTOS, "Ocultos (${itens.size})")
    }

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
    private var instalados: List<Item> = emptyList()          // todos os apps de TV instalados (inclui os ocultos)
    private var lista: MutableList<Entrada> = mutableListOf()   // cards da linha principal, na ordem da tela
    private var modoMover = false
    private var luaNoIcone = false   // true: o icone do clima ja e a lua, entao a linha da fase some
    private var tokenMovendo: String? = null
    private var ordemAntesDeMover: List<String> = emptyList()
    private var ultimoToken: String? = null   // card com foco: a ordem pode mudar, então guardamos o token, não a posição
    private var indiceFoco = 0                // posição do card com foco: usada quando o token some (ex.: app ocultado)

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
        menuAberto?.dismiss()
        fecharPasta()
        if (modoMover) sairDoModoMover(salvar = false)
    }

    override fun onDestroy() {
        rede.shutdownNow()
        super.onDestroy()
    }

    // Apertar HOME de novo volta o foco para o primeiro app.
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.hasCategory(Intent.CATEGORY_HOME) == true) {
            ultimoToken = null
            indiceFoco = 0
        }
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

    // ---------------------------------------------------------------- apps, pilhas e ocultos

    /** Todos os apps de TV instalados, sem ordem e sem tirar os ocultos (quem organiza é montarEntradas). */
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
        return itens.values.toList()
    }

    /**
     * Transforma os apps instalados nos cards da linha: ordem (manual primeiro, depois automática),
     * pilhas (vários apps num card só) e, no fim, o card "Ocultos" se houver algum app oculto.
     */
    private fun montarEntradas(todos: List<Item>): MutableList<Entrada> {
        val ocultos = Organizacao.ocultos(this)
        val visiveis = todos.filter { it.pacote !in ocultos }
        val porPacote = visiveis.associateBy { it.pacote }

        // Pilhas: só valem com 2+ apps instalados e visíveis. Um app nunca fica em duas pilhas.
        val pilhaDe = HashMap<String, Organizacao.Pilha>()   // pacote -> pilha
        val membros = HashMap<String, List<Item>>()          // id da pilha -> apps, na ordem da pilha
        for (pilha in Organizacao.pilhas(this)) {
            val itens = pilha.apps.mapNotNull { porPacote[it] }.filter { it.pacote !in pilhaDe }
            if (itens.size < 2) continue
            membros[pilha.id] = itens
            for (item in itens) pilhaDe[item.pacote] = pilha
        }

        val usados = if (Config.ORDENAR_POR_USO_RECENTE) Uso.ultimoUso(this) else emptyMap()
        val manual = Organizacao.ordem(this).withIndex().associate { it.value to it.index }
        // Os apps de uma pilha usam o token da pilha na ordem manual; a pilha aparece onde está o seu 1º app.
        fun tokenDe(i: Item): String = pilhaDe[i.pacote]?.token ?: i.pacote
        val ordenados = visiveis.sortedWith(
            compareBy<Item>({ manual[tokenDe(it)] ?: Int.MAX_VALUE }, { fixo(it.pacote) }, { -(usados[it.pacote] ?: 0L) }, { posicao(it.pacote) }, { it.nome.lowercase() })
        )

        val entradas = mutableListOf<Entrada>()
        val pilhasJaVistas = HashSet<String>()
        for (item in ordenados) {
            val pilha = pilhaDe[item.pacote]
            if (pilha == null) {
                entradas.add(Entrada.App(item))
            } else if (pilhasJaVistas.add(pilha.id)) {
                entradas.add(Entrada.Pilha(pilha.id, membros.getValue(pilha.id)))
            }
        }
        val escondidos = todos.filter { it.pacote in ocultos }.sortedBy { it.nome.lowercase() }
        if (escondidos.isNotEmpty()) entradas.add(Entrada.Ocultos(escondidos))
        return entradas
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
        instalados = carregarApps()
        lista = montarEntradas(instalados)
        desenhar()
    }

    /** Desenha os cards na ordem de [lista] e devolve o foco ao último card usado. */
    private fun desenhar() {
        linhaApps.removeAllViews()
        lista.forEach { linhaApps.addView(criarCartao(it)) }
        if (lista.isEmpty()) return
        linhaApps.post {
            val achado = lista.indexOfFirst { it.token == ultimoToken }
            // Se o card sumiu (ex.: app ocultado), o foco fica na mesma posição, que agora é o vizinho.
            val alvo = if (achado >= 0) achado else indiceFoco.coerceIn(0, lista.lastIndex)
            linhaApps.getChildAt(alvo)?.requestFocus()
        }
    }

    private fun criarCartao(entrada: Entrada): View {
        val aoFocar = { v: View ->
            ultimoToken = entrada.token
            indiceFoco = linhaApps.indexOfChild(v)
            centralizar(v, rolagem)
        }
        return when (entrada) {
            is Entrada.App -> montarCartao(entrada.token, entrada.nome, miniaturaApp(entrada.item), { abrir(entrada.item.pacote) }, aoFocar)
            is Entrada.Pilha -> montarCartao(entrada.token, entrada.nome, miniaturaPilha(entrada.itens), { abrirPasta(entrada) }, aoFocar)
            is Entrada.Ocultos -> montarCartao(entrada.token, entrada.nome, miniaturaOcultos(), { abrirPasta(entrada) }, aoFocar)
        }
    }

    /**
     * Um card: a miniatura (184x104 dp) e o nome embaixo. Serve para a linha principal e para dentro das pastas.
     * [token] identifica o card (modo de mover e menu de opções), [aoAbrir] roda ao apertar OK.
     */
    private fun montarCartao(token: String, nome: String, miniatura: View, aoAbrir: () -> Unit, aoFocar: (View) -> Unit): View {
        val cartao = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = true
            isClickable = true
            // sem transparência nos cards: banners brancos ficariam acinzentados
            background = fundoVidro(false, dp(18))
            setPadding(dp(8), dp(8), dp(8), dp(12))
            tag = token
            setOnClickListener { aoAbrir() }
            setOnFocusChangeListener { v, temFoco ->
                estilizar(v, token, temFoco)
                if (temFoco) aoFocar(v)
            }
        }
        cartao.addView(miniatura, LinearLayout.LayoutParams(dp(184), dp(104)))
        cartao.addView(
            texto(14f, textoClaro, fonteMedia).apply {
                text = nome
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                letterSpacing = 0.01f
            },
            LinearLayout.LayoutParams(dp(184), WRAP).apply { topMargin = dp(10) }
        )
        cartao.layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(16) }
        return cartao
    }

    /** Imagem de um app com cantos arredondados. */
    private fun miniaturaApp(app: Item): View = ImageView(this).apply {
        setImageDrawable(copia(app.imagem))
        scaleType = if (app.ehBanner) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        arredondar(this, 12)
    }

    /** Pilha: o primeiro app na frente e até 2 atrás, deslocados para cima e para a direita, como uma pilha de cartas. */
    private fun miniaturaPilha(itens: List<Item>): View {
        val camadas = itens.take(3)
        val caixa = FrameLayout(this)
        for (i in camadas.indices.reversed()) {   // de trás para a frente: o último adicionado fica por cima
            val app = camadas[i]
            val vista = ImageView(this).apply {
                setImageDrawable(copia(app.imagem))
                scaleType = if (app.ehBanner) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(0xFF1C2421.toInt())   // opaco: a camada de trás não pode aparecer através da da frente
                alpha = if (i == 0) 1f else 0.8f - 0.25f * (i - 1)
                arredondar(this, 10)
            }
            // frente: 164x92 colada embaixo; cada camada de trás sobe 6 dp e vai 10 dp para a direita (total 184x104)
            caixa.addView(vista, FrameLayout.LayoutParams(dp(164), dp(92)).apply {
                leftMargin = dp(10) * i
                topMargin = dp(12) - dp(6) * i
            })
        }
        return caixa
    }

    private fun miniaturaOcultos(): View = FrameLayout(this).apply {
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(0x26FFFFFF)
        }
        addView(texto(40f, Color.WHITE).apply {
            text = "🙈"
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    /** Recorta a view com cantos arredondados (clipToOutline recorta o conteúdo no formato do contorno). */
    private fun arredondar(v: View, raioDp: Int) {
        v.clipToOutline = true
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(raioDp).toFloat())
            }
        }
    }

    /** Cópia independente da imagem: o mesmo Drawable em duas views ao mesmo tempo pode se atrapalhar. */
    private fun copia(d: Drawable): Drawable = d.constantState?.newDrawable(resources)?.mutate() ?: d

    /** Rola [barra] para deixar o card focado no centro (nas pontas, respeita a margem de 56dp). */
    private fun centralizar(card: View, barra: HorizontalScrollView) {
        val alvo = card.left + card.width / 2 - barra.width / 2 + barra.paddingLeft
        barra.smoothScrollTo(alvo, 0)
    }

    // ---------------------------------------------------------------- pastas (pilhas e ocultos)

    private var pasta: Dialog? = null
    private var pastaToken: String? = null   // qual pasta está aberta: "pilha:<id>" ou TOKEN_OCULTOS

    /** Abre uma pilha (ou os ocultos) numa janela por cima da tela inicial, como uma pasta. Voltar fecha. */
    private fun abrirPasta(entrada: Entrada) {
        val itens = when (entrada) {
            is Entrada.Pilha -> entrada.itens
            is Entrada.Ocultos -> entrada.itens
            is Entrada.App -> return
        }
        fecharPasta()
        val linha = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
        }
        val barra = HorizontalScrollView(this).apply {
            clipChildren = false
            clipToPadding = false
            isHorizontalScrollBarEnabled = false
            setPadding(dp(56), dp(24), dp(56), dp(24))
            addView(linha)
        }
        for (app in itens) {
            linha.addView(montarCartao(app.pacote, app.nome, miniaturaApp(app),
                { fecharPasta(); abrir(app.pacote) },
                { v -> centralizar(v, barra) }))
        }
        val ehOcultos = entrada is Entrada.Ocultos
        val conteudo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(texto(24f, Color.WHITE, fonteLeve).apply {
                text = if (ehOcultos) "Apps ocultos" else entrada.nome
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(56), 0, dp(56), 0)
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(barra, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(texto(13f, textoSuave).apply {
                text = if (ehOcultos) "OK abre  ·  segure OK para mostrar na tela inicial  ·  Voltar fecha"
                       else "OK abre  ·  segure OK para opções  ·  Voltar fecha"
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        val janela = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        janela.setContentView(conteudo)
        janela.window?.apply {
            setBackgroundDrawable(ColorDrawable(0xF70E1311.toInt()))   // escurece a tela inicial por trás
            setLayout(MATCH, MATCH)
        }
        // A janela tem as próprias teclas: o "segurar OK" precisa ser detectado aqui também.
        janela.setOnKeyListener { _, _, ev -> tratarSegurar(ev, janela.currentFocus) }
        janela.setOnDismissListener {
            if (pasta === janela) { pasta = null; pastaToken = null }
        }
        pasta = janela
        pastaToken = entrada.token
        janela.show()
        barra.post { linha.getChildAt(0)?.requestFocus() }
    }

    private fun fecharPasta() {
        pasta?.dismiss()
        pasta = null
        pastaToken = null
    }

    // ---------------------------------------------------------------- menu de opções (segure OK em um card)

    private class Opcao(val rotulo: String, val acao: () -> Unit)

    private var menuAberto: AlertDialog? = null
    private var engolirUp = false   // true entre o "segurar OK" e o menu fechar: o "soltar" da mesma pressão não pode virar clique

    /**
     * Segurar OK (ou ENTER) num card abre o menu de opções. Detectamos o "segurar" pelo repeatCount:
     * o Android repete a tecla enquanto ela está pressionada. Devolve true se o evento foi tratado aqui.
     */
    private fun tratarSegurar(event: KeyEvent, focado: View?): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_DPAD_CENTER && event.keyCode != KeyEvent.KEYCODE_ENTER) return false
        if (engolirUp) {   // o menu está abrindo: ignora as repetições e o "soltar" da mesma pressão
            if (event.action == KeyEvent.ACTION_UP) engolirUp = false
            return true
        }
        val segurado = event.repeatCount >= 1 || (event.flags and KeyEvent.FLAG_LONG_PRESS) != 0
        if (event.action != KeyEvent.ACTION_DOWN || !segurado) return false
        val token = focado?.tag as? String ?: return false   // só os cards têm tag
        if (token == TOKEN_OCULTOS) return false              // o card "Ocultos" não tem menu
        engolirUp = true
        abrirMenu(token)
        return true
    }

    private fun abrirMenu(token: String) {
        val dentroDe = pastaToken
        val entrada = lista.firstOrNull { it.token == token }
        val titulo: String
        val opcoes = mutableListOf<Opcao>()
        if (dentroDe == TOKEN_OCULTOS) {
            titulo = nomeDe(token)
            opcoes += Opcao("Mostrar na tela inicial") { Organizacao.mostrar(this, token); atualizarAposMudanca(token) }
        } else if (dentroDe != null) {   // app dentro de uma pilha
            titulo = nomeDe(token)
            opcoes += Opcao("Tirar da pilha") { Organizacao.tirarDaPilha(this, token); atualizarAposMudanca(token) }
            opcoes += Opcao("Ocultar") { Organizacao.ocultar(this, token); atualizarAposMudanca(null) }
        } else if (entrada is Entrada.App) {
            titulo = entrada.nome
            opcoes += Opcao("Mover") { iniciarMover(token) }
            opcoes += Opcao("Empilhar com…") { escolherParaEmpilhar(entrada) }
            opcoes += Opcao("Ocultar") { Organizacao.ocultar(this, token); atualizarAposMudanca(null) }
            opcoes += Opcao("Ordem automática") { restaurarOrdemAutomatica() }
        } else if (entrada is Entrada.Pilha) {
            titulo = entrada.nome
            opcoes += Opcao("Mover") { iniciarMover(token) }
            opcoes += Opcao("Desfazer pilha") {
                Organizacao.desfazerPilha(this, token)
                atualizarAposMudanca(entrada.itens.first().pacote)
            }
            opcoes += Opcao("Ordem automática") { restaurarOrdemAutomatica() }
        } else {
            return
        }
        menuAberto = AlertDialog.Builder(this)
            .setTitle(titulo)
            .setItems(opcoes.map { it.rotulo }.toTypedArray()) { _, i -> opcoes[i].acao() }
            .setOnDismissListener { engolirUp = false }
            .show()
    }

    /** Segundo passo de "Empilhar com…": lista os outros cards e junta o escolhido com [origem]. */
    private fun escolherParaEmpilhar(origem: Entrada.App) {
        val candidatos = lista.filter { it !is Entrada.Ocultos && it.token != origem.token }
        if (candidatos.isEmpty()) return
        menuAberto = AlertDialog.Builder(this)
            .setTitle("Empilhar ${origem.nome} com…")
            .setItems(candidatos.map { it.nome }.toTypedArray()) { _, i ->
                ultimoToken = Organizacao.empilhar(this, origem.token, candidatos[i].token)
                preencherApps()
            }
            .show()
    }

    /** Depois de empilhar/ocultar/etc.: fecha a pasta aberta, relê tudo e leva o foco para [foco] (ou perto de onde estava). */
    private fun atualizarAposMudanca(foco: String?) {
        fecharPasta()
        if (foco != null) ultimoToken = foco
        preencherApps()
    }

    private fun nomeDe(pacote: String): String = instalados.firstOrNull { it.pacote == pacote }?.nome ?: pacote

    // ---------------------------------------------------------------- mover cards (menu > Mover)

    private val corMover = 0xFF7FD6C2.toInt()

    /** Visual de um card: normal, focado, ou "sendo movido" (borda verde-agua e maior). */
    private fun estilizar(card: View, token: String, focado: Boolean) {
        val movendo = modoMover && token == tokenMovendo
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
     * Segurar OK num card abre o menu (ver tratarSegurar). No modo de mover (menu > Mover):
     * esquerda/direita troca de posicao, OK salva, Voltar cancela, para baixo volta a ordem automatica.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val confirma = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER
        if (!modoMover) {
            if (tratarSegurar(event, currentFocus)) return true
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
                if (soltou) sairDoModoMover(salvar = true)
                true
            }
            event.keyCode == KeyEvent.KEYCODE_BACK -> { if (soltou) sairDoModoMover(salvar = false); true }
            else -> super.dispatchKeyEvent(event)
        }
    }

    private fun iniciarMover(token: String) {
        modoMover = true
        tokenMovendo = token
        ultimoToken = token
        ordemAntesDeMover = lista.map { it.token }
        mostrarDica(true)
        desenhar()
    }

    private fun moverCartao(delta: Int) {
        val token = tokenMovendo ?: return
        val de = lista.indexOfFirst { it.token == token }
        val para = de + delta
        if (de < 0 || para !in lista.indices || lista[para] is Entrada.Ocultos) return   // "Ocultos" fica sempre no fim
        lista.add(para, lista.removeAt(de))
        desenhar()
    }

    private fun sairDoModoMover(salvar: Boolean) {
        if (salvar) Organizacao.salvarOrdem(this, lista.filter { it !is Entrada.Ocultos }.map { it.token })
        else lista = lista.sortedBy { ordemAntesDeMover.indexOf(it.token) }.toMutableList()
        modoMover = false
        tokenMovendo = null
        mostrarDica(false)
        desenhar()
    }

    private fun restaurarOrdemAutomatica() {
        Organizacao.limparOrdem(this)
        modoMover = false
        tokenMovendo = null
        mostrarDica(false)
        preencherApps()
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
