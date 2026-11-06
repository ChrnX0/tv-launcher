package br.paulo.tvlauncher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View

/**
 * "Fundo do foco" da HOME recomendada (esboço V3Final, 06/11/2026): a "capa" de quem está em foco aparece no alto, à
 * direita, atrás do topo, e se dissolve no fundo do tema pela esquerda e por baixo (máscara de transparência, não uma
 * caixa escura: o céu/pintura do tema continua aparecendo em volta).
 *
 * - App: o banner de TV dele (a arte 16:9 que todo app de TV tem para a tela do Google); sem banner, a cor do ícone.
 * - TV ao vivo: degradê coral, a cor do "ao vivo".
 * Fica numa camada de hardware: desenha uma vez por troca de foco, não a cada quadro do fundo vivo.
 */
class CapaFoco(ctx: Context) : View(ctx) {
    private var imagem: Bitmap? = null
    private var cores: IntArray? = null
    private val pintaImagem = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pintaMascara = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val pintaEscuro = Paint().apply { color = 0x99000000.toInt() }   // véu: o clima e a faixa de 9 dias passam por cima (06/11: 0x59 deixava os dias ilegíveis)
    private val origem = Rect()
    private val destino = RectF()

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        alpha = 0f
    }

    /** Mostra uma imagem (banner), ou um degradê de [cores] se não houver imagem. null nos dois esconde. */
    fun mostrar(imagem: Bitmap?, cores: IntArray?) {
        if (imagem === this.imagem && cores.contentEquals(this.cores) && alpha > 0f) return
        this.imagem = imagem
        this.cores = cores
        invalidate()
        animate().cancel()
        if (imagem == null && cores == null) { animate().alpha(0f).setDuration(200).start(); return }
        alpha = 0.3f
        animate().alpha(OPACIDADE).setDuration(260).start()
    }

    fun esconder() = mostrar(null, null)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val img = imagem
        val cs = cores
        when {
            img != null -> {
                // centerCrop: preenche a área toda, cortando o que sobra
                val escala = maxOf(w / img.width, h / img.height)
                val vw = (w / escala).toInt(); val vh = (h / escala).toInt()
                val x0 = (img.width - vw) / 2; val y0 = (img.height - vh) / 2
                origem.set(x0, y0, x0 + vw, y0 + vh)
                destino.set(0f, 0f, w, h)
                c.drawBitmap(img, origem, destino, pintaImagem)
                c.drawRect(0f, 0f, w, h, pintaEscuro)
            }
            cs != null -> {
                pintaImagem.shader = LinearGradient(0f, 0f, w, h, cs, null, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w, h, pintaImagem)
                pintaImagem.shader = null
            }
            else -> return
        }
        // Máscara: transparente na borda esquerda (até ~55% da largura) e embaixo (some antes da faixa de 9 dias).
        pintaMascara.shader = LinearGradient(0f, 0f, w * 0.55f, 0f, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, pintaMascara)
        pintaMascara.shader = LinearGradient(0f, h * 0.3f, 0f, h * 0.85f, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, pintaMascara)
        pintaMascara.shader = null
    }

    companion object {
        const val OPACIDADE = 0.8f
        val CORES_TV = intArrayOf(0xFFFF7A6E.toInt(), 0xFFC0392B.toInt())

        /** O banner de TV do app (o da atividade leanback, senão o do app). null se o app não tiver. */
        fun banner(pm: PackageManager, pacote: String): Drawable? = try {
            pm.getLeanbackLaunchIntentForPackage(pacote)?.let { pm.getActivityBanner(it) }
                ?: pm.getApplicationBanner(pacote)
        } catch (e: Exception) { null }

        /** Desenha um Drawable num Bitmap do tamanho dele (banner: 320x180 px, ou o que o app trouxer). */
        fun bitmap(d: Drawable, larguraPadrao: Int = 320, alturaPadrao: Int = 180): Bitmap {
            if (d is BitmapDrawable && d.bitmap != null) return d.bitmap
            val w = d.intrinsicWidth.takeIf { it > 0 } ?: larguraPadrao
            val h = d.intrinsicHeight.takeIf { it > 0 } ?: alturaPadrao
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            d.setBounds(0, 0, w, h)
            d.draw(c)
            return b
        }

        /** Cor média do ícone (reduzido a 1 pixel), escurecida, para o degradê de quem não tem banner. */
        fun corDoIcone(icone: Drawable): IntArray {
            val p = Bitmap.createScaledBitmap(bitmap(icone, 48, 48), 1, 1, true).getPixel(0, 0)
            val base = Color.rgb(Color.red(p), Color.green(p), Color.blue(p))
            val escura = Color.rgb(Color.red(p) / 3, Color.green(p) / 3, Color.blue(p) / 3)
            return intArrayOf(base, escura)
        }

        /** Intent para abrir um app pela HOME (o mesmo caminho do card). */
        fun abrirIntent(pm: PackageManager, pacote: String): Intent? =
            pm.getLeanbackLaunchIntentForPackage(pacote) ?: pm.getLaunchIntentForPackage(pacote)
    }
}
