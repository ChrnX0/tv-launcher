package br.paulo.tvlauncher

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.net.NetworkInterface
import java.util.concurrent.Executors

/**
 * Pareamento da depuração sem fio PELA PRÓPRIA TV, sem PC (item 5, 04/11/2026).
 *
 * O problema: o código de 6 dígitos do Android só vale enquanto a tela "Parear dispositivo com código" está aberta
 * (medido hoje: o código da foto expirou e a porta deu ECONNREFUSED assim que a tela fechou). Então não dá para ler o
 * código, voltar ao launcher e digitar. A solução (a mesma ideia do fork thedjchi para TV/VR) é uma JANELA FLUTUANTE do
 * launcher por cima da tela de pareamento do Android:
 *
 *  1. [comecar] abre as Opções do desenvolvedor e mostra a janela, ainda SEM pegar o foco (o controle continua mexendo
 *     nas Configurações, para você chegar em Depuração sem fio › Parear dispositivo com código).
 *  2. Enquanto isso a janela procura por mDNS o serviço _adb-tls-pairing._tcp DESTE aparelho (só existe com aquela tela
 *     aberta). Achou: mostra a porta e passa a pegar o foco (teclado de 0 a 9 operado pelo D-pad).
 *  3. "Parear": pair(127.0.0.1, porta, código) pelo gerente ADB e, dando certo, o guardião sobe o Shizuku.
 *
 * Precisa da permissão de sobreposição (SYSTEM_ALERT_WINDOW). O guardião a concede sozinho pelo shell (appops) quando o
 * Shizuku sobe; ver [GuardiaoShizuku]. Sem ela, [podeMostrar] é false e a tela de Configurações avisa.
 */
object PareamentoNaTv {
    private const val SERVICO = "_adb-tls-pairing._tcp"
    private val principal = Handler(Looper.getMainLooper())
    private val trabalho = Executors.newSingleThreadExecutor()
    private var janela: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var ouvinte: NsdManager.DiscoveryListener? = null
    private var porta = -1
    private val codigo = StringBuilder()
    private lateinit var linhaPorta: TextView
    private lateinit var linhaCodigo: TextView
    private lateinit var linhaEstado: TextView

    /**
     * A janela é do SERVIÇO DE ACESSIBILIDADE do launcher (TYPE_ACCESSIBILITY_OVERLAY), não uma janela flutuante comum:
     * medido em 04/11/2026, as Opções do desenvolvedor da TV escondem as janelas flutuantes de apps
     * (dumpsys window: mForceHideNonSystemOverlayWindow=true). A janela de acessibilidade não entra nesse bloqueio.
     */
    fun podeMostrar(@Suppress("UNUSED_PARAMETER") ctx: Context) = AutorizaShizukuService.instancia != null

    fun comecar(ctx: Context): Boolean {
        val app = ctx.applicationContext
        val svc = AutorizaShizukuService.instancia ?: return false
        // Janela antiga ainda na tela: só reabre as Configurações. Janela antiga que SUMIU (o serviço de acessibilidade
        // foi religado, e as janelas dele vão junto — medido com o uiautomator): esquece e monta de novo.
        if (janela?.isAttachedToWindow == true) return true
        fechar(app)
        porta = -1; codigo.setLength(0)
        mostrarJanela(svc)
        procurarPorta(app)
        try {
            app.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { estado("não abri as Opções do desenvolvedor: $e") }
        return true
    }

    fun fechar(ctx: Context) {
        val app = ctx.applicationContext
        ouvinte?.let { try { (app.getSystemService(Context.NSD_SERVICE) as NsdManager).stopServiceDiscovery(it) } catch (_: Exception) {} }
        ouvinte = null
        janela?.let { j -> try { j.context.getSystemService(WindowManager::class.java).removeView(j) } catch (_: Exception) {} }
        janela = null
    }

    // ---------------------------------------------------------------- janela

    private fun dp(ctx: Context, v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    private fun mostrarJanela(app: Context) {
        fun texto(t: String, tam: Float, cor: Int = Color.WHITE) = TextView(app).apply { text = t; textSize = tam; setTextColor(cor) }
        fun tecla(rotulo: String, aoApertar: () -> Unit) = TextView(app).apply {
            text = rotulo; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            isFocusable = true
            setPadding(dp(app, 10), dp(app, 8), dp(app, 10), dp(app, 8))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = dp(app, 10).toFloat(); setColor(0x66FFFFFF) })
                addState(intArrayOf(), GradientDrawable().apply { cornerRadius = dp(app, 10).toFloat(); setColor(0x1AFFFFFF) })
            }
            setOnClickListener { aoApertar() }
        }

        linhaPorta = texto("Abra: Depuração sem fio › Parear dispositivo com código", 15f, 0xCCFFFFFF.toInt())
        linhaCodigo = texto("Código: ______", 26f)
        linhaEstado = texto("procurando a tela de pareamento…", 14f, 0xFF7FD6C2.toInt())

        val teclado = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
        val linhas = listOf(listOf("1", "2", "3", "4", "5"), listOf("6", "7", "8", "9", "0"))
        var primeira: View? = null
        for (l in linhas) {
            teclado.addView(LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                for (d in l) {
                    val t = tecla(d) { if (codigo.length < 6) { codigo.append(d); atualizarCodigo() } }
                    if (primeira == null) primeira = t
                    addView(t, LinearLayout.LayoutParams(dp(app, 52), dp(app, 48)).apply { setMargins(dp(app, 3), dp(app, 3), dp(app, 3), dp(app, 3)) })
                }
            })
        }
        teclado.addView(LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(tecla("Apagar") { if (codigo.isNotEmpty()) { codigo.setLength(codigo.length - 1); atualizarCodigo() } })
            addView(tecla("Parear") { parear(app) }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(app, 8) })
            addView(tecla("Fechar") { fechar(app) }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(app, 8) })
        })

        val painel = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(app, 20), dp(app, 16), dp(app, 20), dp(app, 16))
            background = GradientDrawable().apply { cornerRadius = dp(app, 18).toFloat(); setColor(0xF01C2024.toInt()) }
            addView(texto("Parear o launcher (sem PC)", 20f))
            addView(linhaPorta)
            addView(linhaCodigo)
            addView(teclado)
            addView(linhaEstado)
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Começa SEM foco: o controle continua nas Configurações até a tela de pareamento abrir.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
            // Enquanto procura, fica embaixo à direita (o menu das Configurações, por onde você navega, fica à esquerda).
        ).apply { gravity = Gravity.BOTTOM or Gravity.END; x = dp(app, 24); y = dp(app, 24) }
        app.getSystemService(WindowManager::class.java).addView(painel, p)
        janela = painel; params = p
        painel.tag = primeira
    }

    private fun atualizarCodigo() {
        linhaCodigo.text = "Código: " + codigo.toString().padEnd(6, '_')
    }

    private fun estado(t: String) {
        ServidorShizuku.anotar(janela?.context ?: return, "[pareamento na TV] $t")
        principal.post { if (janela != null) linhaEstado.text = t }
    }

    /** Achou a tela de pareamento aberta: a janela passa a pegar o foco do controle. */
    private fun pegarFoco(@Suppress("UNUSED_PARAMETER") app: Context) {
        val j = janela ?: return
        val p = params ?: return
        p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        // Vai para baixo à esquerda: na tela "Parear com o dispositivo" da TV o CÓDIGO fica na metade direita, e a
        // esquerda só tem o título. Medido em 04/11/2026: no canto superior direito a janela cobria o código.
        p.gravity = Gravity.BOTTOM or Gravity.START
        try { j.context.getSystemService(WindowManager::class.java).updateViewLayout(j, p) } catch (_: Exception) {}
        (j.tag as? View)?.requestFocus()
    }

    // ---------------------------------------------------------------- porta (mDNS) e pareamento

    private fun enderecosLocais(): Set<String> = try {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.mapNotNull { it.hostAddress }.toSet()
    } catch (_: Exception) { emptySet() }

    @Suppress("DEPRECATION")
    private fun procurarPorta(app: Context) {
        val nsd = app.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val locais = enderecosLocais()
        val o = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, erro: Int) {}
                    override fun onServiceResolved(i: NsdServiceInfo) {
                        val host = i.host?.hostAddress ?: return
                        // Só a porta DESTE aparelho: na rede há outros com depuração sem fio (ex.: 192.168.1.159).
                        if (host !in locais && host != "127.0.0.1") return
                        porta = i.port
                        principal.post {
                            if (janela == null) return@post
                            linhaPorta.text = "Tela de pareamento achada (porta $porta). Digite o código:"
                            estado("porta $porta achada")
                            pegarFoco(app)
                        }
                    }
                })
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, erro: Int) { estado("busca mDNS falhou ($erro)") }
            override fun onStopDiscoveryFailed(t: String, erro: Int) {}
        }
        try { nsd.discoverServices(SERVICO, NsdManager.PROTOCOL_DNS_SD, o); ouvinte = o } catch (e: Exception) { estado("busca mDNS: $e") }
    }

    private fun parear(app: Context) {
        val c = codigo.toString()
        if (porta <= 0) { estado("ainda não achei a tela de pareamento"); return }
        if (c.length != 6) { estado("o código tem 6 dígitos"); return }
        estado("pareando…")
        val p = porta
        trabalho.execute {
            val ok = try { AdbConnectionManager.obter(app).pair("127.0.0.1", p, c) } catch (e: Throwable) {
                estado("falhou: ${e.javaClass.simpleName}: ${e.message}"); false
            }
            if (!ok) { estado("o Android recusou o código"); return@execute }
            estado("pareado! ligando o Shizuku…")
            GuardiaoShizuku.parearEGarantir(app, null, -1)
            principal.postDelayed({ fechar(app) }, 4000)
        }
    }
}
