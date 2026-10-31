package br.paulo.tvlauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * O ícone de sinal de Wi-Fi: 4 barrinhas que crescem da esquerda para a direita.
 * [definir] diz quantas acendem e de que cor; as outras ficam apagadas (branco bem fraco).
 * Desenhado em código (Canvas) para não precisar de arquivo de imagem.
 */
class SinalWifi(contexto: Context) : View(contexto) {
    private var barras = 0
    private var cor = 0xFFFFFFFF.toInt()
    private val tinta = Paint(Paint.ANTI_ALIAS_FLAG)
    private val retangulo = RectF()

    fun definir(barras: Int, cor: Int) {
        this.barras = barras
        this.cor = cor
        invalidate()   // pede para a tela redesenhar
    }

    override fun onDraw(tela: Canvas) {
        // 4 barras de largura "l" e 3 vãos de 0,6 l: 4l + 1,8l = 5,8l = largura total.
        val l = width / 5.8f
        val vao = l * 0.6f
        for (i in 0 until 4) {
            val altura = height * (i + 1) / 4f   // 25%, 50%, 75%, 100% da altura
            val esquerda = i * (l + vao)
            tinta.color = if (i < barras) cor else 0x33FFFFFF
            retangulo.set(esquerda, height - altura, esquerda + l, height.toFloat())
            tela.drawRoundRect(retangulo, l / 3f, l / 3f, tinta)
        }
    }
}
