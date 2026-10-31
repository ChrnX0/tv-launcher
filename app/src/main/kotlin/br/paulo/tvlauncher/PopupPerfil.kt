package br.paulo.tvlauncher

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * O menu que abre ao apertar OK na bolinha do perfil (canto superior direito):
 *  - troca rápida de perfil (uma bolinha por conta Google da TV) e "+" para adicionar;
 *  - Configurações do sistema;
 *  - DNS (abre a tela de DNS);
 *  - Wi-Fi: conectado = sinal colorido, nome da rede e velocidade do enlace; sem rede = "Conectar ao Wi-Fi".
 *    Nos dois casos OK abre as configurações de Wi-Fi da TV.
 *
 * É um Dialog: uma janela por cima da tela inicial, com as próprias teclas. Voltar fecha.
 * [aoTrocarPerfil] avisa a tela inicial para redesenhar a bolinha; [aoAbrirConfig] abre as Configurações.
 */
class PopupPerfil(
    private val tela: Activity,
    private val aoTrocarPerfil: () -> Unit,
    private val aoAbrirConfig: () -> Unit,
) {
    private val textoClaro = 0xFFE8EEEA.toInt()
    private val textoSuave = 0xFF93A19A.toInt()
    private val destaque = 0xFF7FD6C2.toInt()   // verde-água: perfil ativo
    private val fonteMedia = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private var dialogo: Dialog? = null
    private var sinal: SinalWifi? = null
    private var txtRede: TextView? = null
    private var txtVelocidade: TextView? = null

    fun abrir(rede: Rede.Resultado?) {
        fechar()
        val contas = Perfil.contas(tela)
        val ativa = Perfil.ativa(tela)

        val legenda = TextView(tela).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(textoSuave)
            gravity = Gravity.CENTER
            maxLines = 1
            text = ativa?.let { Perfil.usuario(it) } ?: "Sem conta Google"
        }

        // ---- Bolinhas dos perfis + "+".
        val bolinhas = LinearLayout(tela).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
        }
        var focoInicial: View? = null
        for (conta in contas) {
            val eAtiva = conta == ativa
            val bolinha = bolinha(Perfil.avatar(tela, dp(56), conta), eAtiva) {
                if (!eAtiva) {
                    Perfil.definirAtiva(tela, conta)
                    aoTrocarPerfil()
                }
                fechar()
            }
            bolinha.setOnFocusChangeListener { v, temFoco ->
                v.background = anel(temFoco, eAtiva)
                escalar(v, temFoco, 1.12f)
                if (temFoco) legenda.text = Perfil.usuario(conta) + if (eAtiva) "  ·  ativo" else ""
            }
            if (eAtiva || focoInicial == null) focoInicial = bolinha
            bolinhas.addView(bolinha, LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(10) })
        }
        val mais = bolinhaMais()
        bolinhas.addView(mais, LinearLayout.LayoutParams(dp(64), dp(64)))
        if (focoInicial == null) focoInicial = mais
        mais.setOnFocusChangeListener { v, temFoco ->
            v.background = anel(temFoco, false)
            escalar(v, temFoco, 1.12f)
            if (temFoco) legenda.text = "Adicionar perfil"
        }
        val rolagem = HorizontalScrollView(tela).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(6), 0, dp(6))
            addView(bolinhas, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        // ---- Linhas de ação.
        val linhaConfig = linha("Configurações do sistema") { fechar(); aoAbrirConfig() }
        val linhaDns = linha(Dns.rotulo(tela)) {
            fechar()
            tela.startActivity(Intent(tela, DnsActivity::class.java))
        }

        // ---- Wi-Fi: um botão. Conectado: sinal colorido + nome + velocidade do enlace. Sem rede: atalho para conectar.
        val icone = SinalWifi(tela)
        val nome = TextView(tela).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(textoClaro)
            typeface = fonteMedia
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val velocidade = TextView(tela).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(textoSuave)
            maxLines = 1
        }
        sinal = icone
        txtRede = nome
        txtVelocidade = velocidade
        mostrarRede(rede)
        val blocoWifi = LinearLayout(tela).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            isFocusable = true
            isClickable = true
            background = vidro(false)
            setOnClickListener { fechar(); abrirWifi() }
            setOnFocusChangeListener { v, temFoco ->
                v.background = vidro(temFoco)
                escalar(v, temFoco, 1.03f)
            }
            addView(icone, LinearLayout.LayoutParams(dp(28), dp(22)).apply { marginEnd = dp(14) })
            addView(nome, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(velocidade, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) })
        }

        val painel = LinearLayout(tela).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(24), dp(22), dp(24), dp(20))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(0xFF161E1B.toInt())   // opaco: nada do fundo aparece por tras do texto
                setStroke(dp(1), 0x33FFFFFF)
            }
            addView(rolagem, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(legenda, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
                bottomMargin = dp(14)
            })
            addView(linhaConfig, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            addView(linhaDns, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) })
            addView(View(tela).apply { setBackgroundColor(0x22FFFFFF) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { bottomMargin = dp(12) })
            addView(blocoWifi, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        // O painel fica no canto superior direito, logo abaixo da bolinha do topo.
        val raiz = FrameLayout(tela).apply {
            clipChildren = false
            setOnClickListener { fechar() }   // toque fora do painel fecha
            addView(painel, FrameLayout.LayoutParams(dp(350), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(112)   // logo abaixo da bolinha (Grade.TOPO + linha da hora)
                marginEnd = dp(Grade.MARGEM)   // borda direita = borda direita da bolinha e do ultimo card
            })
        }

        val janela = Dialog(tela, android.R.style.Theme_Translucent_NoTitleBar)
        janela.setContentView(raiz)
        janela.window?.apply {
            setBackgroundDrawable(ColorDrawable(0x66000000))   // escurece a tela inicial por trás
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        janela.setOnDismissListener {
            if (dialogo === janela) { dialogo = null; sinal = null; txtRede = null; txtVelocidade = null }
        }
        dialogo = janela
        janela.show()
        raiz.post { focoInicial?.requestFocus() }
    }

    fun fechar() {
        dialogo?.dismiss()
        dialogo = null
        sinal = null
        txtRede = null
        txtVelocidade = null
    }

    /** Chega uma medição nova da rede: se o menu estiver aberto, atualiza na hora. */
    fun atualizarRede(r: Rede.Resultado) = mostrarRede(r)

    /**
     * [r] null = a primeira medição ainda não chegou. A velocidade é a do ENLACE Wi-Fi (TV <-> roteador), que o Android
     * informa; não é a velocidade da internet (isso exigiria baixar um arquivo de teste).
     */
    private fun mostrarRede(r: Rede.Resultado?) {
        when {
            r == null -> {
                txtRede?.text = "Wi-Fi"
                txtVelocidade?.text = ""
            }
            !r.conectado -> {
                sinal?.definir(0, textoSuave)
                txtRede?.text = "Conectar ao Wi-Fi"
                txtVelocidade?.text = ""
            }
            else -> {
                sinal?.definir(r.barras, corDoNivel(r.nivel))
                txtRede?.text = r.nome
                txtVelocidade?.text = if (r.mbps > 0) "${r.mbps} Mbps" else ""
            }
        }
    }

    /** Abre a tela de Wi-Fi da TV (escolher e conectar numa rede). Se a TV não tiver essa tela, cai nas Configurações. */
    private fun abrirWifi() {
        val wifi = Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            tela.startActivity(wifi)
        } catch (e: android.content.ActivityNotFoundException) {
            tela.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // ---------------------------------------------------------------- peças

    private fun corDoNivel(nivel: Int): Int = when (nivel) {
        0 -> 0xFF6FD08C.toInt()   // verde: bom
        1 -> 0xFFE8C66A.toInt()   // amarelo: atenção
        else -> 0xFFE88A7A.toInt() // vermelho: ruim
    }

    /** Uma bolinha de perfil: foto/inicial recortada em círculo, com anel (verde-água = ativo, branco = foco). */
    private fun bolinha(imagem: android.graphics.Bitmap, eAtiva: Boolean, aoClicar: () -> Unit): FrameLayout {
        val foto = ImageView(tela).apply {
            setImageBitmap(imagem)
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = circulo()
        }
        return FrameLayout(tela).apply {
            isFocusable = true
            isClickable = true
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = anel(false, eAtiva)
            addView(foto, FrameLayout.LayoutParams(dp(56), dp(56)))
            setOnClickListener { aoClicar() }
        }
    }

    /** A bolinha "+": abre a tela de adicionar conta Google (se a TV não tiver essa tela, cai nas Configurações). */
    private fun bolinhaMais(): FrameLayout {
        val sinalMais = TextView(tela).apply {
            text = "+"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            setTextColor(textoClaro)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x22FFFFFF)
            }
        }
        return FrameLayout(tela).apply {
            isFocusable = true
            isClickable = true
            contentDescription = "Adicionar perfil"
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = anel(false, false)
            addView(sinalMais, FrameLayout.LayoutParams(dp(56), dp(56)))
            setOnClickListener {
                fechar()
                val adicionar = Intent(Settings.ACTION_ADD_ACCOUNT)
                    .putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google"))
                try {
                    tela.startActivity(adicionar)
                } catch (e: Exception) {
                    try { tela.startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
                }
            }
        }
    }

    /** Uma linha de ação do menu (vidro translúcido; fica mais clara com foco). */
    private fun linha(rotulo: String, aoClicar: () -> Unit): TextView = TextView(tela).apply {
        text = rotulo
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(textoClaro)
        typeface = fonteMedia
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(dp(18), dp(14), dp(18), dp(14))
        isFocusable = true
        isClickable = true
        background = vidro(false)
        setOnClickListener { aoClicar() }
        setOnFocusChangeListener { v, temFoco ->
            v.background = vidro(temFoco)
            escalar(v, temFoco, 1.03f)
        }
    }

    private fun anel(focado: Boolean, eAtiva: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.TRANSPARENT)
        val cor = when {
            focado -> 0xD9FFFFFF.toInt()
            eAtiva -> destaque
            else -> 0x33FFFFFF
        }
        setStroke(dp(3), cor)
    }

    private fun vidro(focado: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(if (focado) 0x2EFFFFFF else 0x12FFFFFF)
        setStroke(dp(2), if (focado) 0xD9FFFFFF.toInt() else 0x1FFFFFFF)
    }

    private fun circulo() = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setOval(0, 0, view.width, view.height)
        }
    }

    private fun escalar(v: View, temFoco: Boolean, escala: Float) {
        val alvo = if (temFoco) escala else 1f
        v.animate().scaleX(alvo).scaleY(alvo).setDuration(140).start()
    }

    private fun dp(valor: Int): Int = (valor * tela.resources.displayMetrics.density + 0.5f).toInt()
}
