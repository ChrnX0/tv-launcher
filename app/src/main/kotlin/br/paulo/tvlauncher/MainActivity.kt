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
import android.util.Log
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
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val TOKEN_OCULTOS = "ocultos"   // identifica o card "Ocultos" (nenhum pacote tem esse nome)
private const val ID_PREVIA = "previa"       // id da pilha provisoria mostrada enquanto se arrasta um app para cima de outro
private const val ICONE_ECLIPSE = "eclipse"  // no lugar de um emoji: sol coberto pela lua (IconeEclipse). So aparece durante um eclipse solar REAL (ver Eclipse.kt e o Config.kt)

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
     * Na pilha, [frente] e a posicao (em [itens]) do app que aparece na frente e que o OK abre.
     */
    private sealed class Entrada(val token: String, val nome: String) {
        class App(val item: Item) : Entrada(item.pacote, item.nome)
        class Pilha(val id: String, val itens: List<Item>, val frente: Int = 0) :
            Entrada(Organizacao.tokenDaPilha(id), itens[frente].nome) {
            val daFrente: Item get() = itens[frente]
            val titulo: String get() = itens.joinToString(" + ") { it.nome }
        }
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
    private lateinit var txtMax: TextView   // "↑ 32°" ao lado da temperatura
    private lateinit var txtMin: TextView   // "↓ 18°"
    private lateinit var txtInfo: List<TextView>   // as 3 linhas de informacao do topo (colunas 9-11): lua, umidade, chuva
    private var textoUmidade = ""   // "💧  Umidade 38%"
    private var textoChuva = ""   // "Chuva 91% nas próx. 3 h" ou "Chovendo agora"; vai na 3a linha do clima, junto da lua
    private lateinit var iconeEclipse: IconeEclipse   // sol parcialmente coberto pela lua, desenhado em codigo
    private lateinit var txtDica: TextView            // guia de teclas do modo mover (some fora dele)
    private lateinit var fotoPerfil: ImageView        // a bolinha do perfil, no canto superior direito
    private var popup: PopupPerfil? = null            // menu da bolinha: perfis, Configuracoes, DNS, Wi-Fi
    private var ultimaRede: Rede.Resultado? = null    // ultima medicao de rede (o menu mostra esta ao abrir)
    private lateinit var linhaApps: LinearLayout
    private lateinit var rolagem: HorizontalScrollView
    private var instalados: List<Item> = emptyList()          // todos os apps de TV instalados (inclui os ocultos)
    private var lista: MutableList<Entrada> = mutableListOf()   // cards da linha principal, na ordem da tela
    private var modoMover = false
    private var luaNoIcone = false   // true: o icone do clima ja e a lua, entao a linha da fase some
    private var iconeClima: String? = null        // icone que o CLIMA mandou na ultima busca (o eclipse so o substitui enquanto dura)
    private var ceuCoberto = false                // nuvens ou chuva (codigo > 2): o eclipse nao seria visto, entao fica o icone do clima
    private var eclipseAgora: Eclipse.Estado? = null   // null = nao ha eclipse neste minuto
    private var tokenMovendo: String? = null   // card que destaca em verde-agua no modo mover (o arrastado, ou a pre-pilha)
    private var arrastado: Entrada? = null     // card que esta sendo arrastado (a lista [lista] nao muda ate confirmar)
    private var posMover = 0                   // posicao de teste do arrastado: par = ao lado, impar = em cima de um card
    private var ignorarProximoUp = false       // true logo apos segurar OK: o "soltar" dessa pressao nao confirma
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
    private val tarefaEclipse = object : Runnable {
        override fun run() {
            atualizarEclipse()
            ui.postDelayed(this, Config.ECLIPSE_A_CADA_SEG * 1000L)
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
        Privilegio.vigiarDepuracaoSemFio(this)   // religa a Depuração sem fio sempre que o Android a desligar
    }

    override fun onResume() {
        super.onResume()
        preencherApps()
        ui.post(tarefaClima)
        ui.post(tarefaLua)
        ui.post(tarefaEclipse)
        ui.post(tarefaRede)
        atualizarFotoPerfil()   // a conta ativa ou uma foto pode ter mudado
        Privilegio.religarAcessibilidade(this)
        Privilegio.religarDepuracaoSemFio(this)   // o Android pode ter desligado a Depuração sem fio no reinício
        Despertador.agendar(this)   // garante o proximo alarme agendado (barato: so le as preferencias)
        Privilegio.verificar()   // se a autorização no Shizuku foi revogada, pede de novo
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tarefaClima)
        ui.removeCallbacks(tarefaLua)
        ui.removeCallbacks(tarefaEclipse)
        ui.removeCallbacks(tarefaRede)
        menuAberto?.dismiss()
        popup?.fechar()
        previsao?.fechar()
        fecharPasta()
        if (modoMover) cancelarMover()
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
        // Tudo na grade (ver Grade.kt): o topo comeca em Grade.TOPO e a linha de apps em Grade.LINHA_APPS,
        // com as laterais em Grade.MARGEM. Nada de espacos elasticos: cada coisa tem lugar fixo.
        val raiz = FrameLayout(this).apply {
            clipChildren = false   // a bolinha e o card focado crescem um pouco alem da propria caixa
            clipToPadding = false
        }

        // ---- Topo, nas colunas da grade (ver Grade.kt):
        //   1-4: hora / data     5-8: temperatura (+ maxima/minima ao lado) / descricao     9-11: tres linhas de info     12: bolinha
        // Hora e temperatura tem a mesma fonte e tamanho; data e descricao tambem: tudo cai nas mesmas alturas.
        // As 3 linhas de informacao (lua, umidade, chuva) se dividem igualmente na altura dos blocos ao lado.
        // Hora e temperatura tem a mesma fonte e tamanho; data e descricao tambem: tudo cai nas mesmas alturas.
        val topo = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }

        // A hora e um botao: OK abre o despertador (AlarmesActivity). Mesma folga de foco do clima (ver abaixo).
        val folgaFoco = 12
        val blocoHora = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(folgaFoco), dp(folgaFoco), dp(folgaFoco), dp(folgaFoco))
            isFocusable = true
            isClickable = true
            setOnClickListener { startActivity(Intent(this@MainActivity, AlarmesActivity::class.java)) }
            setOnFocusChangeListener { v, temFoco -> v.background = if (temFoco) realceFoco() else null }
        }
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
            setPadding(0, dp(4), 0, 0)
        })
        topo.addView(blocoHora, FrameLayout.LayoutParams(dp(Grade.largura(4) + 2 * folgaFoco), WRAP).apply {
            leftMargin = dp(Grade.x(1) - folgaFoco)
            topMargin = -dp(folgaFoco)
        })

        // Clima: [icone][temperatura] em cima e "descricao · min – max" embaixo, alinhados a esquerda como a hora.
        txtIcone = texto(44f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        iconeEclipse = IconeEclipse(this).apply { visibility = View.GONE }
        val caixaIcone = FrameLayout(this).apply {
            addView(txtIcone, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
            addView(iconeEclipse, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        txtTemp = texto(72f, Color.WHITE, fonteLeve).apply {   // mesmo tamanho e fonte da hora
            letterSpacing = -0.02f
            includeFontPadding = false
            text = "…"
        }
        val linhaTemp = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(caixaIcone, LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(12) })
            addView(txtTemp, LinearLayout.LayoutParams(WRAP, WRAP))
            // Ao lado da temperatura, pequenas: maxima em cima, minima embaixo.
            txtMax = texto(16f, textoClaro).apply { includeFontPadding = false }
            txtMin = texto(16f, textoSuave).apply {
                includeFontPadding = false
                setPadding(0, dp(8), 0, 0)
            }
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(txtMax)
                addView(txtMin)
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(14) })
        }
        txtDescricao = texto(16f, textoClaro).apply {   // mesmo tamanho e espacamento da data
            letterSpacing = 0.03f
            setPadding(0, dp(4), 0, 0)
            minLines = 1
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        // O bloco do clima e um botao (OK abre a previsao de 7 dias). Folga de 12 dp em volta para o realce de foco:
        // o padding empurra o conteudo 12 dp e a margem negativa puxa de volta, entao o texto fica na grade.
        val blocoClima = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(folgaFoco), dp(folgaFoco), dp(folgaFoco), dp(folgaFoco))
            isFocusable = true
            isClickable = true
            setOnClickListener { abrirPrevisao() }
            setOnFocusChangeListener { v, temFoco -> v.background = if (temFoco) realceFoco() else null }
            addView(linhaTemp, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(txtDescricao, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        topo.addView(blocoClima, FrameLayout.LayoutParams(dp(Grade.largura(4) + 2 * folgaFoco), WRAP).apply {
            leftMargin = dp(Grade.x(5) - folgaFoco)
            topMargin = -dp(folgaFoco)
        })

        // Tres linhas de informacao (lua, umidade, chuva), cada uma com 1/3 da altura dos blocos ao lado.
        txtInfo = List(3) {
            texto(14f, textoSuave).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                letterSpacing = 0.02f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        }
        val blocoInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            txtInfo.forEach { addView(it, LinearLayout.LayoutParams(MATCH, 0, 1f)) }
        }
        topo.addView(blocoInfo, FrameLayout.LayoutParams(dp(Grade.largura(3)), WRAP).apply { leftMargin = dp(Grade.x(9)) })
        // A altura do bloco de informacao copia a do bloco da hora (hora + data).
        blocoHora.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val altura = v.height - dp(2 * folgaFoco)   // o bloco da hora tem a folga do realce em cima e embaixo
            if (blocoInfo.layoutParams.height != altura) {
                blocoInfo.layoutParams = blocoInfo.layoutParams.apply { height = altura }
            }
        }

        // Bolinha: exatamente a coluna 12 (50 dp). Centrada na altura da linha grande (hora/temperatura).
        val bolinha = botaoPerfil()
        topo.addView(bolinha, FrameLayout.LayoutParams(dp(Grade.COLUNA), dp(Grade.COLUNA)).apply { leftMargin = dp(Grade.x(12)) })
        linhaTemp.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            bolinha.translationY = ((v.height - bolinha.height) / 2f).coerceAtLeast(0f)
        }
        raiz.addView(topo, FrameLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(Grade.TOPO) })

        // ---- Linha de apps (rola na horizontal). O padding de cima/baixo e a folga para o card focado crescer;
        // por isso a rolagem comeca FOLGA acima da linha da grade.
        val folga = 24
        rolagem = HorizontalScrollView(this).apply {
            clipChildren = false    // senão a rolagem corta o card focado quando ele cresce
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(Grade.MARGEM), dp(folga), dp(Grade.MARGEM), dp(folga))
        }
        linhaApps = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
        }
        rolagem.addView(linhaApps)
        raiz.addView(rolagem, FrameLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(Grade.LINHA_APPS - folga) })

        // ---- Guia de teclas do modo mover: uma faixa na parte de baixo, por cima de tudo. Some fora do modo mover.
        txtDica = texto(15f, corMover).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER
            maxLines = 2
            maxWidth = dp(1000)
            setPadding(dp(22), dp(10), dp(22), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(0xE60E1311.toInt())
                setStroke(dp(1), 0x667FD6C2)
            }
        }

        return FrameLayout(this).apply {
            background = montarFundo()
            addView(raiz, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(txtDica, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(32)
            })
        }
    }

    /**
     * Fundo: degrade escuro e liso (do canto superior esquerdo, levemente mais claro, para o inferior direito).
     * Substitui a textura pontilhada (res/drawable-nodpi/fundo.jpg, que ficou sem uso). Tambem gasta menos RAM que a imagem.
     */
    private fun montarFundo(): Drawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(0xFF1B2326.toInt(), 0xFF0F1416.toInt(), 0xFF0A0D0E.toInt())
    )

    /** A bolinha do perfil, no canto superior direito: mostra a foto/inicial da conta ativa e abre o popup (OK). */
    private fun botaoPerfil(): View {
        fotoPerfil = ImageView(this).apply {
            setImageBitmap(Perfil.avatar(this@MainActivity, dp(44)))
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)   // recorte redondo
                }
            }
        }
        return FrameLayout(this).apply {
            contentDescription = "Perfil"
            isFocusable = true
            isClickable = true
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = anel(false)
            addView(fotoPerfil, FrameLayout.LayoutParams(dp(44), dp(44)))
            setOnClickListener { abrirPopup() }
            setOnFocusChangeListener { v, temFoco ->
                v.background = anel(temFoco)
                val escala = if (temFoco) 1.12f else 1f
                v.animate().scaleX(escala).scaleY(escala).setDuration(140).start()
            }
        }
    }

    /** Redesenha a bolinha: a conta ativa mudou, ou chegou uma foto nova. */
    private fun atualizarFotoPerfil() {
        fotoPerfil.setImageBitmap(Perfil.avatar(this, dp(44)))
    }

    private var previsao: PrevisaoSemana? = null

    /** Contorno branco dos blocos do topo (hora, clima) quando estao com foco. */
    private fun realceFoco() = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        setColor(0x14FFFFFF)
        setStroke(dp(2), 0xD9FFFFFF.toInt())
    }
    private var ultimaTemp: Int? = null   // a temperatura mostrada no topo (para a linha "Hoje" da previsao bater com ela)

    /** Abre o painel da previsao de 7 dias (ver PrevisaoSemana.kt). */
    private fun abrirPrevisao() {
        val painel = previsao ?: PrevisaoSemana(
            tela = this,
            emSegundoPlano = { bloco -> rede.execute { bloco() } },
            buscar = { httpGet(it) },
            descricao = { descricaoClima(it) },
            tempAgora = { ultimaTemp },
        ).also { previsao = it }
        painel.abrir()
    }

    /** Abre o menu do perfil (trocar perfil, Configurações, DNS, Wi-Fi). Ver PopupPerfil.kt. */
    private fun abrirPopup() {
        val menu = popup ?: PopupPerfil(
            tela = this,
            aoTrocarPerfil = { atualizarFotoPerfil() },
            aoAbrirConfig = { abrir(Config.PACOTE_CONFIG) },
        ).also { popup = it }
        menu.abrir(ultimaRede)
        atualizarRede()   // uma medição nova chega em instantes e atualiza o menu aberto
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
                val itens = membros.getValue(pilha.id)
                val frente = itens.indexOfFirst { it.pacote == pilha.frente }.coerceAtLeast(0)
                entradas.add(Entrada.Pilha(pilha.id, itens, frente))
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

    /** Os cards que aparecem agora: a lista normal, ou (no modo mover) a lista com o card arrastado na posição de teste. */
    private fun listaDaTela(): List<Entrada> = if (modoMover) listaMovendo() else lista

    /** Desenha os cards e devolve o foco ao último card usado. */
    private fun desenhar() {
        linhaApps.removeAllViews()
        val cards = listaDaTela()
        cards.forEach { linhaApps.addView(criarCartao(it)) }
        if (cards.isEmpty()) return
        linhaApps.post {
            val achado = cards.indexOfFirst { it.token == ultimoToken }
            // Se o card sumiu (ex.: app ocultado), o foco fica na mesma posição, que agora é o vizinho.
            val alvo = if (achado >= 0) achado else indiceFoco.coerceIn(0, cards.lastIndex)
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
            // Pilha: o OK abre o app da frente (▲▼ trocam qual é).
            is Entrada.Pilha -> montarCartao(entrada.token, entrada.nome, miniaturaPilha(entrada.itens, entrada.frente), { abrir(entrada.daFrente.pacote) }, aoFocar)
            is Entrada.Ocultos -> montarCartao(entrada.token, entrada.nome, miniaturaOcultos(), { abrirPasta(entrada) }, aoFocar)
        }
    }

    /**
     * Um card: o banner (3 colunas da grade, 16:9) e o nome embaixo, alinhado a esquerda. Sem moldura: o foco aparece
     * como um contorno branco no banner, o card cresce um pouco e o nome fica branco. Serve para a linha principal e
     * para dentro das pastas. [token] identifica o card (modo de mover e menu), [aoAbrir] roda ao apertar OK.
     */
    private fun montarCartao(token: String, nome: String, miniatura: View, aoAbrir: () -> Unit, aoFocar: (View) -> Unit): View {
        val cartao = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isClickable = true
            tag = token
            setOnClickListener { aoAbrir() }
            setOnFocusChangeListener { v, temFoco ->
                estilizar(v, token, temFoco)
                if (temFoco) aoFocar(v)
            }
        }
        // filho 0: o banner (a "moldura" desenha o contorno de foco POR CIMA, no foreground). filho 1: o nome.
        val moldura = FrameLayout(this).apply { addView(miniatura, FrameLayout.LayoutParams(MATCH, MATCH)) }
        cartao.addView(moldura, LinearLayout.LayoutParams(dp(Grade.CARD_LARGURA), dp(Grade.CARD_ALTURA)))
        cartao.addView(
            texto(14f, textoSuave).apply {
                text = nome
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                letterSpacing = 0.01f
                setPadding(dp(2), 0, 0, 0)
            },
            LinearLayout.LayoutParams(dp(Grade.CARD_LARGURA), WRAP).apply { topMargin = dp(10) }
        )
        cartao.layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(Grade.VAO) }
        return cartao
    }

    /** Imagem de um app com cantos arredondados. */
    private fun miniaturaApp(app: Item): View = ImageView(this).apply {
        setImageDrawable(copia(app.imagem))
        scaleType = if (app.ehBanner) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        arredondar(this, 12)
    }

    /**
     * Pilha: o app da [frente] na frente e até 2 atrás, deslocados para cima e para a direita, como uma pilha de cartas.
     * (▲▼ trocam qual fica na frente; o nome embaixo do card é o do app da frente.)
     */
    private fun miniaturaPilha(itens: List<Item>, frente: Int): View {
        val camadas = (listOf(itens[frente]) + itens.filterIndexed { i, _ -> i != frente }).take(3)
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
            // frente: (card - 20) x (card - 12) colada embaixo; cada camada de tras sobe 6 dp e vai 10 dp para a direita
            caixa.addView(vista, FrameLayout.LayoutParams(dp(Grade.CARD_LARGURA - 20), dp(Grade.CARD_ALTURA - 12)).apply {
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

    /**
     * Rola [barra] so o necessario para o card focado caber entre as margens da grade. Assim os cards ficam sempre
     * nas colunas: com 4 ou menos nada se mexe; com mais, a fila anda um card por vez.
     */
    private fun centralizar(card: View, barra: HorizontalScrollView) {
        val esquerda = card.left - barra.scrollX
        val direita = card.right - barra.scrollX
        val minimo = barra.paddingLeft
        val maximo = barra.width - barra.paddingRight
        when {
            esquerda < minimo -> barra.smoothScrollBy(esquerda - minimo, 0)
            direita > maximo -> barra.smoothScrollBy(direita - maximo, 0)
        }
    }

    // ---------------------------------------------------------------- ▲▼ numa pilha: trocar o app da frente

    private var cimaSaiu = false   // true: este "segurar ▲" ja tirou o foco da pilha; o soltar nao troca o app

    /**
     * Num card de pilha:
     *  - ▼ (toque) troca o app da frente para o proximo, na hora;
     *  - ▲ (toque) troca para o anterior, ao SOLTAR a tecla;
     *  - ▲ segurado por 2 s sai da pilha e vai para o topo da tela (clima, bolinha do perfil).
     * Os dois dao a volta (depois do ultimo vem o primeiro). Segurar ▼ nao gira a pilha inteira.
     * Em card solto nao faz nada: a tecla segue o caminho normal.
     */
    private fun trocarDaFrente(event: KeyEvent): Boolean {
        val sobe = event.keyCode == KeyEvent.KEYCODE_DPAD_UP
        if (!sobe && event.keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false
        val atual = currentFocus ?: return false
        val token = atual.tag as? String ?: return false
        val pilha = lista.firstOrNull { it.token == token } as? Entrada.Pilha ?: return false
        if (!sobe) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) girarPilha(pilha, +1)
            return true
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) cimaSaiu = false
                // As repeticoes da tecla segurada chegam a cada ~50 ms; quando passam de 2 s desde o aperto, sai da pilha.
                if (!cimaSaiu && event.eventTime - event.downTime >= 2000) {
                    cimaSaiu = true
                    atual.focusSearch(View.FOCUS_UP)?.requestFocus()
                }
            }
            KeyEvent.ACTION_UP -> if (!cimaSaiu) girarPilha(pilha, -1)
        }
        return true
    }

    /** Troca o app da frente da [pilha] em [passo] (+1 proximo, -1 anterior) e redesenha so aquele card. */
    private fun girarPilha(pilha: Entrada.Pilha, passo: Int) {
        val total = pilha.itens.size
        val novo = (pilha.frente + passo + total) % total   // % da a volta; "+ total" evita numero negativo
        Organizacao.definirFrente(this, pilha.id, pilha.itens[novo].pacote)
        val posicao = lista.indexOf(pilha)
        val nova = Entrada.Pilha(pilha.id, pilha.itens, novo)
        lista[posicao] = nova
        // Poe o card novo ANTES de tirar o velho: tirar o card com foco faria o Android jogar o foco para o 1o card.
        val vista = criarCartao(nova)
        linhaApps.addView(vista, posicao)
        vista.requestFocus()
        linhaApps.removeViewAt(posicao + 1)
    }

    // ---------------------------------------------------------------- pastas (apps ocultos e "remover do grupo")

    private var pasta: Dialog? = null
    private var pastaToken: String? = null   // qual pasta está aberta: TOKEN_OCULTOS ou "pilha:<id>" (modo remover)

    /**
     * Abre uma janela por cima da tela inicial, como uma pasta. Voltar fecha.
     * Com [remover], é a lista dos apps de uma pilha: OK num app pede confirmação para tirá-lo do grupo.
     */
    private fun abrirPasta(entrada: Entrada, remover: Boolean = false) {
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
                {
                    if (remover && entrada is Entrada.Pilha) confirmarRemocao(app, entrada)
                    else { fecharPasta(); abrir(app.pacote) }
                },
                { v -> centralizar(v, barra) }))
        }
        val ehOcultos = entrada is Entrada.Ocultos
        val conteudo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(texto(24f, Color.WHITE, fonteLeve).apply {
                text = if (ehOcultos) "Apps ocultos" else "Qual app sai do grupo?"
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(56), 0, dp(56), 0)
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(barra, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(texto(13f, textoSuave).apply {
                text = if (ehOcultos) "OK abre  ·  segure OK para mostrar na tela inicial  ·  Voltar fecha"
                       else "OK escolhe o app a remover  ·  Voltar fecha"
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        val janela = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        janela.setContentView(conteudo)
        janela.window?.apply {
            setBackgroundDrawable(ColorDrawable(0xF70E1311.toInt()))   // escurece a tela inicial por trás
            setLayout(MATCH, MATCH)
        }
        // A janela tem as próprias teclas: o "segurar OK" (nos ocultos) precisa ser detectado aqui também.
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

    /** Pergunta antes de tirar [app] da [pilha]. O foco começa em "Cancelar": confirmar exige escolher "Remover". */
    private fun confirmarRemocao(app: Item, pilha: Entrada.Pilha) {
        val dialogo = AlertDialog.Builder(this)
            .setTitle("Remover ${app.nome} do grupo?")
            .setMessage("Ele volta a ser um card solto.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Remover") { _, _ ->
                Organizacao.tirarDaPilha(this, app.pacote)
                fecharPasta()
                preencherApps()
                // Se a pilha ainda existe (ficou com 2+ apps), reabre a lista para tirar mais um; senão ela se desfez.
                val restante = lista.firstOrNull { it is Entrada.Pilha && it.id == pilha.id }
                if (restante is Entrada.Pilha) {
                    ultimoToken = restante.token
                    abrirPasta(restante, remover = true)
                } else {
                    ultimoToken = app.pacote
                }
            }
            .show()
        dialogo.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
        menuAberto = dialogo
    }

    // ---------------------------------------------------------------- menu (segure OK em uma pilha)

    private class Opcao(val rotulo: String, val acao: () -> Unit)

    private var menuAberto: AlertDialog? = null
    private var engolirUp = false   // true entre o "segurar OK" e o menu fechar: o "soltar" da mesma pressão não pode virar clique

    /**
     * Segurar OK (ou ENTER) num card. Detectamos o "segurar" pelo repeatCount: o Android repete a tecla
     * enquanto ela está pressionada. O que acontece depende de onde:
     *  - app solto: entra direto no modo mover (arrastar);
     *  - pilha: abre o menu (Mover, Remover cards do grupo);
     *  - app dentro da pasta "Ocultos": menu com "Mostrar na tela inicial".
     * Devolve true se o evento foi tratado aqui.
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
        if (token == TOKEN_OCULTOS) return false              // o card "Ocultos" não tem opções
        val dentroDe = pastaToken
        if (dentroDe == TOKEN_OCULTOS) {
            engolirUp = true
            abrirMenu(token)
            return true
        }
        if (dentroDe != null) return false                    // pasta de "remover do grupo": segurar OK = apertar OK
        return when (lista.firstOrNull { it.token == token }) {
            is Entrada.App -> { iniciarMover(token, porSegurar = true); true }
            is Entrada.Pilha -> { engolirUp = true; abrirMenu(token); true }
            else -> false
        }
    }

    private fun abrirMenu(token: String) {
        val entrada = lista.firstOrNull { it.token == token }
        val titulo: String
        val opcoes = mutableListOf<Opcao>()
        if (pastaToken == TOKEN_OCULTOS) {
            titulo = nomeDe(token)
            opcoes += Opcao("Mostrar na tela inicial") { Organizacao.mostrar(this, token); atualizarAposMudanca(token) }
        } else if (pastaToken == null && entrada is Entrada.Pilha) {
            titulo = entrada.titulo
            opcoes += Opcao("Mover") { iniciarMover(token, porSegurar = false) }
            opcoes += Opcao("Remover cards do grupo") { abrirPasta(entrada, remover = true) }
        } else {
            return
        }
        menuAberto = AlertDialog.Builder(this)
            .setTitle(titulo)
            .setItems(opcoes.map { it.rotulo }.toTypedArray()) { _, i -> opcoes[i].acao() }
            .setOnDismissListener { engolirUp = false }
            .show()
    }

    /** Depois de mostrar/ocultar: fecha a pasta aberta, relê tudo e leva o foco para [foco] (ou perto de onde estava). */
    private fun atualizarAposMudanca(foco: String?) {
        fecharPasta()
        if (foco != null) ultimoToken = foco
        preencherApps()
    }

    private fun nomeDe(pacote: String): String = instalados.firstOrNull { it.pacote == pacote }?.nome ?: pacote

    // ---------------------------------------------------------------- mover cards (segure OK em um app, ou menu da pilha > Mover)

    private val corMover = 0xFF7FD6C2.toInt()

    /**
     * Visual de um card: normal (sem contorno), focado (contorno branco no banner, nome branco) ou
     * "sendo movido" (contorno verde-agua e maior). Funciona com os cards de montarCartao (filho 0 = banner, 1 = nome).
     */
    private fun estilizar(card: View, token: String, focado: Boolean) {
        val movendo = modoMover && token == tokenMovendo
        val grupo = card as? ViewGroup
        (grupo?.getChildAt(0))?.foreground = when {
            movendo -> contorno(corMover, 0x2E7FD6C2)
            focado -> contorno(Color.WHITE, Color.TRANSPARENT)
            else -> null
        }
        (grupo?.getChildAt(1) as? TextView)?.setTextColor(if (focado || movendo) Color.WHITE else textoSuave)
        val escala = if (movendo) 1.10f else if (focado) 1.06f else 1f
        card.animate().scaleX(escala).scaleY(escala).setDuration(140).start()
    }

    /** Contorno de 3 dp com o mesmo arredondamento do banner, desenhado por cima dele. */
    private fun contorno(cor: Int, preenchimento: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(preenchimento)
        setStroke(dp(3), cor)
    }

    /**
     * No modo mover: ◀ ▶ andam uma posição, OK confirma, Voltar cancela,
     * ▲ oculta o app (só card solto) e ▼ volta à ordem automática.
     * Fora dele: segurar OK (ver tratarSegurar) e ▲▼ numa pilha (ver trocarDaFrente).
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val confirma = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER
        if (!modoMover) {
            if (tratarSegurar(event, currentFocus)) return true
            if (trocarDaFrente(event)) return true
            return super.dispatchKeyEvent(event)
        }
        val pressionou = event.action == KeyEvent.ACTION_DOWN
        val soltou = event.action == KeyEvent.ACTION_UP
        return when {
            event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> { if (pressionou) moverCartao(-1); true }
            event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> { if (pressionou) moverCartao(1); true }
            event.keyCode == KeyEvent.KEYCODE_DPAD_UP -> {
                if (pressionou && event.repeatCount == 0) ocultarArrastado()
                true
            }
            event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (pressionou && event.repeatCount == 0) restaurarOrdemAutomatica()
                true
            }
            confirma -> {
                if (soltou) {
                    // O primeiro "soltar" é o fim do segurar que abriu o modo: ignora.
                    if (ignorarProximoUp) ignorarProximoUp = false else confirmarMover()
                }
                true
            }
            event.keyCode == KeyEvent.KEYCODE_BACK -> { if (soltou) cancelarMover(); true }
            else -> super.dispatchKeyEvent(event)
        }
    }

    /*
     * Como o "arrastar" funciona. Os outros cards (sem o arrastado e sem o "Ocultos") ficam parados: m cards.
     * O arrastado tem 2m+1 posições possíveis, numeradas em posMover:
     *   par   (2k)   = ao lado: entra na fila antes do card k (só muda a ordem);
     *   ímpar (2k+1) = em cima do card k: pré-pilha (os dois viram um card só na tela, mas só vira pilha de verdade ao confirmar).
     * Cada ◀ ▶ anda uma posição. Então para passar por um card são 2 toques: "em cima" e depois "do outro lado".
     * Se o usuário não confirmar, o card segue andando. Voltar cancela e nada foi gravado: [lista] nunca é alterada.
     * Uma pilha arrastada só usa as posições pares (não dá para empilhar uma pilha dentro de outra).
     */

    /** Os cards que ficam parados enquanto o [arrastado] anda. */
    private fun outrosCards(): List<Entrada> = lista.filter { it !is Entrada.Ocultos && it.token != arrastado?.token }

    /** O card em cima do qual o arrastado está (pré-pilha), ou null se está "ao lado". */
    private fun alvoDeEmpilhar(): Entrada? = if (posMover % 2 == 1) outrosCards()[posMover / 2] else null

    /** Une um app arrastado a [alvo] (um app solto ou uma pilha) só para mostrar na tela. O que chegou fica na frente. */
    private fun fundir(alvo: Entrada, app: Entrada.App): Entrada.Pilha = when (alvo) {
        is Entrada.Pilha -> Entrada.Pilha(alvo.id, alvo.itens + app.item, alvo.itens.size)
        else -> Entrada.Pilha(ID_PREVIA, listOf((alvo as Entrada.App).item, app.item), 1)
    }

    /** A fila como aparece agora, com o arrastado na posição [posMover]. */
    private fun listaMovendo(): List<Entrada> {
        val movido = arrastado ?: return lista
        val fila = outrosCards().toMutableList()
        val alvo = alvoDeEmpilhar()
        if (alvo == null) fila.add(posMover / 2, movido) else fila[posMover / 2] = fundir(alvo, movido as Entrada.App)
        lista.lastOrNull { it is Entrada.Ocultos }?.let { fila.add(it) }   // "Ocultos" fica sempre no fim
        return fila
    }

    private fun iniciarMover(token: String, porSegurar: Boolean) {
        val card = lista.firstOrNull { it.token == token } ?: return
        if (card is Entrada.Ocultos) return
        modoMover = true
        arrastado = card
        posMover = 2 * lista.filter { it !is Entrada.Ocultos }.indexOfFirst { it.token == token }   // começa "ao lado", onde estava
        ignorarProximoUp = porSegurar   // segurando OK, o "soltar" ainda vai chegar e não pode confirmar
        atualizarMover()
    }

    private fun moverCartao(delta: Int) {
        val passo = if (arrastado is Entrada.App) 1 else 2
        val nova = (posMover + delta * passo).coerceIn(0, 2 * outrosCards().size)
        if (nova == posMover) return
        posMover = nova
        atualizarMover()
    }

    /** Redesenha com o arrastado na posição nova e leva o foco para o card que o representa. */
    private fun atualizarMover() {
        val alvo = alvoDeEmpilhar()
        tokenMovendo = if (alvo == null) arrastado?.token else fundir(alvo, arrastado as Entrada.App).token
        ultimoToken = tokenMovendo
        mostrarDica(true)
        desenhar()
    }

    private fun confirmarMover() {
        val card = arrastado ?: return
        val alvo = alvoDeEmpilhar()
        if (alvo != null && card is Entrada.App) {
            // Congela a ordem de agora (com o arrastado onde estava) e junta: a pilha ocupa o lugar do alvo.
            Organizacao.salvarOrdem(this, lista.filter { it !is Entrada.Ocultos }.map { it.token })
            ultimoToken = Organizacao.empilhar(this, card.token, alvo.token)
        } else {
            Organizacao.salvarOrdem(this, listaMovendo().filter { it !is Entrada.Ocultos }.map { it.token })
        }
        terminarMover()
        preencherApps()
    }

    private fun cancelarMover() {
        ultimoToken = arrastado?.token   // o foco volta para o card que estava sendo movido, no lugar de origem
        terminarMover()
        desenhar()
    }

    private fun terminarMover() {
        modoMover = false
        arrastado = null
        tokenMovendo = null
        ignorarProximoUp = false
        mostrarDica(false)
    }

    /** ▲ no modo mover: oculta o app arrastado (some da fila e vai para o card "Ocultos"). */
    private fun ocultarArrastado() {
        val card = arrastado as? Entrada.App ?: return
        terminarMover()
        Organizacao.ocultar(this, card.token)
        atualizarAposMudanca(null)
    }

    private fun restaurarOrdemAutomatica() {
        terminarMover()
        Organizacao.limparOrdem(this)
        preencherApps()
    }

    /** Durante o modo de mover, aparece um guia de teclas na parte de baixo da tela. */
    private fun mostrarDica(ligada: Boolean) {
        if (!ligada) {
            txtDica.visibility = View.GONE
            return
        }
        val alvo = alvoDeEmpilhar()
        txtDica.text = when {
            alvo is Entrada.Pilha -> "OK coloca na pilha  ·  ◀ ▶ continua andando  ·  Voltar cancela"
            alvo != null -> "OK forma a pilha  ·  ◀ ▶ continua andando  ·  Voltar cancela"
            arrastado is Entrada.App -> "◀ ▶ mover · OK confirma · Voltar cancela · ▲ ocultar · ▼ ordem automática"
            else -> "◀ ▶ mover · OK confirma · Voltar cancela · ▼ ordem automática"
        }
        txtDica.visibility = View.VISIBLE
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
                // Onde estamos: Wi-Fi do Android (±dezenas de m) > IP > Config.kt. Ver Localizacao.kt.
                val ponto = Localizacao.atual(this) { httpGet(it) }
                val unidade = if (Config.USAR_FAHRENHEIT) "&temperature_unit=fahrenheit" else ""
                // Previsao (minima/maxima, chance de chuva, ceu) do modelo da Open-Meteo, no ponto exato.
                val url = "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=${ponto.lat}&longitude=${ponto.lon}" +
                    "&current=temperature_2m,relative_humidity_2m,weather_code,precipitation,is_day" +
                    "&hourly=precipitation_probability&forecast_hours=4" +
                    "&daily=temperature_2m_max,temperature_2m_min" +
                    "&timezone=auto&forecast_days=1$unidade"
                val json = JSONObject(httpGet(url) ?: return@execute)
                val atual = json.getJSONObject("current")
                val dia = json.getJSONObject("daily")
                // Temperatura: a MEDIDA na estacao do aeroporto (METAR) vence a do modelo da Open-Meteo. Em 31/10 as 16:15
                // o modelo dizia 26,6 °C e o aeroporto de Ribeirao Preto mediu 32 °C. Sem METAR recente, fica o modelo.
                val medida = medidaAeroporto(ponto)
                // Umidade: a do aeroporto (calculada da temperatura e do ponto de orvalho medidos); sem ela, a do modelo.
                val umidade = medida?.umidade ?: atual.optInt("relative_humidity_2m", -1).takeIf { it >= 0 }
                val temp = medida?.temp ?: Math.round(atual.getDouble("temperature_2m")).toInt()
                // Minima/maxima do dia vem do modelo; se a medida de agora ja passou delas, o modelo errou: corrige com ela.
                val max = maxOf(temp, Math.round(dia.getJSONArray("temperature_2m_max").getDouble(0)).toInt())
                val min = minOf(temp, Math.round(dia.getJSONArray("temperature_2m_min").getDouble(0)).toInt())
                // Tempo AGORA: o aeroporto (o que um observador VIU) vence o modelo. Em 31/10 as 16:54 o modelo dizia
                // "garoa, chovendo agora" com ceu aberto; o aeroporto dizia CAVOK (sem chuva).
                var codigo = atual.getInt("weather_code")
                var chovendoAgora = atual.optDouble("precipitation", 0.0) > 0.0
                if (medida != null) {
                    val visto = medida.fenomeno
                    if (visto != null) codigo = visto                 // chuva, garoa, trovoada ou neblina observada
                    else if (codigo >= 45) codigo = medida.nuvens     // o modelo inventou chuva/neblina: fica so a nebulosidade
                    chovendoAgora = visto != null && visto >= 51
                }
                val horas = json.getJSONObject("hourly").getJSONArray("precipitation_probability")
                var chance = -1   // maior probabilidade entre a hora atual e as proximas 2
                for (i in 0 until minOf(3, horas.length())) chance = maxOf(chance, horas.optInt(i, -1))
                val chuva = when {
                    chovendoAgora -> "☔  Chovendo agora"
                    chance >= 0 -> "☔  Chuva $chance% em 3 h"
                    else -> ""
                }
                val eDia = atual.optInt("is_day", 1) == 1   // a Open-Meteo diz se e dia no local (pelo nascer/por do sol)
                val (iconeDoTempo, descricao) = descricaoClima(codigo)
                // De noite, ceu limpo/poucas nuvens/parcial mostram a lua na fase de hoje no lugar do sol
                // (se estiver chovendo, vale a chuva, como de dia). Chuva, neblina etc. mantem o proprio icone.
                val luaVirouIcone = !eDia && codigo in 0..2 && !chovendoAgora
                val icone = when {
                    luaVirouIcone -> Lua.emoji(System.currentTimeMillis(), Config.LATITUDE)
                    chovendoAgora && codigo < 51 -> "🌧️"   // o codigo diz "nublado", mas esta chovendo: vale a chuva
                    else -> iconeDoTempo
                }
                ui.post {
                    iconeClima = icone
                    ceuCoberto = codigo > 2 || chovendoAgora
                    txtTemp.text = "$temp°"
                    txtDescricao.text = descricao
                    ultimaTemp = temp
                    txtMax.text = "↑ $max°"
                    txtMin.text = "↓ $min°"
                    textoChuva = chuva
                    textoUmidade = umidade?.let { "💧  Umidade $it%" } ?: ""
                    luaNoIcone = luaVirouIcone
                    atualizarEclipse()   // escolhe o icone (clima ou eclipse) e chama atualizarLua()
                }
            } catch (e: Exception) {
                // Sem internet ou API fora do ar: mantem o ultimo valor mostrado.
            }
        }
    }

    /** A lua e calculada no aparelho (ver Lua.kt): nao usa internet. */
    private fun atualizarLua() {
        val eclipse = eclipseAgora
        val lua = when {
            // Durante um eclipse real, no lugar da fase da lua vai a porcentagem do sol coberta.
            eclipse != null -> {
                val pct = Math.round(eclipse.coberto * 100).toInt()
                val cobertura = if (eclipse.coberto < 0.01) "menos de 1%" else "$pct%"
                "Eclipse solar parcial · $cobertura" + if (ceuCoberto) " (encoberto)" else ""
            }
            luaNoIcone -> ""   // de noite a lua ja e o icone do clima: nao repete
            // so o emoji e o nome da fase ("🌗 Quarto minguante"); a % iluminada fica de fora para a linha caber
            else -> Lua.resumoCurto(System.currentTimeMillis(), Config.LATITUDE).substringBeforeLast("  ·  ")
        }
        // As linhas de informacao, de cima para baixo, so as que existirem (sem buraco no meio).
        val linhas = listOf(lua, textoUmidade, textoChuva).filter { it.isNotEmpty() }
        txtInfo.forEachIndexed { i, t -> t.text = linhas.getOrElse(i) { "" } }
    }

    /**
     * Durante um eclipse solar REAL (tabela do Config.kt) e com o ceu visivel, o icone do clima vira o sol coberto
     * pela lua, na cobertura do minuto. Com nuvem ou chuva fica o icone do clima (o eclipse nao seria visto).
     * Fora do eclipse, volta para o ultimo icone do clima.
     */
    private fun atualizarEclipse() {
        val e = Eclipse.estado(System.currentTimeMillis())
        eclipseAgora = e
        if (e != null) Log.i("TvLauncher.Eclipse", "lua x=${e.luaX} y=${e.luaY} coberto=${"%.1f".format(e.coberto * 100)}% ceuCoberto=$ceuCoberto")
        if (e != null && !ceuCoberto) {
            iconeEclipse.definir(e.luaX, e.luaY, e.raioLua)
            mostrarIcone(ICONE_ECLIPSE)
        } else {
            iconeClima?.let { mostrarIcone(it) }
        }
        atualizarLua()
    }

    /** Mede a rede fora da thread da tela e entrega o resultado ao popup do perfil (que mostra o nome e o sinal). */
    private fun atualizarRede() {
        rede.execute {
            val r = Rede.medir(this)
            ui.post {
                ultimaRede = r
                popup?.atualizarRede(r)
            }
        }
    }

    /** Troca o icone do clima: o eclipse e desenhado em codigo; os outros sao emojis. */
    private fun mostrarIcone(icone: String) {
        if (icone == ICONE_ECLIPSE) {
            iconeEclipse.visibility = View.VISIBLE
            txtIcone.visibility = View.GONE
        } else {
            txtIcone.text = icone
            txtIcone.visibility = View.VISIBLE
            iconeEclipse.visibility = View.GONE
        }
    }


    /** Códigos de clima da Open-Meteo (padrão WMO) -> ícone + texto. O ícone é um emoji; o desenho do eclipse (ICONE_ECLIPSE) só entra em atualizarEclipse(). */
    private fun descricaoClima(codigo: Int): Pair<String, String> = when (codigo) {
        0 -> "☀️" to "Céu limpo"
        1 -> "🌤️" to "Poucas nuvens"
        2 -> "⛅" to "Parcialmente nublado"
        3 -> "☁️" to "Nublado"
        45, 48 -> "🌫️" to "Neblina"
        in 51..57 -> "🌧️" to "Garoa"
        in 61..67 -> "🌧️" to "Chuva"
        in 71..77, 85, 86 -> "❄️" to "Neve"
        in 80..82 -> "🌧️" to "Pancadas de chuva"
        95, 96, 99 -> "⛈️" to "Tempestade"
        else -> "🌡️" to "—"
    }

    /**
     * O que o aeroporto mediu: temperatura (ja na unidade do Config), umidade relativa em % (null se faltar o orvalho),
     * [fenomeno] = codigo WMO do tempo observado (95 trovoada, 80 pancada, 61 chuva, 51 garoa, 45 neblina; null = nada) e
     * [nuvens] = codigo WMO da nebulosidade (1 poucas, 2 parcial, 3 nublado).
     */
    private class Medida(val temp: Int, val umidade: Int?, val fenomeno: Int?, val nuvens: Int)

    /**
     * Ultimo boletim METAR (todo aeroporto publica, em geral a cada hora) da estacao MAIS PERTO de [ponto] num raio
     * de ~65 km, com boletim de no maximo 90 min. Em Ribeirao Preto e o aeroporto Leite Lopes (SBRP), a ~6 km; o INMET
     * nao tem estacao automatica na cidade (a mais perto e Sao Simao, ~35 km). Config.ESTACAO_METAR fixa uma estacao.
     * null se nao houver estacao valida ou a busca falhar: ai fica o modelo. Chamar fora da thread da tela.
     */
    private fun medidaAeroporto(ponto: Localizacao.Ponto): Medida? {
        return try {
            val filtro = if (Config.ESTACAO_METAR.isNotEmpty()) "ids=${Config.ESTACAO_METAR}"
                // A API recusa (erro 400) coordenadas com muitas casas decimais: vao com 2 (~1 km, sobra para uma caixa de 130 km).
                else "bbox=" + listOf(ponto.lat - 0.6, ponto.lon - 0.6, ponto.lat + 0.6, ponto.lon + 0.6)
                    .joinToString(",") { String.format(java.util.Locale.US, "%.2f", it) }
            val lista = JSONArray(httpGet("https://aviationweather.gov/api/data/metar?$filtro&format=json") ?: return null)
            val agoraS = System.currentTimeMillis() / 1000
            val boletim = (0 until lista.length()).mapNotNull { lista.optJSONObject(it) }
                .filter { it.has("temp") && (agoraS - it.optLong("obsTime")) / 60 <= 90 }
                .minByOrNull { Localizacao.km(ponto.lat, ponto.lon, it.optDouble("lat"), it.optDouble("lon")) }
                ?: return null
            Log.i("TvLauncher.Local", "estacao ${boletim.optString("icaoId")} a " +
                "${Localizacao.km(ponto.lat, ponto.lon, boletim.optDouble("lat"), boletim.optDouble("lon")).toInt()} km")
            val celsius = boletim.getDouble("temp")
            // Umidade relativa pela formula de Magnus: compara a pressao de vapor no ponto de orvalho com a da temperatura.
            val umidade = if (boletim.has("dewp")) {
                val orvalho = boletim.getDouble("dewp")
                val vapor = { t: Double -> Math.exp(17.625 * t / (243.04 + t)) }
                Math.round(100 * vapor(orvalho) / vapor(celsius)).toInt().coerceIn(0, 100)
            } else null
            val temp = Math.round(if (Config.USAR_FAHRENHEIT) celsius * 9 / 5 + 32 else celsius).toInt()
            // Tempo presente do METAR (ex.: "-RA" chuva fraca, "SHRA" pancada, "TSRA" trovoada, "DZ" garoa, "BR"/"FG" nevoa).
            val wx = boletim.optString("wxString", "")
            val fenomeno = when {
                "TS" in wx -> 95
                "SH" in wx -> 80
                "RA" in wx -> 61
                "DZ" in wx -> 51
                "FG" in wx || "BR" in wx -> 45
                else -> null
            }
            // Nebulosidade: a camada mais cheia. CAVOK/sem camadas = nada abaixo de 1500 m; nuvens altas podem existir,
            // por isso vira "poucas nuvens" e nao "ceu limpo".
            val camadas = boletim.optJSONArray("clouds")
            var nuvens = 1
            if (camadas != null) for (i in 0 until camadas.length()) {
                nuvens = maxOf(nuvens, when (camadas.optJSONObject(i)?.optString("cover")) {
                    "SCT" -> 2
                    "BKN", "OVC" -> 3
                    else -> 1
                })
            }
            Medida(temp, umidade, fenomeno, nuvens)
        } catch (e: Exception) {
            Log.w("TvLauncher.Local", "METAR falhou: $e")
            null   // formato mudou, sem internet etc.: fica o modelo
        }
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
