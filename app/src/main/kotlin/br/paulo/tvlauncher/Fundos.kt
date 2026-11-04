package br.paulo.tvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
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

/** Desenha a lua com a fase de [agoraMs]: fração iluminada e lado (no hemisfério sul a lua crescente acende à esquerda). */
internal fun desenharLua(c: Canvas, x: Float, y: Float, r: Float, agoraMs: Long, lat: Double, brilho: Paint) {
    val idade = Lua.idade(agoraMs)
    val fracao = idade / 29.530588853
    val iluminada = ((1 - cos(2 * PI * fracao)) / 2).toFloat()
    val crescente = fracao < 0.5
    // Lado aceso: no norte, crescente acende à direita; no sul, espelha.
    val direita = if (lat >= 0) crescente else !crescente
    // Sem halo (o Paulo achou que parecia neblina atrás da lua): so o disco solido, como no sol.
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

// ======================================================================== Escuro

/**
 * O fundo do (único) tema escuro: o grafite fixo de sempre (ver [desenharFundoEscuro]), sem mudar de cor — só por
 * cima dele, uma linha com o caminho do sol e da lua de hoje (posição real do sol, fase real da lua, nascer e pôr).
 * (Era o antigo tema "Céu ao vivo" — nuvens, chuva e estrelas incluídas; o Paulo pediu para tirar tudo isso e manter
 * só esta linha, fundida aqui no Escuro, que não existe mais como opção separada.) A lua "atrasa" em relação ao sol
 * ~50 min por dia de idade.
 */
class FundoEscuro(ctx: Context, private val comArco: Boolean = true) : View(ctx), FundoVivo {
    private var e: EstadoCeu? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val texto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fontes.ui(ctx, Tema.ESCURO); color = 0x8CF3EFE6.toInt(); textSize = ctx.dpf(10f); letterSpacing = 0.04f
    }

    override fun atualizar(e: EstadoCeu) { this.e = e; invalidate() }

    override fun onDraw(c: Canvas) {
        val est = e ?: EstadoCeu(System.currentTimeMillis(), Config.LATITUDE, Config.LONGITUDE, -1, false)
        val w = width.toFloat(); val h = height.toFloat()
        desenharFundoEscuro(c, w, h, p)
        // Sem arco (tela de previsão): o caminho do sol e os textos "nascer/pôr" cruzariam os cartões que rolam por cima.
        if (!comArco) return

        // Arco do sol de hoje. hor mais alto que antes (0.60->0.50): com o brilho do sol/lua (~34 dp de raio), o ponto
        // no horizonte (nascer/por) tinha o brilho entrando embaixo de Grade.LINHA_APPS e cortando nos icones.
        val hor = h * 0.50f; val x0 = w * 0.25f; val x1 = w * 0.75f; val topo = h * 0.36f   // na faixa livre entre o topo e os cards
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
                // Sem halo (o Paulo achou que parecia neblina atrás do sol/lua): so o disco solido.
                p.color = 0xFFFFF3D6.toInt(); c.drawCircle(x, y, context.dpf(8f), p)
            }
            // A lua "atrasa" ~24 h por ciclo: a hora em que ela está no ponto equivalente ao sol.
            val atraso = Lua.idade(est.agoraMs) / 29.530588853 * 24
            val horaLua = ((agora - atraso) % 24 + 24) % 24
            ponto(horaLua)?.let { (x, y) -> desenharLua(c, x, y - context.dpf(6f), context.dpf(17f), est.agoraMs, est.lat, p) }
        }
    }
}

/**
 * O grafite fixo de baixo do tema Escuro: gradiente + dois brilhos bem suaves (um frio atrás da hora, um quente no
 * canto de baixo) + vinheta nas bordas + véu embaixo para os cards. Função solta (não método da classe) só para
 * separar, na leitura, "o fundo fixo" da linha do sol/lua, que a classe FundoEscuro desenha por cima.
 */
private fun desenharFundoEscuro(c: Canvas, w: Float, h: Float, p: Paint) {
    p.shader = LinearGradient(0f, 0f, 0f, h,
        intArrayOf(0xFF0E1317.toInt(), 0xFF0A0D10.toInt(), 0xFF07090B.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    c.drawRect(0f, 0f, w, h, p)
    // brilho frio, atrás da hora e do clima
    p.shader = RadialGradient(w * 0.24f, h * 0.14f, w * 0.58f, 0x5A4A7388, 0x004A7388, Shader.TileMode.CLAMP)
    c.drawRect(0f, 0f, w, h, p)
    // brilho quente, no canto de baixo à direita
    p.shader = RadialGradient(w * 0.94f, h * 1.0f, w * 0.52f, 0x3C8A5A2E, 0x008A5A2E, Shader.TileMode.CLAMP)
    c.drawRect(0f, 0f, w, h, p)
    // vinheta: as bordas escurecem e o olho vai para o centro
    p.shader = RadialGradient(w / 2, h * 0.46f, w * 0.74f, 0x00000000, 0x80000000.toInt(), Shader.TileMode.CLAMP)
    c.drawRect(0f, 0f, w, h, p)
    // véu embaixo: os nomes dos cards ficam sobre um fundo mais escuro
    p.shader = LinearGradient(0f, h * 0.62f, 0f, h, 0x00000000, 0x80000000.toInt(), Shader.TileMode.CLAMP)
    c.drawRect(0f, 0f, w, h, p)
    p.shader = null
}

// ======================================================================== Galeria

/**
 * Uma pintura por dia, em tela cheia, do acervo aberto (domínio público, CC0) do The Met, com etiqueta de museu.
 * A lista é fixa (paisagens e cenas largas, que cabem bem em 16:9); a do dia sai pela data. A imagem é baixada
 * uma vez por dia, reduzida para o tamanho da tela e guardada em arquivo (offline, mostra a última que baixou).
 */
class FundoGaleria(ctx: Context, private val emSegundoPlano: (() -> Unit) -> Unit, private val comEtiqueta: Boolean = true) : FrameLayout(ctx), FundoVivo {
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
    private var baixando = false   // um download por vez (atualizar() é chamado a cada tique do céu)

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
        if (!comEtiqueta) etiqueta.visibility = View.GONE   // tela de previsão: a etiqueta ficaria embaixo dos cartões
    }

    override fun atualizar(e: EstadoCeu) {
        val dia = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(e.agoraMs)
        if (dia == diaCarregado || baixando) return
        val obra = obras[(Calendar.getInstance().apply { timeInMillis = e.agoraMs }.get(Calendar.DAY_OF_YEAR)) % obras.size]
        mostrarEtiqueta(obra)
        val arquivo = File(context.filesDir, "galeria-${obra.id}.jpg")
        if (arquivo.exists()) { imagem.setImageBitmap(BitmapFactory.decodeFile(arquivo.path)); diaCarregado = dia; return }
        // Só marca o dia como carregado quando a imagem CHEGA (revisão de 04/11/2026): antes marcava antes de baixar e,
        // se o download falhasse (rede ainda subindo no boot), a galeria ficava preta até o dia seguinte. Agora o
        // próximo atualizar() tenta de novo.
        baixando = true
        emSegundoPlano {
            val bmp = baixar(obra, arquivo)
            post {
                baixando = false
                if (bmp != null) { imagem.setImageBitmap(bmp); diaCarregado = dia }
            }
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

/**
 * O fundo do tema [t]. [decoracao] = false tira o que só faz sentido na home (arco do sol/lua no Escuro, etiqueta da
 * obra na Galeria) — usado em telas com conteúdo rolando por cima, como a previsão.
 */
fun criarFundo(ctx: Context, t: Tema, decoracao: Boolean = true, emSegundoPlano: (() -> Unit) -> Unit): View = when (t) {
    Tema.GALERIA -> FundoGaleria(ctx, emSegundoPlano, comEtiqueta = decoracao)
    Tema.ESCURO -> FundoEscuro(ctx, comArco = decoracao)
}.also {
    // Camada de hardware: a GPU guarda o fundo pronto numa textura e so o "cola" a cada quadro. Sem isto, os degrades
    // de tela cheia eram refeitos em TODO quadro (medido na TV: 69 ms por quadro no Ceu e no Astrolabio, contra 24 ms sem fundo).
    // O fundo so e redesenhado de verdade quando atualizar() chama invalidate() (1x por minuto e a cada clima novo).
    it.setLayerType(View.LAYER_TYPE_HARDWARE, null)
    // O preto de segurança (enquanto a pintura do dia carrega) fica DENTRO da camada: só é pintado quando a camada é
    // refeita, não a cada quadro como era o preto do contêiner e da janela.
    it.setBackgroundColor(android.graphics.Color.BLACK)
}
