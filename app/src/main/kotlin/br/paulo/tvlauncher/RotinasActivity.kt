package br.paulo.tvlauncher

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

/**
 * Tela das rotinas por horário (Rotinas.kt), aberta pelas Configurações do launcher. Lista as rotinas; OK numa abre
 * o editor. No editor cada linha muda com OK (e ◀ ▶ onde faz sentido), para não precisar de teclado:
 *  - Hora: ◀ ▶ = 5 min para trás/frente, OK = +1 hora;
 *  - Dias, Ação, Condição: OK gira entre as opções; App: OK/▶ próximo, ◀ anterior.
 * Estilo igual ao das Configurações do launcher (vidro, mesmas cores), copiado de propósito.
 */
class RotinasActivity : Activity() {
    private val textoClaro = 0xFFE8EEEA.toInt()
    private val textoSuave = 0xFF93A19A.toInt()
    private val fonteLeve = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val fonteMedia = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private var noEditor = false

    /** Presets de dias (o despertador tem as bolinhas; aqui basta isto). Bit 0 = domingo. */
    private val DIAS = listOf(0b1111111, 0b0111110, 0b1000001, 0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mostrarLista()
    }

    @Deprecated("Activity simples, sem AndroidX")
    override fun onBackPressed() {
        if (noEditor) { mostrarLista(); return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun tela(titulo: String, subtitulo: String): Pair<View, LinearLayout> {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = resources.getDrawable(R.drawable.fundo, theme)
            setPadding(dp(96), dp(48), dp(96), dp(32))
        }
        raiz.addView(texto(36f, textoClaro, fonteLeve).apply { text = titulo })
        raiz.addView(texto(15f, textoSuave).apply { text = subtitulo })
        val lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(4), dp(24), dp(4)); clipChildren = false; clipToPadding = false }
        raiz.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipChildren = false; addView(lista) },
            LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(16) })
        return raiz to lista
    }

    private fun mostrarLista() {
        noEditor = false
        val prox = Rotinas.proxima(this)
        val (raiz, lista) = tela("Rotinas", if (prox == null) "nenhuma rotina ligada" else
            "próxima: ${Rotinas.descricao(this, prox.first).lowercase()} " +
                android.text.format.DateUtils.getRelativeTimeSpanString(prox.second, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS))
        val nova = linha("+ Nova rotina", "ligar, abrir um app ou desligar num horário") { mostrarEditor(null) }
        adicionar(lista, nova)
        for (r in Rotinas.listar(this)) {
            adicionar(lista, linha("%02d:%02d  ·  %s".format(r.hora, r.minuto, Rotinas.descricao(this, r)),
                Despertador.diasTexto(r.dias) + if (r.ativa) "" else " · desligada") { mostrarEditor(r) })
        }
        setContentView(raiz)
        (lista.getChildAt(1) ?: nova).requestFocus()
    }

    private fun mostrarEditor(original: Rotinas.Rotina?) {
        noEditor = true
        var r = original ?: Rotinas.Rotina(0, 7, 0, 0b0111110, Rotinas.Acao.LIGAR)
        val apps = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.distinct().filter { it != packageName }
            .sortedBy { (Rotinas.rotuloApp(this, it) ?: it).lowercase() }
        val (raiz, lista) = tela(if (original == null) "Nova rotina" else "Editar rotina", "OK muda cada linha · BACK sai sem salvar")

        lateinit var lHora: TextView; lateinit var lDias: TextView; lateinit var lAcao: TextView
        lateinit var lApp: TextView; lateinit var lOcioso: TextView; lateinit var lAtiva: TextView
        fun redesenhar() {
            lHora.titulo("Hora: %02d:%02d".format(r.hora, r.minuto))
            lDias.titulo("Dias: " + Despertador.diasTexto(r.dias))
            lAcao.titulo("Ação: " + r.acao.rotulo)
            lApp.titulo("App: " + (Rotinas.rotuloApp(this, r.pacote) ?: "nenhum"))
            lApp.visibility = if (r.acao == Rotinas.Acao.ABRIR) View.VISIBLE else View.GONE
            lOcioso.titulo("Só se ninguém mexeu há: " + Rotinas.ociosoTexto(r.ocioso))
            lOcioso.visibility = if (r.acao == Rotinas.Acao.DESLIGAR) View.VISIBLE else View.GONE
            lAtiva.titulo(if (r.ativa) "Ligada" else "Desligada")
        }
        fun mudarMinutos(delta: Int) {
            val total = ((r.hora * 60 + r.minuto + delta) % 1440 + 1440) % 1440
            r = r.copy(hora = total / 60, minuto = total % 60)
        }
        fun mudarApp(passo: Int) {
            if (apps.isEmpty()) return
            val i = apps.indexOf(r.pacote)
            r = r.copy(pacote = apps[((if (i < 0) -1 else i) + passo + apps.size) % apps.size])
        }

        lHora = linha("", "◀ ▶ 5 minutos · OK +1 hora") { mudarMinutos(60); redesenhar() }
        lHora.setOnKeyListener { _, k, e ->
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (k) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { mudarMinutos(-5); redesenhar(); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { mudarMinutos(5); redesenhar(); true }
                else -> false
            }
        }
        lDias = linha("", "OK: todo dia, seg a sex, fim de semana, uma vez") {
            r = r.copy(dias = DIAS[(DIAS.indexOf(r.dias) + 1) % DIAS.size]); redesenhar()
        }
        lAcao = linha("", "OK muda") {
            val a = Rotinas.Acao.values()
            r = r.copy(acao = a[(r.acao.ordinal + 1) % a.size])
            if (r.acao == Rotinas.Acao.ABRIR && r.pacote.isBlank()) mudarApp(1)
            redesenhar()
        }
        lApp = linha("", "OK ou ▶ próximo · ◀ anterior") { mudarApp(1); redesenhar() }
        lApp.setOnKeyListener { _, k, e ->
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (k) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { mudarApp(-1); redesenhar(); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { mudarApp(1); redesenhar(); true }
                else -> false
            }
        }
        lOcioso = linha("", "não corta o filme de quem está usando a TV") {
            val o = Rotinas.OCIOSO_OPCOES
            r = r.copy(ocioso = o[(o.indexOf(r.ocioso) + 1) % o.size]); redesenhar()
        }
        lAtiva = linha("", "OK liga/desliga esta rotina") { r = r.copy(ativa = !r.ativa); redesenhar() }
        for (v in listOf(lHora, lDias, lAcao, lApp, lOcioso, lAtiva)) adicionar(lista, v)
        adicionar(lista, linha("Salvar", "") {
            if (r.acao == Rotinas.Acao.ABRIR && r.pacote.isBlank()) { Toast.makeText(this, "Escolha o app", Toast.LENGTH_SHORT).show(); return@linha }
            Rotinas.salvar(this, r); mostrarLista()
        })
        if (original != null) adicionar(lista, linha("Apagar esta rotina", "") { Rotinas.apagar(this, original.id); mostrarLista() })
        redesenhar()
        setContentView(raiz)
        lHora.requestFocus()
    }

    // ---------------------------------------------------------------- peças (iguais às das Configurações)

    private fun adicionar(lista: LinearLayout, v: View) = lista.addView(v, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })

    private fun linha(titulo: String, detalhe: String, aoClicar: () -> Unit): TextView = TextView(this).apply {
        tag = detalhe
        titulo(titulo)
        isFocusable = true
        isClickable = true
        background = vidro(false)
        setOnClickListener { aoClicar() }
        setOnFocusChangeListener { v, temFoco ->
            v.background = vidro(temFoco)
            v.animate().scaleX(if (temFoco) 1.02f else 1f).scaleY(if (temFoco) 1.02f else 1f).setDuration(120).start()
        }
    }

    /** Título grande e o detalhe (guardado na tag) embaixo, menor. */
    private fun TextView.titulo(t: String) {
        val detalhe = tag as? String ?: ""
        val sb = SpannableStringBuilder(t)
        sb.setSpan(AbsoluteSizeSpan(dp(20), false), 0, sb.length, 0)
        sb.setSpan(ForegroundColorSpan(textoClaro), 0, sb.length, 0)
        if (detalhe.isNotEmpty()) {
            val ini = sb.length
            sb.append("\n").append(detalhe)
            sb.setSpan(AbsoluteSizeSpan(dp(14), false), ini, sb.length, 0)
            sb.setSpan(ForegroundColorSpan(textoSuave), ini, sb.length, 0)
        }
        text = sb
        typeface = fonteMedia
        setPadding(dp(24), dp(12), dp(24), dp(12))
    }

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
