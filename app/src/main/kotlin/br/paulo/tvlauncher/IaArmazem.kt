package br.paulo.tvlauncher

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Onde a IA na nuvem guarda o que é dela (bloco 6, 05/11/2026).
 *
 * - Arquivo de preferências PRÓPRIO, "ia_seguro", fora do "launcher": o backup do painel (BackupConfig) exporta o
 *   "launcher" inteiro, e a chave de API não pode ir num JSON baixado. O app tem allowBackup=false.
 * - A chave fica CIFRADA (AES-GCM) com uma chave do Android Keystore, que nunca sai do aparelho. Quem copiar o
 *   arquivo de preferências (ex.: um backup do sistema) leva só bytes embaralhados.
 * - A chave nunca volta inteira para ninguém: o painel só vê os 4 últimos caracteres ([finalDaChave]).
 * - Ligar a IA é só pela TV (Configurações do launcher). O painel pode cadastrar a chave, mas não liga.
 */
object IaArmazem {
    private const val TAG = "TvLauncher.Ia"
    private const val ARQUIVO = "ia_seguro"
    private const val APELIDO = "ia_chave_api"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE).also { migrar(it) }

    /** Antes do multi-provedor (05/11/2026) só havia Gemini, sem sufixo nas chaves: passa para "_gemini" uma vez. */
    private fun migrar(p: android.content.SharedPreferences) {
        if (!p.contains("chave_cifrada") && !p.contains("modelo")) return
        val e = p.edit()
        for (nome in listOf("chave_cifrada", "chave_iv", "chave_final", "modelo")) {
            p.getString(nome, null)?.let { if (!p.contains("${nome}_gemini")) e.putString("${nome}_gemini", it) }
            e.remove(nome)
        }
        e.commit()
    }

    fun ligada(ctx: Context): Boolean = prefs(ctx).getBoolean("ligada", false)
    fun definirLigada(ctx: Context, sim: Boolean) { prefs(ctx).edit().putBoolean("ligada", sim).apply() }

    // ---------------------------------------------------------------- provedor (05/11/2026: Gemini, Claude, OpenAI, Perplexity)

    /** O provedor que recebe os pedidos. Padrão: Gemini. Cada provedor tem a SUA chave e o SEU modelo, guardados à parte. */
    fun provedor(ctx: Context): ComandosIa.Provedor =
        ComandosIa.Provedor.porId(prefs(ctx).getString("provedor", null)) ?: ComandosIa.Provedor.GEMINI
    fun definirProvedor(ctx: Context, p: ComandosIa.Provedor) { prefs(ctx).edit().putString("provedor", p.id).apply() }

    /** Ligada E com a chave do provedor escolhido: só assim um pedido vai para a nuvem. */
    fun pronta(ctx: Context): Boolean = ligada(ctx) && temChave(ctx)

    fun modelo(ctx: Context, p: ComandosIa.Provedor = provedor(ctx)): String =
        prefs(ctx).getString("modelo_${p.id}", null)?.takeIf { ComandosIa.modeloValido(it) } ?: p.modeloPadrao
    fun definirModelo(ctx: Context, m: String, p: ComandosIa.Provedor = provedor(ctx)): Boolean {
        if (!ComandosIa.modeloValido(m)) return false
        prefs(ctx).edit().putString("modelo_${p.id}", m).apply()
        return true
    }

    fun temChave(ctx: Context, p: ComandosIa.Provedor = provedor(ctx)): Boolean = prefs(ctx).contains("chave_cifrada_${p.id}")

    /** "…a1B2", ou null sem chave. */
    fun finalDaChave(ctx: Context, p: ComandosIa.Provedor = provedor(ctx)): String? =
        prefs(ctx).getString("chave_final_${p.id}", null)?.let { "…$it" }

    /** Grava a chave do provedor [p] (cifrada). False se o formato for inválido ou o Keystore falhar. Null apaga. */
    fun definirChave(ctx: Context, chave: String?, p: ComandosIa.Provedor = provedor(ctx)): Boolean {
        if (chave == null) {
            prefs(ctx).edit().remove("chave_cifrada_${p.id}").remove("chave_iv_${p.id}").remove("chave_final_${p.id}").apply()
            return true
        }
        if (!ComandosIa.chaveValida(chave)) return false
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, chaveDoKeystore())
            val cifrada = c.doFinal(chave.toByteArray(Charsets.US_ASCII))
            prefs(ctx).edit()
                .putString("chave_cifrada_${p.id}", Base64.encodeToString(cifrada, Base64.NO_WRAP))
                .putString("chave_iv_${p.id}", Base64.encodeToString(c.iv, Base64.NO_WRAP))
                .putString("chave_final_${p.id}", chave.takeLast(4))
                .commit()
        } catch (e: Exception) {
            Log.w(TAG, "não consegui cifrar a chave: ${e.javaClass.simpleName}")
            false
        }
    }

    /** A chave em claro, só para montar o pedido HTTP. Null sem chave ou se o Keystore perdeu a chave dele. */
    fun chave(ctx: Context, p: ComandosIa.Provedor = provedor(ctx)): String? {
        val prefs = prefs(ctx)
        val cifrada = prefs.getString("chave_cifrada_${p.id}", null) ?: return null
        val iv = prefs.getString("chave_iv_${p.id}", null) ?: return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, chaveDoKeystore(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(c.doFinal(Base64.decode(cifrada, Base64.NO_WRAP)), Charsets.US_ASCII)
        } catch (e: Exception) {
            // Nunca logar a chave nem o texto cifrado: só o tipo do erro.
            Log.w(TAG, "não consegui decifrar a chave: ${e.javaClass.simpleName}")
            null
        }
    }

    /** A chave AES do Android Keystore (criada na 1ª vez). Ela não sai do aparelho nem do processo do Keystore. */
    private fun chaveDoKeystore(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(APELIDO, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gerador = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gerador.init(KeyGenParameterSpec.Builder(APELIDO, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gerador.generateKey()
    }

    // ---------------------------------------------------------------- limite de uso (a conta é do dono)

    private val horarios = ArrayDeque<Long>()

    /**
     * No máximo 1 pedido a cada 3 s e 30 por hora. Protege a cota (ou a fatura) da chave do dono se alguém com o PIN
     * do painel, ou um script, ficar mandando pedidos. Devolve null se pode, ou quantos segundos esperar.
     */
    @Synchronized fun reservarVez(agora: Long = System.currentTimeMillis()): Long? {
        while (horarios.isNotEmpty() && agora - horarios.first() > 3_600_000) horarios.removeFirst()
        horarios.lastOrNull()?.let { if (agora - it < 3_000) return (3_000 - (agora - it) + 999) / 1000 }
        if (horarios.size >= 30) return (3_600_000 - (agora - horarios.first()) + 999) / 1000
        horarios.addLast(agora)
        return null
    }
}
