package br.paulo.tvlauncher

import android.view.KeyEvent

/**
 * OK segurado 2 s numa grade de pôsteres (Filmes e Séries, 07/11/2026): toque curto = o clique de sempre (abre a ficha);
 * segurar 2 s = põe/tira dos interesses. Mesma regra do OK segurado nos canais (PlayerActivity), em peça reaproveitável.
 *
 * Por que não o "clique longo" do GridView: o Android dispara o longo em ~0,5 s e não deixa mudar esse tempo. Então a
 * Activity entrega aqui as teclas OK (dispatchKeyEvent) enquanto a grade tem o foco, e nós contamos o tempo pelo próprio
 * evento: quando a tecla fica apertada, o controle repete o ACTION_DOWN (repeatCount 1, 2, ...) e cada repetição traz
 * downTime (quando começou) e eventTime (agora).
 */
class SegurarOk(private val tempoMs: Long = 2_000L) {
    private var apertado = false   // o DOWN começou com a grade em foco (um UP sozinho, vindo de outra tela, é ignorado)
    private var segurou = false    // já disparou nesta apertada: soltar não faz mais nada

    /** Consome o evento (sempre true). Chama [aoCurto] ao soltar antes de 2 s, ou [aoSegurar] uma vez ao completar 2 s. */
    fun tratar(e: KeyEvent, aoCurto: () -> Unit, aoSegurar: () -> Unit): Boolean {
        when (e.action) {
            KeyEvent.ACTION_DOWN ->
                if (e.repeatCount == 0) { apertado = true; segurou = false }
                else if (apertado && !segurou && e.eventTime - e.downTime >= tempoMs) { segurou = true; aoSegurar() }
            KeyEvent.ACTION_UP -> {
                val valia = apertado && !segurou && !e.isCanceled
                apertado = false
                if (valia) aoCurto()
            }
        }
        return true
    }

    /** Esquece uma apertada em andamento (o foco saiu da grade no meio). */
    fun soltar() { apertado = false }

    companion object {
        fun ehOk(k: Int) = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER
    }
}
