package br.paulo.tvlauncher

import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

/**
 * Pequenas manutenções que o launcher faz sozinho, sem o computador por perto:
 * manter o serviço de acessibilidade (AutorizaShizukuService, que hoje só trata teclas do controle — ver aquele
 * arquivo) ligado, e manter a Depuração sem fio ligada.
 *
 * Até 03/11/2026 este arquivo também era a ponte com o app Shizuku (shell via UID shell); foi removida quando o
 * Shizuku foi desinstalado — o que ele cobria e o Device Owner não alcança (pm/cmd arbitrários, ler dados de
 * outros apps) ficou sem substituto por enquanto. Ver PrivilegioDO.kt para o que passou a usar Device Owner.
 */
object Privilegio {
    private var vigiando = false   // os ouvintes da Depuração sem fio são registrados uma vez por processo

    /**
     * Mantém o serviço de acessibilidade (AutorizaShizukuService) ligado.
     * O Android o desliga sempre que o app leva "force-stop". Como o launcher é a tela inicial, ele sobe de novo
     * no próximo HOME e se religa aqui, escrevendo na lista de serviços ativos
     * (permitido porque o app tem WRITE_SECURE_SETTINGS, dada uma vez por adb).
     * Com Config.SERVICO_TECLAS_SEMPRE_LIGADO = false isto não faz nada.
     */
    fun religarAcessibilidade(ctx: Context) {
        if (!Config.SERVICO_TECLAS_SEMPRE_LIGADO) return
        try {
            val cr = ctx.contentResolver
            val meu = ComponentName(ctx, AutorizaShizukuService::class.java)
            val atual = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            // A lista é "pacote/Classe:pacote/Classe". Comparo como ComponentName porque a forma curta (".Classe") também vale.
            val lista = atual.split(':').filter { it.isNotBlank() }
            if (lista.none { ComponentName.unflattenFromString(it) == meu }) {
                Settings.Secure.putString(
                    cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (lista + meu.flattenToString()).joinToString(":")
                )
                Log.i("TvLauncher.Acessibilidade", "servico de acessibilidade estava desligado; religado")
            }
            if (Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }
        } catch (e: SecurityException) {
            Log.w("TvLauncher.Acessibilidade", "sem WRITE_SECURE_SETTINGS: nao consigo religar o servico de acessibilidade")
        }
    }

    /**
     * Mantém a Depuração sem fio (adb pela rede) ligada. Em 31/10/2026 ela voltou DESLIGADA depois de um reinício
     * (o de 30/10 tinha persistido; não sei dizer por que mudou), e sem ela o computador não conecta na TV.
     * Ligar é escrever 1 em "adb_wifi_enabled": é o mesmo que o interruptor das Opções do desenvolvedor faz, e o
     * Android sobe o serviço ao notar a mudança. Escrever em Settings.Global exige WRITE_SECURE_SETTINGS (dada uma vez por adb).
     * Roda no BOOT_COMPLETED (BootReceiver) e a cada vez que o launcher abre. Se já está ligada, não escreve nada.
     * Com Config.DEPURACAO_SEM_FIO_SEMPRE_LIGADA = false isto não faz nada.
     */
    fun religarDepuracaoSemFio(ctx: Context) {
        if (!Config.DEPURACAO_SEM_FIO_SEMPRE_LIGADA) return
        try {
            val cr = ctx.contentResolver
            // O nome é escrito por extenso porque Settings.Global.ADB_WIFI_ENABLED é @hide (não aparece no SDK público).
            if (Settings.Global.getInt(cr, "adb_wifi_enabled", 0) != 1) {
                Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
                Log.i("TvLauncher.Adb", "Depuracao sem fio estava desligada; religada")
            }
        } catch (e: SecurityException) {
            Log.w("TvLauncher.Adb", "sem WRITE_SECURE_SETTINGS: nao consigo religar a Depuracao sem fio")
        }
    }

    /**
     * Olhar uma vez não basta: no boot de 31/10 o launcher abriu às 15:51:16.1 com a depuração ligada e, 0,6 s depois,
     * o Android a desligou ("Network disconnected. Disabling adbwifi": o Wi-Fi oscila durante o boot).
     * Então ficamos de olho em duas coisas enquanto o processo do launcher viver (como é a tela inicial, ele vive quase sempre):
     *  1. o ajuste "adb_wifi_enabled" mudou para 0 -> religa, se houver Wi-Fi;
     *  2. o Wi-Fi conectou -> religa 3 s depois (dá tempo da rede assentar; sem Wi-Fi o Android recusa ligar).
     * Chamado no onCreate da tela e no BootReceiver; só registra uma vez.
     */
    fun vigiarDepuracaoSemFio(ctx: Context) {
        if (!Config.DEPURACAO_SEM_FIO_SEMPRE_LIGADA || vigiando) return
        vigiando = true
        val app = ctx.applicationContext   // o contexto do app inteiro, que não morre com uma tela
        val principal = Handler(Looper.getMainLooper())
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val temWifi = {
            cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        app.contentResolver.registerContentObserver(
            Settings.Global.getUriFor("adb_wifi_enabled"), false,
            object : ContentObserver(principal) {
                override fun onChange(selfChange: Boolean) {
                    if (temWifi()) principal.postDelayed({ religarDepuracaoSemFio(app) }, 3000)
                }
            }
        )
        cm.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    principal.postDelayed({ religarDepuracaoSemFio(app) }, 3000)
                }
            }
        )
        Log.i("TvLauncher.Adb", "vigiando a Depuracao sem fio")
    }
}
