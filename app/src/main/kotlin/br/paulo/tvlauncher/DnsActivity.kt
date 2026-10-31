package br.paulo.tvlauncher

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

/**
 * Tela "DNS privado": escolher o servidor de DNS da TV (Automático, Desligado, um público, ou um seu).
 * Navegação só com o controle: setas movem, OK escolhe, segurar OK num servidor salvo pergunta se quer remover.
 * A lógica de ler/gravar o DNS fica em Dns.kt; aqui só desenhamos a tela.
 *
 * O estilo (cores, vidro) é copiado do MainActivity de propósito, para não mexer lá; se surgir uma terceira tela,
 * vale mover isso para um arquivo comum.
 */
class DnsActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private val trabalho = Executors.newSingleThreadExecutor()   // o teste de rede não pode rodar na thread da tela
    private var geracaoTeste = 0   // cada teste novo invalida o anterior (se você trocar de servidor no meio)

    private val textoClaro = 0xFFE8EEEA.toInt()
    private val textoSuave = 0xFF93A19A.toInt()
    private val destaque = 0xFF7FD6C2.toInt()
    private val fonteLeve = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val fonteMedia = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private lateinit var lista: LinearLayout
    private lateinit var txtStatus: TextView
    private var linhaMarcada: View? = null   // a linha do servidor em uso: recebe o foco ao desenhar a lista

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(montarTela())
        atualizar()
        testar()
    }

    override fun onDestroy() {
        super.onDestroy()
        geracaoTeste++
        trabalho.shutdownNow()
    }

    // ---------------------------------------------------------------- tela

    private fun montarTela(): View {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = resources.getDrawable(R.drawable.fundo, theme)
            setPadding(dp(96), dp(48), dp(96), dp(32))
        }
        raiz.addView(texto(36f, textoClaro, fonteLeve).apply { text = "DNS privado" })
        txtStatus = texto(16f, textoSuave).apply { setPadding(0, dp(6), 0, dp(16)); maxLines = 2 }
        raiz.addView(txtStatus)

        lista = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false   // a linha focada cresce um pouco; sem isso as bordas seriam cortadas
            clipToPadding = false   // sem isto o Android ainda corta o que passa da margem
            setPadding(dp(24), dp(4), dp(24), dp(4))   // folga lateral: a linha focada cresce 2% e a borda seria cortada
        }
        val rolagem = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            addView(lista)
        }
        raiz.addView(rolagem, LinearLayout.LayoutParams(MATCH, 0, 1f))
        raiz.addView(texto(13f, textoSuave).apply {
            text = "OK: usar   ·   segurar OK em um servidor seu: remover   ·   Voltar: sair"
            setPadding(0, dp(10), 0, 0)
        })
        return raiz
    }

    /** Redesenha a lista inteira a partir do estado atual do sistema. */
    private fun atualizar() {
        Dns.guardarAtualSeDesconhecido(this)
        val atual = Dns.atual(this)
        fun emUso(host: String) = atual.modo == Dns.Modo.HOST && atual.host.equals(host, ignoreCase = true)

        lista.removeAllViews()
        linhaMarcada = null

        adicionar(linha("Automático", "Usa o DNS do roteador e criptografa quando ele aceitar (padrão do Android)",
            atual.modo == Dns.Modo.AUTOMATICO, { escolher(Dns.Modo.AUTOMATICO, null) }))
        adicionar(linha("Desligado", "DNS comum, sem criptografia",
            atual.modo == Dns.Modo.DESLIGADO, { escolher(Dns.Modo.DESLIGADO, null) }))

        secao("Servidores públicos")
        for (s in Dns.PRESETS) {
            adicionar(linha(s.nome, s.host, emUso(s.host), { escolher(Dns.Modo.HOST, s.host) }))
        }

        secao("Meus servidores")
        val meus = Dns.salvos(this).filter { s -> Dns.PRESETS.none { it.host.equals(s.host, ignoreCase = true) } }
        for (s in meus) {
            adicionar(linha(s.nome, s.host, emUso(s.host), { escolher(Dns.Modo.HOST, s.host) },
                aoSegurar = { pedirRemocao(s, emUso(s.host)) }))
        }
        adicionar(linha("+  Adicionar servidor", "Endereço de DNS sobre TLS, como dns.exemplo.com", false, { dialogoAdicionar() }))

        lista.post { linhaMarcada?.requestFocus() }
        mostrarStatus("")
    }

    private fun adicionar(v: View) {
        lista.addView(v, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
    }

    private fun secao(titulo: String) {
        lista.addView(texto(13f, textoSuave, fonteMedia).apply {
            text = titulo.uppercase()
            letterSpacing = 0.12f
            setPadding(dp(24), dp(14), 0, dp(6))
        })
    }

    /** Uma linha da lista: marca (●/○), nome e detalhe. [aoSegurar] é opcional (só os servidores salvos têm). */
    private fun linha(
        titulo: String, detalhe: String, marcada: Boolean,
        aoClicar: () -> Unit, aoSegurar: (() -> Unit)? = null,
    ): View {
        val marca = texto(20f, if (marcada) destaque else textoSuave).apply {
            text = if (marcada) "●" else "○"
            minWidth = dp(44)
        }
        val coluna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(texto(20f, textoClaro, fonteMedia).apply {
                text = titulo; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            addView(texto(14f, textoSuave).apply {
                text = detalhe; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
        }
        val l = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(dp(24), dp(12), dp(24), dp(12))
            background = vidro(false)
            addView(marca)
            addView(coluna, LinearLayout.LayoutParams(0, WRAP, 1f))
            setOnClickListener { aoClicar() }
            if (aoSegurar != null) setOnLongClickListener { aoSegurar(); true }
            setOnFocusChangeListener { v, temFoco ->
                v.background = vidro(temFoco)
                val escala = if (temFoco) 1.02f else 1f
                v.animate().scaleX(escala).scaleY(escala).setDuration(120).start()
            }
        }
        if (marcada) linhaMarcada = l
        return l
    }

    // ---------------------------------------------------------------- ações

    private fun escolher(modo: Dns.Modo, host: String?) {
        if (!Dns.aplicar(this, modo, host)) {
            txtStatus.text = "Sem permissão para mudar o DNS. No computador: " +
                "adb shell pm grant br.paulo.tvlauncher android.permission.WRITE_SECURE_SETTINGS"
            return
        }
        atualizar()
        testar()
    }

    private fun pedirRemocao(servidor: Dns.Servidor, emUso: Boolean) {
        if (emUso) {
            mostrarStatus("troque de servidor antes de remover este")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Remover ${servidor.nome}?")
            .setMessage(servidor.host)
            .setPositiveButton("Remover") { _, _ ->
                Dns.remover(this, servidor.host)
                atualizar()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun dialogoAdicionar() {
        val campoNome = EditText(this).apply {
            hint = "Nome (opcional)"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val campoEndereco = EditText(this).apply {
            hint = "Endereço, ex.: dns.exemplo.com"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val caixa = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(campoNome)
            addView(campoEndereco)
        }
        val dialogo = AlertDialog.Builder(this)
            .setTitle("Adicionar servidor DNS")
            .setView(caixa)
            .setPositiveButton("Salvar e usar") { _, _ ->
                val host = Dns.normalizar(campoEndereco.text.toString())
                if (host == null) {
                    txtStatus.text = "Endereço inválido. Use um nome como dns.exemplo.com (o Android não aceita número de IP)."
                    return@setPositiveButton
                }
                val nome = campoNome.text.toString().trim().ifEmpty { host }
                // Servidor público já está na lista fixa: só passa a usar, sem guardar uma cópia.
                if (Dns.PRESETS.none { it.host == host }) Dns.salvar(this, Dns.Servidor(nome, host))
                escolher(Dns.Modo.HOST, host)
            }
            .setNegativeButton("Cancelar", null)
            .create()
        dialogo.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialogo.show()
    }

    // ---------------------------------------------------------------- status e teste

    private fun mostrarStatus(extra: String) {
        val e = Dns.atual(this)
        val em = when (e.modo) {
            Dns.Modo.AUTOMATICO -> "Automático"
            Dns.Modo.DESLIGADO -> "Desligado"
            Dns.Modo.HOST -> Dns.nomeDe(this, e.host.orEmpty()) ?: e.host.orEmpty()
        }
        txtStatus.text = "Em uso: $em" + if (extra.isNotEmpty()) "   ·   $extra" else ""
    }

    /** Resolve um nome de verdade pela rede e mostra o resultado, sem travar a tela. */
    private fun testar() {
        val minha = ++geracaoTeste
        val modo = Dns.atual(this).modo
        mostrarStatus("testando…")
        trabalho.execute {
            try {
                val ms = Dns.testarResolucao(this)
                val situacao = Dns.situacaoDaRede(this)
                ui.post {
                    if (minha == geracaoTeste && !isDestroyed) mostrarStatus(resultado(ms, situacao, modo))
                }
            } catch (e: Throwable) {
                // tela fechada no meio do teste: nada a mostrar
            }
        }
    }

    private fun resultado(ms: Long?, situacao: Dns.Situacao, modo: Dns.Modo): String = when {
        ms == null -> "FALHA: nenhum nome resolveu. Escolha outro servidor ou Automático."
        modo == Dns.Modo.DESLIGADO -> "resolvendo em $ms ms (sem criptografia)"
        situacao.tlsAtivo -> "resolvendo em $ms ms   ·   TLS ativo" + (situacao.servidor?.let { " com $it" } ?: "")
        else -> "resolvendo em $ms ms   ·   sem TLS no momento"
    }

    // ---------------------------------------------------------------- utilidades

    private fun dp(valor: Int): Int = (valor * resources.displayMetrics.density + 0.5f).toInt()

    private fun texto(sp: Float, cor: Int, fonte: Typeface? = null): TextView = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(cor)
        if (fonte != null) typeface = fonte
    }

    private fun vidro(focado: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(if (focado) 0x2EFFFFFF else 0x12FFFFFF)
        setStroke(dp(2), if (focado) 0xD9FFFFFF.toInt() else 0x1FFFFFFF)
    }
}
