package br.paulo.tvlauncher

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.widget.AbsListView

/**
 * Moldura de foco das listas do player (06/11/2026). Na TV (sem toque), o ListView/GridView desenha a moldura no item
 * selecionado MESMO sem ter o foco: com duas colunas na tela, apareciam dois destaques ao mesmo tempo (visto na Sala no
 * catálogo de Filmes: "Buscar" e o 1º pôster). Aqui a moldura só existe na lista que tem o foco; a outra fica sem.
 */
object PlayerFoco {
    fun molduraSoComFoco(l: AbsListView, moldura: Drawable) {
        val nenhuma = ColorDrawable(Color.TRANSPARENT)
        l.selector = if (l.hasFocus()) moldura else nenhuma
        l.setOnFocusChangeListener { _, tem -> l.selector = if (tem) moldura else nenhuma; l.invalidate() }
    }
}
