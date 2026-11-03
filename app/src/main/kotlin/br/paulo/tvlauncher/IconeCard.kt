package br.paulo.tvlauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * Icone pequeno (desenhado em codigo, sem arquivos de imagem) para o lado esquerdo do titulo dos cards do
 * popup de perfil: mesmo espirito do SinalWifi e do IndicadorPilha, so que aqui um simbolo fixo por [tipo]
 * ("bluetooth", "sistema", "audio" ou "launcher"). Traço fino e branco translucido, para combinar com o resto.
 */
class IconeCard(ctx: Context, private val tipo: String) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val tinta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFFE8EEEA.toInt()
    }
    private val preenchimento = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFE8EEEA.toInt()
    }
    private val caminho = Path()
    private val caixa = RectF()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        when (tipo) {
            "bluetooth" -> bluetooth(c, w, h)
            "sistema" -> sistema(c, w, h)
            "audio" -> audio(c, w, h)
            "launcher" -> launcher(c, w, h)
        }
    }

    /** O "laço duplo" do simbolo Bluetooth: duas pontas no centro formando um X alongado. */
    private fun bluetooth(c: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val topo = h * 0.08f; val fundo = h * 0.92f
        val meioCima = h * 0.32f; val meioBaixo = h * 0.68f
        val direita = cx + w * 0.22f
        caminho.reset()
        caminho.moveTo(cx, topo)
        caminho.lineTo(direita, meioCima)
        caminho.lineTo(cx, fundo)
        caminho.lineTo(direita, meioBaixo)
        caminho.close()
        c.drawPath(caminho, tinta)
    }

    /** Engrenagem simplificada: anel central com 6 dentes ao redor. */
    private fun sistema(c: Canvas, w: Float, h: Float) {
        val cx = w / 2f; val cy = h / 2f
        val rOut = w * 0.46f; val rIn = w * 0.30f
        for (i in 0 until 6) {
            c.save()
            c.rotate(i * 60f, cx, cy)
            caixa.set(cx - w * 0.08f, cy - rOut, cx + w * 0.08f, cy - rIn * 0.82f)
            c.drawRoundRect(caixa, w * 0.03f, w * 0.03f, preenchimento)
            c.restore()
        }
        c.drawCircle(cx, cy, rIn, tinta)
    }

    /** Alto-falante: corpo + cone, com duas ondas de som abrindo para a direita. */
    private fun audio(c: Canvas, w: Float, h: Float) {
        caminho.reset()
        caminho.moveTo(w * 0.06f, h * 0.38f)
        caminho.lineTo(w * 0.30f, h * 0.38f)
        caminho.lineTo(w * 0.52f, h * 0.14f)
        caminho.lineTo(w * 0.52f, h * 0.86f)
        caminho.lineTo(w * 0.30f, h * 0.62f)
        caminho.lineTo(w * 0.06f, h * 0.62f)
        caminho.close()
        c.drawPath(caminho, tinta)
        caixa.set(w * 0.56f, h * 0.30f, w * 0.80f, h * 0.70f)
        c.drawArc(caixa, -50f, 100f, false, tinta)
        caixa.set(w * 0.68f, h * 0.14f, w * 1.00f, h * 0.86f)
        c.drawArc(caixa, -45f, 90f, false, tinta)
    }

    /** Grade 2x2: quatro quadradinhos arredondados — "nossas configs", o app em si. */
    private fun launcher(c: Canvas, w: Float, h: Float) {
        val gap = w * 0.14f
        val lado = (w - gap) / 2f
        val raio = lado * 0.22f
        for (linha in 0..1) for (coluna in 0..1) {
            val x0 = coluna * (lado + gap); val y0 = linha * (lado + gap)
            caixa.set(x0, y0, x0 + lado, y0 + lado)
            c.drawRoundRect(caixa, raio, raio, preenchimento)
        }
    }
}
