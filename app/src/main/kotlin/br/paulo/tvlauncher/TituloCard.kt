package br.paulo.tvlauncher

import android.content.Context
import android.graphics.Paint
import android.util.TypedValue
import android.widget.TextView

/**
 * Título do card do catálogo (Filmes/Séries). O TextView quebra no meio da palavra quando uma palavra sozinha não
 * cabe na largura do card ("Desaparecid / a"). Aqui, antes de medir, a fonte desce de 0,5 em 0,5 sp até a palavra
 * mais larga caber, com piso em [minimoSp]; abaixo disso aceita a quebra (título absurdo de longo).
 */
class TituloCard(ctx: Context, private val tamanhoSp: Float, private val minimoSp: Float = 9f) : TextView(ctx) {

    private val medidor = Paint()

    init { setTextSize(TypedValue.COMPLEX_UNIT_SP, tamanhoSp) }

    override fun onMeasure(larguraSpec: Int, alturaSpec: Int) {
        val largura = MeasureSpec.getSize(larguraSpec) - paddingLeft - paddingRight
        if (MeasureSpec.getMode(larguraSpec) != MeasureSpec.UNSPECIFIED && largura > 0) ajustar(largura)
        super.onMeasure(larguraSpec, alturaSpec)
    }

    private fun ajustar(largura: Int) {
        val palavras = text.split(' ', '\n').filter { it.isNotEmpty() }
        medidor.set(paint)
        var sp = tamanhoSp
        while (true) {
            medidor.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
            if (sp <= minimoSp || palavras.all { medidor.measureText(it) <= largura }) break
            sp -= 0.5f
        }
        // Só troca quando muda: setTextSize pede outro layout, e trocar sempre faria um laço de medidas.
        if (textSize != medidor.textSize) setTextSize(TypedValue.COMPLEX_UNIT_PX, medidor.textSize)
    }
}
