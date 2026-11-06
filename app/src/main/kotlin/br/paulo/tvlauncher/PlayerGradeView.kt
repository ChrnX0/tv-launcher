package br.paulo.tvlauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Player universal, guia em grade (06/11/2026): canais nas linhas, horários nas colunas, como na TV a cabo.
 *
 * É UMA View que desenha tudo no Canvas, em vez de uma View por programa: com 8 linhas × ~6 programas seriam ~50 Views
 * refeitas a cada ◀ ▶, e esta TV (S905D3) já está no limite da GPU na HOME. Aqui cada passo é um único desenho.
 * A matemática (cursor, janela, rolagem) é a PlayerGrade, testada fora do Android.
 *
 * Teclas: ▲ ▼ canal, ◀ ▶ programa, CH+ CH- página, OK assiste o canal da linha, VOLTAR fecha.
 */
class PlayerGradeView(
    ctx: Context,
    private val aoAssistir: (Canal) -> Unit,
    private val aoFechar: () -> Unit,
) : View(ctx) {
    private var canais: List<Canal> = emptyList()
    private var guia = Guia(emptyList())
    private var titulo = ""
    private var atualId: String? = null
    private var linha = 0
    private var topo = 0
    private var foco = 0L          // instante do cursor
    private var janela = 0L        // começo da janela de 3 h

    private val d = resources.displayMetrics.density
    private val hora = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val papel = 0xFFF5F3EE.toInt()
    private val suave = 0xB3F5F3EE.toInt()
    private val ambar = 0xFFF5B544.toInt()
    private val coral = 0xFFFF5A4E.toInt()

    private val pTexto = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = papel; textSize = 14f * d; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val pPequeno = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = suave; textSize = 11.5f * d }
    private val pTitulo = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = papel; textSize = 19f * d; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val pRotulo = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99F5F3EE.toInt(); textSize = 11.5f * d; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.06f }
    private val pBloco = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pBorda = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d; color = papel }
    private val pLinha = Paint().apply { color = coral; strokeWidth = 2f * d }
    private val r = RectF()

    // Medidas (em px)
    private val margem = 28f * d
    private val alturaCabecalho = 34f * d
    private val alturaRegua = 26f * d
    private val alturaLinha = 54f * d
    private val alturaDetalhe = 110f * d
    private val larguraCanal = 220f * d

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(0xF20B0F17.toInt())
        visibility = GONE
    }

    val aberta: Boolean get() = visibility == VISIBLE

    fun abrir(canais: List<Canal>, guia: Guia, titulo: String, atual: Canal?) {
        this.canais = canais; this.guia = guia; this.titulo = titulo; atualId = atual?.id
        val agora = System.currentTimeMillis()
        linha = canais.indexOfFirst { it.id == atualId }.coerceAtLeast(0)
        topo = PlayerGrade.ajustarTopo(linha - visiveis() / 2, linha, visiveis(), canais.size)
        foco = agora
        janela = PlayerGrade.inicioFaixa(agora)
        visibility = VISIBLE
        requestFocus()
        invalidate()
        removeCallbacks(relogio); postDelayed(relogio, 30_000)
    }

    fun fechar() { removeCallbacks(relogio); visibility = GONE }

    fun atualizarGuia(g: Guia) { guia = g; if (aberta) invalidate() }

    /** A linha vermelha do "agora" anda sozinha; e o cursor não pode ficar no passado. */
    private val relogio: Runnable = object : Runnable {
        override fun run() {
            if (!aberta) return
            val agora = System.currentTimeMillis()
            if (foco < agora) foco = agora
            janela = PlayerGrade.ajustarJanela(janela, foco, agora)
            invalidate()
            postDelayed(this, 30_000)
        }
    }

    private fun visiveis(): Int {
        val h = if (height > 0) height.toFloat() else resources.displayMetrics.heightPixels.toFloat()
        return ((h - margem - alturaCabecalho - alturaRegua - alturaDetalhe - margem / 2) / alturaLinha).toInt().coerceAtLeast(1)
    }

    private fun programas(c: Canal): List<Programa> = if (c.guiaId.isEmpty()) emptyList() else guia.doCanal(c.guiaId)

    // ---------------------------------------------------------------- teclas

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!aberta || canais.isEmpty()) return super.onKeyDown(keyCode, event)
        val agora = System.currentTimeMillis()
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> trocarLinha(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> trocarLinha(+1)
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> trocarLinha(-visiveis())
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> trocarLinha(+visiveis())
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val passo = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) +1 else -1
                foco = PlayerGrade.mover(programas(canais[linha]), foco, passo, agora)
                janela = PlayerGrade.ajustarJanela(janela, foco, agora)
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.repeatCount == 0) aoAssistir(canais[linha])
                return true
            }
            KeyEvent.KEYCODE_BACK -> { aoFechar(); return true }
            else -> return super.onKeyDown(keyCode, event)   // volume e o resto seguem para o Android
        }
        invalidate()
        return true
    }

    private fun trocarLinha(passo: Int) {
        linha = PlayerGrade.moverLinha(linha, passo, canais.size)
        topo = PlayerGrade.ajustarTopo(topo, linha, visiveis(), canais.size)
    }

    // ---------------------------------------------------------------- desenho

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (canais.isEmpty()) return
        val agora = System.currentTimeMillis()
        val esquerda = margem
        val direita = width - margem
        val xGrade = esquerda + larguraCanal
        val larguraGrade = direita - xGrade
        val pxPorMs = larguraGrade / PlayerGrade.JANELA
        fun x(t: Long) = xGrade + (t - janela) * pxPorMs

        // Cabeçalho: título e hora.
        var y = margem
        c.drawText(titulo.uppercase(Locale.getDefault()), esquerda, y + 14f * d, pRotulo)
        val relogio = hora.format(Date(agora))
        c.drawText(relogio, direita - pTexto.measureText(relogio), y + 14f * d, pTexto)
        y += alturaCabecalho

        // Régua de 30 em 30 min.
        var t = janela
        while (t < janela + PlayerGrade.JANELA) {
            c.drawText(hora.format(Date(t)), x(t) + 6f * d, y + 16f * d, pPequeno)
            t += PlayerGrade.FAIXA
        }
        y += alturaRegua
        val yLinhas = y

        // Linhas.
        val n = visiveis()
        val focado = programaFocado()
        for (k in 0 until n) {
            val i = topo + k
            if (i >= canais.size) break
            val canal = canais[i]
            val y0 = yLinhas + k * alturaLinha
            val y1 = y0 + alturaLinha - 4f * d
            val meio = (y0 + y1) / 2

            // Coluna do canal: número, logo e nome.
            pTexto.color = if (canal.id == atualId) ambar else papel
            c.drawText(canal.numero.toString(), esquerda + 4f * d, meio + 5f * d, pTexto)
            val logo = PlayerLogos.emMemoria(context, canal.logo) { invalidate() }
            val xLogo = esquerda + 46f * d
            if (logo != null) {
                val esc = minOf(52f * d / logo.width, 34f * d / logo.height)
                r.set(xLogo, meio - logo.height * esc / 2, xLogo + logo.width * esc, meio + logo.height * esc / 2)
                c.drawBitmap(logo, null, r, null)
            }
            pTexto.color = papel
            val nome = TextUtils.ellipsize(canal.nome, pTexto, larguraCanal - 120f * d, TextUtils.TruncateAt.END)
            c.drawText(nome, 0, nome.length, esquerda + 106f * d, meio + 5f * d, pTexto)

            // Programas.
            val ps = PlayerGrade.naJanela(programas(canal), janela)
            if (ps.isEmpty()) {
                r.set(xGrade, y0, direita, y1)
                pBloco.color = 0x0FFFFFFF
                c.drawRoundRect(r, 8f * d, 8f * d, pBloco)
                c.drawText(context.getString(R.string.player_sem_guia_curto), xGrade + 12f * d, meio + 4f * d, pPequeno)
                if (i == linha) c.drawRoundRect(r, 8f * d, 8f * d, pBorda)
                continue
            }
            for (p in ps) {
                val xa = maxOf(x(p.inicio), xGrade) + 2f * d
                val xb = minOf(x(p.fim), direita) - 2f * d
                if (xb - xa < 4f * d) continue
                r.set(xa, y0, xb, y1)
                val noAr = agora >= p.inicio && agora < p.fim
                pBloco.color = when {
                    i == linha && p === focado -> 0x33F5F3EE
                    noAr -> 0x1FF5F3EE
                    else -> 0x12F5F3EE
                }
                c.drawRoundRect(r, 8f * d, 8f * d, pBloco)
                if (i == linha && p === focado) c.drawRoundRect(r, 8f * d, 8f * d, pBorda)
                val larguraTexto = xb - xa - 20f * d
                if (larguraTexto > 12f * d) {
                    pTexto.color = papel
                    val tt = TextUtils.ellipsize(p.titulo, pTexto, larguraTexto, TextUtils.TruncateAt.END)
                    c.drawText(tt, 0, tt.length, xa + 10f * d, meio - 1f * d, pTexto)
                    val hh = TextUtils.ellipsize("${hora.format(Date(p.inicio))} – ${hora.format(Date(p.fim))}", pPequeno, larguraTexto, TextUtils.TruncateAt.END)
                    c.drawText(hh, 0, hh.length, xa + 10f * d, meio + 15f * d, pPequeno)
                }
            }
        }

        // Linha do "agora".
        val xAgora = x(agora)
        if (xAgora in xGrade..direita) c.drawLine(xAgora, yLinhas - 6f * d, xAgora, yLinhas + n * alturaLinha, pLinha)

        // Detalhe do programa em foco (embaixo).
        val yD = height - margem / 2 - alturaDetalhe
        r.set(esquerda, yD, direita, height - margem / 2)
        pBloco.color = 0xCC0B0F17.toInt()
        c.drawRoundRect(r, 14f * d, 14f * d, pBloco)
        val canal = canais.getOrNull(linha) ?: return
        val xt = esquerda + 18f * d
        val larg = direita - esquerda - 36f * d
        var yt = yD + 30f * d
        val tituloDet = TextUtils.ellipsize(focado?.titulo ?: canal.nome, pTitulo, larg, TextUtils.TruncateAt.END)
        c.drawText(tituloDet, 0, tituloDet.length, xt, yt, pTitulo)
        yt += 20f * d
        val info = buildList {
            add("${canal.numero}  ${canal.nome}")
            focado?.let { add("${hora.format(Date(it.inicio))} – ${hora.format(Date(it.fim))}"); if (it.categoria.isNotEmpty()) add(it.categoria) }
            add(context.getString(R.string.player_grade_teclas))
        }.joinToString("   ·   ")
        val infoCurta = TextUtils.ellipsize(info, pPequeno, larg, TextUtils.TruncateAt.END)
        c.drawText(infoCurta, 0, infoCurta.length, xt, yt, pPequeno)
        val desc = focado?.descricao.orEmpty()
        if (desc.isNotEmpty()) {
            // Descrição em até 2 linhas (corte simples por largura; o resto vira "…").
            pPequeno.color = papel
            var resto = desc
            for (k in 0 until 2) {
                if (resto.isEmpty()) break
                yt += 19f * d
                val cabe = pPequeno.breakText(resto, true, larg, null)
                val ultima = k == 1 || cabe >= resto.length
                val trecho = if (ultima) TextUtils.ellipsize(resto, pPequeno, larg, TextUtils.TruncateAt.END).toString()
                    else resto.substring(0, resto.lastIndexOf(' ', cabe).takeIf { it > 0 } ?: cabe)
                c.drawText(trecho, xt, yt, pPequeno)
                resto = resto.substring(minOf(resto.length, trecho.length)).trimStart()
                if (ultima) break
            }
            pPequeno.color = suave
        }
    }

    private fun programaFocado(): Programa? = canais.getOrNull(linha)?.let { PlayerGrade.programaEm(programas(it), foco) }
}
