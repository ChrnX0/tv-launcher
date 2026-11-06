package br.paulo.tvlauncher

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Player universal, lista completa (06/11/2026): a tela de canais que abre com OK por cima do vídeo.
 *
 *   [ categorias ]  [ canais da categoria         ]  [ detalhe do canal em foco ]
 *     Todos 312       12  (logo) Canal  programa ▬     logo, programa de agora, horário,
 *     Favoritos 4     13  (logo) Canal  programa ▬     barra, descrição e o que vem depois
 *     Recentes 9      ...
 *     Esportes 42
 *
 * As duas listas são ListView (do próprio Android): só cria as linhas que cabem na tela, então 20 mil canais custam o
 * mesmo que 20. O ◀ ▶ entre as colunas é a navegação de foco padrão do Android (a Activity devolve false para essas teclas).
 *
 * Teclas: ▲▼ andam; ◀ ▶ trocam de coluna; OK num canal assiste; OK segurado (ou MENU) põe/tira dos favoritos;
 * OK segurado numa categoria esconde/mostra a categoria; VOLTAR fecha.
 * No topo da coluna de categorias ficam duas ações: Buscar e Filtros (as janelas são da PlayerActivity).
 */
class PlayerListaTela(
    private val act: Activity,
    private val aoAssistir: (Canal, String) -> Unit,      // canal escolhido + categoria em que estava
    private val aoFavoritar: (Canal) -> Unit,
    private val aoBuscar: () -> Unit,
    private val aoFiltros: () -> Unit,
    private val aoOcultarGrupo: (String) -> Unit,   // nome do grupo (vazio = "Outros")
    private val aoGuia: (List<Canal>, String) -> Unit,   // guia em grade dos canais da categoria + nome dela
    private val aoFilmes: () -> Unit,
    private val aoSeries: () -> Unit,
) {
    private val tela = Handler(Looper.getMainLooper())
    private val papel = 0xFFF5F3EE.toInt()
    private val suave = 0xB3F5F3EE.toInt()
    private val apagado = 0x80F5F3EE.toInt()
    private val ambar = 0xFFF5B544.toInt()
    private val coral = 0xFFFF5A4E.toInt()
    private val fonteMedia = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val hora = SimpleDateFormat("HH:mm", Locale.getDefault())

    private var canais: List<Canal> = emptyList()
    private var guia = Guia(emptyList())
    private var favoritos: Set<String> = emptySet()
    private var recentes: List<String> = emptyList()
    private var grupos: List<GrupoCanais> = emptyList()
    private var chave = PlayerLista.TODOS              // categoria cujos canais estão na coluna do meio
    private var doMeio: List<Canal> = emptyList()
    private var atualId: String? = null
    private var busca: Pair<String, List<Canal>>? = null   // termo + resultados (a categoria "Busca")
    private var resumoFiltro = ""                           // "4K · BR" na linha de Filtros
    private var ocultas: Set<String> = emptySet()           // grupos ocultos que estão aparecendo (PIN): ganham 🔒
    var temVod = false                                      // há conta Xtream: mostra a ação Filmes

    val raiz: FrameLayout = FrameLayout(act).apply {
        visibility = View.GONE
        // Escuro à esquerda, mais claro à direita: o vídeo continua aparecendo atrás do detalhe.
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF50B0F17.toInt(), 0xE60B0F17.toInt(), 0xB30B0F17.toInt()))
    }
    private val listaGrupos = lista()
    private val listaCanais = lista()
    private val tituloMeio = rotulo()
    private lateinit var detLogo: ImageView
    private lateinit var detCanal: TextView
    private lateinit var detTitulo: TextView
    private lateinit var detHorario: TextView
    private lateinit var detBarra: ProgressBar
    private lateinit var detDescricao: TextView
    private lateinit var detSeguir: LinearLayout

    val aberta: Boolean get() = raiz.visibility == View.VISIBLE

    init {
        val colunas = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(32), dp(26), dp(32), dp(22)) }
        colunas.addView(coluna(rotulo().apply { text = act.getString(R.string.player_categorias) }, listaGrupos), LinearLayout.LayoutParams(dp(210), -1))
        colunas.addView(coluna(tituloMeio, listaCanais), LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(14); marginEnd = dp(18) })
        colunas.addView(montarDetalhe(), LinearLayout.LayoutParams(dp(290), -1))
        raiz.addView(colunas, FrameLayout.LayoutParams(-1, -1))

    }

    private val trocarGrupo = Runnable { escolherGrupo(listaGrupos.selectedItemPosition) }

    // ---------------------------------------------------------------- uso pela PlayerActivity

    /**
     * @param canais já filtrados (PlayerFiltro.aplicar): o que não passa no filtro nem aparece
     * @param ocultas grupos ocultos que estão visíveis agora (depois do PIN), para marcar com 🔒
     */
    fun abrir(canais: List<Canal>, guia: Guia, favoritos: Set<String>, recentes: List<String>, chave: String, atual: Canal?,
              resumoFiltro: String = "", ocultas: Set<String> = emptySet(), focarCategorias: Boolean = false) {
        this.canais = canais; this.guia = guia; this.favoritos = favoritos; this.recentes = recentes
        this.resumoFiltro = resumoFiltro; this.ocultas = ocultas
        atualId = atual?.id
        busca = busca?.let { (t, r) -> val ids = canais.mapTo(HashSet()) { it.id }; t to r.filter { it.id in ids } }
        montarGrupos()
        this.chave = PlayerLista.chaveValida(grupos, chave)
        adaptadorGrupos.notifyDataSetChanged()
        listaGrupos.setSelection(grupos.indexOfFirst { it.chave == this.chave }.coerceAtLeast(0))
        preencherMeio(manterNoAtual = true)
        raiz.visibility = View.VISIBLE
        if (focarCategorias || doMeio.isEmpty()) focarGrupoAtual() else listaCanais.requestFocus()
    }

    /** Resultado de uma busca: vira a categoria "Busca", já escolhida, com o foco nos canais achados. */
    fun mostrarBusca(termo: String, resultados: List<Canal>) {
        busca = termo to resultados
        montarGrupos()
        chave = PlayerLista.BUSCA
        adaptadorGrupos.notifyDataSetChanged()
        listaGrupos.setSelection(grupos.indexOfFirst { it.chave == chave }.coerceAtLeast(0))
        preencherMeio(manterNoAtual = false)
        if (doMeio.isEmpty()) focarGrupoAtual() else listaCanais.requestFocus()
    }

    /** O guia em grade fechou e a lista ficou por baixo: o foco volta para ela. */
    fun voltarFoco() { listaGrupos.requestFocus() }

    private fun focarGrupoAtual() {
        listaGrupos.requestFocus()
        listaGrupos.setSelection(grupos.indexOfFirst { it.chave == chave }.coerceAtLeast(0))
    }

    private fun montarGrupos() {
        grupos = listOf(GrupoCanais(PlayerLista.ACAO_BUSCAR, "", -1), GrupoCanais(PlayerLista.ACAO_FILTROS, resumoFiltro, -1),
            GrupoCanais(PlayerLista.ACAO_GUIA, "", -1)) +
            (if (temVod) listOf(GrupoCanais(PlayerLista.ACAO_FILMES, "", -1), GrupoCanais(PlayerLista.ACAO_SERIES, "", -1)) else emptyList()) +
            PlayerLista.grupos(canais, favoritos, recentes, busca?.let { it.first to it.second.size })
    }

    fun fechar() {
        tela.removeCallbacksAndMessages(null)
        raiz.visibility = View.GONE
    }

    /** O guia terminou de carregar com a lista aberta. */
    fun atualizarGuia(g: Guia) {
        guia = g
        if (!aberta) return
        adaptadorCanais.notifyDataSetChanged()
        mostrarDetalhe(doMeio.getOrNull(listaCanais.selectedItemPosition))
    }

    /** Favorito mudou (OK segurado): redesenha a estrela e a contagem sem tirar o foco do lugar. */
    fun atualizarFavoritos(f: Set<String>) {
        favoritos = f
        montarGrupos()
        adaptadorGrupos.notifyDataSetChanged()
        if (chave == PlayerLista.FAVORITOS) {
            val pos = listaCanais.selectedItemPosition
            doMeio = PlayerLista.filtrar(canais, chave, favoritos, recentes)
            adaptadorCanais.notifyDataSetChanged()
            atualizarTituloMeio()
            if (doMeio.isEmpty()) { listaGrupos.requestFocus(); mostrarDetalhe(null) }
            else listaCanais.setSelection(pos.coerceAtMost(doMeio.size - 1))
        } else adaptadorCanais.notifyDataSetChanged()
    }

    /**
     * Teclas que chegam à Activity com a lista aberta (as ▲▼ e OK simples a ListView já tratou). Tudo é tratado aqui,
     * inclusive ◀ ▶, para o foco nunca escapar para fora da lista. Devolve false para o que não é da lista
     * (volume segue para o Android; números, a Activity fecha a lista e digita o canal).
     */
    fun tecla(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_BACK -> {
            if (listaGrupos.hasFocus() && doMeio.isNotEmpty()) listaCanais.requestFocus() else fechar()
            true
        }
        KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BOOKMARK, KeyEvent.KEYCODE_PROG_YELLOW -> {
            if (listaCanais.hasFocus()) doMeio.getOrNull(listaCanais.selectedItemPosition)?.let { aoFavoritar(it) }
            true
        }
        KeyEvent.KEYCODE_DPAD_RIGHT -> {
            if (listaGrupos.hasFocus()) {
                // Desceu rápido nas categorias e já apertou ▶: aplica agora a categoria em que parou.
                tela.removeCallbacks(trocarGrupo); trocarGrupo.run()
                if (doMeio.isNotEmpty()) listaCanais.requestFocus()
            }
            true
        }
        KeyEvent.KEYCODE_DPAD_LEFT -> { listaGrupos.requestFocus(); true }
        // ▲▼ e OK repetidos (tecla segurada), CH+ CH- e INFO não podem trocar de canal por baixo da lista.
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
        KeyEvent.KEYCODE_LAST_CHANNEL, KeyEvent.KEYCODE_INFO -> true
        else -> false   // volume, mídia, números: seguem para a Activity / o Android
    }

    // ---------------------------------------------------------------- colunas

    private fun escolherGrupo(pos: Int) {
        val g = grupos.getOrNull(pos) ?: return
        if (g.chave == chave || g.chave.startsWith("*acao:")) return   // passar pelas ações não troca a coluna do meio
        chave = g.chave
        adaptadorGrupos.notifyDataSetChanged()
        preencherMeio(manterNoAtual = true)
    }

    private fun preencherMeio(manterNoAtual: Boolean) {
        doMeio = if (chave == PlayerLista.BUSCA) busca?.second.orEmpty() else PlayerLista.filtrar(canais, chave, favoritos, recentes)
        adaptadorCanais.notifyDataSetChanged()
        atualizarTituloMeio()
        val pos = if (manterNoAtual) PlayerLista.posicao(doMeio, atualId) else 0
        // Abre com o canal no meio da coluna, não colado no topo (dá para ver os vizinhos).
        listaCanais.setSelectionFromTop(pos, dp(150))
        mostrarDetalhe(doMeio.getOrNull(pos))
    }

    private fun atualizarTituloMeio() {
        val nome = grupos.firstOrNull { it.chave == chave }?.let { nomeDoGrupo(it) } ?: ""
        val filtro = resumoFiltro.takeIf { it.isNotEmpty() }?.let { "  ·  $it" } ?: ""
        tituloMeio.text = act.getString(R.string.player_canais_de, nome.uppercase(Locale.getDefault()), doMeio.size) + filtro
    }

    private fun nomeDoGrupo(g: GrupoCanais): String = when (g.chave) {
        PlayerLista.TODOS -> act.getString(R.string.player_cat_todos)
        PlayerLista.FAVORITOS -> act.getString(R.string.player_cat_favoritos)
        PlayerLista.RECENTES -> act.getString(R.string.player_cat_recentes)
        PlayerLista.BUSCA -> act.getString(R.string.player_cat_busca, g.nome)
        PlayerLista.ACAO_BUSCAR -> act.getString(R.string.player_acao_buscar)
        PlayerLista.ACAO_FILTROS -> act.getString(R.string.player_acao_filtros)
        PlayerLista.ACAO_GUIA -> act.getString(R.string.player_acao_guia)
        PlayerLista.ACAO_FILMES -> act.getString(R.string.player_acao_filmes)
        PlayerLista.ACAO_SERIES -> act.getString(R.string.player_acao_series)
        else -> g.nome.ifEmpty { act.getString(R.string.player_cat_outros) }
    }

    private val adaptadorGrupos = object : BaseAdapter() {
        override fun getCount() = grupos.size
        override fun getItem(p: Int) = grupos[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, reuso: View?, pai: ViewGroup?): View {
            val linha = (reuso as? LinearLayout) ?: LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(9), dp(12), dp(9))
                addView(TextView(act).apply { textSize = 14.5f; typeface = fonteMedia; maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                    LinearLayout.LayoutParams(0, -2, 1f))
                addView(TextView(act).apply { textSize = 12f; setTextColor(apagado) })
            }
            val g = grupos[p]
            val escolhida = g.chave == chave
            (linha.getChildAt(0) as TextView).apply {
                text = when (g.chave) {
                    PlayerLista.FAVORITOS -> "★  " + nomeDoGrupo(g)
                    PlayerLista.RECENTES -> "↺  " + nomeDoGrupo(g)
                    PlayerLista.BUSCA -> "⌕  " + nomeDoGrupo(g)
                    PlayerLista.ACAO_BUSCAR -> "⌕  " + nomeDoGrupo(g)
                    PlayerLista.ACAO_FILTROS -> "☰  " + nomeDoGrupo(g)
                    PlayerLista.ACAO_GUIA -> "▦  " + nomeDoGrupo(g)
                    PlayerLista.ACAO_FILMES -> "🎬  " + nomeDoGrupo(g)
                    PlayerLista.ACAO_SERIES -> "📺  " + nomeDoGrupo(g)
                    else -> (if (g.nome in ocultas) "🔒 " else "") + nomeDoGrupo(g)
                }
                setTextColor(if (escolhida) ambar else if (g.chave == PlayerLista.ACAO_FILTROS && g.nome.isNotEmpty()) ambar else papel)
            }
            // Ações: Filtros mostra o filtro em vigor no lugar da contagem; Buscar não tem número.
            (linha.getChildAt(1) as TextView).text = when {
                g.chave == PlayerLista.ACAO_FILTROS -> g.nome
                g.quantos < 0 -> ""
                else -> g.quantos.toString()
            }
            return linha
        }
    }

    /** Peças de uma linha de canal, guardadas na tag para não procurar a cada desenho. */
    private class Linha(val numero: TextView, val logo: ImageView, val nome: TextView, val sub: TextView, val barra: ProgressBar, val estrela: TextView)

    private val adaptadorCanais = object : BaseAdapter() {
        override fun getCount() = doMeio.size
        override fun getItem(p: Int) = doMeio[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, reuso: View?, pai: ViewGroup?): View {
            val v = reuso ?: novaLinhaCanal()
            val l = v.tag as Linha
            val c = doMeio[p]
            val agora = System.currentTimeMillis()
            val prog = c.guiaId.takeIf { it.isNotEmpty() }?.let { guia.agora(it, agora) }
            l.numero.text = c.numero.toString()
            l.numero.setTextColor(if (c.id == atualId) ambar else papel)
            l.nome.text = c.nome
            l.sub.text = when {
                c.id == atualId -> act.getString(R.string.player_assistindo) + (prog?.let { " · ${it.titulo}" } ?: "")
                prog != null -> prog.titulo
                else -> c.grupo
            }
            l.barra.visibility = if (prog != null) View.VISIBLE else View.INVISIBLE
            prog?.let { l.barra.progress = (guia.progresso(it, agora) * 1000).toInt() }
            l.estrela.visibility = if (c.id in favoritos) View.VISIBLE else View.GONE
            PlayerLogos.carregar(act, c.logo, l.logo)
            return v
        }
    }

    private fun novaLinhaCanal(): View {
        val linha = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(7), dp(12), dp(7))
            layoutParams = AbsListView.LayoutParams(-1, dp(58))
        }
        val numero = TextView(act).apply { textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER }
        linha.addView(numero, LinearLayout.LayoutParams(dp(44), -2))
        val logo = ImageView(act).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(0x14FFFFFF) }
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        linha.addView(logo, LinearLayout.LayoutParams(dp(58), dp(40)).apply { marginEnd = dp(12) })
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val nome = TextView(act).apply { textSize = 14.5f; typeface = fonteMedia; setTextColor(papel); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val sub = TextView(act).apply { textSize = 12f; setTextColor(suave); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val barra = barra()
        col.addView(nome); col.addView(sub)
        col.addView(barra, LinearLayout.LayoutParams(-1, dp(3)).apply { topMargin = dp(4) })
        linha.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        val estrela = TextView(act).apply { text = "★"; textSize = 16f; setTextColor(ambar); setPadding(dp(10), 0, 0, 0) }
        linha.addView(estrela)
        linha.tag = Linha(numero, logo, nome, sub, barra, estrela)
        return linha
    }

    // ---------------------------------------------------------------- detalhe (direita)

    private fun montarDetalhe(): View {
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0xCC0B0F17.toInt()); setStroke(dp(1), 0x1FFFFFFF) }
            setPadding(dp(18), dp(18), dp(18), dp(16))
        }
        detLogo = ImageView(act).apply { scaleType = ImageView.ScaleType.FIT_START }
        col.addView(detLogo, LinearLayout.LayoutParams(dp(120), dp(64)))
        detCanal = TextView(act).apply { textSize = 13f; setTextColor(suave); maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        col.addView(detCanal, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        detTitulo = TextView(act).apply { textSize = 19f; typeface = fonteMedia; setTextColor(papel); maxLines = 3; ellipsize = TextUtils.TruncateAt.END }
        col.addView(detTitulo, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        detHorario = TextView(act).apply { textSize = 12.5f; setTextColor(suave) }
        col.addView(detHorario, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        detBarra = barra()
        col.addView(detBarra, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(8) })
        detDescricao = TextView(act).apply { textSize = 12.5f; setTextColor(suave); maxLines = 7; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.15f) }
        col.addView(detDescricao, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        detSeguir = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(detSeguir, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })
        col.addView(TextView(act).apply { text = act.getString(R.string.player_lista_teclas); textSize = 11f; setTextColor(apagado) })
        return col
    }

    private fun mostrarDetalhe(c: Canal?) {
        detSeguir.removeAllViews()
        if (c == null) {
            detLogo.setImageDrawable(null); detCanal.text = ""; detHorario.text = ""; detDescricao.text = ""
            detBarra.visibility = View.INVISIBLE
            detTitulo.text = act.getString(if (chave == PlayerLista.FAVORITOS) R.string.player_sem_favoritos else R.string.player_categoria_vazia)
            return
        }
        PlayerLogos.carregar(act, c.logo, detLogo)
        detCanal.text = listOf(c.numero.toString(), c.nome, c.grupo).filter { it.isNotEmpty() }.joinToString("  ·  ")
        val agora = System.currentTimeMillis()
        val prox = c.guiaId.takeIf { it.isNotEmpty() }?.let { guia.proximos(it, agora, 4) }.orEmpty()
        val atual = prox.firstOrNull()?.takeIf { agora >= it.inicio }
        if (atual == null) {
            detTitulo.text = c.nome
            detHorario.text = if (prox.isEmpty()) act.getString(R.string.player_sem_guia) else ""
            detBarra.visibility = View.INVISIBLE
            detDescricao.text = ""
        } else {
            detTitulo.text = atual.titulo
            val faltam = ((atual.fim - agora) / 60_000L).coerceAtLeast(0)
            detHorario.text = act.getString(R.string.player_horario, hora.format(Date(atual.inicio)), hora.format(Date(atual.fim)), faltam) +
                (atual.categoria.takeIf { it.isNotEmpty() }?.let { "  ·  $it" } ?: "")
            detBarra.visibility = View.VISIBLE
            detBarra.progress = (guia.progresso(atual, agora) * 1000).toInt()
            detDescricao.text = atual.descricao
        }
        val depois = prox.filter { it !== atual }.take(3)
        if (depois.isNotEmpty()) {
            detSeguir.addView(rotulo().apply { text = act.getString(R.string.player_depois) })
            depois.forEach { p ->
                detSeguir.addView(TextView(act).apply {
                    text = "${hora.format(Date(p.inicio))}   ${p.titulo}"; textSize = 12.5f; setTextColor(papel)
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, dp(3))
                })
            }
        }
    }

    // ---------------------------------------------------------------- peças

    private fun lista() = ListView(act).apply {
        divider = null
        isVerticalScrollBarEnabled = false
        itemsCanFocus = false
        isFocusable = true
        choiceMode = AbsListView.CHOICE_MODE_NONE
        // A moldura branca do foco (só aparece na lista que tem o foco: é o que diz ao usuário em que coluna está).
        PlayerFoco.molduraSoComFoco(this, GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0x29F5F3EE); setStroke(dp(2), papel) })
        setDrawSelectorOnTop(false)
        cacheColorHint = 0
    }

    private fun coluna(titulo: TextView, lista: ListView) = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        addView(titulo)
        addView(lista, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun rotulo() = TextView(act).apply {
        textSize = 11.5f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.06f; setTextColor(0x99F5F3EE.toInt())
        setPadding(dp(10), dp(4), dp(10), dp(8)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }

    private fun barra() = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progressTintList = ColorStateList.valueOf(coral)
        progressBackgroundTintList = ColorStateList.valueOf(0x38FFFFFF)
    }

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density + 0.5f).toInt()

    // Segundo init, no FIM da classe de propósito: o Kotlin inicia as propriedades na ordem em que aparecem, e os
    // adaptadores acima só existem a partir daqui (no 1º init seriam null).
    init {
        listaGrupos.adapter = adaptadorGrupos
        listaCanais.adapter = adaptadorCanais
        listaGrupos.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                // Trocar a coluna do meio a cada passo travaria ao descer rápido: espera o dedo parar 180 ms.
                tela.removeCallbacks(trocarGrupo)
                if (listaGrupos.hasFocus()) tela.postDelayed(trocarGrupo, 180)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        listaGrupos.setOnItemClickListener { _, _, pos, _ ->
            when (grupos.getOrNull(pos)?.chave) {
                PlayerLista.ACAO_BUSCAR -> aoBuscar()
                PlayerLista.ACAO_FILTROS -> aoFiltros()
                PlayerLista.ACAO_FILMES -> aoFilmes()
                PlayerLista.ACAO_SERIES -> aoSeries()
                PlayerLista.ACAO_GUIA -> grupos.firstOrNull { it.chave == chave }?.let { aoGuia(doMeio, nomeDoGrupo(it)) }
                else -> { escolherGrupo(pos); if (doMeio.isNotEmpty()) listaCanais.requestFocus() }
            }
        }
        listaGrupos.isLongClickable = true
        listaGrupos.setOnItemLongClickListener { _, _, pos, _ ->
            grupos.getOrNull(pos)?.takeIf { it.chave.startsWith("g:") }?.let { aoOcultarGrupo(it.nome) }
            true
        }
        listaCanais.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { mostrarDetalhe(doMeio.getOrNull(pos)) }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        listaCanais.setOnItemClickListener { _, _, pos, _ -> doMeio.getOrNull(pos)?.let { aoAssistir(it, chave) } }
        listaCanais.isLongClickable = true
        listaCanais.setOnItemLongClickListener { _, _, pos, _ -> doMeio.getOrNull(pos)?.let { aoFavoritar(it) }; true }
    }
}
