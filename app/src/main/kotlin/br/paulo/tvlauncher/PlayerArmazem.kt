package br.paulo.tvlauncher

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Player universal, passo 2 (06/11/2026): onde o player guarda as coisas dele na TV.
 *
 * - As contas (links de lista, usuário e senha do Xtream) ficam CIFRADAS (AES-GCM) com uma chave do Android Keystore,
 *   que nunca sai do aparelho, no arquivo files/player/contas.json. Ele fica fora das preferências "launcher", que o
 *   backup do painel exporta (BackupConfig): senha de IPTV não pode ir num JSON baixado. É arquivo, e não
 *   SharedPreferences, porque quem grava é o processo do launcher e quem lê é o do player (":player"), e cada processo
 *   guarda a sua própria cópia das preferências em memória (o player veria a conta velha).
 * - O resto (último canal, favoritos) é preferência comum, em "player".
 * - Cache de canais e do guia: arquivos em files/player/, sem senha (ver PlayerDados.canaisParaJson).
 */
object PlayerArmazem {
    private const val TAG = "TvLauncher.Player"
    private const val APELIDO = "player_fontes"

    private fun arquivoContas(ctx: Context) = File(pasta(ctx), "contas.json")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("player", Context.MODE_PRIVATE)

    fun pasta(ctx: Context): File = File(ctx.filesDir, "player").apply { mkdirs() }

    // ---------------------------------------------------------------- contas

    fun fontes(ctx: Context): List<FonteTv> {
        val f = arquivoContas(ctx)
        if (!f.exists()) return emptyList()
        val o = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return emptyList()
        val dados = o.optString("dados").ifEmpty { return emptyList() }
        val iv = o.optString("iv").ifEmpty { return emptyList() }
        val claro = runCatching { decifrar(dados, iv) }.onFailure { Log.w(TAG, "contas ilegíveis: ${it.javaClass.simpleName}") }.getOrNull()
        return PlayerDados.fontesDeJson(claro)
    }

    private fun gravar(ctx: Context, fontes: List<FonteTv>): Boolean = runCatching {
        val (dados, iv) = cifrar(PlayerDados.fontesParaJson(fontes))
        // Grava num temporário e troca de nome: se a TV desligar no meio, fica o arquivo antigo inteiro, não metade.
        val tmp = File(pasta(ctx), "contas.json.tmp")
        tmp.writeText(JSONObject().put("dados", dados).put("iv", iv).toString())
        tmp.renameTo(arquivoContas(ctx))
    }.onFailure { Log.w(TAG, "não gravou as contas: ${it.javaClass.simpleName}") }.getOrDefault(false)

    /** Cria uma conta nova (id gerado). Devolve a conta, ou o motivo de não ter criado. */
    fun adicionar(ctx: Context, nome: String, tipo: String, endereco: String, usuario: String = "", senha: String = "", guia: String = ""): Result<FonteTv> {
        val atuais = fontes(ctx)
        if (atuais.size >= 8) return Result.failure(IllegalStateException("já há 8 contas; apague uma antes"))
        val nova = PlayerDados.criar(UUID.randomUUID().toString().take(8), nome, tipo, endereco, usuario, senha, guia).getOrElse { return Result.failure(it) }
        return if (gravar(ctx, atuais + nova)) Result.success(nova) else Result.failure(IllegalStateException("não consegui gravar"))
    }

    fun apagar(ctx: Context, id: String): Boolean {
        val atuais = fontes(ctx)
        if (atuais.none { it.id == id }) return false
        PlayerCarga.apagarCache(ctx, id)
        return gravar(ctx, atuais.filter { it.id != id })
    }

    // ---------------------------------------------------------------- uso

    fun ultimoCanal(ctx: Context): String? = prefs(ctx).getString("ultimo", null)
    fun definirUltimoCanal(ctx: Context, id: String) { prefs(ctx).edit().putString("ultimo", id).apply() }

    // ---------------------------------------------------------------- cifra (mesma receita do IaArmazem)

    private fun chave(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(APELIDO, null) as? SecretKey)?.let { return it }
        val gerador = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gerador.init(KeyGenParameterSpec.Builder(APELIDO, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gerador.generateKey()
    }

    private fun cifrar(texto: String): Pair<String, String> {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, chave()) }
        return Base64.encodeToString(c.doFinal(texto.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP) to
            Base64.encodeToString(c.iv, Base64.NO_WRAP)
    }

    private fun decifrar(dados: String, iv: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, chave(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return String(c.doFinal(Base64.decode(dados, Base64.NO_WRAP)), Charsets.UTF_8)
    }
}
