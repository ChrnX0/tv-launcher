package br.paulo.tvlauncher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.AlarmClock
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar

/**
 * A tela do despertador: abre com OK no relógio do topo. Lista os alarmes; OK num alarme abre o editor.
 * Também é o "app de despertador" do Android: atende AlarmClock.ACTION_SET_ALARM (o que o Gemini/Assistente
 * pode mandar ao ouvir "me acorda às 7") e ACTION_SHOW_ALARMS.
 */
class AlarmesActivity : Activity() {
    private val textoClaro = 0xFFE8EEEA.toInt()
    private val textoSuave = 0xFF93A19A.toInt()
    private val verde = 0xFF7FD99B.toInt()
    private val fonteLeve = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val fonteMedia = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private lateinit var conteudo: FrameLayout
    private var editando = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        conteudo = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF1B2326.toInt(), 0xFF0F1416.toInt(), 0xFF0A0D0E.toInt()))
        }
        setContentView(conteudo)
        // Só trata o pedido na 1ª criação: recriada (ex.: girar/trocar config), a Activity recebe o MESMO intent e
        // criava o alarme de novo (achado 10 da revisão, 04/11/2026).
        if (savedInstanceState != null || !tratarPedido(intent)) mostrarLista()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!tratarPedido(intent)) mostrarLista()
    }

    /**
     * Pedido de outro app (Gemini): "criar alarme às H:M [nos dias X] [com nome Y]". Devolve true se fechou a tela
     * sozinho (quando o pedido diz para não mostrar a interface).
     */
    private fun tratarPedido(i: Intent?): Boolean {
        if (i?.action != AlarmClock.ACTION_SET_ALARM || !i.hasExtra(AlarmClock.EXTRA_HOUR)) return false
        // EXTRA_DAYS vem como Calendar.SUNDAY (1) .. Calendar.SATURDAY (7); no nosso formato, bit 0 = domingo.
        var dias = 0
        i.getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS)?.forEach { d -> if (d in 1..7) dias = dias or (1 shl (d - 1)) }
        val novo = Despertador.Alarme(
            0, i.getIntExtra(AlarmClock.EXTRA_HOUR, 7).coerceIn(0, 23), i.getIntExtra(AlarmClock.EXTRA_MINUTES, 0).coerceIn(0, 59),
            dias, i.getStringExtra(AlarmClock.EXTRA_MESSAGE).orEmpty(), true)

        // Quem pediu? (achado 20 da revisão, 04/11/2026) A tela atende SET_ALARM de qualquer app, e com EXTRA_SKIP_UI
        // um app qualquer criava alarmes em silêncio. Agora só o Assistente e o sistema criam direto; qualquer outro
        // app passa por uma confirmação na tela. getLaunchedFromPackage (Android 14) vem do sistema — o app que chama
        // não consegue falsificar (ao contrário do "referrer").
        val quem = if (android.os.Build.VERSION.SDK_INT >= 34) launchedFromPackage else null
        android.util.Log.i("TvLauncher.Alarme", "SET_ALARM de $quem: ${Despertador.horaTexto(novo)}")
        if (quem == null || quem !in CONFIAVEIS) {
            confirmarPedido(novo, quem)
            return true
        }
        val a = Despertador.salvar(this, novo)
        Toast.makeText(this, getString(R.string.al_criado, Despertador.horaTexto(a)), Toast.LENGTH_LONG).show()
        if (i.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false)) { finish(); return true }
        mostrarLista()
        return false
    }

    /** Pedido de app desconhecido: só cria se a pessoa apertar OK em "Criar". Voltar/Cancelar = não cria nada. */
    private fun confirmarPedido(novo: Despertador.Alarme, quem: String?) {
        val nomeApp = quem?.let { p -> try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString() } catch (e: Exception) { p } }
            ?: getString(R.string.al_um_app)
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.al_criar_q, Despertador.horaTexto(novo)))
            .setMessage(getString(R.string.al_pedido, nomeApp))
            .setPositiveButton(getString(R.string.al_criar)) { _, _ ->
                val a = Despertador.salvar(this, novo)
                Toast.makeText(this, getString(R.string.al_criado, Despertador.horaTexto(a)), Toast.LENGTH_LONG).show()
                mostrarLista()
            }
            .setNegativeButton(getString(R.string.comum_cancelar)) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    companion object {
        /** Quem cria alarme direto, sem confirmação: o Assistente da TV ("Ok Google, me acorda às 7") e o sistema. */
        private val CONFIAVEIS = setOf("com.google.android.katniss", "com.google.android.googlequicksearchbox", "android")
    }

    // ---------------------------------------------------------------- lista

    private fun mostrarLista() {
        editando = false
        conteudo.removeAllViews()
        val coluna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(Grade.MARGEM), dp(Grade.TOPO), dp(Grade.MARGEM), dp(Grade.TOPO))
        }
        coluna.addView(texto(34f, Color.WHITE, fonteMedia).apply { text = getString(R.string.al_titulo) })
        val prox = Despertador.proximo(this)
        coluna.addView(texto(16f, textoSuave).apply {
            text = if (prox == null) getString(R.string.al_nenhum) else getString(R.string.al_proximo, quando(prox.second))
            setPadding(0, dp(4), 0, dp(20))
        })
        val novo = linha(null)
        coluna.addView(novo, larguraLinha())
        Despertador.listar(this).forEach { coluna.addView(linha(it), larguraLinha()) }
        conteudo.addView(ScrollView(this).apply { addView(coluna) })
        novo.post { (coluna.getChildAt(3) ?: novo).requestFocus() }   // foco no 1º alarme; sem alarmes, no "Novo"
    }

    private fun larguraLinha() = LinearLayout.LayoutParams(dp(Grade.largura(8)), ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }

    /** Uma linha da lista. [a] null = a linha "+ Novo alarme". */
    private fun linha(a: Despertador.Alarme?): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(22), dp(14), dp(22), dp(14))
        isFocusable = true
        isClickable = true
        background = fundo(false)
        setOnFocusChangeListener { v, f -> v.background = fundo(f) }
        setOnClickListener { mostrarEditor(a) }
        if (a == null) {
            addView(texto(22f, textoClaro, fonteMedia).apply { text = getString(R.string.al_novo_mais) })
            return@apply
        }
        addView(texto(40f, if (a.ativo) Color.WHITE else textoSuave, fonteLeve).apply {
            text = Despertador.horaTexto(a); includeFontPadding = false
        }, LinearLayout.LayoutParams(dp(140), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(LinearLayout(this@AlarmesActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(texto(17f, textoClaro).apply { text = Despertador.diasTexto(context, a.dias) })
            if (a.nome.isNotBlank()) addView(texto(15f, textoSuave).apply { text = a.nome })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(texto(15f, if (a.ativo) verde else textoSuave, fonteMedia).apply { text = if (a.ativo) getString(R.string.al_ligado) else getString(R.string.al_desligado) })
    }

    // ---------------------------------------------------------------- editor

    /** Editor de um alarme ([a] null = novo). ▲▼ mudam hora e minuto; OK marca/desmarca os dias. */
    private fun mostrarEditor(a: Despertador.Alarme?) {
        editando = true
        conteudo.removeAllViews()
        val agora = Calendar.getInstance()
        var hora = a?.hora ?: agora.get(Calendar.HOUR_OF_DAY)
        var minuto = a?.minuto ?: 0
        var dias = a?.dias ?: 0b0111110
        var ativo = a?.ativo ?: true
        var som = a?.som ?: 0
        var rampa = a?.rampa ?: 0
        var bomDia = a?.bomDia ?: true
        var previa: android.media.MediaPlayer? = null
        fun pararPrevia() { previa?.release(); previa = null }

        val coluna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(Grade.MARGEM), dp(Grade.TOPO), dp(Grade.MARGEM), dp(Grade.TOPO))
        }
        coluna.addView(texto(30f, Color.WHITE, fonteMedia).apply { text = if (a == null) getString(R.string.al_novo) else getString(R.string.al_editar) })
        coluna.addView(texto(15f, textoSuave).apply {
            text = getString(R.string.al_editor_dica)
            setPadding(0, dp(4), 0, dp(18))
        })

        // Hora e minuto: dois números grandes; com foco, ▲▼ mudam o valor (e dão a volta: 23 -> 0).
        val txtHora = numero("%02d".format(hora))
        val txtMin = numero("%02d".format(minuto))
        txtHora.setOnKeyListener { _, k, e ->
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (k) {
                KeyEvent.KEYCODE_DPAD_UP -> { hora = (hora + 1) % 24; txtHora.text = "%02d".format(hora); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { hora = (hora + 23) % 24; txtHora.text = "%02d".format(hora); true }
                else -> false
            }
        }
        txtMin.setOnKeyListener { _, k, e ->
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val passo = if (e.repeatCount > 0) 5 else 1   // segurando, anda de 5 em 5
            when (k) {
                KeyEvent.KEYCODE_DPAD_UP -> { minuto = (minuto + passo) % 60; txtMin.text = "%02d".format(minuto); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { minuto = (minuto - passo + 60) % 60; txtMin.text = "%02d".format(minuto); true }
                else -> false
            }
        }
        coluna.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(txtHora, LinearLayout.LayoutParams(dp(130), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(texto(64f, Color.WHITE, fonteLeve).apply { text = ":"; setPadding(dp(8), 0, dp(8), 0) })
            addView(txtMin, LinearLayout.LayoutParams(dp(130), ViewGroup.LayoutParams.WRAP_CONTENT))
        })

        // Dias da semana: 7 bolinhas (D S T Q Q S S); OK liga/desliga. Nenhum marcado = toca uma vez só.
        val legendaDias = texto(15f, textoSuave).apply { setPadding(0, dp(10), 0, 0) }
        fun atualizarLegenda() { legendaDias.text = Despertador.diasTexto(this, dias) }
        val linhaDias = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(20), 0, 0) }
        Despertador.NOMES_DIAS.forEachIndexed { i, nome ->
            val bit = 1 shl i
            val b = texto(18f, Color.WHITE, fonteMedia).apply {
                text = nome
                gravity = Gravity.CENTER
                isFocusable = true
                isClickable = true
            }
            fun pintar(foco: Boolean) {
                b.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (dias and bit != 0) 0xFF1F7A68.toInt() else 0x14FFFFFF)
                    setStroke(dp(2), if (foco) Color.WHITE else 0x22FFFFFF)
                }
            }
            pintar(false)
            b.setOnFocusChangeListener { _, f -> pintar(f) }
            b.setOnClickListener { dias = dias xor bit; pintar(true); atualizarLegenda() }
            linhaDias.addView(b, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(10) })
        }
        atualizarLegenda()
        coluna.addView(linhaDias)
        coluna.addView(legendaDias)

        val nome = EditText(this).apply {
            hint = getString(R.string.al_nome)
            setText(a?.nome.orEmpty())
            setTextColor(Color.WHITE)
            setHintTextColor(textoSuave)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine()
        }
        coluna.addView(nome, LinearLayout.LayoutParams(dp(Grade.largura(5)), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })

        // Som e rampa: botões que giram entre as opções com OK. "Ouvir" toca o som escolhido por 4 s.
        val linhaSom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(16), 0, 0) }
        lateinit var btSom: TextView
        lateinit var btRampa: TextView
        btSom = botao(getString(R.string.al_som, SomAlarme.NOMES[som])) { som = (som + 1) % SomAlarme.NOMES.size; btSom.text = getString(R.string.al_som, SomAlarme.NOMES[som]) }
        btRampa = botao(SomAlarme.rampaTexto(rampa)) {
            rampa = SomAlarme.RAMPAS[(SomAlarme.RAMPAS.indexOf(rampa) + 1) % SomAlarme.RAMPAS.size]
            btRampa.text = SomAlarme.rampaTexto(rampa)
        }
        val btOuvir = botao(getString(R.string.al_ouvir)) {
            pararPrevia()
            previa = SomAlarme.criar(this, som)?.also { it.start() }
            btSom.postDelayed({ pararPrevia() }, 4000)
        }
        // "Bom dia" falado ao parar o alarme (ver Despertador.Alarme.bomDia).
        lateinit var btBomDia: TextView
        fun textoBomDia() = if (bomDia) "Bom dia: sim" else "Bom dia: não"
        btBomDia = botao(textoBomDia()) { bomDia = !bomDia; btBomDia.text = textoBomDia() }
        linhaSom.addView(btSom, botaoLp()); linhaSom.addView(btRampa, botaoLp()); linhaSom.addView(btOuvir, botaoLp())
        linhaSom.addView(btBomDia, botaoLp())
        coluna.addView(linhaSom)

        // Botões: Salvar · Ligado/Desligado · Apagar (só se já existe) · Cancelar
        val botoes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(24), 0, 0) }
        val salvar = botao(getString(R.string.rot_salvar)) {
            Despertador.salvar(this, Despertador.Alarme(a?.id ?: 0, hora, minuto, dias, nome.text.toString().trim(), ativo, som, rampa, bomDia))
            pararPrevia()
            mostrarLista()
        }
        botoes.addView(salvar, botaoLp())
        if (a != null) {
            lateinit var liga: TextView
            liga = botao(if (ativo) getString(R.string.al_ligado) else getString(R.string.al_desligado)) { ativo = !ativo; liga.text = if (ativo) getString(R.string.al_ligado) else getString(R.string.al_desligado) }
            botoes.addView(liga, botaoLp())
            botoes.addView(botao(getString(R.string.al_apagar)) { Despertador.apagar(this, a.id); mostrarLista() }, botaoLp())
        }
        botoes.addView(botao(getString(R.string.comum_cancelar)) { pararPrevia(); mostrarLista() }, botaoLp())
        coluna.addView(botoes)

        conteudo.addView(ScrollView(this).apply { addView(coluna) })
        txtHora.post { txtHora.requestFocus() }
    }

    private fun botaoLp() = LinearLayout.LayoutParams(dp(Grade.largura(2)), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(Grade.VAO) }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && editando) { mostrarLista(); return true }   // Voltar no editor = cancelar
        return super.onKeyDown(keyCode, event)
    }

    // ---------------------------------------------------------------- peças

    /** "hoje às 07:00", "amanhã às 07:00" ou "seg., 3 às 07:00". */
    private fun quando(ms: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        val hoje = Calendar.getInstance()
        val diasAte = ((c.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis -
            (hoje.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis) / 86_400_000L
        val dia = when (diasAte) {
            0L -> getString(R.string.al_hoje)
            1L -> getString(R.string.al_amanha)
            else -> java.text.SimpleDateFormat("EEE, d", java.util.Locale.getDefault()).format(c.time)
        }
        return getString(R.string.al_dia_as, dia, "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)))
    }

    private fun numero(valor: String) = texto(64f, Color.WHITE, fonteLeve).apply {
        text = valor
        gravity = Gravity.CENTER
        isFocusable = true
        includeFontPadding = false
        setPadding(0, dp(8), 0, dp(8))
        background = fundo(false)
        setOnFocusChangeListener { v, f -> v.background = fundo(f) }
    }

    private fun botao(rotulo: String, aoClicar: () -> Unit) = texto(17f, Color.WHITE, fonteMedia).apply {
        text = rotulo
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(14), dp(12), dp(14))
        isFocusable = true
        isClickable = true
        background = fundo(false)
        setOnClickListener { aoClicar() }
        setOnFocusChangeListener { v, f -> v.background = fundo(f) }
    }

    private fun fundo(focado: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(if (focado) 0x2EFFFFFF else 0x12FFFFFF)
        setStroke(dp(2), if (focado) 0xD9FFFFFF.toInt() else 0x1FFFFFFF)
    }

    private fun texto(sp: Float, cor: Int, fonte: Typeface? = null) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(cor)
        if (fonte != null) typeface = fonte
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}
