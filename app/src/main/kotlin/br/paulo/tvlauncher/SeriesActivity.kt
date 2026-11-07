package br.paulo.tvlauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
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
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import java.util.Locale

/**
 * Player universal, séries do Xtream (06/11/2026): catálogo de séries (mesma arrumação dos filmes: categorias à esquerda,
 * pôsteres à direita) e a ficha da série com temporadas e episódios.
 *
 * Na ficha: o botão principal oferece "Continuar T2E5 de 12:34" (ou o episódio seguinte, se o último terminou, ou
 * "Assistir T1E1"); embaixo, as temporadas e a lista de episódios com a barra do que já foi visto. Quando um episódio
 * vai até o fim, o próximo começa sozinho (VodActivity devolve "terminou").
 */
class SeriesActivity : Activity() {
    private companion object {
        const val BUSCAR = "*buscar"
        const val ORDEM = "*ordem"
        const val CONTINUAR = "*continuar"
        const val INTERESSES = "*interesses"
        const val TODOS = "*todos"
        const val BUSCA = "*busca"
        const val CODIGO_VIDEO = 7
    }

    private data class Item(val chave: String, val nome: String, val quantos: Int)

    private val tela = Handler(Looper.getMainLooper())
    private val papel = 0xFFF5F3EE.toInt()
    private val suave = 0xB3F5F3EE.toInt()
    private val apagado = 0x80F5F3EE.toInt()
    private val ambar = 0xFFF5B544.toInt()
    private val coral = 0xFFFF5A4E.toInt()
    private val fonteMedia = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private var series: List<Serie> = emptyList()
    private var ultimos: Map<String, UltimoEpisodio> = emptyMap()
    private var posicoes: Map<String, Posicao> = emptyMap()
    private var interesses: Set<String> = emptySet()
    private val segurar = SegurarOk()
    private var ordem = Vod.Ordem.RECENTES
    private var itens: List<Item> = emptyList()
    private var chave = TODOS
    private var termo = ""
    private var resultados: List<Serie> = emptyList()
    private var naGrade: List<Serie> = emptyList()

    // Ficha aberta
    private var aberta: Serie? = null
    private var fichaAberta: FichaSerie? = null
    private var temporada = 1
    private var tocando: Episodio? = null   // o episódio que foi para a VodActivity (para o "próximo")

    private lateinit var listaCats: ListView
    private lateinit var grade: GridView
    private lateinit var cabecalho: TextView
    private lateinit var focado: TextView
    private lateinit var aviso: TextView
    private lateinit var painel: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ordem = runCatching { Vod.Ordem.valueOf(getSharedPreferences("player", MODE_PRIVATE).getString("ordem_series", "")!!) }.getOrDefault(Vod.Ordem.RECENTES)
        val raiz = FrameLayout(this).apply { setBackgroundColor(0xFF0B0F17.toInt()) }
        val colunas = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(32), dp(26), dp(32), dp(18)) }
        listaCats = ListView(this).apply { estilo(this) }
        colunas.addView(coluna(rotulo(getString(R.string.series_titulo)), listaCats), LinearLayout.LayoutParams(dp(210), -1))
        val direita = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cabecalho = rotulo("")
        direita.addView(cabecalho)
        grade = GridView(this).apply {
            numColumns = 6; horizontalSpacing = dp(12); verticalSpacing = dp(14); stretchMode = GridView.STRETCH_COLUMN_WIDTH
            estilo(this); setPadding(dp(4), dp(4), dp(4), dp(4)); clipToPadding = false
        }
        direita.addView(grade, LinearLayout.LayoutParams(-1, 0, 1f))
        focado = TextView(this).apply { textSize = 13f; setTextColor(suave); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(8), dp(8), 0, 0) }
        direita.addView(focado)
        colunas.addView(direita, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(16) })
        raiz.addView(colunas, FrameLayout.LayoutParams(-1, -1))
        // Focável: enquanto a ficha carrega, o foco sai da grade de baixo (senão ▲▼ mexeriam nela por trás).
        painel = FrameLayout(this).apply { visibility = View.GONE; setBackgroundColor(0xFF0B0F17.toInt()); isFocusable = true }
        raiz.addView(painel, FrameLayout.LayoutParams(-1, -1))
        aviso = TextView(this).apply {
            textSize = 18f; setTextColor(papel); gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0xD90B0F17.toInt()) }
            setPadding(dp(24), dp(16), dp(24), dp(16)); text = getString(R.string.series_carregando)
        }
        raiz.addView(aviso, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setContentView(raiz)

        listaCats.adapter = adaptadorCats
        grade.adapter = adaptadorGrade
        listaCats.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                tela.removeCallbacks(trocarCat); if (listaCats.hasFocus()) tela.postDelayed(trocarCat, 180)
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
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                mostrarFocado(naGrade.getOrNull(pos))
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        grade.setOnItemClickListener { _, _, pos, _ -> naGrade.getOrNull(pos)?.let { abrirSerie(it) } }
        carregar()
    }

    override fun onDestroy() { tela.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun carregar() {
        Thread {
            val s = PlayerCarga.series(this)
            val u = PlayerArmazem.ultimosEpisodios(this)
            val p = PlayerArmazem.posicoes(this)
            val i = PlayerArmazem.interesses(this, series = true)
            tela.post {
                if (isDestroyed) return@post
                series = s; ultimos = u; posicoes = p; interesses = i
                if (s.isEmpty()) { aviso.text = getString(R.string.series_vazio); return@post }
                aviso.visibility = View.GONE
                chave = if (Series.continuar(s, u).isNotEmpty()) CONTINUAR else TODOS
                montarItens(); preencherGrade(); grade.requestFocus()
            }
        }.start()
    }

    // ---------------------------------------------------------------- catálogo

    private val trocarCat = Runnable { escolherCat(listaCats.selectedItemPosition) }

    private fun montarItens() {
        val l = ArrayList<Item>()
        l += Item(BUSCAR, getString(R.string.player_acao_buscar), -1)
        l += Item(ORDEM, getString(R.string.filmes_ordem, nomeOrdem(ordem)), -1)
        val cont = Series.continuar(series, ultimos).size
        if (cont > 0) l += Item(CONTINUAR, getString(R.string.filmes_continuar), cont)
        l += Item(INTERESSES, getString(R.string.filmes_interesses), Vod.interesses(series, interesses) { it.chave }.size)
        if (termo.isNotEmpty()) l += Item(BUSCA, getString(R.string.player_cat_busca, termo), resultados.size)
        l += Item(TODOS, getString(R.string.player_cat_todos), series.size)
        Series.grupos(series).forEach { (g, n) -> l += Item("g:$g", PlayerLista.nomeExibido(g).ifEmpty { getString(R.string.player_cat_outros) }, n) }
        itens = l
        if (itens.none { it.chave == chave }) chave = TODOS
        adaptadorCats.notifyDataSetChanged()
        if (!listaCats.hasFocus()) listaCats.setSelection(itens.indexOfFirst { it.chave == chave }.coerceAtLeast(0))
    }

    private fun escolherCat(pos: Int) {
        val it = itens.getOrNull(pos) ?: return
        if (it.chave == chave || it.chave == BUSCAR || it.chave == ORDEM) return
        chave = it.chave; adaptadorCats.notifyDataSetChanged(); preencherGrade()
    }

    private fun preencherGrade() {
        naGrade = when {
            chave == CONTINUAR -> Series.continuar(series, ultimos)
            chave == INTERESSES -> Vod.interesses(series, interesses) { it.chave }   // a última marcada primeiro
            chave == BUSCA -> resultados
            chave.startsWith("g:") -> Series.ordenar(series.filter { it.grupo.trim() == chave.removePrefix("g:") }, ordem)
            else -> Series.ordenar(series, ordem)
        }
        adaptadorGrade.notifyDataSetChanged()
        grade.setSelection(0)
        // Mesmo jeito do FilmesActivity: a linha do rodapé passa a mostrar o 1º item da categoria nova
        // (o setSelection(0) não dispara onItemSelected quando a posição já era 0, e o texto ficava velho).
        mostrarFocado(naGrade.firstOrNull())
        val nome = itens.firstOrNull { it.chave == chave }?.nome.orEmpty()
        cabecalho.text = getString(R.string.series_cabecalho, nome.uppercase(Locale.getDefault()), naGrade.size)
    }

    private fun mostrarFocado(s: Serie?) {
        if (s == null && chave == INTERESSES) { focado.text = getString(R.string.filmes_interesses_vazio); return }
        focado.text = s?.let {
            listOfNotNull(it.nome, it.ano?.toString(), it.nota?.let { n -> "★ %.1f".format(Locale.getDefault(), n) }, PlayerLista.nomeExibido(it.grupo).ifEmpty { null }).joinToString("   ·   ")
        }.orEmpty()
    }

    private fun nomeOrdem(o: Vod.Ordem) = getString(when (o) {
        Vod.Ordem.RECENTES -> R.string.series_ordem_recentes
        Vod.Ordem.NOME -> R.string.filmes_ordem_nome
        Vod.Ordem.NOTA -> R.string.filmes_ordem_nota
        Vod.Ordem.ANO -> R.string.filmes_ordem_ano
    })

    private fun escolherOrdem() {
        val todas = Vod.Ordem.values()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.filmes_ordem_titulo)
            .setSingleChoiceItems(todas.map { nomeOrdem(it) }.toTypedArray(), todas.indexOf(ordem)) { d, i ->
                d.dismiss(); ordem = todas[i]
                getSharedPreferences("player", MODE_PRIVATE).edit().putString("ordem_series", ordem.name).apply()
                montarItens(); preencherGrade()
            }.show()
    }

    private fun buscar() {
        val campo = EditText(this).apply {
            setText(termo); setSelection(termo.length); hint = getString(R.string.series_busca_dica)
            inputType = InputType.TYPE_CLASS_TEXT; imeOptions = EditorInfo.IME_ACTION_SEARCH; isSingleLine = true
        }
        val aplicar = { t: String ->
            if (t.isNotEmpty()) {
                termo = t; resultados = Series.buscar(series, t); chave = BUSCA
                montarItens(); preencherGrade(); if (resultados.isNotEmpty()) grade.requestFocus()
            }
        }
        val d = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.player_acao_buscar)
            .setView(FrameLayout(this).apply { setPadding(dp(20), dp(10), dp(20), 0); addView(campo) })
            .setPositiveButton(R.string.player_acao_buscar) { _, _ -> aplicar(campo.text.toString().trim()) }
            .setNegativeButton(android.R.string.cancel, null).create()
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
            val linha = (reuso as? LinearLayout) ?: LinearLayout(this@SeriesActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(9), dp(12), dp(9))
                addView(TextView(context).apply { textSize = 14.5f; typeface = fonteMedia; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(TextView(context).apply { textSize = 12f; setTextColor(apagado) })
            }
            val it = itens[p]
            (linha.getChildAt(0) as TextView).apply {
                text = when (it.chave) { BUSCAR, BUSCA -> "⌕  "; ORDEM -> "⇅  "; CONTINUAR -> "▶  "; INTERESSES -> "★  "; else -> "" } + it.nome
                setTextColor(if (it.chave == chave) ambar else papel)
            }
            (linha.getChildAt(1) as TextView).text = if (it.quantos < 0) "" else it.quantos.toString()
            return linha
        }
    }

    private class Card(val capa: ImageView, val nome: TextView, val sub: TextView, val estrela: TextView)

    private val adaptadorGrade = object : BaseAdapter() {
        override fun getCount() = naGrade.size
        override fun getItem(p: Int) = naGrade[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, reuso: View?, pai: ViewGroup?): View {
            val v = reuso ?: novoCard()
            val c = v.tag as Card
            val s = naGrade[p]
            c.nome.text = Vod.nomeLimpo(s.nome)
            c.sub.text = ultimos[s.chave]?.let { "T${it.temporada}E${it.numero}" }.orEmpty()
            c.sub.visibility = if (c.sub.text.isEmpty()) View.GONE else View.VISIBLE
            c.estrela.visibility = if (s.chave in interesses) View.VISIBLE else View.GONE
            PlayerLogos.carregar(this@SeriesActivity, s.capa, c.capa, lado = 360)
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
        // Capa num FrameLayout para a estrela dos interesses ficar por cima (canto de cima, à direita).
        val moldura = FrameLayout(this)
        moldura.addView(capa, FrameLayout.LayoutParams(-1, -1))
        val estrela = TextView(this).apply {
            text = "★"; textSize = 15f; setTextColor(ambar); setPadding(dp(6), dp(1), dp(6), dp(2))
            background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(0xD90B0F17.toInt()) }
        }
        moldura.addView(estrela, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(5), dp(5), 0) })
        col.addView(moldura, LinearLayout.LayoutParams(-1, dp(150)))
        val nome = TituloCard(this, 12f).apply { setTextColor(papel); maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(6), 0, 0) }
        col.addView(nome)
        val sub = TextView(this).apply { textSize = 11f; setTextColor(ambar) }
        col.addView(sub)
        col.layoutParams = AbsListView.LayoutParams(-1, dp(212))
        col.tag = Card(capa, nome, sub, estrela)
        return col
    }

    // ---------------------------------------------------------------- ficha da série

    private fun abrirSerie(s: Serie) {
        aberta = s; fichaAberta = null
        desenharFicha()
        Thread {
            val f = PlayerCarga.fichaSerie(this, s)
            tela.post {
                if (isDestroyed || aberta !== s) return@post
                fichaAberta = f
                temporada = ultimos[s.chave]?.temporada?.takeIf { f?.temporadas?.containsKey(it) == true } ?: f?.temporadas?.keys?.firstOrNull() ?: 1
                desenharFicha()
            }
        }.start()
    }

    private fun fecharFicha() {
        aberta = null; fichaAberta = null; painel.visibility = View.GONE; painel.removeAllViews()
        if (naGrade.isNotEmpty()) grade.requestFocus() else listaCats.requestFocus()
    }

    // ---------------------------------------------------------------- interesses (OK segurado 2 s, igual aos filmes)

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (!SegurarOk.ehOk(e.keyCode) || painel.visibility == View.VISIBLE || !grade.hasFocus()) {
            if (SegurarOk.ehOk(e.keyCode)) segurar.soltar()
            return super.dispatchKeyEvent(e)
        }
        return segurar.tratar(e,
            aoCurto = { naGrade.getOrNull(grade.selectedItemPosition)?.let { abrirSerie(it) } },
            aoSegurar = { naGrade.getOrNull(grade.selectedItemPosition)?.let { alternarInteresse(it) } })
    }

    private fun alternarInteresse(s: Serie) {
        val antes = interesses
        interesses = Vod.alternarInteresse(interesses, s.chave)
        if (interesses === antes) { avisoBreve(getString(R.string.filmes_interesses_cheio)); return }
        val virou = s.chave in interesses
        val copia = interesses
        Thread { PlayerArmazem.definirInteresses(this, series = true, copia) }.start()
        montarItens()
        if (chave == INTERESSES) {
            val onde = grade.selectedItemPosition
            preencherGrade()
            if (naGrade.isNotEmpty()) { val p = onde.coerceIn(0, naGrade.size - 1); grade.setSelection(p); mostrarFocado(naGrade[p]) }
            else listaCats.requestFocus()
        } else adaptadorGrade.notifyDataSetChanged()
        avisoBreve(getString(if (virou) R.string.filmes_interesse_pos else R.string.filmes_interesse_tirou, Vod.nomeLimpo(s.nome)))
    }

    private val esconderAviso = Runnable { aviso.visibility = View.GONE }

    private fun avisoBreve(t: String) {
        aviso.text = t; aviso.visibility = View.VISIBLE
        tela.removeCallbacks(esconderAviso); tela.postDelayed(esconderAviso, 2_500)
    }

    private fun desenharFicha(focarEpisodio: Int? = null) {
        val s = aberta ?: return
        val f = fichaAberta
        painel.removeAllViews()
        val linha = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(48), dp(34), dp(48), dp(24)) }
        val capa = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0x14FFFFFF) }; clipToOutline = true
        }
        PlayerLogos.carregar(this, s.capa, capa, lado = 600)
        linha.addView(capa, LinearLayout.LayoutParams(dp(180), dp(270)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(28), 0, 0, 0) }
        col.addView(TextView(this).apply { text = Vod.nomeLimpo(s.nome); textSize = 26f; typeface = fonteMedia; setTextColor(papel); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        val dados = listOfNotNull(s.ano?.toString(), f?.temporadas?.size?.let { resources.getQuantityString(R.plurals.series_temporadas, it, it) },
            s.nota?.let { "★ %.1f".format(Locale.getDefault(), it) }, f?.genero?.ifEmpty { null } ?: PlayerLista.nomeExibido(s.grupo).ifEmpty { null }).joinToString("   ·   ")
        col.addView(TextView(this).apply { text = dados; textSize = 13.5f; setTextColor(suave); setPadding(0, dp(6), 0, 0) })
        f?.sinopse?.takeIf { it.isNotEmpty() }?.let {
            col.addView(TextView(this).apply { text = it; textSize = 13.5f; setTextColor(papel); maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(8), 0, 0) })
        }

        if (f == null) {
            col.addView(TextView(this).apply { text = getString(R.string.filmes_ficha_carregando); textSize = 13f; setTextColor(apagado); setPadding(0, dp(14), 0, 0) })
        } else if (f.temporadas.isEmpty()) {
            col.addView(TextView(this).apply { text = getString(R.string.series_sem_episodios); textSize = 14f; setTextColor(suave); setPadding(0, dp(14), 0, 0) })
        } else {
            // Botão principal.
            val sugestao = Series.sugestao(f, s.fonteId, ultimos[s.chave], posicoes)
            val principal = sugestao?.let { (ep, retomar) ->
                val txt = if (retomar) getString(R.string.series_continuar, ep.rotulo, relogio(posicoes[ep.chave(s.fonteId)]?.ms ?: 0))
                    else getString(R.string.series_assistir, ep.rotulo)
                botao(txt) { tocar(s, ep, doComeco = !retomar) }
            }
            principal?.let { col.addView(it, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) }) }

            // Temporadas.
            val temps = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            f.temporadas.keys.forEach { t ->
                temps.addView(botao(getString(R.string.series_temporada, t), pequeno = true, marcado = t == temporada) {
                    temporada = t; desenharFicha(focarEpisodio = -1)
                }.apply { tag = "t$t" }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            }
            col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(temps) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

            // Episódios da temporada.
            val eps = f.temporadas[temporada].orEmpty()
            val lista = ListView(this).apply { estilo(this) }
            lista.adapter = object : BaseAdapter() {
                override fun getCount() = eps.size
                override fun getItem(p: Int) = eps[p]
                override fun getItemId(p: Int) = p.toLong()
                override fun getView(p: Int, reuso: View?, pai: ViewGroup?): View = linhaEpisodio(s, eps[p], reuso)
            }
            lista.setOnItemClickListener { _, _, p, _ -> eps.getOrNull(p)?.let { ep ->
                tocar(s, ep, doComeco = !Vod.deveRetomar(posicoes[ep.chave(s.fonteId)]))
            } }
            col.addView(lista, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })

            painel.post {
                when {
                    focarEpisodio == -1 -> temps.findViewWithTag<View>("t$temporada")?.requestFocus()
                    focarEpisodio != null -> { lista.requestFocus(); lista.setSelection(focarEpisodio) }
                    else -> principal?.requestFocus() ?: lista.requestFocus()
                }
            }
        }
        linha.addView(col, LinearLayout.LayoutParams(0, -1, 1f))
        painel.addView(linha, FrameLayout.LayoutParams(-1, -1))
        painel.visibility = View.VISIBLE
        if (f == null) painel.requestFocus()
    }

    private fun linhaEpisodio(s: Serie, ep: Episodio, reuso: View?): View {
        val v = (reuso as? LinearLayout) ?: LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8))
            val topo = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            topo.addView(TextView(context).apply { textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(ambar) }, LinearLayout.LayoutParams(dp(40), -2))
            topo.addView(TextView(context).apply { textSize = 14f; typeface = fonteMedia; setTextColor(papel); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
            topo.addView(TextView(context).apply { textSize = 12f; setTextColor(apagado) })
            addView(topo)
            addView(TextView(context).apply { textSize = 12f; setTextColor(suave); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(40), dp(2), 0, 0) })
            addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000; progressTintList = ColorStateList.valueOf(coral); progressBackgroundTintList = ColorStateList.valueOf(0x38FFFFFF)
            }, LinearLayout.LayoutParams(-1, dp(3)).apply { topMargin = dp(5); marginStart = dp(40) })
        }
        val topo = v.getChildAt(0) as LinearLayout
        (topo.getChildAt(0) as TextView).text = ep.numero.toString()
        (topo.getChildAt(1) as TextView).text = ep.titulo
        (topo.getChildAt(2) as TextView).text = ep.duracaoMin?.let { "$it min" }.orEmpty()
        (v.getChildAt(1) as TextView).apply { text = ep.sinopse; visibility = if (ep.sinopse.isEmpty()) View.GONE else View.VISIBLE }
        val barra = v.getChildAt(2) as ProgressBar
        val pos = posicoes[ep.chave(s.fonteId)]
        if (pos != null && pos.duracao > 0) { barra.visibility = View.VISIBLE; barra.progress = (pos.ms * 1000 / pos.duracao).toInt() }
        else barra.visibility = View.INVISIBLE
        return v
    }

    private fun tocar(s: Serie, ep: Episodio, doComeco: Boolean) {
        tocando = ep
        val agora = System.currentTimeMillis()
        ultimos = Series.registrarUltimo(ultimos, s.chave, UltimoEpisodio(ep.id, ep.temporada, ep.numero, ep.titulo, ep.extensao, agora))
        val copia = ultimos
        Thread { PlayerArmazem.definirUltimosEpisodios(this, copia) }.start()
        VodActivity.abrirComResultado(this, CODIGO_VIDEO, VodActivity.TIPO_EPISODIO, s.fonteId, ep.id, ep.extensao,
            "${Vod.nomeLimpo(s.nome)} · ${ep.rotulo} · ${ep.titulo}", doComeco)
    }

    // API antiga (deprecated), mas é a que existe numa Activity comum, sem AndroidX.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CODIGO_VIDEO) return
        val s = aberta; val f = fichaAberta; val ep = tocando
        // Posições mudaram: relê e redesenha a ficha (barras e botão principal).
        Thread {
            val p = PlayerArmazem.posicoes(this)
            tela.post {
                if (isDestroyed) return@post
                posicoes = p
                val terminou = resultCode == RESULT_OK && data?.getBooleanExtra(VodActivity.RESULTADO_TERMINOU, false) == true
                val prox = if (terminou && s != null && f != null && ep != null) Series.proximo(f, ep) else null
                if (prox != null && s != null) { temporada = prox.temporada; desenharFicha(); tocar(s, prox, doComeco = true) }
                else { montarItens(); adaptadorGrade.notifyDataSetChanged(); if (aberta != null) desenharFicha() }
            }
        }.start()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (painel.visibility == View.VISIBLE && keyCode == KeyEvent.KEYCODE_BACK) { fecharFicha(); return true }
        if (painel.visibility != View.VISIBLE && keyCode == KeyEvent.KEYCODE_BACK && !listaCats.hasFocus() && itens.isNotEmpty()) { listaCats.requestFocus(); return true }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------------------------------------------------------- peças

    private fun botao(texto: String, pequeno: Boolean = false, marcado: Boolean = false, aoApertar: () -> Unit) = TextView(this).apply {
        text = texto; textSize = if (pequeno) 13.5f else 16f; typeface = fonteMedia
        setPadding(dp(if (pequeno) 16 else 22), dp(if (pequeno) 7 else 11), dp(if (pequeno) 16 else 22), dp(if (pequeno) 7 else 11))
        isFocusable = true; isClickable = true
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(papel) })
            addState(intArrayOf(), GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(if (marcado) 0x4DF5B544 else 0x29F5F3EE) })
        }
        setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(0xFF0B0F17.toInt(), papel)))
        setOnClickListener { aoApertar() }
    }

    private fun estilo(l: AbsListView) {
        l.isVerticalScrollBarEnabled = false
        PlayerFoco.molduraSoComFoco(l, GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0x29F5F3EE); setStroke(dp(2), papel) })
        l.setDrawSelectorOnTop(l is GridView)
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

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}
