package br.paulo.tvlauncher

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * "Claude pela internet": a tela que guia o passo a passo com QR codes (05/11/2026, pedido do Paulo: sem chave, sem console).
 *
 *  1. Falta o PIN do painel -> pede para definir (o PIN é o login do Claude).
 *  2. Liga o MCP e o túnel.
 *  3. Falta entrar no Tailscale -> QR do login (o celular abre; "Sign in with Google" com a mesma conta da TV).
 *  4. Conta nova sem HTTPS/Funnel -> QR da página do Tailscale que liga os dois.
 *  5. No ar -> QR e texto do endereço do MCP, e como colar no Claude, ChatGPT, Codex etc.
 *
 * Confere o estado a cada 2 s enquanto está aberta. BACK sai; nada fica preso aqui.
 */
class ConectarClaudeActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var titulo: TextView
    private lateinit var texto: TextView
    private lateinit var qr: ImageView
    private var qrAtual: String? = null
    private val claro = 0xFFE8EEEA.toInt()
    private val suave = 0xFF93A19A.toInt()

    private val volta = object : Runnable {
        override fun run() { atualizar(); ui.postDelayed(this, 2000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Abrir esta tela é pedir para conectar: liga o MCP (se já tem PIN). Desligar fica nas Configurações.
        if (PainelArmazem.temPin(this) && !McpServidor.ligado(this)) McpServidor.definirLigado(this, true)
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = resources.getDrawable(R.drawable.fundo, theme)
            setPadding(dp(96), dp(48), dp(96), dp(48))
            gravity = Gravity.CENTER_VERTICAL
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titulo = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f); setTextColor(claro); typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
        texto = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); setTextColor(suave); setPadding(0, dp(16), dp(32), 0); setLineSpacing(0f, 1.2f) }
        col.addView(titulo); col.addView(texto)
        val sair = TextView(this).apply {
            text = "Fechar"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); setTextColor(claro)
            setPadding(dp(24), dp(12), dp(24), dp(12)); isFocusable = true; isClickable = true
            background = vidro(true); setOnClickListener { finish() }
            setOnFocusChangeListener { v, f -> v.background = vidro(f) }
        }
        col.addView(sair, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) })
        raiz.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        qr = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        raiz.addView(qr, LinearLayout.LayoutParams(dp(320), dp(320)))
        setContentView(raiz)
        sair.requestFocus()
    }

    override fun onResume() { super.onResume(); ui.post(volta) }
    override fun onPause() { ui.removeCallbacks(volta); super.onPause() }

    private fun atualizar() {
        val e = Passo.agora(this)
        titulo.text = e.titulo
        texto.text = e.texto
        if (e.qr != qrAtual) {
            qrAtual = e.qr
            qr.setImageBitmap(e.qr?.let { try { QrCodigo.bitmap(it, dp(320)) } catch (x: Exception) { null } })
            qr.visibility = if (e.qr == null) android.view.View.INVISIBLE else android.view.View.VISIBLE
        }
    }

    /** Em que passo estamos. Também usado pelo painel (aba Claude) e pelas Configurações. */
    class Passo(val id: String, val titulo: String, val texto: String, val qr: String?) {
        companion object {
            fun agora(ctx: android.content.Context): Passo {
                val conta = Perfil.contas(ctx).firstOrNull()
                val dicaConta = conta?.let { "\nNo celular, toque em \"Sign in with Google\" e use $it (a conta desta TV)." } ?: ""
                return when {
                    !PainelArmazem.temPin(ctx) -> Passo("pin", "Primeiro, um PIN",
                        "Defina o PIN em Configurações do launcher > Painel no navegador.\nÉ ele que você digita quando o Claude pedir para entrar na TV.", null)
                    !TunelTailscale.binario(ctx).exists() -> Passo("sem_tunel", "Esta versão veio sem o túnel",
                        "O launcher foi compilado sem o Tailscale. Instale a versão completa.", null)
                    !McpServidor.ligado(ctx) -> Passo("desligado", "Desligado", "Ligue em Configurações do launcher > Claude pela internet.", null)
                    TunelTailscale.urlLogin != null -> Passo("login", "1. Entre no Tailscale",
                        "Aponte a câmera do celular para o código ao lado e entre (é grátis).$dicaConta\n\nO Tailscale cria um endereço seguro só para esta TV na internet.",
                        TunelTailscale.urlLogin)
                    TunelTailscale.urlAtivar != null -> Passo("ativar", "2. Ligue o endereço público",
                        "Conta nova: falta ligar HTTPS e Funnel. Abra o código ao lado no celular e confirme.", TunelTailscale.urlAtivar)
                    TunelTailscale.rodando() && TunelTailscale.nome(ctx) != null -> {
                        val url = "https://${TunelTailscale.nome(ctx)}/mcp"
                        Passo("pronto", "Pronto: a TV está no ar",
                            "Endereço: $url\n\nClaude: Configurações > Conectores > Adicionar conector personalizado.\n" +
                                "ChatGPT: Configurações > Conectores (modo desenvolvedor).\nCodex, Claude Code, Cursor: adicione um servidor MCP com esse endereço.\n\n" +
                                "Ao conectar, ele pede o PIN. Ações na TV só com OK no controle." +
                                (TunelTailscale.conta(ctx)?.let { "\nConta Tailscale: $it" } ?: ""), url)
                    }
                    else -> Passo("subindo", "Conectando…",
                        "Ligando o túnel. Leva alguns segundos." + (TunelTailscale.ultimoErro(ctx)?.let { "\n\nÚltimo erro: $it" } ?: ""), null)
                }
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun vidro(focado: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(if (focado) 0x2EFFFFFF else 0x12FFFFFF)
        setStroke(dp(2), if (focado) 0xD9FFFFFF.toInt() else 0x1FFFFFFF)
    }
}
