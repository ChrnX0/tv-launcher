package br.paulo.tvlauncher

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.io.File
import java.net.Inet4Address
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Sobe e vigia o túnel para a internet (programa Go em tunel/, empacotado como libtsnet.so). Opção E, 05/11/2026.
 *
 * O túnel é o Tailscale rodando DENTRO de um processo comum (tsnet), sem usar a vaga de VPN do Android: o AdGuard VPN
 * e o DNS privado continuam como estão. Ele publica https://<nome>.ts.net -> 127.0.0.1:[Config.MCP_PORTA] (McpServidor).
 *
 * Roda como o próprio launcher (não precisa do shell): o binário fica na pasta nativa do app, de onde o Android deixa
 * executar. Liga junto com o MCP; morreu, religa com espera crescente (10 s, 20 s ... até 5 min).
 *
 * Chave do Tailscale ("tskey-auth-..."): colada no painel (com PIN), guardada CIFRADA (Keystore) e APAGADA assim que o
 * primeiro login dá certo. Daí em diante o túnel usa o estado salvo na pasta tunel/ dos dados do app.
 */
object TunelTailscale {
    private const val TAG = "TvLauncher.Tunel"
    private const val PREFS = "tunel_seguro"     // fora do backup
    private const val APELIDO = "tailscale_chave"
    const val NOME_NO = "tv-sala"

    @Volatile private var processo: Process? = null
    @Volatile private var querRodando = false
    @Volatile private var vigia: Thread? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun binario(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "libtsnet.so")
    private fun pastaEstado(ctx: Context) = File(ctx.filesDir, "tunel")

    /** O endereço público (ex.: tv-sala.tail1234.ts.net), ou null antes do 1º login. */
    fun nome(ctx: Context): String? = prefs(ctx).getString("nome", null)
    fun ultimoErro(ctx: Context): String? = prefs(ctx).getString("erro", null)
    fun rodando(): Boolean = processo?.isAlive == true

    /** Já entrou no Tailscale alguma vez (estado salvo) ou tem chave esperando o 1º login. */
    fun configurado(ctx: Context): Boolean = temChave(ctx) || File(pastaEstado(ctx), "tailscaled.state").exists()

    /** Liga ou desliga conforme o MCP. Idempotente; chamar de qualquer thread. */
    @Synchronized fun ajustar(ctx: Context) {
        val app = ctx.applicationContext
        querRodando = McpServidor.ligado(app) && configurado(app) && binario(app).exists()
        if (querRodando && vigia?.isAlive != true) {
            vigia = Thread({ vigiar(app) }, "Tunel-vigia").apply { isDaemon = true; start() }
        } else if (!querRodando) {
            processo?.destroy()
            processo = null
        }
    }

    /** "Esquecer o Tailscale": para o túnel e apaga estado e chave (para entrar com outra conta). */
    fun esquecer(ctx: Context) {
        val app = ctx.applicationContext
        querRodando = false
        processo?.destroy(); processo = null
        pastaEstado(app).deleteRecursively()
        prefs(app).edit().clear().commit()
    }

    private fun vigiar(app: Context) {
        var espera = 10_000L
        while (querRodando) {
            val inicio = SystemClock.elapsedRealtime()
            try { rodarUmaVez(app) } catch (e: Exception) { anotarErro(app, "não iniciou: ${e.javaClass.simpleName}") }
            if (!querRodando) break
            // Ficou de pé mais de 10 min: era uma queda, não um erro de configuração. Recomeça a espera curta.
            if (SystemClock.elapsedRealtime() - inicio > 10 * 60_000L) espera = 10_000L
            Log.i(TAG, "túnel parou; religo em ${espera / 1000} s")
            try { Thread.sleep(espera) } catch (_: InterruptedException) { break }
            espera = (espera * 2).coerceAtMost(5 * 60_000L)
        }
    }

    private fun rodarUmaVez(app: Context) {
        val pasta = pastaEstado(app).apply { mkdirs() }
        val pb = ProcessBuilder(binario(app).absolutePath).directory(pasta).redirectErrorStream(true)
        val env = pb.environment()
        env["TUNEL_DIR"] = pasta.absolutePath
        env["TUNEL_NOME"] = NOME_NO
        env["TUNEL_ALVO"] = "127.0.0.1:${Config.MCP_PORTA}"
        env["SSL_CERT_DIR"] = "/system/etc/security/cacerts"
        env["HOME"] = pasta.absolutePath
        dnsDoSistema(app)?.let { env["TUNEL_DNS"] = it }
        chave(app)?.let { env["TS_AUTHKEY"] = it }   // só existe até o 1º login dar certo
        val p = pb.start()
        processo = p
        Log.i(TAG, "túnel iniciado")
        p.inputStream.bufferedReader().useLines { linhas ->
            for (l in linhas) {
                when {
                    l.startsWith("NOME ") -> {
                        val host = l.removePrefix("NOME ").trim().lowercase()
                        if (host.endsWith(".ts.net") && host.all { it.isLetterOrDigit() || it in ".-" }) {
                            prefs(app).edit().putString("nome", host).remove("erro").apply()
                            definirChave(app, null)   // login feito: a chave não é mais necessária
                            Log.i(TAG, "túnel no ar: https://$host")
                        }
                    }
                    l.startsWith("ERRO ") -> anotarErro(app, l.removePrefix("ERRO ").take(200))
                }
            }
        }
        p.waitFor()
        processo = null
    }

    private fun anotarErro(app: Context, texto: String) {
        Log.w(TAG, "túnel: $texto")
        prefs(app).edit().putString("erro", texto).apply()
    }

    /** O DNS da rede ativa (o Android não tem /etc/resolv.conf para o Go ler). Primeiro IPv4, ou null (o túnel usa 1.1.1.1). */
    private fun dnsDoSistema(app: Context): String? = try {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        cm.getLinkProperties(cm.activeNetwork)?.dnsServers?.firstOrNull { it is Inet4Address }?.hostAddress
    } catch (e: Exception) { null }

    // ---------------------------------------------------------------- chave do Tailscale (só até o 1º login)

    /** "tskey-auth-..." ou "tskey-...": letras, números e hífen, de 20 a 200 caracteres. */
    fun chaveValida(c: String?): Boolean = c != null && c.startsWith("tskey-") && c.length in 20..200 &&
        c.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }

    fun temChave(ctx: Context): Boolean = prefs(ctx).contains("chave_cifrada")

    /** Grava (cifrada) ou apaga (null). False se o formato for inválido ou o Keystore falhar. */
    fun definirChave(ctx: Context, chave: String?): Boolean {
        if (chave == null) { prefs(ctx).edit().remove("chave_cifrada").remove("chave_iv").apply(); return true }
        if (!chaveValida(chave)) return false
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, chaveDoKeystore())
            val cifrada = c.doFinal(chave.toByteArray(Charsets.US_ASCII))
            prefs(ctx).edit()
                .putString("chave_cifrada", Base64.encodeToString(cifrada, Base64.NO_WRAP))
                .putString("chave_iv", Base64.encodeToString(c.iv, Base64.NO_WRAP))
                .remove("erro")
                .commit()
        } catch (e: Exception) { Log.w(TAG, "não cifrou a chave: ${e.javaClass.simpleName}"); false }
    }

    private fun chave(ctx: Context): String? {
        val p = prefs(ctx)
        val cifrada = p.getString("chave_cifrada", null) ?: return null
        val iv = p.getString("chave_iv", null) ?: return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, chaveDoKeystore(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(c.doFinal(Base64.decode(cifrada, Base64.NO_WRAP)), Charsets.US_ASCII)
        } catch (e: Exception) { Log.w(TAG, "não decifrou a chave: ${e.javaClass.simpleName}"); null }
    }

    private fun chaveDoKeystore(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(APELIDO, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(APELIDO, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return g.generateKey()
    }
}
