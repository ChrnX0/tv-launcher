package br.paulo.tvlauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * Player universal, filmes do Xtream (06/11/2026): o catálogo de filmes, com pôsteres, categorias, ordem, busca,
 * "continuar assistindo" e a ficha de cada filme. Quem toca é a VodActivity.
 *
 *   [ categorias ]   [ grade de pôsteres (6 por linha)                ]
 *     ⌕ Buscar         ▓▓ ▓▓ ▓▓ ▓▓ ▓▓ ▓▓      OK num pôster abre a ficha: sinopse, elenco, duração,
 *     ⇅ Ordem          ▓▓ ▓▓ ▓▓ ▓▓ ▓▓ ▓▓      e os botões Continuar / Do começo / Assistir.
 *     ▶ Continuar
 *     ★ Interesses     OK segurado 2 s num pôster põe/tira dos interesses (como os favoritos dos canais).
 *     ✓ Assistidos     tudo o que já foi visto, com a etiqueta "42%" ou "Finalizado" no pôster.
 *     Todos · Ação · ...
 *
 * As duas colunas são ListView/GridView do Android (só desenham o que está na tela: 30 mil filmes custam o mesmo que 30).
 * Processo ":player" (manifesto), como o resto do player.
 */
class FilmesActivity : Activity() {
    private companion object {
        const val BUSCAR = "*buscar"
        const val ORDEM = "*ordem"
        const val CONTINUAR = "*continuar"
        const val INTERESSES = "*interesses"
        const val ASSISTIDOS = "*assistidos"
        const val TODOS = "*todos"
        const val BUSCA = "*busca"
    }

    private data class Item(val chave: String, val nome: String, val quantos: Int)

    private val tela = Handler(Looper.getMainLooper())
    private val papel = 0xFFF5F3EE.toInt()
    private val suave = 0xB3F5F3EE.toInt()
    private val apagado = 0x80F5F3EE.toInt()
    private val ambar = 0xFFF5B544.toInt()
    private val coral = 0xFFFF5A4E.toInt()
    private val verdeEtiqueta = 0xE62E7D4F.toInt()
    private val fonteMedia = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private var filmes: List<Filme> = emptyList()
    private var posicoes: Map<String, Posicao> = emptyMap()
    private var interesses: Set<String> = emptySet()
    private val segurar = SegurarOk()
    private var ordem = Vod.Ordem.RECENTES
    private var itens: List<Item> = emptyList()
    private var chave = TODOS
    private var termo = ""
    private var resultados: List<Filme> = emptyList()
    private var naGrade: List<Filme> = emptyList()
    private var ficha: Filme? = null

    private lateinit var listaCats: ListView
    private lateinit var grade: GridView
    private lateinit var cabecalho: TextView
    private lateinit var focado: TextView
    private lateinit var aviso: TextView
    private lateinit var painelFicha: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ordem = runCatching { Vod.Ordem.valueOf(getSharedPreferences("player", MODE_PRIVATE).getString("ordem_filmes", "")!!) }.getOrDefault(Vod.Ordem.RECENTES)
        val raiz = FrameLayout(this).apply { setBackgroundColor(0xFF0B0F17.toInt()) }
        val colunas = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(32), dp(26), dp(32), dp(18)) }
        listaCats = ListView(this).apply { estiloLista(this) }
        colunas.addView(coluna(rotulo(getString(R.string.filmes_titulo)), listaCats), LinearLayout.LayoutParams(dp(210), -1))
        val direita = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cabecalho = rotulo("")
        direita.addView(cabecalho)
        grade = GridView(this).apply {
            numColumns = 6
            horizontalSpacing = dp(12); verticalSpacing = dp(14)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            estiloLista(this)
            setPadding(dp(4), dp(4), dp(4), dp(4)); clipToPadding = false
        }
        direita.addView(grade, LinearLayout.LayoutParams(-1, 0, 1f))
        focado = TextView(this).apply { textSize = 13f; setTextColor(suave); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(8), dp(8), 0, 0) }
        direita.addView(focado)
        // Dica fixa das teclas, no mesmo jeito da lista de canais do player (player_lista_teclas: 11sp, cor apagada).
        direita.addView(TextView(this).apply { text = getString(R.string.vod_grade_teclas); textSize = 11f; setTextColor(apagado); setPadding(dp(8), dp(6), 0, 0) })
        colunas.addView(direita, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(16) })
        raiz.addView(colunas, FrameLayout.LayoutParams(-1, -1))
        painelFicha = FrameLayout(this).apply { visibility = View.GONE; setBackgroundColor(0xFF0B0F17.toInt()) }
        raiz.addView(painelFicha, FrameLayout.LayoutParams(-1, -1))
        aviso = TextView(this).apply {
            textSize = 18f; setTextColor(papel); gravity = Gravity.CENTER; background = vidro(16)
            setPadding(dp(24), dp(16), dp(24), dp(16)); text = getString(R.string.filmes_carregando)
        }
        raiz.addView(aviso, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setContentView(raiz)

        listaCats.adapter = adaptadorCats
        grade.adapter = adaptadorGrade
        listaCats.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                tela.removeCallbacks(trocarCat)
                if (listaCats.hasFocus()) tela.postDelayed(trocarCat, 180)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        listaCats.setOnItemClickListener { _, _, pos, _ ->
            when (itens.getOrNull(pos)?.chave) {
                BUSCAR -> buscar()
                ORDEM -> escolherOrdem()
                else -> { escolherCat(pos); if (naGrade.isNotEmpty()) grade.requestFocus() }
            }
        }
        grade.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { mostrarFocado(naGrade.getOrNull(pos)) }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        grade.setOnItemClickListener { _, _, pos, _ -> naGrade.getOrNull(pos)?.let { abrirFicha(it) } }
        carregar()
    }

    override fun onResume() {
        super.onResume()
        // Voltou da VodActivity: a posição mudou ("continuar" e os botões da ficha).
        if (filmes.isNotEmpty()) Thread {
            val p = PlayerArmazem.posicoes(this)
            tela.post {
                if (isDestroyed) return@post
                posicoes = p; montarItens()
                // "Assistidos" e "Continuar" mudam de conteúdo ao voltar do vídeo: refaz a grade sem perder o lugar.
                if (chave == ASSISTIDOS || chave == CONTINUAR) refazerGrade() else adaptadorGrade.notifyDataSetChanged()
                ficha?.let { abrirFicha(it, manterFoco = true) }
            }
        }.start()
    }

    override fun onDestroy() { tela.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun carregar() {
        Thread {
            val f = PlayerCarga.filmes(this)
            val p = PlayerArmazem.posicoes(this)
            val i = PlayerArmazem.interesses(this, series = false)
            tela.post {
                if (isDestroyed) return@post
                filmes = f; posicoes = p; interesses = i
                if (f.isEmpty()) { aviso.text = getString(R.string.filmes_vazio); return@post }
                aviso.visibility = View.GONE
                chave = if (Vod.continuar(f, p).isNotEmpty()) CONTINUAR else TODOS
                montarItens()
                preencherGrade()
                grade.requestFocus()
            }
        }.start()
    }

    // ---------------------------------------------------------------- categorias

    private val trocarCat = Runnable { escolherCat(listaCats.selectedItemPosition) }

    private fun montarItens() {
        val l = ArrayList<Item>()
        l += Item(BUSCAR, getString(R.string.player_acao_buscar), -1)
        l += Item(ORDEM, getString(R.string.filmes_ordem, nomeOrdem(ordem)), -1)
        val cont = Vod.continuar(filmes, posicoes).size
        if (cont > 0) l += Item(CONTINUAR, getString(R.string.filmes_continuar), cont)
        // Interesses aparece sempre (vazio explica como usar); Assistidos só depois do 1º filme visto.
        l += Item(INTERESSES, getString(R.string.filmes_interesses), Vod.interesses(filmes, interesses) { it.chave }.size)
        val vistos = Vod.assistidos(filmes, posicoes).size
        if (vistos > 0) l += Item(ASSISTIDOS, getString(R.string.filmes_assistidos), vistos)
        if (termo.isNotEmpty()) l += Item(BUSCA, getString(R.string.player_cat_busca, termo), resultados.size)
        l += Item(TODOS, getString(R.string.player_cat_todos), filmes.size)
        Vod.grupos(filmes).forEach { (g, n) -> l += Item("g:$g", PlayerLista.nomeExibido(g).ifEmpty { getString(R.string.player_cat_outros) }, n) }
        itens = l
        if (itens.none { it.chave == chave }) chave = TODOS
        adaptadorCats.notifyDataSetChanged()
        val pos = itens.indexOfFirst { it.chave == chave }
        if (!listaCats.hasFocus()) listaCats.setSelection(pos.coerceAtLeast(0))
    }

    private fun escolherCat(pos: Int) {
        val it = itens.getOrNull(pos) ?: return
        if (it.chave == chave || it.chave == BUSCAR || it.chave == ORDEM) return
        chave = it.chave
        adaptadorCats.notifyDataSetChanged()
        preencherGrade()
    }

    private fun preencherGrade() {
        naGrade = when {
            chave == CONTINUAR -> Vod.continuar(filmes, posicoes)          // já na ordem de "mais recente"
            chave == INTERESSES -> Vod.interesses(filmes, interesses) { it.chave }   // o último marcado primeiro
            chave == ASSISTIDOS -> Vod.assistidos(filmes, posicoes)        // o visto mais recente primeiro
            chave == BUSCA -> resultados
            chave.startsWith("g:") -> Vod.ordenar(filmes.filter { it.grupo.trim() == chave.removePrefix("g:") }, ordem)
            else -> Vod.ordenar(filmes, ordem)
        }
        adaptadorGrade.notifyDataSetChanged()
        grade.setSelection(0)
        val nome = itens.firstOrNull { it.chave == chave }?.nome.orEmpty()
        cabecalho.text = getString(R.string.filmes_cabecalho, nome.uppercase(Locale.getDefault()), naGrade.size)
        mostrarFocado(naGrade.firstOrNull())
    }

    /** Recalcula a categoria atual sem voltar ao 1º pôster (tirou um interesse, voltou do vídeo). */
    private fun refazerGrade() {
        val onde = grade.selectedItemPosition
        val tinhaFoco = grade.hasFocus()
        preencherGrade()
        if (naGrade.isNotEmpty()) { val p = onde.coerceIn(0, naGrade.size - 1); grade.setSelection(p); mostrarFocado(naGrade[p]) }
        else if (tinhaFoco) listaCats.requestFocus()   // esvaziou: o foco volta para as categorias
    }

    // ---------------------------------------------------------------- interesses (OK segurado 2 s)

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        // Só o OK com a grade em foco e a ficha fechada; o resto segue o caminho normal do Android.
        if (!SegurarOk.ehOk(e.keyCode) || painelFicha.visibility == View.VISIBLE || !grade.hasFocus()) {
            if (SegurarOk.ehOk(e.keyCode)) segurar.soltar()
            return super.dispatchKeyEvent(e)
        }
        return segurar.tratar(e,
            aoCurto = { naGrade.getOrNull(grade.selectedItemPosition)?.let { abrirFicha(it) } },
            aoSegurar = { naGrade.getOrNull(grade.selectedItemPosition)?.let { alternarInteresse(it) } })
    }

    private fun alternarInteresse(f: Filme) {
        val antes = interesses
        interesses = Vod.alternarInteresse(interesses, f.chave)
        if (interesses === antes) { avisoBreve(getString(R.string.filmes_interesses_cheio)); return }
        val virou = f.chave in interesses
        val copia = interesses
        Thread { PlayerArmazem.definirInteresses(this, series = false, copia) }.start()   // disco fora da thread da tela
        montarItens()
        if (chave == INTERESSES) refazerGrade() else adaptadorGrade.notifyDataSetChanged()
        avisoBreve(getString(if (virou) R.string.filmes_interesse_pos else R.string.filmes_interesse_tirou, Vod.nomeLimpo(f.nome)))
    }

    private val esconderAviso = Runnable { aviso.visibility = View.GONE }

    /** O mesmo balão do "Carregando", por 2,5 s. */
    private fun avisoBreve(t: String) {
        aviso.text = t; aviso.visibility = View.VISIBLE
        tela.removeCallbacks(esconderAviso); tela.postDelayed(esconderAviso, 2_500)
    }

    private fun nomeOrdem(o: Vod.Ordem) = getString(when (o) {
        Vod.Ordem.RECENTES -> R.string.filmes_ordem_recentes
        Vod.Ordem.NOME -> R.string.filmes_ordem_nome
        Vod.Ordem.NOTA -> R.string.filmes_ordem_nota
        Vod.Ordem.ANO -> R.string.filmes_ordem_ano
    })

    private fun escolherOrdem() {
        val todas = Vod.Ordem.values()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.filmes_ordem_titulo)
            .setSingleChoiceItems(todas.map { nomeOrdem(it) }.toTypedArray(), todas.indexOf(ordem)) { d, i ->
                d.dismiss()
                ordem = todas[i]
                getSharedPreferences("player", MODE_PRIVATE).edit().putString("ordem_filmes", ordem.name).apply()
                montarItens(); preencherGrade()
            }
            .show()
    }

    private fun buscar() {
        val campo = EditText(this).apply {
            setText(termo); setSelection(termo.length); hint = getString(R.string.filmes_busca_dica)
            inputType = InputType.TYPE_CLASS_TEXT; imeOptions = EditorInfo.IME_ACTION_SEARCH; isSingleLine = true
        }
        val aplicar = { t: String ->
            if (t.isNotEmpty()) {
                termo = t; resultados = Vod.buscar(filmes, t); chave = BUSCA
                montarItens(); preencherGrade()
                if (resultados.isNotEmpty()) grade.requestFocus()
            }
        }
        val d = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.player_acao_buscar)
            .setView(FrameLayout(this).apply { setPadding(dp(20), dp(10), dp(20), 0); addView(campo) })
            .setPositiveButton(R.string.player_acao_buscar) { _, _ -> aplicar(campo.text.toString().trim()) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        campo.setOnEditorActionListener { _, acao, _ ->
            if (acao == EditorInfo.IME_ACTION_SEARCH) { d.dismiss(); aplicar(campo.text.toString().trim()); true } else false
        }
        d.show()
    }

    private val adaptadorCats = object : BaseAdapter() {
        override fun getCount() = itens.size
        override fun getItem(p: Int) = itens[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, reuso: View?, pai: ViewGroup?): View {
            val linha = (reuso as? LinearLayout) ?: LinearLayout(this@FilmesActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(9), dp(12), dp(9))
                addView(TextView(context).apply { textSize = 14.5f; typeface = fonteMedia; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(TextView(context).apply { textSize = 12f; setTextColor(apagado) })
            }
            val it = itens[p]
            (linha.getChildAt(0) as TextView).apply {
                text = when (it.chave) { BUSCAR, BUSCA -> "⌕  "; ORDEM -> "⇅  "; CONTINUAR -> "▶  "; INTERESSES -> "★  "; ASSISTIDOS -> "✓  "; else -> "" } + it.nome
                setTextColor(if (it.chave == chave) ambar else papel)
            }
            (linha.getChildAt(1) as TextView).text = if (it.quantos < 0) "" else it.quantos.toString()
            return linha
        }
    }

    // ---------------------------------------------------------------- grade de pôsteres

    private class Card(val capa: ImageView, val nome: TextView, val barra: ProgressBar, val etiqueta: TextView, val estrela: TextView)

    private val adaptadorGrade = object : BaseAdapter() {
        override fun getCount() = naGrade.size
        override fun getItem(p: Int) = naGrade[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, reuso: View?, pai: ViewGroup?): View {
            val v = reuso ?: novoCard()
            val c = v.tag as Card
            val f = naGrade[p]
            c.nome.text = Vod.nomeLimpo(f.nome)
            val pos = posicoes[f.chave]
            if (Vod.deveRetomar(pos) && pos!!.duracao > 0) {
                c.barra.visibility = View.VISIBLE; c.barra.progress = (pos.ms * 1000 / pos.duracao).toInt()
            } else c.barra.visibility = View.GONE
            // Etiqueta do quanto foi visto: "42%", "Finalizado" (95% ou mais) ou "Assistido" (servidor sem duração).
            val pct = Vod.progresso(pos)
            if (pct == null) c.etiqueta.visibility = View.GONE else {
                c.etiqueta.visibility = View.VISIBLE
                c.etiqueta.text = when (pct) { 100 -> getString(R.string.filmes_finalizado); 0 -> getString(R.string.filmes_assistido); else -> "$pct%" }
                (c.etiqueta.background as GradientDrawable).setColor(if (pct == 100) verdeEtiqueta else 0xD90B0F17.toInt())
            }
            c.estrela.visibility = if (f.chave in interesses) View.VISIBLE else View.GONE
            PlayerLogos.carregar(this@FilmesActivity, f.capa, c.capa, lado = 360)
            return v
        }
    }

    private fun novoCard(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(6)) }
        val capa = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(0x14FFFFFF) }
            clipToOutline = true
        }
        // Largura da coluna / altura 2:3 (pôster de cinema). A largura real vem do GridView; aqui uma altura fixa basta.
        // A capa vai num FrameLayout para a etiqueta (embaixo, à esquerda) e a estrela (em cima, à direita) ficarem por cima.
        val moldura = FrameLayout(this)
        moldura.addView(capa, FrameLayout.LayoutParams(-1, -1))
        val etiqueta = TextView(this).apply {
            textSize = 11f; typeface = fonteMedia; setTextColor(papel); setPadding(dp(7), dp(2), dp(7), dp(2))
            background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(0xD90B0F17.toInt()) }
        }
        moldura.addView(etiqueta, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(5), 0, 0, dp(5)) })
        val estrela = TextView(this).apply {
            text = "★"; textSize = 15f; setTextColor(ambar); setPadding(dp(6), dp(1), dp(6), dp(2))
            background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(0xD90B0F17.toInt()) }
        }
        moldura.addView(estrela, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(5), dp(5), 0) })
        col.addView(moldura, LinearLayout.LayoutParams(-1, dp(150)))
        val barra = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progressTintList = ColorStateList.valueOf(coral); progressBackgroundTintList = ColorStateList.valueOf(0x38FFFFFF)
        }
        col.addView(barra, LinearLayout.LayoutParams(-1, dp(3)).apply { topMargin = dp(4) })
        val nome = TituloCard(this, 12f).apply { setTextColor(papel); maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(4), 0, 0) }
        col.addView(nome)
        col.layoutParams = AbsListView.LayoutParams(-1, dp(212))
        col.tag = Card(capa, nome, barra, etiqueta, estrela)
        return col
    }

    private fun mostrarFocado(f: Filme?) {
        if (f == null && chave == INTERESSES) { focado.text = getString(R.string.filmes_interesses_vazio); return }
        focado.text = f?.let { listOfNotNull(it.nome, it.ano?.toString(), it.nota?.let { n -> "★ %.1f".format(Locale.getDefault(), n) }, PlayerLista.nomeExibido(it.grupo).ifEmpty { null }).joinToString("   ·   ") }.orEmpty()
    }

    // ---------------------------------------------------------------- ficha do filme

    private fun abrirFicha(f: Filme, manterFoco: Boolean = false) {
        ficha = f
        desenharFicha(f, null, manterFoco)
        Thread {
            val info = PlayerCarga.fichaFilme(this, f)
            tela.post { if (!isDestroyed && ficha === f) desenharFicha(f, info, manterFoco = true) }
        }.start()
    }

    private fun fecharFicha() {
        ficha = null; painelFicha.visibility = View.GONE; painelFicha.removeAllViews()
        if (naGrade.isNotEmpty()) grade.requestFocus() else listaCats.requestFocus()
    }

    private fun desenharFicha(f: Filme, info: FichaFilme?, manterFoco: Boolean) {
        val focoAntes = if (manterFoco) (painelFicha.findFocus()?.tag as? String) else null
        painelFicha.removeAllViews()
        val linha = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(48), dp(40), dp(48), dp(32)) }
        val capa = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0x14FFFFFF) }
            clipToOutline = true
        }
        PlayerLogos.carregar(this, f.capa, capa, lado = 600)
        linha.addView(capa, LinearLayout.LayoutParams(dp(220), dp(330)))
        val texto = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(32), 0, 0, 0) }
        texto.addView(TextView(this).apply { text = Vod.nomeLimpo(f.nome); textSize = 28f; typeface = fonteMedia; setTextColor(papel); maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        val dados = listOfNotNull(
            (f.ano?.toString() ?: info?.lancamento?.take(4)?.takeIf { it.isNotEmpty() }),
            info?.duracaoMin?.let { if (it >= 60) "${it / 60} h ${it % 60} min" else "$it min" },
            f.nota?.let { "★ %.1f".format(Locale.getDefault(), it) },
            info?.genero?.ifEmpty { null } ?: PlayerLista.nomeExibido(f.grupo).ifEmpty { null },
        ).joinToString("   ·   ")
        texto.addView(TextView(this).apply { text = dados; textSize = 14f; setTextColor(suave); setPadding(0, dp(8), 0, 0) })

        // Botões: Continuar (se começou) + Do começo, ou só Assistir.
        val pos = posicoes[f.chave]
        val botoes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(18), 0, dp(14)) }
        val primeiro: TextView
        if (Vod.deveRetomar(pos)) {
            primeiro = botao(getString(R.string.filmes_continuar_de, relogio(pos!!.ms)), "continuar") { tocar(f, doComeco = false) }
            botoes.addView(primeiro)
            botoes.addView(botao(getString(R.string.filmes_do_comeco), "comeco") { tocar(f, doComeco = true) }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        } else {
            primeiro = botao(getString(R.string.filmes_assistir), "assistir") { tocar(f, doComeco = true) }
            botoes.addView(primeiro)
        }
        texto.addView(botoes)

        val corpo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (info == null) corpo.addView(TextView(this).apply { text = getString(R.string.filmes_ficha_carregando); textSize = 13f; setTextColor(apagado) })
        info?.sinopse?.takeIf { it.isNotEmpty() }?.let { corpo.addView(TextView(this).apply { text = it; textSize = 15f; setTextColor(papel); setLineSpacing(0f, 1.2f) }) }
        info?.diretor?.takeIf { it.isNotEmpty() }?.let { corpo.addView(rotuloValor(getString(R.string.filmes_direcao), it)) }
        info?.elenco?.takeIf { it.isNotEmpty() }?.let { corpo.addView(rotuloValor(getString(R.string.filmes_elenco), it)) }
        // A sinopse longa rola com ▼ (o ScrollView não pega foco; quem rola é a tecla, ver onKeyDown).
        val rolagem = ScrollView(this).apply { addView(corpo); isFocusable = false; tag = "rolagem" }
        texto.addView(rolagem, LinearLayout.LayoutParams(-1, 0, 1f))
        linha.addView(texto, LinearLayout.LayoutParams(0, -1, 1f))
        painelFicha.addView(linha, FrameLayout.LayoutParams(-1, -1))
        painelFicha.visibility = View.VISIBLE
        (focoAntes?.let { t -> botoes.findViewWithTag<View>(t) } ?: primeiro).requestFocus()
    }

    private fun tocar(f: Filme, doComeco: Boolean) =
        VodActivity.abrir(this, VodActivity.TIPO_FILME, f.fonteId, f.id, f.extensao, Vod.nomeLimpo(f.nome), doComeco)

    private fun botao(texto: String, marca: String, aoApertar: () -> Unit) = TextView(this).apply {
        text = texto; textSize = 16f; typeface = fonteMedia; tag = marca
        setPadding(dp(22), dp(11), dp(22), dp(11))
        isFocusable = true; isClickable = true
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(papel) })
            addState(intArrayOf(), GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(0x29F5F3EE) })
        }
        setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(0xFF0B0F17.toInt(), papel)))
        setOnClickListener { aoApertar() }
    }

    private fun rotuloValor(r: String, v: String) = TextView(this).apply {
        text = "$r: $v"; textSize = 13f; setTextColor(suave); setPadding(0, dp(10), 0, 0)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (painelFicha.visibility == View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> { fecharFicha(); return true }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP -> {
                    painelFicha.findViewWithTag<ScrollView>("rolagem")?.smoothScrollBy(0, if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) dp(80) else -dp(80))
                    return true
                }
            }
            return super.onKeyDown(keyCode, event)
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && listaCats.hasFocus().not() && itens.isNotEmpty()) { listaCats.requestFocus(); return true }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------------------------------------------------------- peças

    private fun estiloLista(l: AbsListView) {
        l.isVerticalScrollBarEnabled = false
        PlayerFoco.molduraSoComFoco(l, GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0x29F5F3EE); setStroke(dp(2), papel) })
        l.setDrawSelectorOnTop(l is GridView)   // no pôster a moldura vai por cima (a capa cobre o fundo)
        l.cacheColorHint = Color.TRANSPARENT
        if (l is ListView) l.divider = null
    }

    private fun coluna(titulo: TextView, lista: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; addView(titulo); addView(lista, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun rotulo(t: String) = TextView(this).apply {
        text = t; textSize = 11.5f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.06f; setTextColor(0x99F5F3EE.toInt())
        setPadding(dp(10), dp(4), dp(10), dp(8)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }

    private fun relogio(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    private fun vidro(raio: Int) = GradientDrawable().apply { cornerRadius = dp(raio).toFloat(); setColor(0xD90B0F17.toInt()); setStroke(dp(1), 0x1FFFFFFF) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}
