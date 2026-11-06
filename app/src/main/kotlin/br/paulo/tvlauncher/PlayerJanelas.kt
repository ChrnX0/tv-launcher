package br.paulo.tvlauncher

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout

/**
 * Player universal, busca e filtros (06/11/2026): as janelinhas (AlertDialog do próprio Android) de busca, filtros e
 * PIN. São do sistema de propósito: o teclado da TV (com o microfone para ditar) já sabe trabalhar com elas.
 */
class PlayerJanelas(private val act: Activity) {
    private val tema = android.R.style.Theme_DeviceDefault_Dialog_Alert
    private var errosPin = 0   // nesta abertura do player; 5 erros = só depois de sair e voltar

    /** Pede o termo de busca. `aoBuscar` recebe o texto já sem espaços nas pontas (vazio = desistiu). */
    fun buscar(ultimo: String, aoBuscar: (String) -> Unit) {
        val campo = EditText(act).apply {
            setText(ultimo); setSelection(ultimo.length)
            hint = act.getString(R.string.player_busca_dica)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            isSingleLine = true
        }
        val d = AlertDialog.Builder(act, tema)
            .setTitle(R.string.player_acao_buscar)
            .setView(moldura(campo))
            .setPositiveButton(R.string.player_acao_buscar) { _, _ -> aoBuscar(campo.text.toString().trim()) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        // "Buscar" do teclado da TV (ou ENTER) já busca, sem ter de descer até o botão.
        campo.setOnEditorActionListener { _, acao, ev ->
            val enter = ev?.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN
            if (acao == EditorInfo.IME_ACTION_SEARCH || enter) { d.dismiss(); aoBuscar(campo.text.toString().trim()); true } else false
        }
        d.setOnShowListener { campo.requestFocus() }
        d.show()
    }

    /**
     * Menu de filtros. Mostra só as opções que existem nesta lista (país só se houver 2 ou mais).
     * @param aoMudar recebe o filtro novo
     * @param aoPedirOcultas chamado em "Mostrar categorias ocultas" (a Activity pede o PIN)
     */
    fun filtros(canais: List<Canal>, f: PlayerFiltro.Filtro, temOcultas: Boolean, aoMudar: (PlayerFiltro.Filtro) -> Unit, aoPedirOcultas: () -> Unit) {
        val qualidades = PlayerFiltro.qualidades(canais)
        val paises = PlayerFiltro.paises(canais)
        val qualquer = act.getString(R.string.player_filtro_qualquer)
        val itens = ArrayList<Pair<String, () -> Unit>>()
        if (qualidades.isNotEmpty()) itens += act.getString(R.string.player_filtro_qualidade, f.qualidade?.let { PlayerFiltro.rotulo(it) } ?: qualquer) to {
            escolher(R.string.player_filtro_qualidade_titulo, listOf(qualquer) + qualidades.map { "${PlayerFiltro.rotulo(it.first)}  (${it.second})" },
                1 + qualidades.indexOfFirst { it.first == f.qualidade }) { i -> aoMudar(f.copy(qualidade = qualidades.getOrNull(i - 1)?.first)) }
        }
        if (paises.size >= 2) itens += act.getString(R.string.player_filtro_pais, f.pais ?: qualquer) to {
            escolher(R.string.player_filtro_pais_titulo, listOf(qualquer) + paises.map { "${it.first}  (${it.second})" },
                1 + paises.indexOfFirst { it.first == f.pais }) { i -> aoMudar(f.copy(pais = paises.getOrNull(i - 1)?.first)) }
        }
        if (temOcultas) itens += act.getString(if (f.mostrarOcultas) R.string.player_ocultas_esconder else R.string.player_ocultas_mostrar) to {
            if (f.mostrarOcultas) aoMudar(f.copy(mostrarOcultas = false)) else aoPedirOcultas()
        }
        if (f.ativo) itens += act.getString(R.string.player_filtro_limpar) to { aoMudar(f.copy(qualidade = null, pais = null)) }
        if (itens.isEmpty()) itens += act.getString(R.string.player_filtro_nada) to {}
        AlertDialog.Builder(act, tema)
            .setTitle(R.string.player_acao_filtros)
            .setItems(itens.map { it.first }.toTypedArray()) { _, i -> itens[i].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun escolher(titulo: Int, opcoes: List<String>, marcada: Int, aoEscolher: (Int) -> Unit) {
        AlertDialog.Builder(act, tema)
            .setTitle(titulo)
            .setSingleChoiceItems(opcoes.toTypedArray(), marcada.coerceAtLeast(0)) { d, i -> d.dismiss(); aoEscolher(i) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Pede o PIN do painel (o mesmo do painel no navegador e do MCP). Sem PIN definido, libera direto: a categoria oculta
     * é só para limpar a lista, e a mensagem diz como proteger.
     */
    fun pedirPin(aoLiberar: () -> Unit, aviso: (String) -> Unit) {
        if (!PainelArmazem.temPin(act)) { aviso(act.getString(R.string.player_pin_sem)); aoLiberar(); return }
        if (errosPin >= 5) { aviso(act.getString(R.string.player_pin_bloqueado)); return }
        val campo = EditText(act).apply {
            hint = act.getString(R.string.player_pin_dica)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            isSingleLine = true
        }
        val conferir = {
            // PBKDF2 com 100 mil voltas: pode passar de 1 s nesta TV, então roda fora da thread da tela.
            val pin = campo.text.toString()
            Thread {
                val certo = PainelArmazem.conferirPin(act, pin)
                act.runOnUiThread {
                    if (act.isDestroyed) return@runOnUiThread
                    if (certo) { errosPin = 0; aoLiberar() } else { errosPin++; aviso(act.getString(R.string.player_pin_errado)) }
                }
            }.start()
        }
        val d = AlertDialog.Builder(act, tema)
            .setTitle(R.string.player_pin_titulo)
            .setView(moldura(campo))
            .setPositiveButton(android.R.string.ok) { _, _ -> conferir() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        campo.setOnEditorActionListener { _, acao, _ ->
            if (acao == EditorInfo.IME_ACTION_DONE || acao == EditorInfo.IME_ACTION_GO) { d.dismiss(); conferir(); true } else false
        }
        d.setOnShowListener { campo.requestFocus() }
        d.show()
    }

    private fun moldura(campo: EditText) = FrameLayout(act).apply {
        val m = (20 * act.resources.displayMetrics.density).toInt()
        setPadding(m, m / 2, m, 0)
        addView(campo)
    }
}
