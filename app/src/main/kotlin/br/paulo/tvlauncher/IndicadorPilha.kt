package br.paulo.tvlauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/**
 * O selo das pilhas de cards: uma pastilha escura com "^ v" (seta para cima e seta para baixo, lado a lado) e "2/3",
 * que diz qual dos 3 apps esta na frente. Mostra que a pilha tem varios apps e que ▲ ▼ trocam qual fica na frente.
 * Tudo desenhado em codigo (sem fonte de simbolos, que muda de TV para TV).
 */
class IndicadorPilha(ctx: Context, private val posicao: Int, private val total: Int, fonte: Typeface) : View(ctx) {
    companion object {
        const val LARGURA_DP = 76
        const val ALTURA_DP = 24
    }

    private val d = ctx.resources.displayMetrics.density
    private val caixa = RectF()
    private val tinta = Paint(Paint.ANTI_ALIAS_FLAG)
    private val seta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFFFFFFFF.toInt()
    }
    private val numero = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = fonte
        color = 0xFFFFFFFF.toInt()
        textSize = 11f * d
        textAlign = Paint.Align.LEFT
    }
    private val caminho = Path()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        // pastilha escura e translucida, com uma borda clara fina (le sobre banners claros e escuros)
        caixa.set(0.5f * d, 0.5f * d, w - 0.5f * d, h - 0.5f * d)
        tinta.style = Paint.Style.FILL; tinta.color = 0xCC0B0F12.toInt()
        c.drawRoundRect(caixa, h / 2, h / 2, tinta)
        tinta.style = Paint.Style.STROKE; tinta.strokeWidth = 1f * d; tinta.color = 0x40FFFFFF
        c.drawRoundRect(caixa, h / 2, h / 2, tinta)

        val cy = h / 2
        chevron(c, 14f * d, cy, subindo = true)
        chevron(c, 28f * d, cy, subindo = false)
        // divisoria e "posicao/total"
        tinta.style = Paint.Style.FILL; tinta.color = 0x40FFFFFF
        c.drawRect(38f * d, cy - 5f * d, 38f * d + 1f * d, cy + 5f * d, tinta)
        val texto = "$posicao/$total"
        c.drawText(texto, 45f * d, cy - (numero.ascent() + numero.descent()) / 2, numero)
    }

    /** Uma seta em "v" (ou "^"): dois tracos arredondados, 8 dp de largura. */
    private fun chevron(c: Canvas, cx: Float, cy: Float, subindo: Boolean) {
        val meia = 4.5f * d; val alt = 2.5f * d
        val ponta = if (subindo) cy - alt else cy + alt
        val base = if (subindo) cy + alt else cy - alt
        caminho.reset()
        caminho.moveTo(cx - meia, base)
        caminho.lineTo(cx, ponta)
        caminho.lineTo(cx + meia, base)
        c.drawPath(caminho, seta)
    }
}
