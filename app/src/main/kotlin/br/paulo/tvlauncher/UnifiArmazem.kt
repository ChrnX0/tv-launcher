package br.paulo.tvlauncher

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Onde a integração com o roteador UniFi guarda o que é dela (05/11/2026). Mesma receita da chave da IA:
 *
 * - Preferências PRÓPRIAS, "unifi_seguro": o backup do painel (BackupConfig) só exporta "launcher", "despertador" e
 *   "dns", então a chave e a lista de aparelhos nunca vão num JSON baixado. O app tem allowBackup=false.
 * - A chave fica CIFRADA (AES-GCM) com uma chave do Android Keystore, que nunca sai do aparelho.
 * - O painel nunca recebe a chave de volta: só os 4 últimos caracteres ([finalDaChave]).
 * - Trocar o ENDEREÇO apaga a chave, o certificado confirmado e a lista de conhecidos. Sem isso, quem entrasse no
 *   painel poderia apontar o endereço para um computador dele na rede e a TV entregaria a chave já guardada.
 */
object UnifiArmazem {
    private const val TAG = "TvLauncher.Unifi"
    private const val ARQUIVO = "unifi_seguro"
    private const val APELIDO = "unifi_chave_api"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE)

    fun ligado(ctx: Context): Boolean = prefs(ctx).getBoolean("ligado", false)
    fun definirLigado(ctx: Context, sim: Boolean) { prefs(ctx).edit().putBoolean("ligado", sim).apply() }

    /** Recado na TV quando aparece aparelho nunca visto. Desligado por padrão. */
    fun avisoNovos(ctx: Context): Boolean = prefs(ctx).getBoolean("aviso_novos", false)
    fun definirAvisoNovos(ctx: Context, sim: Boolean) {
        // Ao ligar, a próxima leitura só monta a lista de conhecidos (senão a 1ª vez avisaria a casa inteira).
        prefs(ctx).edit().putBoolean("aviso_novos", sim).apply { if (sim) putBoolean("semeado", false) }.apply()
    }

    fun endereco(ctx: Context): String? = prefs(ctx).getString("endereco", null)

    /** Grava o endereço (já validado por [Unifi.enderecoValido]). Se mudou, apaga chave, certificado e conhecidos. */
    fun definirEndereco(ctx: Context, novo: String) {
        if (novo == endereco(ctx)) return
        prefs(ctx).edit().putString("endereco", novo)
            .remove("chave_cifrada").remove("chave_iv").remove("chave_final")
            .remove("certificado").remove("conhecidos").putBoolean("semeado", false)
            .commit()
    }

    /** Impressão SHA-256 do certificado que o dono confirmou ("AB:CD:…"), ou null. */
    fun certificado(ctx: Context): String? = prefs(ctx).getString("certificado", null)
    fun definirCertificado(ctx: Context, impressao: String?) {
        prefs(ctx).edit().apply { if (impressao == null) remove("certificado") else putString("certificado", impressao) }.apply()
    }

    /** Ligado, com endereço, chave e certificado confirmado: só assim um pedido sai para o roteador. */
    fun pronto(ctx: Context): Boolean = ligado(ctx) && endereco(ctx) != null && temChave(ctx) && certificado(ctx) != null

    fun temChave(ctx: Context): Boolean = prefs(ctx).contains("chave_cifrada")
    fun finalDaChave(ctx: Context): String? = prefs(ctx).getString("chave_final", null)?.let { "…$it" }

    /** Grava a chave (já validada por [Unifi.chaveValida]), cifrada. Null apaga. False se o Keystore falhar. */
    fun definirChave(ctx: Context, chave: String?): Boolean {
        if (chave == null) {
            prefs(ctx).edit().remove("chave_cifrada").remove("chave_iv").remove("chave_final").apply()
            return true
        }
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, chaveDoKeystore())
            val cifrada = c.doFinal(chave.toByteArray(Charsets.US_ASCII))
            prefs(ctx).edit()
                .putString("chave_cifrada", Base64.encodeToString(cifrada, Base64.NO_WRAP))
                .putString("chave_iv", Base64.encodeToString(c.iv, Base64.NO_WRAP))
                .putString("chave_final", chave.takeLast(4))
                .commit()
        } catch (e: Exception) {
            Log.w(TAG, "não consegui cifrar a chave: ${e.javaClass.simpleName}")
            false
        }
    }

    /** A chave em claro, só para montar o pedido. Null sem chave ou se o Keystore perdeu a dele. */
    fun chave(ctx: Context): String? {
        val p = prefs(ctx)
        val cifrada = p.getString("chave_cifrada", null) ?: return null
        val iv = p.getString("chave_iv", null) ?: return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, chaveDoKeystore(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(c.doFinal(Base64.decode(cifrada, Base64.NO_WRAP)), Charsets.US_ASCII)
        } catch (e: Exception) {
            Log.w(TAG, "não consegui decifrar a chave: ${e.javaClass.simpleName}")   // nunca a chave no log
            null
        }
    }

    /** Apaga tudo (botão "Esquecer o roteador" do painel). A chave AES do Keystore fica: sem o texto cifrado, não vale nada. */
    fun esquecer(ctx: Context) { prefs(ctx).edit().clear().commit() }

    // ---------------------------------------------------------------- aparelhos já vistos (para o aviso de novos)

    fun semeado(ctx: Context): Boolean = prefs(ctx).getBoolean("semeado", false)

    fun conhecidos(ctx: Context): List<String> = try {
        val a = JSONArray(prefs(ctx).getString("conhecidos", "[]"))
        (0 until a.length()).map { a.getString(it) }
    } catch (e: Exception) { emptyList() }

    fun definirConhecidos(ctx: Context, lista: List<String>) {
        prefs(ctx).edit().putString("conhecidos", JSONArray(lista).toString()).putBoolean("semeado", true).apply()
    }

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
}
