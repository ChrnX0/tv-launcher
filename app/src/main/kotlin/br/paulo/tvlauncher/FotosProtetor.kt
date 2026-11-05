package br.paulo.tvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.InputStream

/**
 * Fotos do protetor de tela (05/11/2026, fila longa do brainstorm). As fotos chegam pelo painel no navegador (aba
 * Fotos) e ficam numa pasta PRIVADA do launcher (filesDir/fotos-protetor): não precisam do "acesso a todos os
 * arquivos" e nenhum outro app as vê. Com pelo menos uma foto, o ProtetorTela passa a mostrá-las por trás do relógio,
 * trocando a cada [TROCA_MS]. Sem fotos, o protetor fica como sempre foi.
 *
 * Por que não o Google Fotos: exigiria conta e API do Google (decisão do brainstorm de 04/11/2026).
 *
 * Memória: a TV tem pouca RAM. Cada foto é decodificada já reduzida para o tamanho da tela (inSampleSize), em RGB_565
 * (metade dos bytes do ARGB), e só duas ficam carregadas por vez (a atual e a que entra no esmaecimento).
 */
object FotosProtetor {
    const val LIMITE_BYTES = 15L * 1024 * 1024   // uma foto de celular tem 2 a 6 MB; isto barra vídeo/lixo
    const val MAXIMO_FOTOS = 200
    const val TROCA_MS = 60_000L
    private val EXTENSOES = setOf("jpg", "jpeg", "png", "webp")

    fun pasta(ctx: Context) = File(ctx.filesDir, "fotos-protetor")

    /** Nome aceito: só letras, números, espaço, ponto, hífen e sublinhado, com extensão de imagem. Nada de "/" ou "..". */
    fun nomeValido(nome: String): Boolean =
        nome.length in 5..100 && Regex("""[\p{L}\p{N} ._-]+""").matches(nome) && !nome.startsWith(".") &&
            nome.substringAfterLast('.', "").lowercase() in EXTENSOES

    fun listar(ctx: Context): List<File> =
        pasta(ctx).listFiles { f -> f.isFile && nomeValido(f.name) }?.sortedBy { it.name.lowercase() } ?: emptyList()

    fun arquivo(ctx: Context, nome: String): File? = if (nomeValido(nome)) File(pasta(ctx), nome).takeIf { it.isFile } else null

    /**
     * Recebe uma foto do painel. Grava num temporário, confere que é mesmo uma imagem (o Android consegue ler as medidas)
     * e só então dá o nome final. Devolve null se deu certo, ou o motivo em português.
     */
    fun receber(ctx: Context, nome: String, tamanho: Long, corpo: InputStream): String? {
        if (!nomeValido(nome)) return "nome inválido (use letras, números, espaço, . - _ e termine em .jpg, .png ou .webp)"
        if (tamanho <= 0 || tamanho > LIMITE_BYTES) return "foto vazia ou maior que 15 MB"
        if (listar(ctx).size >= MAXIMO_FOTOS) return "já tem $MAXIMO_FOTOS fotos; apague algumas antes"
        val dir = pasta(ctx).apply { mkdirs() }
        val tmp = File(dir, ".envio.tmp")
        try {
            tmp.outputStream().use { saida -> corpo.copyTo(saida) }
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.path, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return "o arquivo não é uma imagem que a TV consiga abrir"
            val destino = File(dir, nome)
            if (destino.exists()) destino.delete()
            if (!tmp.renameTo(destino)) return "não consegui gravar a foto na TV"
            return null
        } finally { tmp.delete() }
    }

    fun apagar(ctx: Context, nome: String): Boolean = arquivo(ctx, nome)?.delete() == true

    /** Fator de redução (potência de 2) para a foto caber em [largura]x[altura] sem desperdício. */
    fun reducao(larguraFoto: Int, alturaFoto: Int, largura: Int, altura: Int): Int {
        var n = 1
        while (larguraFoto / (n * 2) >= largura && alturaFoto / (n * 2) >= altura) n *= 2
        return n
    }

    /** A foto reduzida para a tela, ou null se não abrir. */
    fun carregar(f: File, largura: Int, altura: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply {
            inSampleSize = reducao(o.outWidth, o.outHeight, largura, altura)
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    } catch (e: Throwable) { null }   // OutOfMemoryError incluso: sem a foto, o protetor segue só com o relógio
}
