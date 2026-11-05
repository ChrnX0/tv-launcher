package br.paulo.tvlauncher

import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
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
 *  - grade de 5 cards em 2 colunas: Wi-Fi + Bluetooth lado a lado (como no quick settings nativo — ver foto),
 *    Launcher (nossas configs) + Sistema na linha de baixo, e Saída de áudio sozinha, ocupando a largura toda.
 *    (Google Home ficou de fora por enquanto: não existe tela fixa pra abrir nesse Chromecast — era uma função do
 *    launcher padrão do Google, que foi substituído pelo nosso. Projeto futuro à parte, com a Home API do Google.)
 *
 * É um Dialog: uma janela por cima da tela inicial, com as próprias teclas. Voltar fecha.
 * [aoTrocarPerfil] avisa a tela inicial para redesenhar a bolinha; [aoAbrirConfig] abre as Configurações do sistema.
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
    private var txtRedeTitulo: TextView? = null
    private var txtRedeDetalhe: TextView? = null

    fun abrir(rede: Rede.Resultado?) {
        fechar()
        val contas = Perfil.contas(tela)
        val ativa = Perfil.ativa(tela)

        val legenda = TextView(tela).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(textoSuave)
            gravity = Gravity.CENTER
            maxLines = 1
            text = ativa?.let { Perfil.usuario(it) } ?: tela.getString(R.string.pp_sem_conta)
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
                if (temFoco) legenda.text = Perfil.usuario(conta) + if (eAtiva) tela.getString(R.string.pp_ativo) else ""
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
            if (temFoco) legenda.text = tela.getString(R.string.pp_adicionar)
        }
        val rolagem = HorizontalScrollView(tela).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(2), 0, dp(2))
            addView(bolinhas, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        // ---- Grade de cards: Launcher, Sistema, Wi-Fi, Bluetooth, Saída de áudio.
        val cardLauncher = card("launcher", "Launcher", tela.getString(R.string.pp_launcher_det)) {
            fechar()
            tela.startActivity(Intent(tela, ConfigLauncherActivity::class.java))
        }
        val cardSistema = card("sistema", tela.getString(R.string.pp_sistema), tela.getString(R.string.pp_sistema_det)) {
            fechar()
            aoAbrirConfig()
        }
        val icone = SinalWifi(tela)
        val cardWifi = cardWifi(icone) { fechar(); abrirWifi() }
        val cardBluetooth = card("bluetooth", "Bluetooth", tela.getString(R.string.pp_bt_det)) {
            fechar()
            // Tela de pareamento do sistema (ver conversa: a lista "Controles remotos e acessórios" é um fragmento
            // interno do MainSettings, sem Activity própria — esta aqui é a mesma seção, já no fluxo de busca).
            abrirComponente("com.android.tv.settings", "com.android.tv.settings.accessories.AddAccessoryActivity")
        }
        val cardAudio = card("audio", tela.getString(R.string.pp_audio), tela.getString(R.string.pp_audio_det)) {
            fechar()
            try {
                tela.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: ActivityNotFoundException) {
                aoAbrirConfig()
            }
        }

        sinal = icone
        mostrarRede(rede)

        val linha1 = LinearLayout(tela).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(cardWifi, pesoCard())
            addView(cardBluetooth, pesoCard().apply { marginStart = dp(10) })
        }
        val linha2 = LinearLayout(tela).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(cardLauncher, pesoCard())
            addView(cardSistema, pesoCard().apply { marginStart = dp(10) })
        }
        // (margens entre as linhas ficam no addView do painel, abaixo)

        val painel = LinearLayout(tela).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(22), dp(14), dp(22), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(0xFF161E1B.toInt())   // opaco: nada do fundo aparece por tras do texto
                setStroke(dp(1), 0x33FFFFFF)
            }
            addView(rolagem, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(legenda, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
                bottomMargin = dp(10)
            })
            addView(linha1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            addView(linha2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            addView(cardAudio, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        // O painel fica no canto superior direito, logo abaixo da bolinha do topo.
        val raiz = FrameLayout(tela).apply {
            clipChildren = false
            setOnClickListener { fechar() }   // toque fora do painel fecha
            addView(painel, FrameLayout.LayoutParams(dp(400), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(104)   // logo abaixo da bolinha (Grade.TOPO + linha da hora)
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
            if (dialogo === janela) { dialogo = null; sinal = null; txtRedeTitulo = null; txtRedeDetalhe = null }
        }
        dialogo = janela
        janela.show()
        raiz.post { focoInicial?.requestFocus() }
    }

    /** O menu está na tela agora? (a medição periódica da rede só roda com ele aberto — ver MainActivity.tarefaRede) */
    val aberto: Boolean get() = dialogo?.isShowing == true

    fun fechar() {
        dialogo?.dismiss()
        dialogo = null
        sinal = null
        txtRedeTitulo = null
        txtRedeDetalhe = null
    }

    /** Chega uma medição nova da rede: se o menu estiver aberto, atualiza na hora. */
    fun atualizarRede(r: Rede.Resultado) = mostrarRede(r)

    /**
     * [r] null = a primeira medição ainda não chegou. A velocidade é a do ENLACE Wi-Fi (TV <-> roteador), que o Android
     * informa; não é a velocidade da internet (isso exigiria baixar um arquivo de teste — ver ConfigLauncherActivity).
     */
    private fun mostrarRede(r: Rede.Resultado?) {
        when {
            r == null -> {
                txtRedeTitulo?.text = "Wi-Fi"
                txtRedeDetalhe?.text = tela.getString(R.string.pp_carregando)
            }
            !r.conectado -> {
                sinal?.definir(0, textoSuave)
                txtRedeTitulo?.text = "Wi-Fi"
                txtRedeDetalhe?.text = tela.getString(R.string.pp_conectar)
            }
            else -> {
                sinal?.definir(r.barras, corDoNivel(r.nivel))
                txtRedeTitulo?.text = r.nome
                txtRedeDetalhe?.text = if (r.mbps > 0) "${r.mbps} Mbps" else tela.getString(R.string.pp_conectado)
            }
        }
    }

    /** Abre a tela de Wi-Fi da TV (escolher e conectar numa rede). Se a TV não tiver essa tela, cai nas Configurações. */
    private fun abrirWifi() {
        val wifi = Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            tela.startActivity(wifi)
        } catch (e: ActivityNotFoundException) {
            try { tela.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
    }

    /** Abre uma Activity de outro app pelo nome completo do pacote/classe. Se não existir nesse firmware, fica quieto. */
    private fun abrirComponente(pacote: String, classe: String) {
        try {
            tela.startActivity(Intent().setClassName(pacote, classe).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // não quebra o popup, só não abre nada
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
            contentDescription = tela.getString(R.string.pp_adicionar)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = anel(false, false)
            addView(sinalMais, FrameLayout.LayoutParams(dp(56), dp(56)))
            setOnClickListener {
                fechar()
                // As Configurações da TV (a pessoa vai em "Contas e perfis"), não o ACTION_ADD_ACCOUNT: medido em 04/11/2026
                // que o fluxo de adicionar conta passa pelo seletor do GMS (NoTouchAccountPickerActivity), que tenta abrir
                // o seletor de perfis do launcher do Google desativado (launcherx ProfileChooserActivity) e quebra — 151
                // crashes do com.google.android.gms.ui em 03/11. (O ACTION_SYNC_SETTINGS foi testado e descartado: abre
                // a tela de UMA conta, com "Remover conta" logo abaixo — perigoso para um botão "+".)
                val adicionar = Intent(Settings.ACTION_SETTINGS)
                try {
                    tela.startActivity(adicionar)
                } catch (e: Exception) {
                    try { tela.startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
                }
            }
        }
    }

    /** Um card da grade: ícone + título (claro) na primeira linha, detalhe (suave, menor) embaixo; foco clareia e levanta. */
    private fun card(tipoIcone: String, titulo: String, detalhe: String, aoClicar: () -> Unit): LinearLayout {
        val txtTitulo = TextView(tela).apply {
            text = titulo
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(textoClaro)
            typeface = fonteMedia
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val txtDetalhe = TextView(tela).apply {
            text = detalhe
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(textoSuave)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        }
        val linhaIcone = LinearLayout(tela).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(IconeCard(tela, tipoIcone), LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) })
            addView(txtTitulo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return LinearLayout(tela).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isClickable = true
            background = vidro(false)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            addView(linhaIcone)
            addView(txtDetalhe)
            setOnClickListener { aoClicar() }
            setOnFocusChangeListener { v, temFoco ->
                v.background = vidro(temFoco)
                v.elevation = if (temFoco) dp(6).toFloat() else 0f
                escalar(v, temFoco, 1.03f)
            }
        }
    }

    /** O card de Wi-Fi: mesma casca dos outros, mas com o ícone de sinal ao lado do título (nome da rede). */
    private fun cardWifi(icone: SinalWifi, aoClicar: () -> Unit): LinearLayout {
        val txtTitulo = TextView(tela).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(textoClaro)
            typeface = fonteMedia
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val txtDetalhe = TextView(tela).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(textoSuave)
            maxLines = 1
            setPadding(0, dp(4), 0, 0)
        }
        txtRedeTitulo = txtTitulo
        txtRedeDetalhe = txtDetalhe
        val linhaIcone = LinearLayout(tela).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(icone, LinearLayout.LayoutParams(dp(20), dp(16)).apply { marginEnd = dp(8) })
            addView(txtTitulo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return LinearLayout(tela).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isClickable = true
            background = vidro(false)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            addView(linhaIcone)
            addView(txtDetalhe)
            setOnClickListener { aoClicar() }
            setOnFocusChangeListener { v, temFoco ->
                v.background = vidro(temFoco)
                v.elevation = if (temFoco) dp(6).toFloat() else 0f
                escalar(v, temFoco, 1.03f)
            }
        }
    }

    private fun pesoCard() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

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
        cornerRadius = dp(26).toFloat()
        setColor(if (focado) 0x3AFFFFFF else 0x1AFFFFFF)
        setStroke(dp(2), if (focado) 0xD9FFFFFF.toInt() else 0x2AFFFFFF)
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
