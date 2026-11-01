package br.paulo.tvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** O que os fundos vivos precisam saber do mundo. [codigo] = código WMO do clima (-1 = ainda não chegou). */
data class EstadoCeu(val agoraMs: Long, val lat: Double, val lon: Double, val codigo: Int, val chovendo: Boolean)

/** Um fundo que se redesenha quando o tempo, o sol ou o clima mudam (a tela chama a cada minuto e a cada clima novo). */
interface FundoVivo {
    fun atualizar(e: EstadoCeu)
}

private fun Context.dpf(v: Float) = v * resources.displayMetrics.density

/**
 * Veu escuro no topo, atras da hora, do clima e das 3 linhas de informacao (que ocupam ate ~30% da altura).
 * Forte onde fica o texto (~70% de escuro no alto, ~55% no meio do bloco) e some em 48% da altura.
 * [rgb] = a cor do veu sem transparencia. E o que garante o contraste do texto em qualquer cor de fundo.
 */
private fun veuDoTopo(c: Canvas, w: Float, h: Float, rgb: Int) {
    val p = Paint()
    p.shader = LinearGradient(0f, 0f, 0f, h * 0.48f,
        intArrayOf((0xB3 shl 24) or rgb, (0x8C shl 24) or rgb, rgb),
        floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    c.drawRect(0f, 0f, w, h * 0.48f, p)
}

/** Desenha a lua com a fase de [agoraMs]: fração iluminada e lado (no hemisfério sul a lua crescente acende à esquerda). */
internal fun desenharLua(c: Canvas, x: Float, y: Float, r: Float, agoraMs: Long, lat: Double, brilho: Paint) {
    val idade = Lua.idade(agoraMs)
    val fracao = idade / 29.530588853
    val iluminada = ((1 - cos(2 * PI * fracao)) / 2).toFloat()
    val crescente = fracao < 0.5
    // Lado aceso: no norte, crescente acende à direita; no sul, espelha.
    val direita = if (lat >= 0) crescente else !crescente
    brilho.shader = RadialGradient(x, y, r * 3.4f, 0x33DCE6FF, 0x00DCE6FF, Shader.TileMode.CLAMP)
    c.drawCircle(x, y, r * 3.4f, brilho)
    brilho.shader = null
    brilho.color = 0xE6283046.toInt()
    c.drawCircle(x, y, r, brilho)
    // Metade acesa + elipse: com menos da metade iluminada a elipse "come" a metade; com mais, soma.
    val metade = Path().apply { addArc(x - r, y - r, x + r, y + r, if (direita) -90f else 90f, 180f); close() }
    val k = abs(1 - 2 * iluminada) * r
    val elipse = Path().apply { addOval(x - k, y - r, x + k, y + r, Path.Direction.CW) }
    metade.op(elipse, if (iluminada < 0.5f) Path.Op.DIFFERENCE else Path.Op.UNION)
    brilho.color = 0xFFECE6D6.toInt()
    c.drawPath(metade, brilho)
}

/** Hora decimal (0-24) do instante [ms] no fuso local. */
internal fun horaDecimal(ms: Long): Double {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    return c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60.0
}

// ======================================================================== Céu ao vivo

/**
 * O céu de agora: a cor vem da altura real do sol (noite, crepúsculo, dourado, dia), as nuvens e a chuva do clima,
 * as estrelas aparecem quando o sol passa de 6° abaixo do horizonte. Um arco pontilhado mostra o caminho do sol
 * de hoje, do nascer ao pôr, com o sol no ponto certo; fora dele, a lua na fase real (posição aproximada:
 * ela atrasa em relação ao sol ~50 min por dia de idade).
 */
class FundoCeu(ctx: Context) : View(ctx), FundoVivo {
    private var e: EstadoCeu? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val texto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fontes.ui(ctx, Tema.CEU); color = 0x8CF3EFE6.toInt(); textSize = ctx.dpf(10f); letterSpacing = 0.04f
    }

    override fun atualizar(e: EstadoCeu) { this.e = e; invalidate() }

    override fun onDraw(c: Canvas) {
        val est = e ?: EstadoCeu(System.currentTimeMillis(), Config.LATITUDE, Config.LONGITUDE, -1, false)
        val w = width.toFloat(); val h = height.toFloat()
        val alt = Astronomia.alturaSol(est.agoraMs, est.lat, est.lon)
        val cores = coresDoCeu(alt)
        p.shader = LinearGradient(0f, 0f, 0f, h, cores, floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p); p.shader = null

        if (alt < -6) estrelas(c, w, h, ((-6 - alt) / 10).coerceIn(0.0, 1.0).toFloat())
        nuvens(c, w, h, alt, est.codigo)
        if (est.chovendo || est.codigo in 51..67 || est.codigo in 80..99) chuva(c, w, h)
        if (est.codigo == 45 || est.codigo == 48) {   // neblina: um véu claro na parte de baixo
            p.shader = LinearGradient(0f, h * 0.3f, 0f, h, 0x00C8CDD2, 0x66C8CDD2, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p); p.shader = null
        }

        // Arco do sol de hoje.
        val hor = h * 0.60f; val x0 = w * 0.25f; val x1 = w * 0.75f; val topo = h * 0.36f   // na faixa livre entre o topo e os cards
        p.style = Paint.Style.STROKE; p.strokeWidth = context.dpf(0.75f); p.color = 0x2EF3EFE6
        c.drawLine(0f, hor, w, hor, p)
        val arco = Path()
        for (i in 0..100) {
            val t = i / 100f
            val x = x0 + (x1 - x0) * t; val y = hor - sin(PI * t).toFloat() * (hor - topo)
            if (i == 0) arco.moveTo(x, y) else arco.lineTo(x, y)
        }
        p.pathEffect = DashPathEffect(floatArrayOf(context.dpf(1f), context.dpf(5f)), 0f)
        p.strokeWidth = context.dpf(1f); p.color = 0x59F3EFE6
        c.drawPath(arco, p); p.pathEffect = null; p.style = Paint.Style.FILL

        val np = Astronomia.nascerEPor(est.agoraMs, est.lat, est.lon)
        if (np != null) {
            val fmt = SimpleDateFormat("HH:mm", Locale.US)
            c.drawText("nascer " + fmt.format(np.first), x0 - context.dpf(30f), hor + context.dpf(16f), texto)
            c.drawText("pôr " + fmt.format(np.second), x1 - context.dpf(20f), hor + context.dpf(16f), texto)
            val nasc = horaDecimal(np.first); val por = horaDecimal(np.second); val agora = horaDecimal(est.agoraMs)
            val ponto = { horaNoArco: Double ->
                val t = ((horaNoArco - nasc) / (por - nasc)).toFloat()
                if (t in 0f..1f) Pair(x0 + (x1 - x0) * t, hor - sin(PI * t).toFloat() * (hor - topo)) else null
            }
            ponto(agora)?.let { (x, y) ->
                val r = context.dpf(60f)
                p.shader = RadialGradient(x, y, r, intArrayOf(0xE6FFD68C.toInt(), 0x8CFFC878.toInt(), 0x00FFC878), floatArrayOf(0f, 0.18f, 1f), Shader.TileMode.CLAMP)
                c.drawCircle(x, y, r, p); p.shader = null
                p.color = 0xFFFFF3D6.toInt(); c.drawCircle(x, y, context.dpf(8f), p)
            }
            // A lua "atrasa" ~24 h por ciclo: a hora em que ela está no ponto equivalente ao sol.
            val atraso = Lua.idade(est.agoraMs) / 29.530588853 * 24
            val horaLua = ((agora - atraso) % 24 + 24) % 24
            ponto(horaLua)?.let { (x, y) -> desenharLua(c, x, y - context.dpf(6f), context.dpf(17f), est.agoraMs, est.lat, p) }
        }

        // Véu escuro embaixo: os cards e os nomes ficam legíveis em qualquer céu.
        p.shader = LinearGradient(0f, h * 0.48f, 0f, h, 0x00030510, 0xD1030510.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p); p.shader = null
        veuDoTopo(c, w, h, 0x030510)
    }

    /** Três cores (topo, meio, horizonte) conforme a altura do sol, misturando noite → crepúsculo → dia. */
    private fun coresDoCeu(alt: Double): IntArray {
        val noite = intArrayOf(0xFF060A18.toInt(), 0xFF0E162C.toInt(), 0xFF161E34.toInt())
        val crep = intArrayOf(0xFF181E46.toInt(), 0xFF784670.toInt(), 0xFFF08C5A.toInt())
        val dia = intArrayOf(0xFF346EBE.toInt(), 0xFF6EA5DC.toInt(), 0xFFBED7EB.toInt())
        fun mix(a: Int, b: Int, t: Float): Int {
            val u = t.coerceIn(0f, 1f)
            return Color.rgb(
                (Color.red(a) + (Color.red(b) - Color.red(a)) * u).toInt(),
                (Color.green(a) + (Color.green(b) - Color.green(a)) * u).toInt(),
                (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * u).toInt())
        }
        return when {
            alt < -12 -> noite
            alt < 0 -> IntArray(3) { mix(noite[it], crep[it], ((alt + 12) / 12).toFloat()) }
            alt < 12 -> IntArray(3) { mix(crep[it], dia[it], (alt / 12).toFloat()) }
            else -> dia
        }
    }

    private fun estrelas(c: Canvas, w: Float, h: Float, forca: Float) {
        var s = 7L
        fun r(): Float { s = (s * 16807) % 2147483647; return s / 2147483647f }
        repeat(420) {
            val x = r() * w; val y = r() * h * 0.7f
            val a = r() * 0.8f * (1 - y / (h * 0.75f)) * forca
            p.color = Color.argb((a * 255).toInt().coerceIn(0, 255), 255, 255, 255)
            c.drawCircle(x, y, (r() * 1.6f + 0.3f) * context.dpf(0.5f), p)
        }
    }

    /** Nuvens: manchas suaves achatadas; quantas, pelo código do clima. À noite, escuras. */
    private fun nuvens(c: Canvas, w: Float, h: Float, alt: Double, codigo: Int) {
        val n = when (codigo) { 0 -> 2; 1 -> 5; 2 -> 9; 3, 45, 48 -> 16; in 51..99 -> 18; else -> 4 }
        var s = 3L
        fun r(): Float { s = (s * 48271) % 2147483647; return s / 2147483647f }
        val noite = alt < -6
        val base = if (noite) 0x28344F else 0xFFFFFF
        val forte = if (codigo >= 3) 0.42f else 0.30f
        repeat(n) {
            val x = r() * w; val y = h * 0.12f + r() * h * 0.36f; val raio = context.dpf(100f + r() * 220f)
            c.save(); c.translate(x, y); c.scale(1f, 0.32f)
            val a = ((if (noite) 0.18f else forte) * 255).toInt()
            p.shader = RadialGradient(0f, 0f, raio, (a shl 24) or base, base, Shader.TileMode.CLAMP)
            c.drawCircle(0f, 0f, raio, p); p.shader = null
            c.restore()
        }
    }

    private fun chuva(c: Canvas, w: Float, h: Float) {
        var s = 11L
        fun r(): Float { s = (s * 69621) % 2147483647; return s / 2147483647f }
        p.strokeWidth = context.dpf(0.6f); p.color = 0x26FFFFFF
        repeat(260) {
            val x = r() * w; val y = r() * h; val l = context.dpf(14f + r() * 14f)
            c.drawLine(x, y, x - l * 0.25f, y + l, p)
        }
    }
}

// ======================================================================== Astrolábio

/**
 * Um mostrador de 24 h ao fundo, como um instrumento antigo: 0 h embaixo, 12 h em cima (o sol sobe pela esquerda).
 * O setor da noite fica escuro, o do dia levemente dourado; o ponteiro dourado é o sol agora, e a lua aparece na
 * posição aproximada da fase. Linhas finas e transparentes: é fundo, não compete com a hora.
 */
class FundoAstrolabio(ctx: Context) : View(ctx), FundoVivo {
    private var e: EstadoCeu? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mono = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fontes.mono(ctx); textAlign = Paint.Align.CENTER }
    private val ouro = 0xFFE8B55A.toInt()

    override fun atualizar(e: EstadoCeu) { this.e = e; invalidate() }

    override fun onDraw(c: Canvas) {
        val est = e ?: EstadoCeu(System.currentTimeMillis(), Config.LATITUDE, Config.LONGITUDE, -1, false)
        val w = width.toFloat(); val h = height.toFloat()
        p.shader = RadialGradient(w * 0.3f, h * 0.4f, w * 0.8f, 0xFF16202A.toInt(), 0xFF0A0E13.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p); p.shader = null

        val cx = w * 0.5f; val cy = h * 0.47f; val raio = h * 0.6f
        fun ang(hora: Double) = (hora / 24 * 2 * PI + PI / 2).toFloat()   // 0 h embaixo
        val np = Astronomia.nascerEPor(est.agoraMs, est.lat, est.lon)
        val nasc = np?.let { horaDecimal(it.first) } ?: 6.0
        val por = np?.let { horaDecimal(it.second) } ?: 18.0
        val graus = { a: Float -> Math.toDegrees(a.toDouble()).toFloat() }
        // setores: noite e dia
        p.color = 0x8C060A14.toInt()
        c.drawArc(cx - raio, cy - raio, cx + raio, cy + raio, graus(ang(por)), graus(ang(nasc + 24) - ang(por)), true, p)
        p.color = 0x12E8B55A
        c.drawArc(cx - raio, cy - raio, cx + raio, cy + raio, graus(ang(nasc)), graus(ang(por) - ang(nasc)), true, p)
        // aros
        p.style = Paint.Style.STROKE; p.strokeWidth = context.dpf(0.75f); p.color = 0x4DEFE6D2
        listOf(raio, raio * 0.85f, raio * 0.55f).forEach { c.drawCircle(cx, cy, it, p) }
        // marcas de 15 min, 1 h e 3 h
        for (m in 0 until 96) {
            val a = ang(m / 4.0)
            val l = context.dpf(if (m % 4 != 0) 4f else if (m % 12 != 0) 8f else 14f)
            p.color = if (m % 4 != 0) 0x33EFE6D2 else 0x80EFE6D2.toInt()
            val r0 = raio * 0.85f
            c.drawLine(cx + cos(a) * r0, cy + sin(a) * r0, cx + cos(a) * (r0 + l), cy + sin(a) * (r0 + l), p)
        }
        p.style = Paint.Style.FILL
        mono.textSize = context.dpf(10f); mono.color = 0x99EFE6D2.toInt()
        for (hh in 0 until 24 step 3) {
            val a = ang(hh.toDouble()); val r = raio * 0.93f
            c.drawText("%02d".format(hh), cx + cos(a) * r, cy + sin(a) * r + context.dpf(4f), mono)
        }
        // sol agora
        val agora = horaDecimal(est.agoraMs)
        val a = ang(agora); val rs = raio * 0.80f
        p.color = ouro; p.strokeWidth = context.dpf(1.25f); p.alpha = 200
        c.drawLine(cx, cy, cx + cos(a) * rs, cy + sin(a) * rs, p)
        p.alpha = 255
        c.drawCircle(cx + cos(a) * rs, cy + sin(a) * rs, context.dpf(7f), p)
        // lua: atrasa ~24 h por ciclo em relação ao sol
        val atraso = Lua.idade(est.agoraMs) / 29.530588853 * 24
        val al = ang(((agora - atraso) % 24 + 24) % 24)
        desenharLua(c, cx + cos(al) * raio * 0.70f, cy + sin(al) * raio * 0.70f, context.dpf(10f), est.agoraMs, est.lat, p)
        // legenda no aro interno
        mono.textSize = context.dpf(9f); mono.color = 0x80EFE6D2.toInt()
        val fmt = SimpleDateFormat("HH:mm", Locale.US)
        val legenda = if (np != null) "NASCER ${fmt.format(np.first)}  ·  PÔR ${fmt.format(np.second)}  ·  %.2f° S".format(abs(est.lat)) else ""
        c.drawText(legenda, cx, cy + raio * 0.55f - context.dpf(10f), mono)
        // véu embaixo para os cards
        p.shader = LinearGradient(0f, h * 0.55f, 0f, h, 0x000A0E13, 0xCC0A0E13.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p); p.shader = null
        veuDoTopo(c, w, h, 0x0A0E13)
    }
}

// ======================================================================== Galeria

/**
 * Uma pintura por dia, em tela cheia, do acervo aberto (domínio público, CC0) do The Met, com etiqueta de museu.
 * A lista é fixa (paisagens e cenas largas, que cabem bem em 16:9); a do dia sai pela data. A imagem é baixada
 * uma vez por dia, reduzida para o tamanho da tela e guardada em arquivo (offline, mostra a última que baixou).
 */
class FundoGaleria(ctx: Context, private val emSegundoPlano: (() -> Unit) -> Unit) : FrameLayout(ctx), FundoVivo {
    private class Obra(val id: Int, val titulo: String, val artista: String, val ano: String)

    private val obras = listOf(
        Obra(436535, "Campo de trigo com ciprestes", "Vincent van Gogh", "1889"),
        Obra(437323, "Paisagem brasileira", "Frans Post", "1650"),
        Obra(435809, "Os ceifeiros", "Pieter Bruegel, o Velho", "1565"),
        Obra(437659, "Vista do Sena", "Georges Seurat", "1882–83"),
        Obra(435749, "Na praia em Trouville", "Eugène Boudin", "1863"),
        Obra(437852, "Saltash com a balsa, Cornualha", "J. M. W. Turner", "1811"),
        Obra(436965, "A família Monet no jardim em Argenteuil", "Édouard Manet", "1874"),
        Obra(437299, "Colina de Jalais, Pontoise", "Camille Pissarro", "1867"),
        Obra(438101, "Campo Santa Maria Zobenigo, Veneza", "Canaletto", "década de 1730"),
        Obra(437979, "Navio ao luar", "Ivan Aivazovsky", "séc. XIX"),
        Obra(438816, "A floresta no inverno ao pôr do sol", "Théodore Rousseau", "c. 1846–67"),
        Obra(435988, "Ville-d'Avray", "Camille Corot", "1870"),
        Obra(438000, "Três mulheres taitianas", "Paul Gauguin", "1896"),
        Obra(435922, "Catedral de Salisbury vista do jardim do bispo", "John Constable", "c. 1825"),
        Obra(437192, "Diversão num rio congelado", "Aert van der Neer", "c. 1660"),
        Obra(436084, "Barcos no litoral de Étaples", "Charles-François Daubigny", "1871"),
        Obra(438822, "A refeição do leão", "Henri Rousseau", "c. 1907"),
        Obra(436832, "Paisagem com rio largo", "Philips Koninck", "c. 1648–49"),
        Obra(438117, "A ilha de San Michele, Veneza", "Francesco Guardi", "década de 1770"),
        Obra(437514, "Orla do bosque em Monts-Girard", "Théodore Rousseau", "1852–54"),
        Obra(435750, "Beaulieu: a baía de Fourmis", "Eugène Boudin", "1892"),
        Obra(436448, "Uma fazenda na Bretanha", "Paul Gauguin", "c. 1894"),
        Obra(437078, "Cachoeira em Mont-Dore", "Achille-Etna Michallon", "1818"),
        Obra(435647, "Vaprio d'Adda", "Bernardo Bellotto", "1744"),
        Obra(437040, "Mudança de pasto", "Anton Mauve", "c. 1880"),
        Obra(436787, "A Pont Neuf", "Johan Barthold Jongkind", "1849–50"),
        Obra(437179, "Perto de Penshurst, Kent", "Patrick Nasmyth", "1828"),
        Obra(435880, "Rochas em Fontainebleau", "Paul Cézanne", "década de 1890"),
    )
    private val imagem = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val etiqueta = TextView(ctx).apply {
        gravity = Gravity.END
        setTextColor(0xE6FBF7EE.toInt())
        setShadowLayer(ctx.dpf(8f), 0f, 0f, 0x99000000.toInt())
    }
    private var diaCarregado = ""

    init {
        setBackgroundColor(0xFF15140F.toInt())
        addView(imagem, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // Sombra no canto da hora e embaixo, onde ficam os cards: a pintura continua viva no meio.
        addView(View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x000A0C08, 0x000A0C08, 0x000A0C08, 0xDB080907.toInt()))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // Veu do topo, atras da hora e do clima: mantem o texto legivel mesmo sobre o ceu claro de uma pintura.
        addView(View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xB30A0C08.toInt(), 0x800A0C08.toInt(), 0x000A0C08))
        }, LayoutParams(LayoutParams.MATCH_PARENT, ctx.dpf(260f).toInt(), Gravity.TOP))
        addView(etiqueta, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.BOTTOM).apply {
            rightMargin = ctx.dpf(Grade.MARGEM.toFloat()).toInt()
            bottomMargin = ctx.dpf((540 - Grade.LINHA_APPS + 14).toFloat()).toInt()
        })
    }

    override fun atualizar(e: EstadoCeu) {
        val dia = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(e.agoraMs)
        if (dia == diaCarregado) return
        diaCarregado = dia
        val obra = obras[(Calendar.getInstance().apply { timeInMillis = e.agoraMs }.get(Calendar.DAY_OF_YEAR)) % obras.size]
        mostrarEtiqueta(obra)
        val arquivo = File(context.filesDir, "galeria-${obra.id}.jpg")
        if (arquivo.exists()) { imagem.setImageBitmap(BitmapFactory.decodeFile(arquivo.path)); return }
        emSegundoPlano {
            val bmp = baixar(obra, arquivo)
            post { if (bmp != null) imagem.setImageBitmap(bmp) }
        }
    }

    private fun mostrarEtiqueta(o: Obra) {
        val serif = Fontes.display(context, Tema.GALERIA)
        val sb = android.text.SpannableStringBuilder()
        fun parte(t: String, sp: Float, face: Typeface, italico: Boolean = false) {
            val ini = sb.length; sb.append(t)
            sb.setSpan(android.text.style.AbsoluteSizeSpan(sp.toInt(), true), ini, sb.length, 0)
            if (android.os.Build.VERSION.SDK_INT >= 28) sb.setSpan(android.text.style.TypefaceSpan(face), ini, sb.length, 0)   // fonte por trecho: Android 9+
            if (italico) sb.setSpan(android.text.style.StyleSpan(Typeface.ITALIC), ini, sb.length, 0)
        }
        parte(o.titulo + "\n", 17f, serif, true)
        parte("${o.artista}, ${o.ano}\n", 15f, serif)
        parte("THE MET · DOMÍNIO PÚBLICO · OBRA DO DIA", 9f, Fontes.uiMedia(context, Tema.GALERIA))
        etiqueta.text = sb
        etiqueta.setLineSpacing(0f, 1.1f)
    }

    /** Pergunta ao Met o endereço da imagem, baixa e reduz para ~1920 px de largura. */
    private fun baixar(o: Obra, destino: File): Bitmap? = try {
        val meta = JSONObject(ler("https://collectionapi.metmuseum.org/public/collection/v1/objects/${o.id}"))
        val url = meta.optString("primaryImage").ifEmpty { meta.optString("primaryImageSmall") }
        val bruto = File(context.cacheDir, "galeria-bruto.jpg")
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 15000; readTimeout = 60000
            inputStream.use { entrada -> bruto.outputStream().use { entrada.copyTo(it) } }
            disconnect()
        }
        // Lê só o tamanho, escolhe a redução (potência de 2) que ainda deixa >= 1920 de largura, e lê de verdade.
        val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(bruto.path, limites)
        var amostra = 1
        while (limites.outWidth / (amostra * 2) >= 1920) amostra *= 2
        val bmp = BitmapFactory.decodeFile(bruto.path, BitmapFactory.Options().apply { inSampleSize = amostra })
        bruto.delete()
        context.filesDir.listFiles { f -> f.name.startsWith("galeria-") }?.forEach { it.delete() }   // guarda só a do dia
        destino.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp
    } catch (e: Exception) {
        Log.w("TvLauncher.Galeria", "nao consegui baixar a obra ${o.id}: $e")
        null
    }

    private fun ler(u: String): String = (URL(u).openConnection() as HttpURLConnection).run {
        connectTimeout = 15000; readTimeout = 20000
        setRequestProperty("User-Agent", "tv-launcher/0.1")
        inputStream.bufferedReader().use { it.readText() }.also { disconnect() }
    }
}

/** O fundo do tema [t]. ESCURO = o degradê liso de antes (não muda com o tempo). */
fun criarFundo(ctx: Context, t: Tema, emSegundoPlano: (() -> Unit) -> Unit): View = when (t) {
    Tema.CEU -> FundoCeu(ctx)
    Tema.GALERIA -> FundoGaleria(ctx, emSegundoPlano)
    Tema.ASTROLABIO -> FundoAstrolabio(ctx)
    Tema.ESCURO -> View(ctx).apply {
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF1B2326.toInt(), 0xFF0F1416.toInt(), 0xFF0A0D0E.toInt()))
    }
}.also {
    // Camada de hardware: a GPU guarda o fundo pronto numa textura e so o "cola" a cada quadro. Sem isto, os degrades
    // de tela cheia eram refeitos em TODO quadro (medido na TV: 69 ms por quadro no Ceu e no Astrolabio, contra 24 ms sem fundo).
    // O fundo so e redesenhado de verdade quando atualizar() chama invalidate() (1x por minuto e a cada clima novo).
    it.setLayerType(View.LAYER_TYPE_HARDWARE, null)
}
