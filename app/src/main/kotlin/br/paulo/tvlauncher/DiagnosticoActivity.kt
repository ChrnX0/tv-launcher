package br.paulo.tvlauncher

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.format.DateUtils
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

/**
 * Tela do "diagnóstico de 1 botão" (aberta pela 1ª linha das Configurações do launcher). Ao abrir, roda TODAS as
 * verificações de uma vez (Wi-Fi, internet, DNS, VPN e o histórico dos apps) e vai preenchendo a tela conforme cada
 * uma termina. OK num app mostra o que aconteceu com ele (texto completo do crash ou o motivo do encerramento).
 * Toda a lógica fica em Diagnostico.kt; aqui é só tela. Estilo copiado do ConfigLauncherActivity de propósito.
 */
class DiagnosticoActivity : Activity() {
    // 4 threads: as verificações de rede esperam a rede (até ~9 s o DNS), não a CPU; rodar juntas encurta a espera.
    private val trabalho = Executors.newFixedThreadPool(4)
    private val ui = Handler(Looper.getMainLooper())

    private val textoClaro = 0xFFE8EEEA.toInt()
    private val textoSuave = 0xFF93A19A.toInt()
    private val cores = intArrayOf(0xFF7FD6A0.toInt(), 0xFFF2C14E.toInt(), 0xFFF07B6E.toInt())   // ok, atenção, falha
    private val fonteLeve = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val fonteMedia = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private lateinit var principal: View
    private lateinit var lista: LinearLayout
    private var noDetalhe = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        principal = montarTela()
        setContentView(principal)
        verificar()
    }

    override fun onDestroy() {
        super.onDestroy()
        trabalho.shutdownNow()
    }

    /** BACK no detalhe de um app volta para a lista, não fecha a tela. */
    @Deprecated("Activity simples, sem AndroidX")
    override fun onBackPressed() {
        if (noDetalhe) { noDetalhe = false; setContentView(principal); return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ---------------------------------------------------------------- tela principal

    private fun montarTela(): View {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = resources.getDrawable(R.drawable.fundo, theme)
            setPadding(dp(96), dp(48), dp(96), dp(32))
        }
        raiz.addView(texto(36f, textoClaro, fonteLeve).apply { text = "Diagnóstico" })
        lista = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(24), dp(4), dp(24), dp(4))
        }
        raiz.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            addView(lista)
        }, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(16) })
        return raiz
    }

    /** Limpa a lista e roda tudo de novo (é o que o "Verificar de novo" faz). */
    private fun verificar() {
        lista.removeAllViews()
        val refazer = linha("Verificar de novo", "roda todas as verificações outra vez", -1) { verificar() }
        adicionar(refazer)
        refazer.requestFocus()

        secao("Rede")
        val resumo = info("Resumo", "verificando…").also { adicionar(it) }
        val wifi = info("Wi-Fi", "verificando…").also { adicionar(it) }
        val internet = info("Internet", "verificando…").also { adicionar(it) }
        val dns = info("DNS", "verificando…").also { adicionar(it) }
        val vpn = info("VPN", "verificando…").also { adicionar(it) }

        // Cada verificação numa thread; quando as quatro chegam, calcula o resumo.
        val resultados = arrayOfNulls<Diagnostico.Item>(4)
        fun rodar(i: Int, linha: TextView, f: () -> Diagnostico.Item) = trabalho.execute {
            val r = try { f() } catch (e: Exception) { Diagnostico.Item(linha.text.toString().substringBefore('\n'), "erro: ${e.javaClass.simpleName}", 2) }
            ui.post {
                if (isDestroyed) return@post
                linha.mostrar(r)
                resultados[i] = r
                if (resultados.all { it != null })
                    resumo.mostrar(Diagnostico.veredito(resultados[0]!!, resultados[1]!!, resultados[2]!!, resultados[3]!!))
            }
        }
        rodar(0, wifi) { Diagnostico.wifi(this) }
        rodar(1, internet) { Diagnostico.internet(this) }
        rodar(2, dns) { Diagnostico.dns(this) }
        rodar(3, vpn) { Diagnostico.vpn(this) }

        secao("Apps (últimos 7 dias)")
        val carregando = info("Lendo o histórico dos apps", "…").also { adicionar(it) }
        trabalho.execute {
            val apps = Diagnostico.appsDaTv(this)
            val ocorr = Diagnostico.ocorrencias(this, apps.map { it.first })
            val podeLer = Diagnostico.podeLerCrashes(this)
            ui.post {
                if (isDestroyed) return@post
                lista.removeView(carregando)
                if (!podeLer) adicionar(info("Sem permissão para ler crashes de outros apps",
                    "o launcher se concede READ_LOGS e DUMP pelo próprio shell; aguarde alguns minutos ou religue a TV", 1))
                // Primeiro quem teve problema (mais recente no topo), depois o resto em ordem alfabética.
                val nomes = apps.toMap()
                val comProblema = ocorr.entries.sortedByDescending { it.value.first().quando }
                for ((pacote, l) in comProblema) {
                    val ult = l.first()
                    val nivel = if (l.any { it.grave }) 2 else 1
                    val quando = DateUtils.getRelativeTimeSpanString(ult.quando, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                    adicionar(linha(nomes[pacote] ?: Diagnostico.rotuloDe(this, pacote),
                        "$quando: ${ult.tipo} — ${ult.resumo}" + (if (l.size > 1) " (+${l.size - 1})" else ""), nivel) {
                        mostrarDetalhe(nomes[pacote] ?: pacote, pacote, l)
                    })
                }
                for ((pacote, nome) in apps) if (pacote !in ocorr)
                    adicionar(linha(nome, "nada registrado", 0) { mostrarDetalhe(nome, pacote, emptyList()) })
            }
        }
    }

    // ---------------------------------------------------------------- detalhe de um app

    /** Troca a tela pelo histórico completo de um app. A rolagem é pelas setas do controle (a ScrollView tem o foco). */
    private fun mostrarDetalhe(nome: String, pacote: String, l: List<Diagnostico.Ocorrencia>) {
        val sb = StringBuilder()
        if (l.isEmpty()) sb.append("Nenhum crash, travada ou encerramento anormal nos últimos 7 dias.\n\n" +
            "Se o app abre mas não carrega nada, o problema costuma ser rede: veja a seção Rede na tela anterior.")
        for (o in l) {
            sb.append("■ ").append(DateUtils.formatDateTime(this, o.quando,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY))
                .append(" — ").append(o.tipo).append("\n")
            // Do texto do DropBox, as primeiras 25 linhas bastam: cabeçalho + o erro + o começo da pilha.
            sb.append(o.texto.lines().take(25).joinToString("\n")).append("\n\n")
        }
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = resources.getDrawable(R.drawable.fundo, theme)
            setPadding(dp(96), dp(48), dp(96), dp(32))
        }
        raiz.addView(texto(32f, textoClaro, fonteLeve).apply { text = nome })
        raiz.addView(texto(14f, textoSuave).apply { text = "$pacote · setas rolam · BACK volta" })
        val corpo = texto(15f, textoClaro, Typeface.MONOSPACE).apply { text = sb; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        val rolagem = ScrollView(this).apply {
            isFocusable = true
            isVerticalScrollBarEnabled = true
            addView(corpo)
        }
        raiz.addView(rolagem, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(16) })
        noDetalhe = true
        setContentView(raiz)
        rolagem.requestFocus()
    }

    // ---------------------------------------------------------------- peças

    private fun adicionar(v: View) = lista.addView(v, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })

    private fun secao(titulo: String) = lista.addView(texto(13f, textoSuave, fonteMedia).apply {
        text = titulo.uppercase(); letterSpacing = 0.12f; setPadding(dp(24), dp(14), 0, dp(6))
    })

    private fun linha(titulo: String, detalhe: String, nivel: Int, aoClicar: () -> Unit): TextView = TextView(this).apply {
        montarLinha(titulo, detalhe, nivel)
        isFocusable = true
        isClickable = true
        background = vidro(false)
        setOnClickListener { aoClicar() }
        setOnFocusChangeListener { v, temFoco ->
            v.background = vidro(temFoco)
            v.animate().scaleX(if (temFoco) 1.02f else 1f).scaleY(if (temFoco) 1.02f else 1f).setDuration(120).start()
        }
    }

    private fun info(titulo: String, detalhe: String, nivel: Int = -1): TextView = TextView(this).apply {
        montarLinha(titulo, detalhe, nivel)
        setPadding(dp(24), dp(10), dp(24), dp(10))
    }

    private fun TextView.mostrar(item: Diagnostico.Item) = montarLinha(item.titulo, item.detalhe, item.nivel)

    /** "● Título" (bolinha na cor do nível; -1 = sem bolinha) e o detalhe embaixo, menor e suave. */
    private fun TextView.montarLinha(titulo: String, detalhe: String, nivel: Int) {
        val sb = SpannableStringBuilder()
        if (nivel in 0..2) {
            sb.append("● ")
            sb.setSpan(ForegroundColorSpan(cores[nivel]), 0, sb.length, 0)
            sb.setSpan(AbsoluteSizeSpan(dp(20), false), 0, sb.length, 0)
        }
        val ini0 = sb.length
        sb.append(titulo)
        sb.setSpan(AbsoluteSizeSpan(dp(20), false), ini0, sb.length, 0)
        sb.setSpan(ForegroundColorSpan(textoClaro), ini0, sb.length, 0)
        sb.setSpan(TypefaceSpan(fonteMedia), ini0, sb.length, 0)
        val ini = sb.length
        sb.append("\n").append(detalhe)
        sb.setSpan(AbsoluteSizeSpan(dp(14), false), ini, sb.length, 0)
        sb.setSpan(ForegroundColorSpan(textoSuave), ini, sb.length, 0)
        text = sb
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
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
