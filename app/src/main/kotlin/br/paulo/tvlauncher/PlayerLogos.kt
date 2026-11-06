package br.paulo.tvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Player universal, lista completa (06/11/2026): os logotipos dos canais (tvg-logo / stream_icon).
 *
 * Feito à mão em vez de uma biblioteca (Glide, Coil): são ~150 linhas contra ~1 MB de APK, e aqui só precisa de três
 * coisas: memória (LruCache), disco (cacheDir/logos, que o Android pode limpar quando faltar espaço) e 3 downloads ao
 * mesmo tempo. A imagem é reduzida ao tamanho do card ANTES de ir para a memória (inSampleSize): um PNG de 1000 px
 * viraria 4 MB por logo.
 *
 * Só na thread da tela: carregar(). Download e decodificação rodam no grupo de 3 threads.
 */
object PlayerLogos {
    private const val MAX_BYTES = 1L * 1024 * 1024   // logo maior que 1 MB não é logo
    private const val LADO_PX = 160                   // maior lado guardado (o maior card usa ~120 px na TV 1080p)

    private val tela = Handler(Looper.getMainLooper())
    private val trabalho = Executors.newFixedThreadPool(3)
    private val falhou = HashSet<String>()            // não tenta de novo, nesta sessão, o que já deu erro

    // Teto de 12 MB de bitmaps (o processo :player é só do vídeo; a HOME não carrega isto).
    private val memoria = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /**
     * Mostra o logo em `alvo`. A View de uma linha de lista é reaproveitada para outro canal enquanto o download corre:
     * por isso a URL vai na tag, e o resultado só é posto se a tag ainda for a mesma.
     */
    fun carregar(ctx: Context, url: String, alvo: ImageView) {
        alvo.tag = url
        alvo.setImageDrawable(null)
        if (url.isEmpty() || !M3u.urlAceita(url) || url in falhou) return
        memoria.get(url)?.let { alvo.setImageBitmap(it); return }
        val dir = File(ctx.cacheDir, "logos")
        trabalho.execute {
            val bmp = runCatching { obter(dir, url) }.getOrNull()
            tela.post {
                if (bmp == null) { falhou += url; return@post }
                memoria.put(url, bmp)
                if (alvo.tag == url) alvo.setImageBitmap(bmp)
            }
        }
    }

    private fun obter(dir: File, url: String): Bitmap? {
        val arquivo = File(dir, hash(url))
        val bytes = if (arquivo.exists()) arquivo.readBytes() else baixar(url).also {
            runCatching { dir.mkdirs(); File(dir, "${arquivo.name}.tmp").apply { writeBytes(it) }.renameTo(arquivo) }
        }
        return decodificar(bytes)
    }

    private fun decodificar(bytes: ByteArray): Bitmap? {
        val medidas = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, medidas)
        if (medidas.outWidth <= 0 || medidas.outHeight <= 0) return null
        var amostra = 1
        while (maxOf(medidas.outWidth, medidas.outHeight) / (amostra * 2) >= LADO_PX) amostra *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = amostra })
    }

    /** Baixa seguindo até 4 redirecionamentos (de http para https o HttpURLConnection não segue sozinho). TLS padrão. */
    private fun baixar(endereco: String): ByteArray {
        var url = endereco
        repeat(5) {
            if (!M3u.urlAceita(url)) throw IOException("endereço recusado")
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8_000
            c.readTimeout = 10_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "TvLauncher-Player/1.0")
            try {
                val codigo = c.responseCode
                if (codigo in 300..399) {
                    url = URL(URL(url), c.getHeaderField("Location") ?: throw IOException("sem destino")).toString()
                    return@repeat
                }
                if (codigo != 200) throw IOException("HTTP $codigo")
                if (c.contentLengthLong > MAX_BYTES) throw IOException("grande demais")
                c.inputStream.use { entrada ->
                    val saida = ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = entrada.read(buf)
                        if (n < 0) break
                        saida.write(buf, 0, n)
                        if (saida.size() > MAX_BYTES) throw IOException("grande demais")
                    }
                    return saida.toByteArray()
                }
            } finally { c.disconnect() }
        }
        throw IOException("redirecionamentos demais")
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
