package br.paulo.tvlauncher

import android.accessibilityservice.AccessibilityService
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Desligar sozinha (05/11/2026, ideia aprovada no brainstorm): depois de X minutos sem NINGUÉM apertar o controle, a
 * TV avisa por 60 s ("vai desligar, aperte qualquer botão") e então desliga. É o "ainda está assistindo?" da Netflix,
 * para a TV que ficou ligada com todo mundo dormindo.
 *
 * - "Sem uso" = nenhuma tecla do controle. Quem vê as teclas é o serviço de acessibilidade (AutorizaShizukuService),
 *   que chama [atividade] a cada tecla; o painel no navegador também conta (PainelControle). Assistir um filme
 *   inteiro sem tocar no controle CONTA como sem uso: o aviso aparece e um botão qualquer o cancela.
 * - Sem o serviço de acessibilidade ligado ninguém veria as teclas, então NÃO desliga (desligaria no meio do uso).
 * - Custo: nenhum por tecla (só grava a hora); um único agendamento na thread da tela, que dorme até o prazo.
 * - Desligar = apagar a tela (o Chromecast manda a TV entrar em standby pelo HDMI-CEC, como o botão de ligar do
 *   controle). Três caminhos, na ordem: ação de acessibilidade "bloquear tela", lockNow do Device Owner, e a tecla
 *   SLEEP pelo nosso shell. Para no primeiro que apagar a tela de fato.
 * Valor em minutos nas preferências "launcher" (chave [CHAVE]); 0 = desligado (o padrão).
 */
object AutoDesligar {
    private const val TAG = "TvLauncher.AutoDesl"
    const val CHAVE = "auto_desligar_min"
    /** Opções oferecidas na TV e no painel. 0 = nunca. */
    val OPCOES = listOf(0, 30, 60, 90, 120, 180, 240)
    const val AVISO_S = 60

    private val principal = Handler(Looper.getMainLooper())
    @Volatile private var ultima = SystemClock.elapsedRealtime()
    private var app: Context? = null
    private var janela: View? = null
    private var textoAviso: TextView? = null
    private var restanteAviso = 0
    /** A tecla que cancelou o aviso: a "subida" dela também é engolida (senão o app da frente vê meia tecla). */
    private var engolirSubida = -1
    @Volatile var ultimoDesligamento = 0L; private set

    fun minutos(ctx: Context): Int = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).getInt(CHAVE, 0)

    fun definir(ctx: Context, min: Int): Boolean {
        if (min !in OPCOES) return false
        ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().putInt(CHAVE, min).apply()
        atividade()
        principal.post { reagendar() }
        Log.i(TAG, "desligar sozinha: ${if (min == 0) "nunca" else "$min min"}")
        return true
    }

    /** Liga o vigia. Idempotente. */
    fun iniciar(ctx: Context) = principal.post {
        if (app != null) return@post
        val a = ctx.applicationContext
        app = a
        // Tela acendeu = alguém ligou a TV (ou o despertador): conta do zero. Apagou: some com o aviso.
        a.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action == Intent.ACTION_SCREEN_ON) atividade() else fecharAviso()
                reagendar()
            }
        }, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) })
        reagendar()
    }

    /** Alguém mexeu (tecla do controle ou do painel). Barato: só a hora. */
    fun atividade() {
        ultima = SystemClock.elapsedRealtime()
        if (janela != null) principal.post { fecharAviso(); reagendar() }
    }

    /**
     * Chamado pelo serviço de acessibilidade ANTES de qualquer outro tratamento de tecla. true = engolir a tecla:
     * com o aviso na tela, o botão que o cancela não deve chegar ao app (senão um OK pausaria o filme, por exemplo).
     */
    fun tecla(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_UP && e.keyCode == engolirSubida) { engolirSubida = -1; return true }
        val comAviso = janela != null
        atividade()
        if (comAviso && e.action == KeyEvent.ACTION_DOWN) { engolirSubida = e.keyCode; return true }
        return false
    }

    private val verificar = Runnable { verificar() }
    private val contar = Runnable { contar() }

    private fun reagendar() {
        principal.removeCallbacks(verificar)
        val a = app ?: return
        val min = minutos(a)
        if (min == 0 || !telaAcesa(a)) return   // a tela acender chama reagendar() de novo
        val prazo = ultima + min * 60_000L - AVISO_S * 1000L
        principal.postDelayed(verificar, (prazo - SystemClock.elapsedRealtime()).coerceAtLeast(1000))
    }

    private fun verificar() {
        val a = app ?: return
        val min = minutos(a)
        if (min == 0 || !telaAcesa(a)) return
        val faltam = ultima + min * 60_000L - SystemClock.elapsedRealtime()
        if (faltam > AVISO_S * 1000L + 1000) { reagendar(); return }   // alguém mexeu enquanto dormíamos
        val servico = AutorizaShizukuService.instancia
        if (servico == null) {
            // Sem o serviço não vemos as teclas: desligar agora poderia ser no meio do uso. Tenta de novo mais tarde.
            Log.w(TAG, "serviço de acessibilidade desligado: não desligo a TV")
            principal.postDelayed(verificar, 10 * 60_000L); return
        }
        mostrarAviso(servico)
    }

    // ---------------------------------------------------------------- aviso na tela

    private fun mostrarAviso(servico: AccessibilityService) {
        if (janela != null) return
        restanteAviso = AVISO_S
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), servico.resources.displayMetrics).toInt() }
        fun texto(sp: Float, cor: Int) = TextView(servico).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(cor); gravity = Gravity.CENTER }
        val titulo = texto(30f, Color.WHITE)
        textoAviso = titulo
        val caixa = LinearLayout(servico).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(32), dp(48), dp(32))
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(0xE6101418.toInt()); setStroke(dp(1), 0x40FFFFFF) }
            addView(titulo)
            addView(texto(20f, 0xFFB8C2CC.toInt()).apply {
                text = "Ninguém mexe no controle há ${minutos(servico)} min.\nAperte qualquer botão para continuar."
                setPadding(0, dp(12), 0, 0)
            })
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            // Janela do serviço de acessibilidade: fica por cima de qualquer app, sem pedir permissão extra.
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Sem foco: as teclas continuam indo para o app; quem as vê primeiro é o serviço (ver [tecla]).
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }
        try {
            servico.getSystemService(WindowManager::class.java).addView(caixa, p)
            janela = caixa
        } catch (e: Exception) { Log.w(TAG, "aviso não abriu: $e"); return }
        Log.i(TAG, "aviso: desliga em $AVISO_S s")
        contar()
    }

    private fun contar() {
        if (janela == null) return
        if (restanteAviso <= 0) { fecharAviso(); desligar(); return }
        textoAviso?.text = "A TV vai desligar em $restanteAviso s"
        restanteAviso--
        principal.postDelayed(contar, 1000)
    }

    private fun fecharAviso() {
        principal.removeCallbacks(contar)
        val j = janela ?: return
        janela = null; textoAviso = null
        try { j.context.getSystemService(WindowManager::class.java).removeView(j) } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- desligar

    private fun telaAcesa(ctx: Context) = ctx.getSystemService(PowerManager::class.java).isInteractive

    /** Apaga a tela pelo 1º caminho que funcionar (ver o comentário do objeto). Público para o teste por adb. */
    fun desligar() {
        val a = app ?: return
        ultimoDesligamento = System.currentTimeMillis()
        val caminhos = listOf<Pair<String, () -> Boolean>>(
            "acessibilidade" to {
                AutorizaShizukuService.instancia?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) == true
            },
            "Device Owner" to {
                try { a.getSystemService(DevicePolicyManager::class.java).lockNow(); true } catch (e: Exception) { false }
            },
            "shell" to {
                // Fora da thread da tela: o shell leva ~1 s.
                Thread { GuardiaoShizuku.comando(a, "input keyevent ${KeyEvent.KEYCODE_SLEEP}") }.start(); true
            },
        )
        fun tentar(i: Int) {
            if (i >= caminhos.size || !telaAcesa(a)) return
            val (nome, fazer) = caminhos[i]
            val aceitou = try { fazer() } catch (e: Exception) { false }
            Log.i(TAG, "desligando pela $nome: ${if (aceitou) "pedido aceito" else "recusado"}")
            // Confere 3 s depois: se a tela continua acesa, tenta o próximo caminho.
            principal.postDelayed({ if (telaAcesa(a)) tentar(i + 1) else Log.i(TAG, "tela apagada pela $nome") }, if (aceitou) 3000 else 0)
        }
        tentar(0)
    }

    /** Usado só pelo comando de teste por adb: mostra o aviso agora, como se o prazo tivesse vencido. */
    fun testarAviso() = principal.post {
        val s = AutorizaShizukuService.instancia ?: return@post
        mostrarAviso(s)
    }
}
