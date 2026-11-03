package br.paulo.tvlauncher

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.security.KeyPair
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.Date
import java.util.Random
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.PrivateKeyUsageExtension
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo

/**
 * Gerente de conexão ADB do launcher (MARCO 2 do shell próprio): implementa [AbsAdbConnectionManager] da libadb-android
 * (Apache-2.0), usando a MESMA chave que [ShellProprio] gera e guarda. É com ele que o app pareia e conecta no adbd
 * do proprio aparelho (depuração sem fio), abrindo um shell — sem o app Shizuku.
 *
 * O certificado X509 e gerado a partir da nossa chave (classes sun.security da lib sun-security-android).
 */
class AdbConnectionManager private constructor(ctx: Context) : AbsAdbConnectionManager() {
    private val chavePrivada: PrivateKey
    private val certificado: Certificate

    init {
        // A versão da API do adbd que vamos falar: a do proprio aparelho.
        api = Build.VERSION.SDK_INT
        val par = ShellProprio.chave(ctx)
        chavePrivada = par.private
        certificado = gerarCertificado(par)
    }

    override fun getPrivateKey(): PrivateKey = chavePrivada
    override fun getCertificate(): Certificate = certificado
    override fun getDeviceName(): String = "tv-launcher"

    companion object {
        @Volatile private var instancia: AdbConnectionManager? = null
        fun obter(ctx: Context): AdbConnectionManager =
            instancia ?: synchronized(this) {
                instancia ?: AdbConnectionManager(ctx.applicationContext).also { instancia = it }
            }

        /** Gera um X509 auto-assinado a partir do par de chaves (porta do exemplo da libadb-android). */
        private fun gerarCertificado(par: KeyPair): Certificate {
            val algoritmo = "SHA512withRSA"
            val agora = Date()
            val expira = Date(System.currentTimeMillis() + 10L * 365 * 24 * 60 * 60 * 1000)   // 10 anos
            val ext = CertificateExtensions()
            ext.set("SubjectKeyIdentifier",
                SubjectKeyIdentifierExtension(KeyIdentifier(par.public).identifier))
            val nome = X500Name("CN=tv-launcher")
            ext.set("PrivateKeyUsage", PrivateKeyUsageExtension(agora, expira))
            val info = X509CertInfo()
            info.set("version", CertificateVersion(2))
            info.set("serialNumber", CertificateSerialNumber(Random().nextInt() and Int.MAX_VALUE))
            info.set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(algoritmo)))
            info.set("subject", CertificateSubjectName(nome))
            info.set("key", CertificateX509Key(par.public))
            info.set("validity", CertificateValidity(agora, expira))
            info.set("issuer", CertificateIssuerName(nome))
            info.set("extensions", ext)
            val cert = X509CertImpl(info)
            cert.sign(par.private, algoritmo)
            return cert
        }
    }
}
