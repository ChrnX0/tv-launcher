package br.paulo.tvlauncher

import android.content.Context
import android.graphics.Typeface

/**
 * Os visuais da tela inicial. Escolhe-se no menu da bolinha do perfil ("Aparência"); fica salvo.
 *  - CEU: o céu de agora, desenhado a partir da posição real do sol, do clima e da fase da lua.
 *  - GALERIA: uma pintura de domínio público por dia (acervo aberto do The Met), com etiqueta de museu.
 *  - ASTROLABIO: um mostrador de 24 h ao fundo, com o sol e a lua nas posições de agora.
 *  - ESCURO: o fundo liso de antes.
 * As fontes (em assets/fonts, licença SIL Open Font) dão a personalidade de cada um.
 */
enum class Tema(val nome: String) {
    CEU("Céu ao vivo"), GALERIA("Galeria"), ASTROLABIO("Astrolábio"), ESCURO("Escuro");

    companion object {
        private const val CHAVE = "tema"

        fun atual(ctx: Context): Tema = try {
            valueOf(ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).getString(CHAVE, CEU.name) ?: CEU.name)
        } catch (e: IllegalArgumentException) { CEU }

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
        Tema.CEU -> carregar(ctx, "instrument_serif.ttf")
        Tema.GALERIA -> carregar(ctx, "cormorant.ttf", "'wght' 500")
        Tema.ASTROLABIO -> carregar(ctx, "fraunces.ttf", "'wght' 300, 'opsz' 144, 'SOFT' 50")
        Tema.ESCURO -> Typeface.create("sans-serif-light", Typeface.NORMAL)
    }

    fun ui(ctx: Context, tema: Tema): Typeface =
        if (tema == Tema.ESCURO) Typeface.DEFAULT else carregar(ctx, "figtree.ttf", "'wght' 400")

    fun uiMedia(ctx: Context, tema: Tema): Typeface =
        if (tema == Tema.ESCURO) Typeface.create("sans-serif-medium", Typeface.NORMAL) else carregar(ctx, "figtree.ttf", "'wght' 560")

    fun mono(ctx: Context): Typeface = carregar(ctx, "plex_mono.ttf")
}
