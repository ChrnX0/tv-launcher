package br.paulo.tvlauncher

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
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

    /** Um app na grade: pacote (identificador), nome, imagem (banner ou ícone), se é banner (16:9) e o ícone redondo da linha principal. */
    private class Item(val pacote: String, val nome: String, val imagem: Drawable, val ehBanner: Boolean, val icone: Drawable)

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
    // Nos temas com fundo desenhado (ceu, pintura, mostrador) o cinza escuro sumia: la o "suave" e quase tao claro quanto o claro.
    private val textoSuave: Int get() = if (tema == Tema.ESCURO) 0xFF93A19A.toInt() else 0xFFCFD9D4.toInt()
    // O visual escolhido no menu do perfil (Tema.kt). Lido no onCreate; trocar de tema recria a tela.
    private var tema = Tema.ESCURO
    private val fonteLeve by lazy { Fontes.display(this, tema) }   // hora e temperatura
    private val fonteMedia by lazy { Fontes.uiMedia(this, tema) }
    private val fonteUi by lazy { Fontes.ui(this, tema) }

    private var fundo: View? = null                 // o fundo vivo do tema (céu, pintura, mostrador)
    @Volatile private var pontoAtual: Localizacao.Ponto? = null
    private var ultimoCodigo = -1
    private var ultimoChovendo = false

    private lateinit var txtIcone: TextView   // so para o texto de reserva
    private lateinit var imgIcone: ImageView   // o icone do clima (imagem de assets/clima/)
    private lateinit var txtTemp: TextView
    private lateinit var txtChuva: TextView   // a linha informativa embaixo da temperatura: chuva prevista ou a condicao de agora
    private lateinit var linhaChuva: LinearLayout
    private lateinit var txtMax: TextView   // "↑ 32°" ao lado da temperatura
    private lateinit var txtMin: TextView   // "↓ 18°"
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
    private val tarefaEclipse = object : Runnable {
        override fun run() {
            atualizarEclipse()
            atualizarFundo()   // o céu e o mostrador andam com o relógio
            ui.postDelayed(this, Config.ECLIPSE_A_CADA_SEG * 1000L)
        }
    }
    private val tarefaRede = object : Runnable {
        override fun run() {
            // Só mede com o menu do perfil aberto (o único lugar que mostra a medida), ou uma vez para ter a 1ª
            // (revisão 04/11): antes, com a HOME parada, eram 3 conexões a 1.1.1.1 + um Log.i a cada 30 s, o dia todo,
            // na mesma fila do clima e da galeria. Ao abrir o menu, abrirPopup() já pede uma medição na hora.
            if (popup?.aberto == true || ultimaRede == null) atualizarRede()
            ui.postDelayed(this, Config.REDE_A_CADA_SEG * 1000L)
        }
    }

    // Portao de rede: no boot, a TV demora a conectar e o primeiro buscarClima() falha calado -> o widget ficaria
    // ate 5 min mostrando nada/velho. Este observador dispara uma busca ASSIM QUE a rede sobe, se o clima estiver velho.
    private var vigiaRede: android.net.ConnectivityManager.NetworkCallback? = null

    private fun vigiarRedeParaClima() {
        if (vigiaRede != null) return
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return
        vigiaRede = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(rede: android.net.Network) {
                // Clima fresco (< 2 min)? Entao nao precisa; senao, busca agora, fora da fila dos 5 min.
                val em = getSharedPreferences("clima", MODE_PRIVATE).getLong("em", 0)
                if (System.currentTimeMillis() - em > 2 * 60_000L) ui.post { buscarClima() }
            }
        }
        try { cm.registerDefaultNetworkCallback(vigiaRede!!) } catch (e: Exception) { vigiaRede = null }
    }

    private fun pararVigiaRede() {
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        vigiaRede?.let { try { cm?.unregisterNetworkCallback(it) } catch (e: Exception) {} }
        vigiaRede = null
    }

    // ---------------------------------------------------------------- ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tema = Tema.atual(this)
        setContentView(montarTela())
        window.setBackgroundDrawable(null)   // a janela não pinta o fundo padrão do tema por baixo de tudo (ver montarTela)
        Privilegio.religarAcessibilidade(this)   // religa o serviço de teclas do controle se o Android o desligou
        Privilegio.vigiarDepuracaoSemFio(this)   // religa a Depuração sem fio sempre que o Android a desligar
        PrivilegioDO.aplicar(this)   // Device Owner: fixa o nosso protetor e oculta apps substituidos (ver PrivilegioDO.kt)
        if (Config.SHELL_PROPRIO_DIAG) ShellProprio.diagnosticar(this)   // MARCO 1 do shell proprio: loga chave + porta adb (ver ShellProprio.kt)
        // Shizuku embutido (MARCO S3): sobe o servidor se não estiver de pé e liga o vigia (binder, Wi-Fi, 10 min).
        GuardiaoShizuku.vigiar(this)
        GuardiaoShizuku.garantir(this, "launcher abriu")
    }

    override fun onResume() {
        super.onResume()
        // O tema agora só troca na tela separada de Configurações do launcher (ConfigLauncherActivity), que não
        // tem como chamar recreate() nesta Activity diretamente — então aqui a gente mesmo percebe a troca ao voltar.
        if (Tema.atual(this) != tema) { recreate(); return }
        preencherApps()
        ui.post(tarefaClima)
        ui.post(tarefaEclipse)
        ui.post(tarefaRede)
        vigiarRedeParaClima()   // busca o clima assim que a rede subir no boot, sem esperar o ciclo de 5 min
        atualizarFotoPerfil()   // a conta ativa ou uma foto pode ter mudado
        Privilegio.religarAcessibilidade(this)
        Privilegio.religarDepuracaoSemFio(this)   // o Android pode ter desligado a Depuração sem fio no reinício
        Despertador.agendar(this)   // garante o proximo alarme agendado (barato: so le as preferencias)
        // DNS fixo que parou de responder deixa a TV "sem internet": confere ao voltar à HOME (fora da thread; só testa
        // a rede de verdade se o modo é fixo E a rede perdeu a validação — no caso normal custa quase nada).
        rede.execute { Dns.voltarAoAutomaticoSeQuebrado(this) }
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tarefaClima)
        ui.removeCallbacks(tarefaEclipse)
        ui.removeCallbacks(tarefaRede)
        pararVigiaRede()
        menuAberto?.dismiss()
        popup?.fechar()
        fecharPasta()
        if (modoMover) cancelarMover()
    }

    override fun onDestroy() {
        rede.shutdownNow()
        // Cada recreate() (troca de tema) cria uma Activity nova com a sua própria fila: sem isto a thread "Apps"
        // da Activity velha ficava viva para sempre. shutdown (não Now): deixa terminar uma carga em curso.
        filaApps.shutdown()
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
        //   1-4: hora / data   |   5-8: icone (+ maxima/minima ao lado), temperatura, chuva   9-11: tres linhas de info   12: bolinha
        // Hora e temperatura tem a mesma fonte e tamanho; data e descricao tambem: tudo cai nas mesmas alturas.
        // As 3 linhas de informacao (lua, umidade, chuva) se dividem igualmente na altura dos blocos ao lado.
        // Hora e temperatura tem a mesma fonte e tamanho; data e descricao tambem: tudo cai nas mesmas alturas.
        val topo = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }

        // A hora e um botao: OK abre o despertador (AlarmesActivity). Mesma folga de foco do clima (ver abaixo).
        val folgaFoco = 12
        // Hora e data sao dois botoes: OK na hora abre o despertador; OK na data, a agenda do perfil ativo.
        // Cada um tem a folga do realce (padding de 12 dp e margem de -12 dp): o texto continua na grade.
        val blocoHora = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val f = dp(folgaFoco)
        blocoHora.addView(TextClock(this).apply {
            format12Hour = "HH:mm"
            format24Hour = "HH:mm"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (tema == Tema.ESCURO) 72f else 84f)
            setTextColor(Color.WHITE)
            typeface = fonteLeve
            if (tema != Tema.ESCURO) setShadowLayer(dp(10).toFloat(), 0f, 0f, 0x99000000.toInt())
            letterSpacing = -0.02f
            includeFontPadding = false
            setPadding(f, f, f, f)
            isFocusable = true
            isClickable = true
            setOnClickListener { startActivity(Intent(this@MainActivity, AlarmesActivity::class.java)) }
            setOnFocusChangeListener { v, temFoco -> v.background = if (temFoco) realceFoco() else null }
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(-f, -f, -f, -f) })
        blocoHora.addView(TextClock(this).apply {
            format12Hour = "EEEE, d 'de' MMMM"
            format24Hour = "EEEE, d 'de' MMMM"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            maxLines = 1   // "segunda-feira, 2 de novembro" quebrava em 2 linhas: encolhe a letra até caber (mín. 12 sp)
            setAutoSizeTextTypeUniformWithConfiguration(12, 16, 1, TypedValue.COMPLEX_UNIT_SP)
            setTextColor(if (tema == Tema.ESCURO) textoSuave else 0xFAF7F3EA.toInt())
            typeface = fonteUi
            if (tema != Tema.ESCURO) setShadowLayer(dp(8).toFloat(), 0f, 0f, 0x99000000.toInt())
            letterSpacing = 0.03f
            setPadding(f, f + dp(4), f, f)
            isFocusable = true
            isClickable = true
            setOnClickListener { startActivity(Intent(this@MainActivity, CalendarioActivity::class.java)) }
            setOnFocusChangeListener { v, temFoco -> v.background = if (temFoco) realceFoco() else null }
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(-f, -f, -f, -f) })
        topo.addView(blocoHora, FrameLayout.LayoutParams(dp(Grade.largura(4)), WRAP).apply { leftMargin = dp(Grade.x(1)) })

        // Clima (como na foto de referencia), em tres linhas, alinhadas a esquerda como a hora:
        //   [icone] ↑ max         icone do tempo, com a maxima em cima e a minima embaixo, ao lado dele
        //           ↓ min
        //   31°                   temperatura
        //   ☔ Chuva 80% em 3 h    previsao de chuva
        // Antes dele, uma barra fina vertical separa o clima da hora.
        txtIcone = texto(44f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        imgIcone = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START; adjustViewBounds = true; visibility = View.GONE }   // FIT_START: o desenho encosta na esquerda, alinhado com o texto de baixo
        iconeEclipse = IconeEclipse(this).apply { visibility = View.GONE }
        val caixaIcone = FrameLayout(this).apply {
            addView(txtIcone, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
            addView(imgIcone, FrameLayout.LayoutParams(WRAP, MATCH, Gravity.START))
            addView(iconeEclipse, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        txtMax = texto(16f, textoClaro).apply { includeFontPadding = false }
        txtMin = texto(16f, textoClaro).apply {
            includeFontPadding = false
            setPadding(0, dp(6), 0, 0)
        }
        txtTemp = texto(if (tema == Tema.ESCURO) 56f else 60f, Color.WHITE, fonteLeve).apply {
            letterSpacing = -0.02f
            includeFontPadding = false
            text = "…"
        }
        // Linha 1 (mesma altura do numero da hora): [icone][temperatura][max / min empilhadas]
        val linhaIcone = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(caixaIcone, LinearLayout.LayoutParams(WRAP, dp(80)).apply { marginEnd = dp(10) })
            addView(txtTemp, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(txtMax)
                addView(txtMin)
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(14) })
        }
        // Linha 2 (mesma altura da data): a chuva, com o mesmo tamanho da data.
        txtChuva = texto(16f, textoClaro).apply {
            letterSpacing = 0.03f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        linhaChuva = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
            addView(txtChuva, LinearLayout.LayoutParams(WRAP, WRAP))
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
            addView(linhaIcone, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(linhaChuva, LinearLayout.LayoutParams(WRAP, WRAP))
        }
        topo.addView(blocoClima, FrameLayout.LayoutParams(WRAP, WRAP).apply {
            leftMargin = dp(Grade.x(5) - folgaFoco)
            topMargin = -dp(folgaFoco)
        })
        // A barra separadora: no meio da calha entre as colunas 4 e 5, com a altura do bloco da hora.
        val barra = View(this).apply { setBackgroundColor(0x66FFFFFF) }
        topo.addView(barra, FrameLayout.LayoutParams(dp(2), WRAP).apply { leftMargin = dp(Grade.x(5) - Grade.VAO / 2 - 1) })

        blocoHora.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val altura = v.height   // altura do bloco hora + data (as folgas do realce se anulam com as margens negativas)
            // As duas linhas do clima tem a altura das duas linhas da esquerda: icone/temperatura/max-min na da hora, chuva na da data.
            val hora = blocoHora.getChildAt(0).height - 2 * f
            val data = blocoHora.getChildAt(1).height - 2 * f
            // O icone do clima tem a altura do numero da hora.
            if (caixaIcone.layoutParams.height != hora) {
                caixaIcone.layoutParams = caixaIcone.layoutParams.apply { height = hora }
                iconeEclipse.layoutParams = FrameLayout.LayoutParams(hora, hora, Gravity.START)
            }
            if (linhaIcone.layoutParams.height != hora || linhaChuva.layoutParams.height != data) {
                linhaIcone.layoutParams = linhaIcone.layoutParams.apply { height = hora }
                linhaChuva.layoutParams = linhaChuva.layoutParams.apply { height = data }
            }
            if (barra.layoutParams.height != altura) {
                barra.layoutParams = barra.layoutParams.apply { height = altura }
            }
        }

        // Bolinha: exatamente a coluna 12 (50 dp). Centrada na altura da linha grande (hora/temperatura).
        val bolinha = botaoPerfil()
        topo.addView(bolinha, FrameLayout.LayoutParams(dp(Grade.COLUNA), dp(Grade.COLUNA)).apply { leftMargin = dp(Grade.x(12)) })
        blocoHora.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            // centro do numero da hora = metade da altura do TextClock sem a folga do realce (a folga se anula com a margem negativa)
            val alturaHora = (v as LinearLayout).getChildAt(0).height - 2 * dp(folgaFoco)
            bolinha.translationY = ((alturaHora - bolinha.height) / 2f).coerceAtLeast(0f)
        }
        raiz.addView(topo, FrameLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(Grade.TOPO) })

        // ---- Linha de apps (rola na horizontal). O padding de cima/baixo e a folga para o card focado crescer;
        // por isso a rolagem comeca FOLGA acima da linha da grade.
        val folga = 24
        rolagem = HorizontalScrollView(this).apply {
            clipChildren = false    // senão a rolagem corta o card focado quando ele cresce
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(Grade.MARGEM - Grade.VAO_DISCO / 2), dp(folga) - dp(ANEL), dp(Grade.MARGEM - Grade.VAO_DISCO / 2), dp(folga))
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

        val vivo = criarFundo(this, tema) { bloco -> rede.execute { bloco() } }
        fundo = vivo
        // Sem fundo preto aqui (nem na janela, ver onCreate): o fundo vivo já cobre a tela inteira e é opaco. Cada camada
        // de tela cheia a mais é a GPU pintando 1920x1080 de novo A CADA QUADRO (medido: 3 camadas, 22 ms por quadro).
        return FrameLayout(this).apply {
            addView(vivo, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(raiz, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(txtDica, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(32)
            })
        }
    }

    /** Passa ao fundo vivo a hora, o lugar e o clima de agora (ver Fundos.kt). */
    private fun atualizarFundo() {
        val p = pontoAtual
        (fundo as? FundoVivo)?.atualizar(EstadoCeu(System.currentTimeMillis(), p?.lat ?: Config.LATITUDE,
            p?.lon ?: Config.LONGITUDE, ultimoCodigo, ultimoChovendo))
    }

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


    /** Contorno branco dos blocos do topo (hora, clima) quando estao com foco. */
    private fun realceFoco() = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        setColor(0x14FFFFFF)
        setStroke(dp(2), 0xD9FFFFFF.toInt())
    }
    private var ultimaMedida: Medida? = null   // a ultima leitura boa do aeroporto (ver buscarClima)
    private var ultimaMedidaMs = 0L
    private var ultimaTemp: Int? = null   // a temperatura mostrada no topo (para a linha "Hoje" da previsao bater com ela)

    /** Abre a tela da previsao (ver PrevisaoActivity.kt); leva a temperatura do topo para as duas telas baterem. */
    private fun abrirPrevisao() {
        startActivity(Intent(this, PrevisaoActivity::class.java)
            .putExtra(PrevisaoActivity.EXTRA_TEMP, ultimaTemp ?: Int.MIN_VALUE)
            .putExtra(PrevisaoActivity.EXTRA_CHOVENDO, ultimoChovendo))
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

    /** pacote -> (APK|activity, Item). Vive enquanto o processo da HOME viver (sobrevive à troca de tema). */
    private companion object { val cacheApps = HashMap<String, Pair<String, Item>>() }

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
            // Cache por APK (04/11/2026): isto roda na thread da tela a cada volta à HOME, e o serviço de teclas do
            // controle divide essa thread — medido 107-260 ms só aqui. O caminho do APK muda quando o app é
            // atualizado, então serve de chave: app igual = reaproveita nome e ícone já carregados.
            // (Antes também carregava o banner, que nada usava, e o ícone duas vezes.)
            val chave = ri.activityInfo.applicationInfo.sourceDir + "|" + ri.activityInfo.name
            itens[pkg] = cacheApps[pkg]?.takeIf { it.first == chave }?.second ?: run {
                val icone = ri.loadIcon(pm)
                Item(pkg, ri.loadLabel(pm).toString(), icone, false, iconeRedondo(icone)).also { cacheApps[pkg] = chave to it }
            }
        }
        cacheApps.keys.retainAll(itens.keys)   // app desinstalado sai do cache
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

        // Lido fora da tela por preencherApps (queryAndAggregateUsageStats de 14 dias é caro); aqui só se usa o mapa.
        val usados = ultimoUso
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
        // Carregar os apps (PackageManager + ícone redondo de cada um) roda FORA da thread da tela (04/11/2026: um ANR de
        // 5 s tinha iconeRedondo na pilha da thread principal, no 1º onResume do processo). Só o desenho volta para ela.
        val t0 = android.os.SystemClock.elapsedRealtime()
        filaApps.execute {
            val todos = carregarApps()   // cacheApps só é tocado nesta fila (uma thread)
            // Uso recente também aqui fora (04/11/2026): antes rodava dentro de montarEntradas, na thread da tela.
            val uso = if (Config.ORDENAR_POR_USO_RECENTE) Uso.ultimoUso(this) else emptyMap()
            val t1 = android.os.SystemClock.elapsedRealtime()
            ui.post {
                if (isDestroyed) return@post
                instalados = todos
                ultimoUso = uso   // reorganizar() reaproveita este mapa até a próxima volta à HOME
                lista = montarEntradas(instalados)
                desenhar()
                // Medir para não deixar crescer: a thread da tela também atende as teclas do controle.
                android.util.Log.i("TvLauncher.Apps", "preencherApps: carregar=${t1 - t0} ms (fora da tela), " +
                    "desenhar=${android.os.SystemClock.elapsedRealtime() - t1} ms")
            }
        }
    }
    private val filaApps = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "Apps") }
    /** pacote -> último uso (ms), lido por preencherApps na filaApps. Só lido/escrito na thread da tela. */
    private var ultimoUso: Map<String, Long> = emptyMap()

    /** Só reorganiza (pilhas, ordem, ocultos) com os apps já carregados: síncrono, quem chama lê a [lista] em seguida. */
    private fun reorganizar() {
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
     * Um card: um disco de [Grade.DISCO] dp (o ícone do app, recortado em círculo) e o nome embaixo, centralizado.
     * O foco aparece como um anel branco no disco (e o disco cresce 12%); o nome fica branco. Serve para a linha principal e
     * para dentro das pastas. [token] identifica o card (modo de mover e menu), [aoAbrir] roda ao apertar OK.
     * O card ocupa DISCO + VAO_DISCO de largura: o disco fica no meio e o nome tem espaço para respirar.
     */
    private fun montarCartao(token: String, nome: String, miniatura: View, aoAbrir: () -> Unit, aoFocar: (View) -> Unit): View {
        val cartao = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            isFocusable = true
            isClickable = true
            tag = token
            setOnClickListener { aoAbrir() }
            setOnFocusChangeListener { v, temFoco ->
                estilizar(v, token, temFoco)
                if (temFoco) aoFocar(v)
            }
        }
        // filho 0: o disco (o anel de foco é desenhado POR CIMA, no foreground). filho 1: o nome.
        val moldura = FrameLayout(this).apply {
            clipChildren = false
            // o disco fica 6 dp para dentro: o anel de foco é desenhado na borda da moldura, com uma folga, como no Google TV
            addView(miniatura, FrameLayout.LayoutParams(MATCH, MATCH).apply { setMargins(dp(ANEL), dp(ANEL), dp(ANEL), dp(ANEL)) })
            foreground = fioFino()
        }
        cartao.addView(moldura, LinearLayout.LayoutParams(dp(Grade.DISCO + 2 * ANEL), dp(Grade.DISCO + 2 * ANEL)))
        cartao.addView(
            texto(15f, textoSuave).apply {
                text = nome
                maxLines = 1
                gravity = Gravity.CENTER
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(dp(Grade.DISCO + Grade.VAO_DISCO), WRAP).apply { topMargin = dp(8) }
        )
        cartao.layoutParams = LinearLayout.LayoutParams(dp(Grade.DISCO + Grade.VAO_DISCO), WRAP)
        return cartao
    }

    /** O ícone do app num disco (já pronto em [iconeRedondo]). */
    private fun miniaturaApp(app: Item): View = discoDoApp(app)

    private fun discoDoApp(app: Item): ImageView = ImageView(this).apply {
        setImageDrawable(copia(app.icone))
        scaleType = ImageView.ScaleType.CENTER_CROP
        circular(this)
    }

    /**
     * Prepara o ícone para o disco, UMA vez por app (na carga da lista, não a cada quadro):
     * - adaptativo (a maioria): desenha as camadas de fundo e frente sem máscara; o disco recorta em círculo.
     * - comum (ícone quadrado, ex.: YouTube, 1-Stream): pinta o fundo com a cor da borda do próprio ícone e aumenta o
     *   ícone 18%, para os cantos arredondados dele saírem do círculo e não aparecer "um quadrado dentro da bola".
     * Vira um bitmap de 192 px: desenhar bitmap é mais barato que redesenhar o ícone vetorial em cada animação de foco.
     */
    private fun iconeRedondo(d: Drawable): Drawable {
        val t = 192
        val bmp = Bitmap.createBitmap(t, t, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (d is android.graphics.drawable.AdaptiveIconDrawable) {
            // As camadas têm 108 unidades; a parte visível são as 72 do meio: desenha 1,5x maior e centralizado.
            val extra = t / 4
            d.background?.let { it.setBounds(-extra, -extra, t + extra, t + extra); it.draw(c) }
            d.foreground?.let { it.setBounds(-extra, -extra, t + extra, t + extra); it.draw(c) }
        } else {
            val amostra = Bitmap.createBitmap(t, t, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, t, t); d.draw(Canvas(amostra))
            val px = amostra.getPixel(t / 2, t / 12)   // perto da borda de cima, no meio: a cor do fundo do ícone
            c.drawColor(if ((px ushr 24) > 200) px else 0xFFF2F2F2.toInt())
            val m = (t * 0.09f).toInt()
            d.setBounds(-m, -m, t + m, t + m); d.draw(c)
            amostra.recycle()
        }
        return android.graphics.drawable.BitmapDrawable(resources, bmp)
    }

    /** Recorta a view em círculo. */
    private fun circular(v: View) {
        v.clipToOutline = true
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
    }

    /**
     * Pilha: o disco do app da [frente] na frente e até 2 atrás, deslocados para cima e para a direita (como cartas).
     * Embaixo, o selo "▲▼ 1/3": a pilha tem vários apps e as setas trocam qual fica na frente. O nome é o do app da frente.
     */
    private fun miniaturaPilha(itens: List<Item>, frente: Int): View {
        val camadas = (listOf(itens[frente]) + itens.filterIndexed { i, _ -> i != frente }).take(3)
        val caixa = FrameLayout(this).apply { clipChildren = false }
        for (i in camadas.indices.reversed()) {   // de trás para a frente: o último adicionado fica por cima
            val disco = discoDoApp(camadas[i]).apply {
                alpha = if (i == 0) 1f else 0.85f - 0.25f * (i - 1)
                if (i > 0) setColorFilter(0xFF888888.toInt(), android.graphics.PorterDuff.Mode.MULTIPLY)   // escurece as de trás
            }
            caixa.addView(disco, FrameLayout.LayoutParams(MATCH, MATCH).apply {
                leftMargin = dp(8) * i
                topMargin = -dp(5) * i
                rightMargin = -dp(8) * i
                bottomMargin = dp(5) * i
            })
        }
        caixa.addView(IndicadorPilha(this),
            FrameLayout.LayoutParams(dp(IndicadorPilha.LARGURA_DP), dp(IndicadorPilha.ALTURA_DP), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = -dp(8)
            })
        return caixa
    }

    private fun miniaturaOcultos(): View = FrameLayout(this).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x26FFFFFF) }
        addView(texto(32f, Color.WHITE).apply {
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

    private var cimaAgendado: Runnable? = null   // o cronometro do "segurar ▲"; null = nenhum rodando
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
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                cimaSaiu = false
                cimaAgendado?.let { ui.removeCallbacks(it) }
                // Um cronometro, em vez de contar as repeticoes da tecla segurada: a 1a repeticao so chega ~0,5 s depois do aperto
                // e nem todo controle repete. Soltar antes do tempo cancela o cronometro e a tecla vira "trocar o app da frente".
                cimaAgendado = Runnable {
                    cimaAgendado = null
                    if (currentFocus === atual) {
                        cimaSaiu = true
                        atual.focusSearch(View.FOCUS_UP)?.requestFocus()
                    }
                }.also { ui.postDelayed(it, Config.PILHA_SUBIR_MS) }
            }
            KeyEvent.ACTION_UP -> {
                cimaAgendado?.let { ui.removeCallbacks(it) }
                cimaAgendado = null
                if (!cimaSaiu) girarPilha(pilha, -1)
            }
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
                reorganizar()
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
        reorganizar()
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
        // Sem anel no foco (o circulo branco ficava pesado sobre o icone): so o disco crescendo 12% e o nome
        // ficando branco ja mostram onde esta, como no Google TV de referencia. O fio fino continua sempre
        // (nao so no foco): e o que impede um icone escuro (ex.: Netflix) de sumir no fundo.
        (grupo?.getChildAt(0))?.foreground = if (movendo) contorno(corMover, 0x2E7FD6C2, 3) else fioFino()
        (grupo?.getChildAt(1) as? TextView)?.setTextColor(if (focado || movendo) Color.WHITE else textoSuave)
        // Só o disco cresce (o nome não: crescendo junto, ele encostava no nome do vizinho).
        val escala = if (movendo) 1.16f else if (focado) 1.12f else 1f
        (grupo?.getChildAt(0) ?: card).animate().scaleX(escala).scaleY(escala).setDuration(120).start()
    }

    private val ANEL = 6   // folga (dp) entre o disco e o anel de foco

    /** Fio de 1 dp em volta do disco (não da moldura): ícones escuros (Netflix) não somem no fundo. */
    private fun fioFino() = android.graphics.drawable.InsetDrawable(contorno(0x33FFFFFF, Color.TRANSPARENT, 1), dp(ANEL))

    /** Anel circular de [espessura] dp (com [preenchimento] por dentro), desenhado por cima do disco. */
    private fun contorno(cor: Int, preenchimento: Int, espessura: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(preenchimento)
        setStroke(dp(espessura), cor)
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
        reorganizar()
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
        reorganizar()
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
                    "&hourly=precipitation_probability&forecast_hours=24" +
                    "&daily=temperature_2m_max,temperature_2m_min" +
                    "&timezone=auto&forecast_days=1$unidade"
                val json = JSONObject(httpGet(url) ?: return@execute)
                val atual = json.getJSONObject("current")
                val dia = json.getJSONObject("daily")
                // Temperatura: a MEDIDA na estacao do aeroporto (METAR) vence a do modelo da Open-Meteo. Em 31/10 as 16:15
                // o modelo dizia 26,6 °C e o aeroporto de Ribeirao Preto mediu 32 °C. Sem METAR recente, fica o modelo.
                // Se o METAR falhar (timeout acontece), vale a ultima medida boa de ate 60 min, e nao o modelo: em 01/11 as 19:13 e 19:20
                // o METAR deu timeout e o modelo mostrou 24 C e "chovendo agora" com o aeroporto em 31 C, CAVOK.
                val nova = medidaAeroporto(ponto) ?: medidaAeroporto(ponto)   // uma segunda tentativa
                if (nova != null) { ultimaMedida = nova; ultimaMedidaMs = System.currentTimeMillis() }
                val medida = nova ?: ultimaMedida?.takeIf { System.currentTimeMillis() - ultimaMedidaMs < 60 * 60_000L }
                registrarComparacao(ponto, nova)
                // Umidade: a do aeroporto (calculada da temperatura e do ponto de orvalho medidos); sem ela, a do modelo.
                val umidade = medida?.umidade ?: atual.optInt("relative_humidity_2m", -1).takeIf { it >= 0 }
                val temp = medida?.temp ?: Math.round(atual.getDouble("temperature_2m")).toInt()
                // Minima/maxima do dia vem do modelo; se a medida de agora ja passou delas, o modelo errou: corrige com ela.
                val max = maxOf(temp, Math.round(dia.getJSONArray("temperature_2m_max").getDouble(0)).toInt())
                val min = minOf(temp, Math.round(dia.getJSONArray("temperature_2m_min").getDouble(0)).toInt())
                // Tempo AGORA: o aeroporto (o que um observador VIU) vence o modelo. Em 31/10 as 16:54 o modelo dizia
                // "garoa, chovendo agora" com ceu aberto; o aeroporto dizia CAVOK (sem chuva).
                var codigo = atual.getInt("weather_code")
                var chovendoAgora = atual.optDouble("precipitation", 0.0) >= 0.2 && codigo >= 51   // so o modelo (sem METAR): exige chuva de verdade, nao qualquer traco
                if (medida != null) {
                    val visto = medida.fenomeno
                    // O aeroporto vence o modelo SEMPRE que ha observacao: chuva/neblina (visto), ou, sem nada disso,
                    // a nebulosidade que ele mediu (medida.nuvens). Antes so corrigia o modelo quando codigo >= 45
                    // (chuva/neblina inventada); um "nublado" (codigo 1-3) errado do modelo ficava sem correcao —
                    // bug visto em 03/11: modelo dizia nublado com o aeroporto (e o ceu real) limpo.
                    codigo = visto ?: medida.nuvens
                    chovendoAgora = visto != null && visto >= 51
                }
                // Dia/noite calculado AQUI, nao pelo "is_day" da Open-Meteo: ele vira noite no instante exato do por do
                // sol, sem o crepusculo civil (a claridade que sobra no ceu por uns 20 min depois). Flagrado em 03/11
                // as 18:38 (por as 18:24, icone ja de lua, ceu da varanda ainda claro). Usa o nascer/por REAL do lugar
                // (Astronomia.nascerEPor) com margem so no por (Config.MARGEM_CREPUSCULO_MIN); cai no is_day da API
                // se o calculo falhar (nao deve, exceto perto dos polos).
                val agoraMs = System.currentTimeMillis()
                val eDia = Astronomia.eDia(agoraMs, ponto.lat, ponto.lon, atual.optInt("is_day", 1) == 1)
                val (iconeDoTempo, descricao) = descricaoClima(codigo, eDia, chovendoAgora)
                // A linha informativa do clima: se chove agora ou ha previsao de chuva HOJE (primeira hora com chance >= 40%),
                // diz quando; senao, diz a condicao do tempo de agora (ex.: "Poucas nuvens").
                // Chance de chuva: a MEDIANA de ECMWF+ICON+GFS, nao o best_match (≈ECMWF) sozinho. Medido no CSV de comparacao:
                // em horas que o aeroporto marcou SEM chuva, o ECMWF deu 49-75% enquanto ICON/GFS davam 10-18%. A mediana
                // dos tres descarta esse exagero. Se a chamada multi-modelo falhar, cai no best_match da chamada principal.
                val medModelo = chuvaMediana(ponto)
                val horasData: List<String>; val horasChuva: List<Int>
                if (medModelo != null) { horasData = medModelo.first; horasChuva = medModelo.second }
                else {
                    val hc = json.getJSONObject("hourly").getJSONArray("precipitation_probability")
                    val ht = json.getJSONObject("hourly").getJSONArray("time")
                    horasChuva = (0 until hc.length()).map { hc.optInt(it, 0) }
                    horasData = (0 until ht.length()).map { ht.getString(it) }
                }
                val hoje = atual.optString("time", "").take(10)   // "2026-11-01"
                var primeira = -1   // quantas horas faltam para a primeira hora de hoje com chance de chuva >= 40%
                var picoChuva = 0
                for (i in horasChuva.indices) {
                    if (i >= horasData.size || !horasData[i].startsWith(hoje)) break   // so ate o fim do dia
                    val p = horasChuva[i]
                    if (p >= 40) { primeira = i; picoChuva = p; break }
                }
                val textoLinha = when {
                    chovendoAgora -> "Chovendo agora"
                    primeira == 0 -> "$descricao - Chuva $picoChuva% neste momento"
                    primeira > 0 -> "$descricao - Chuva $picoChuva% em $primeira h"
                    else -> descricao
                }
                // De noite, ceu limpo/poucas nuvens/parcial mostram a lua na fase de hoje no lugar do sol
                // (se estiver chovendo, vale a chuva, como de dia). Chuva, neblina etc. mantem o proprio icone.
                val luaVirouIcone = !eDia && codigo == 0 && !chovendoAgora   // noite limpa: a lua na fase de hoje; com nuvens, o icone lua+nuvem
                val icone = when {
                    // ponto.lat (lugar real), não Config.LATITUDE: o desenho da fase depende do hemisfério (revisão 04/11).
                    luaVirouIcone -> Lua.chaveIcone(System.currentTimeMillis(), ponto.lat)
                    else -> iconeDoTempo   // (se o codigo diz "nublado" mas esta chovendo, descricaoClima ja devolve a chuva)
                }
                // Decodifica e apara o ícone AQUI, fora da thread da tela (04/11/2026: um ANR de 5 s do launcher tinha
                // IconesClima.aparar na pilha da thread principal). Na tela, bitmap() já acha pronto no cache.
                if (IconesClima.ehIcone(icone)) IconesClima.bitmap(this, icone)
                ui.post {
                    pontoAtual = ponto
                    ultimoCodigo = codigo
                    ultimoChovendo = chovendoAgora
                    atualizarFundo()
                    iconeClima = icone
                    ceuCoberto = codigo > 2 || chovendoAgora
                    txtTemp.text = "$temp°"
                    ultimaTemp = temp
                    // Guarda o que o topo mostra: a previsão aberta por voz ("Boletim do Tempo") e o protetor de tela
                    // (ProtetorTela.kt, sem rede própria) não passam por aqui e leem isto.
                    getSharedPreferences("clima", MODE_PRIVATE).edit()
                        .putInt("temp", temp).putBoolean("chovendo", chovendoAgora)
                        .putInt("codigo", codigo).putBoolean("eDia", eDia)
                        .putInt("umidade", umidade ?: -1).putInt("chuva", picoChuva)
                        .putFloat("lat", ponto.lat.toFloat()).putFloat("lon", ponto.lon.toFloat())
                        .putLong("em", System.currentTimeMillis()).apply()
                    txtMax.text = "↑ $max°"
                    txtMin.text = "↓ $min°"
                    txtChuva.text = textoLinha
                    atualizarEclipse()   // escolhe o icone: o do clima ou, num eclipse real, o sol coberto
                }
            } catch (e: Exception) {
                // Sem internet ou API fora do ar: mantem o ultimo valor mostrado.
            }
        }
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
            imgIcone.visibility = View.GONE
        } else if (IconesClima.ehIcone(icone) && IconesClima.bitmap(this, icone) != null) {
            imgIcone.setImageBitmap(IconesClima.bitmap(this, icone))
            imgIcone.visibility = View.VISIBLE
            txtIcone.visibility = View.GONE
            iconeEclipse.visibility = View.GONE
        } else {
            imgIcone.visibility = View.GONE
            txtIcone.text = icone
            txtIcone.visibility = View.VISIBLE
            iconeEclipse.visibility = View.GONE
        }
    }


    /** Códigos de clima da Open-Meteo (padrão WMO) -> chave do ícone (IconesClima.kt) + texto. O eclipse (ICONE_ECLIPSE) só entra em atualizarEclipse(). */
    private fun descricaoClima(codigo: Int, dia: Boolean = true, chovendo: Boolean = false): Pair<String, String> {
        val texto = IconesClima.texto(codigo)
        return IconesClima.chave(codigo, dia, chovendo) to texto
    }

    private var ultimaComparacaoMs = 0L

    /**
     * TESTE DE FONTES (so grava, nao muda o que a tela mostra): a cada 30 min guarda uma linha em
     * Android/data/br.paulo.tvlauncher/files/comparacao_clima.csv com o que o aeroporto MEDIU (temperatura e fenomeno) e o que tres
     * modelos da Open-Meteo (ECMWF, ICON, GFS) PREVIAM para esta hora (temperatura e chance de chuva). Depois de alguns dias da para ver
     * qual modelo chega mais perto da medida. Chamar fora da thread da tela.
     */
    /**
     * Chance de chuva por hora (24 h) como MEDIANA de tres modelos (ECMWF, ICON, GFS). Uma chamada so a Open-Meteo,
     * com ?models=... : cada variavel volta com sufixo por modelo (precipitation_probability_ecmwf_ifs025, ...),
     * e o eixo "time" e compartilhado. null se a chamada falhar (quem chama cai no best_match). Nao verifiquei todos
     * os casos de borda da API; por isso o try e o fallback.
     */
    private fun chuvaMediana(ponto: Localizacao.Ponto): Pair<List<String>, List<Int>>? = try {
        val modelos = listOf("ecmwf_ifs025", "icon_global", "gfs_seamless")
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${ponto.lat}&longitude=${ponto.lon}" +
            "&hourly=precipitation_probability&forecast_hours=24&timezone=auto&models=${modelos.joinToString(",")}"
        val h = JSONObject(httpGet(url) ?: throw IllegalStateException("sem resposta")).getJSONObject("hourly")
        val tempo = h.getJSONArray("time")
        val arrays = modelos.mapNotNull { h.optJSONArray("precipitation_probability_$it") }
        if (arrays.isEmpty()) throw IllegalStateException("sem modelos")
        val datas = (0 until tempo.length()).map { tempo.getString(it) }
        val mediana = (0 until tempo.length()).map { i ->
            val vals = arrays.mapNotNull { a -> if (i < a.length() && !a.isNull(i)) a.optInt(i, -1).takeIf { it >= 0 } else null }.sorted()
            when {
                vals.isEmpty() -> 0
                vals.size % 2 == 1 -> vals[vals.size / 2]
                else -> (vals[vals.size / 2 - 1] + vals[vals.size / 2]) / 2
            }
        }
        datas to mediana
    } catch (e: Exception) {
        Log.w("TvLauncher.Local", "chuva multi-modelo falhou: $e")
        null
    }

    private fun registrarComparacao(ponto: Localizacao.Ponto, medida: Medida?) {
        val agora = System.currentTimeMillis()
        // ultimaComparacaoMs zerava a cada abertura do app -> gravava linha repetida a cada boot. Agora o carimbo
        // persiste em SharedPreferences, entao vale o intervalo de 30 min mesmo apos reiniciar o launcher.
        val prefs = getSharedPreferences("clima", MODE_PRIVATE)
        if (ultimaComparacaoMs == 0L) ultimaComparacaoMs = prefs.getLong("ultima_comparacao", 0)
        if (agora - ultimaComparacaoMs < 30 * 60_000L) return
        try {
            val modelos = listOf("ecmwf_ifs025", "icon_global", "gfs_seamless")
            val url = "https://api.open-meteo.com/v1/forecast?latitude=${ponto.lat}&longitude=${ponto.lon}" +
                "&hourly=precipitation_probability,temperature_2m&forecast_hours=1&timezone=auto&models=${modelos.joinToString(",")}"
            val h = JSONObject(httpGet(url) ?: return).getJSONObject("hourly")
            val colunas = modelos.flatMap { m ->
                listOf(h.getJSONArray("temperature_2m_$m").optString(0), h.getJSONArray("precipitation_probability_$m").optString(0))
            }
            val linha = (listOf(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(agora)),
                medida?.temp?.toString() ?: "", medida?.fenomeno?.toString() ?: "") + colunas).joinToString(",")
            val arq = java.io.File(getExternalFilesDir(null), "comparacao_clima.csv")
            if (!arq.exists()) arq.writeText("hora,metar_temp,metar_fenomeno,ecmwf_temp,ecmwf_chuva,icon_temp,icon_chuva,gfs_temp,gfs_chuva\n")
            arq.appendText(linha + "\n")
            ultimaComparacaoMs = agora
            prefs.edit().putLong("ultima_comparacao", agora).apply()
        } catch (e: Exception) {
            Log.w("TvLauncher.Local", "comparacao falhou: $e")
        }
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
        typeface = fonte ?: fonteUi
        // Sobre o céu ou a pintura, uma sombra suave mantém o texto legível em qualquer cor de fundo.
        if (tema != Tema.ESCURO) setShadowLayer(dp(8).toFloat(), 0f, 0f, 0x99000000.toInt())
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
