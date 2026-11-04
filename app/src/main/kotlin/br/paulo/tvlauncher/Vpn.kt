package br.paulo.tvlauncher

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Ikev2VpnProfile
import android.net.NetworkCapabilities
import android.net.VpnManager
import android.os.Build
import android.util.Log
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * VPN da AdGuard SEM o app da AdGuard: usamos as credenciais IPsec/IKEv2 que a conta gera "para roteador" e o cliente
 * IKEv2 que já vem DENTRO do Android (VpnManager, Android 11+). O launcher só cadastra o perfil; quem mantém o túnel é
 * o próprio sistema — nenhum serviço nosso fica rodando, ao contrário do app da AdGuard (motivo: ele é pesado).
 *
 * Credenciais: BuildConfig.VPN_* (vêm do local.properties, fora do git; ver app/build.gradle.kts).
 * "Sempre ativa" (religa sozinha, inclusive depois de reiniciar a TV) é feito pelo Device Owner, em [PrivilegioDO].
 */
object Vpn {
    private const val TAG = "TvLauncher.Vpn"

    /** A API do cliente IKEv2 embutido é do Android 11 (API 30). Esta TV é 14 (API 34). */
    fun suportada() = Build.VERSION.SDK_INT >= 30

    fun configurada() = BuildConfig.VPN_SERVIDOR.isNotBlank() && BuildConfig.VPN_ID_REMOTO.isNotBlank() &&
        BuildConfig.VPN_USUARIO.isNotBlank() && BuildConfig.VPN_SENHA.isNotBlank()

    /**
     * Cadastra (ou recadastra) o perfil no Android. Na primeira vez o sistema pode exigir que o usuário autorize
     * "este app criar uma VPN": aí devolve o Intent da tela de confirmação, que quem chama abre com
     * startActivityForResult. Devolve null quando já está autorizado (ou quando não dá: veja o log).
     */
    fun cadastrar(ctx: Context): Intent? {
        if (!suportada() || !configurada()) return null
        return try {
            // Builder(servidor, identidade): "identidade" aqui é a identidade IKE do SERVIDOR (verifiquei no javadoc
            // do AOSP: "@param identity the identity string to be used for IKEv2 authentication"), que é o campo
            // "ID do servidor remoto" do painel AdGuard — diferente do usuário do EAP, passado abaixo.
            // TESTE 3: as 2 primeiras tentativas (identidade = ID remoto, depois = usuário), ambas sem fixar
            // certificado, deram AuthenticationFailedException na troca EAP final (verifiquei no logcat). Agora
            // fixando o certificado raiz que o painel AdGuard oferece (res/raw/vpn_cert.pem, AAA Certificate
            // Services da Comodo) em vez de confiar nas CAs do sistema — hipótese não confirmada.
            val perfil = Ikev2VpnProfile.Builder(BuildConfig.VPN_SERVIDOR, BuildConfig.VPN_ID_REMOTO)
                .setAuthUsernamePassword(BuildConfig.VPN_USUARIO, BuildConfig.VPN_SENHA, certificadoServidor(ctx))
                .build()
            gerente(ctx).provisionVpnProfile(perfil)
        } catch (e: Exception) {
            Log.w(TAG, "cadastrar falhou: $e"); null
        }
    }

    /** Liga o túnel com o perfil cadastrado. Devolve false se o Android recusar (ex.: perfil ainda não autorizado). */
    fun ligar(ctx: Context): Boolean = try {
        if (!suportada()) false
        else {
            // startProvisionedVpnProfileSession é a forma nova (Android 13+); antes disso, startProvisionedVpnProfile.
            if (Build.VERSION.SDK_INT >= 33) gerente(ctx).startProvisionedVpnProfileSession()
            else @Suppress("DEPRECATION") gerente(ctx).startProvisionedVpnProfile()
            true
        }
    } catch (e: Exception) {
        Log.w(TAG, "ligar falhou: $e"); false
    }

    fun desligar(ctx: Context) {
        try { if (suportada()) gerente(ctx).stopProvisionedVpnProfile() } catch (e: Exception) { Log.w(TAG, "desligar falhou: $e") }
    }

    /** Há uma rede VPN de pé agora? (qualquer VPN — na prática, a nossa) */
    fun ativa(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    } catch (e: Exception) { false }

    /** Lê o certificado raiz embutido (res/raw/vpn_cert.pem) — o mesmo "comodo.crt" que o painel AdGuard oferece. */
    private fun certificadoServidor(ctx: Context): X509Certificate =
        ctx.resources.openRawResource(R.raw.vpn_cert).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }

    private fun gerente(ctx: Context) = ctx.getSystemService(VpnManager::class.java)
}
