package br.paulo.tvlauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * O selo das pilhas de cards: uma pastilha escura com "^ v" (seta para cima e seta para baixo, lado a lado),
 * so para mostrar que a pilha tem mais de um app e que ▲ ▼ trocam qual fica na frente. Sem numero (simplificado:
 * o "1/2" ocupava espaco e nao ajudava — a troca de card ja mostra visualmente quantos tem). Tudo desenhado em
 * codigo (sem fonte de simbolos, que muda de TV para TV).
 */
class IndicadorPilha(ctx: Context) : View(ctx) {
    companion object {
        const val LARGURA_DP = 40
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
        chevron(c, w / 2 - 6f * d, cy, subindo = true)
        chevron(c, w / 2 + 6f * d, cy, subindo = false)
    }

    /** Uma seta em "v" (ou "^"): dois tracos arredondados. */
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
