package br.paulo.tvlauncher

import android.accounts.AccountManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import java.io.File

/**
 * Perfis do launcher = as contas Google que estão na TV. O perfil ATIVO é só do launcher: muda a bolinha do topo,
 * mas não troca a conta do YouTube, do Gemini etc. (isso é do Google TV, não do launcher).
 *
 * Imagem de cada perfil, em ordem de preferência:
 *  1. a foto em  <pasta de arquivos do app>/perfil-<usuario>.jpg (ou .jpeg/.png), onde <usuario> é a parte do
 *     e-mail antes do @, em minúsculas. Para colocar, por adb:
 *       adb push foto.jpg /sdcard/Android/data/br.paulo.tvlauncher/files/perfil-fulano.jpg
 *     (a primeira conta também aceita o nome antigo, perfil.jpg)
 *  2. a inicial do e-mail, num círculo colorido (precisa da permissão GET_ACCOUNTS, concedida uma vez por adb:
 *       pm grant br.paulo.tvlauncher android.permission.GET_ACCOUNTS)
 *  3. um ícone de pessoa.
 *
 * Por que a foto não vem "sozinha" da conta: ler a foto do perfil Google exige entrar com OAuth numa API da Google
 * (projeto no Google Cloud + login na TV). O launcher só guarda a imagem que você colocar.
 */
object Perfil {
    private const val TAG = "TvLauncher.Perfil"
    private const val PREFS = "launcher"
    private const val CHAVE_ATIVO = "perfil_ativo"

    /** Os e-mails das contas Google da TV, na ordem do Android. Vazio se não houver conta (ou sem permissão). */
    fun contas(contexto: Context): List<String> {
        return try {
            AccountManager.get(contexto).getAccountsByType("com.google").map { it.name }
        } catch (e: Exception) {
            Log.w(TAG, "sem acesso as contas: $e")
            emptyList()
        }
    }

    /** A conta escolhida no popup. Se nada foi escolhido (ou a conta sumiu da TV), é a primeira. */
    fun ativa(contexto: Context): String? {
        val todas = contas(contexto)
        val salva = contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CHAVE_ATIVO, null)
        return if (salva != null && salva in todas) salva else todas.firstOrNull()
    }

    fun definirAtiva(contexto: Context, conta: String) {
        contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(CHAVE_ATIVO, conta).apply()
    }

    /** "fulano@gmail.com" -> "fulano". */
    fun usuario(conta: String): String = conta.substringBefore('@')

    /**
     * Cache dos avatares já desenhados (04/11/2026): o onResume da HOME redesenhava a bolinha toda vez — decodificar a
     * foto, recortar e escalar na thread da tela. A chave inclui o caminho e a data da foto, então trocar a foto por
     * adb (ou trocar de conta) gera chave nova e redesenha. Poucas contas na TV: o mapa fica pequeno.
     * Quem recebe o Bitmap não pode dar recycle() nele (hoje ninguém dá).
     */
    private val cacheAvatar = HashMap<String, Bitmap>()

    @Synchronized
    fun avatar(contexto: Context, lado: Int, conta: String? = ativa(contexto)): Bitmap {
        if (conta != null) {
            val primeira = conta == contas(contexto).firstOrNull()
            val arquivo = acharFoto(contexto, conta, primeira)
            if (arquivo != null) {
                val chave = "$conta|$lado|${arquivo.path}|${arquivo.lastModified()}"
                cacheAvatar[chave]?.let { return it }
                carregarFoto(arquivo, lado)?.let { foto ->
                    cacheAvatar.keys.removeAll { it.startsWith("$conta|$lado|") }   // a versão velha desta foto
                    cacheAvatar[chave] = foto
                    return foto
                }
            }
        }
        val inicial = conta?.firstOrNull()?.uppercaseChar()?.toString() ?: ""
        return cacheAvatar.getOrPut("inicial|$inicial|$lado") { desenharInicial(inicial, lado) }
    }

    private fun acharFoto(contexto: Context, conta: String, primeira: Boolean): File? {
        val pasta = contexto.getExternalFilesDir(null) ?: return null
        val base = "perfil-" + usuario(conta).lowercase()
        val nomes = mutableListOf("$base.jpg", "$base.jpeg", "$base.png")
        if (primeira) nomes += listOf("perfil.jpg", "perfil.jpeg", "perfil.png")   // nome antigo
        return nomes.map { File(pasta, it) }.firstOrNull { it.exists() }
    }

    private fun carregarFoto(arquivo: File, lado: Int): Bitmap? {
        return try {
            // 1º lê só o tamanho; 2º decodifica já reduzido (poupa RAM numa foto grande).
            val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(arquivo.path, limites)
            var amostra = 1
            while (limites.outWidth / (amostra * 2) >= lado * 2 && limites.outHeight / (amostra * 2) >= lado * 2) {
                amostra *= 2
            }
            val original = BitmapFactory.decodeFile(arquivo.path, BitmapFactory.Options().apply { inSampleSize = amostra })
                ?: return null
            // Recorta o quadrado central e ajusta ao tamanho do botão. O recorte redondo é feito pela View.
            val menor = minOf(original.width, original.height)
            val quadrado = Bitmap.createBitmap(
                original, (original.width - menor) / 2, (original.height - menor) / 2, menor, menor
            )
            Bitmap.createScaledBitmap(quadrado, lado, lado, true)
        } catch (e: Exception) {
            Log.w(TAG, "foto invalida: $e")
            null
        }
    }

    private fun desenharInicial(letra: String, lado: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888)
        val tela = Canvas(bitmap)
        val cores = intArrayOf(0xFF3E7C6E.toInt(), 0xFF4A6FA5.toInt(), 0xFF8A5A9E.toInt(), 0xFFB06A4F.toInt(), 0xFF5E8C4A.toInt())
        val tinta = Paint(Paint.ANTI_ALIAS_FLAG)
        tinta.color = if (letra.isEmpty()) 0xFF3A4743.toInt() else cores[letra[0].code % cores.size]
        tela.drawRect(0f, 0f, lado.toFloat(), lado.toFloat(), tinta)
        tinta.color = Color.WHITE
        if (letra.isNotEmpty()) {
            tinta.textSize = lado * 0.5f
            tinta.textAlign = Paint.Align.CENTER
            tinta.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            val y = lado / 2f - (tinta.descent() + tinta.ascent()) / 2f   // centraliza na vertical
            tela.drawText(letra, lado / 2f, y, tinta)
        } else {
            // ícone de pessoa: cabeça + ombros
            tela.drawCircle(lado / 2f, lado * 0.38f, lado * 0.18f, tinta)
            tela.drawOval(RectF(lado * 0.22f, lado * 0.58f, lado * 0.78f, lado * 1.1f), tinta)
        }
        return bitmap
    }
}
