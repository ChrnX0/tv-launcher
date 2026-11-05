package br.paulo.tvlauncher

import android.content.Context
import android.graphics.Typeface

/**
 * Os visuais da tela inicial. Escolhe-se no menu da bolinha do perfil ("Aparência"); fica salvo.
 *  - ESCURO: grafite azulado com brilhos bem suaves (código) + o céu de agora por cima — sol, lua e nuvens
 *    caminhando na tela, a partir da posição real do sol e do clima (era o antigo tema "Céu ao vivo"; hoje é
 *    parte fixa do Escuro, não existe mais como opção separada).
 *  - GALERIA: uma pintura de domínio público por dia (acervo aberto do The Met), com etiqueta de museu.
 * As fontes (em assets/fonts, licença SIL Open Font) dão a personalidade de cada um.
 */
enum class Tema(private val rotulo: Int) {
    ESCURO(R.string.tema_escuro), GALERIA(R.string.tema_galeria);

    /** Nome do tema no idioma da TV. */
    val nome: String get() = txt(rotulo)

    companion object {
        private const val CHAVE = "tema"

        fun atual(ctx: Context): Tema = try {
            valueOf(ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).getString(CHAVE, ESCURO.name) ?: ESCURO.name)
        } catch (e: IllegalArgumentException) { ESCURO }

        /** Passa para o próximo tema da lista (dá a volta). */
        fun proximo(ctx: Context): Tema {
            val novo = values()[(atual(ctx).ordinal + 1) % values().size]
            ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().putString(CHAVE, novo.name).apply()
            return novo
        }
    }
}

/** As fontes de cada tema, carregadas uma vez. "display" = hora e temperatura; "ui" = o resto. */
object Fontes {
    private val cache = HashMap<String, Typeface>()

    private fun carregar(ctx: Context, arquivo: String, variacao: String? = null): Typeface =
        cache.getOrPut("$arquivo|$variacao") {
            try {
                Typeface.Builder(ctx.assets, "fonts/$arquivo").apply { if (variacao != null) setFontVariationSettings(variacao) }.build()
            } catch (e: Exception) {
                Typeface.DEFAULT   // arquivo faltando: a tela continua funcionando com a fonte do sistema
            }
        }

    fun display(ctx: Context, tema: Tema): Typeface = when (tema) {
        Tema.GALERIA -> carregar(ctx, "cormorant.ttf", "'wght' 500")
        Tema.ESCURO -> Typeface.create("sans-serif-light", Typeface.NORMAL)
    }

    fun ui(ctx: Context, tema: Tema): Typeface =
        if (tema == Tema.ESCURO) Typeface.DEFAULT else carregar(ctx, "figtree.ttf", "'wght' 400")

    fun uiMedia(ctx: Context, tema: Tema): Typeface =
        if (tema == Tema.ESCURO) Typeface.create("sans-serif-medium", Typeface.NORMAL) else carregar(ctx, "figtree.ttf", "'wght' 560")

    fun mono(ctx: Context): Typeface = carregar(ctx, "plex_mono.ttf")
}
