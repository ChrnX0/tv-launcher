package br.paulo.tvlauncher

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import kotlin.concurrent.thread

/**
 * Shell próprio — a base para o launcher iniciar seu PRÓPRIO servidor shell (via depuração wifi), sem o app Shizuku.
 *
 * Estado honesto: isto é o MARCO 1, as duas peças determinísticas que não dependem de biblioteca nem de root:
 *   1. [chave] — um par de chaves RSA-2048 do app, guardado nos arquivos privados. É a credencial que o adb vai confiar
 *      (hoje a chave do adb só entra em /data/misc/adb/adb_keys, que é root-only; por isso o MARCO 2 usa o PAREAMENTO
 *      da depuração sem fio, que registra a chave sem root).
 *   2. [descobrir] — acha a porta do adb na rede por mDNS (_adb-tls-connect._tcp), a mesma que o `adb mdns services` lista.
 *      A porta muda a cada boot; por isso é descoberta, não fixada.
 *
 * O MARCO 2 (handshake TLS + pareamento SPAKE2 + rodar o servidor) é o cliente ADB embutido — precisa de teste com reboot.
 */
object ShellProprio {
    private const val TAG = "TvLauncher.ShellProprio"
    private const val CONEXAO = "_adb-tls-connect._tcp"
    private const val PAREAMENTO = "_adb-tls-pairing._tcp"

    /**
     * Par de chaves RSA-2048 do app (gera e guarda na 1ª vez; depois relê). É a credencial que o adbd confia depois do
     * pareamento — perder ou trocar = parear de novo.
     *
     * @Synchronized + gravação atômica (revisão de 04/11/2026): antes, duas threads na 1ª execução (diagnóstico,
     * pareamento, Configurações) podiam gerar duas chaves ao mesmo tempo e gravar a privada de uma com a pública da
     * outra — ou o processo morrer entre as duas gravações. Agora grava em .tmp e renomeia.
     */
    @Synchronized
    fun chave(ctx: Context): KeyPair {
        val fPriv = File(ctx.filesDir, "adb_key.pk8")
        val fPub = File(ctx.filesDir, "adb_key.x509")
        val kf = KeyFactory.getInstance("RSA")
        if (fPriv.exists() && fPub.exists()) {
            val priv = kf.generatePrivate(PKCS8EncodedKeySpec(fPriv.readBytes()))
            val pub = kf.generatePublic(X509EncodedKeySpec(fPub.readBytes()))
            return KeyPair(pub, priv)
        }
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        fun gravar(destino: File, bytes: ByteArray) {
            val tmp = File(destino.path + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(destino)) { destino.writeBytes(bytes); tmp.delete() }
        }
        gravar(fPub, kp.public.encoded)
        gravar(fPriv, kp.private.encoded)   // a privada por último: só "existe a chave" quando as duas estão lá
        Log.i(TAG, "par de chaves ADB gerado e guardado em ${ctx.filesDir}")
        return kp
    }

    /** Impressão digital curta da chave pública, só para conferir no log que ela é estável entre boots. */
    fun impressaoDigital(ctx: Context): String {
        val h = MessageDigest.getInstance("SHA-256").digest(chave(ctx).public.encoded)
        return h.take(8).joinToString(":") { "%02x".format(it) }
    }

    /** Descobre por mDNS onde o adb está escutando (conexão e pareamento). Loga host:porta. Para sozinho em 6 s. */
    fun descobrir(ctx: Context) {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: run {
            Log.w(TAG, "sem NsdManager"); return
        }
        for (tipo in listOf(CONEXAO, PAREAMENTO)) {
            val ouvinte = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(t: String) {}
                override fun onServiceFound(info: NsdServiceInfo) {
                    Log.i(TAG, "mDNS achou ($tipo): ${info.serviceName}")
                    try {
                        nsd.resolveService(info, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(i: NsdServiceInfo, erro: Int) {}
                            override fun onServiceResolved(i: NsdServiceInfo) {
                                Log.i(TAG, "ADB $tipo em ${i.host?.hostAddress}:${i.port}")
                            }
                        })
                    } catch (e: Exception) { Log.w(TAG, "resolve falhou: $e") }
                }
                override fun onServiceLost(info: NsdServiceInfo) {}
                override fun onDiscoveryStopped(t: String) {}
                override fun onStartDiscoveryFailed(t: String, erro: Int) { Log.w(TAG, "descoberta falhou ($tipo): $erro") }
                override fun onStopDiscoveryFailed(t: String, erro: Int) {}
            }
            try {
                nsd.discoverServices(tipo, NsdManager.PROTOCOL_DNS_SD, ouvinte)
                Handler(Looper.getMainLooper()).postDelayed({ try { nsd.stopServiceDiscovery(ouvinte) } catch (_: Exception) {} }, 6000)
            } catch (e: Exception) { Log.w(TAG, "discoverServices $tipo: $e") }
        }
    }

    /** Diagnóstico do MARCO 1: prova no log que a chave é estável e que a porta do adb é descoberta. */
    fun diagnosticar(ctx: Context) {
        thread(name = "ShellProprio") {
            Log.i(TAG, "chave ADB (fingerprint): ${impressaoDigital(ctx)}")
            Handler(Looper.getMainLooper()).post { descobrir(ctx) }
        }
    }
}
