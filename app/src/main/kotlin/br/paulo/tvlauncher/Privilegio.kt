package br.paulo.tvlauncher

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

/**
 * Ponte entre o launcher e o Shizuku. Com o Shizuku rodando e este app autorizado, o launcher consegue
 * executar comandos com a permissão do "shell" (a mesma do adb), direto da TV, sem o computador.
 *
 * Fluxo: o Shizuku entrega um "binder" (o canal de conversa com o serviço dele) -> se ainda não somos
 * autorizados, pedimos permissão -> o AutorizaShizukuService aprova o diálogo -> rodamos um teste (`id`).
 */
object Privilegio {
    private const val TAG = "TvLauncher.Shizuku"
    private const val CODIGO_PEDIDO = 7001
    private var iniciado = false

    // Quando o último pedido foi feito (0 = nenhum aberto). Evita empilhar diálogos, mas libera um novo pedido após 60 s,
    // para o caso de o diálogo ter sido dispensado sem resposta (aí nenhum retorno chega e o launcher ficaria mudo).
    @Volatile private var pedindoDesde = 0L

    /** Liga os ouvintes uma única vez (chamado em MainActivity.onCreate). */
    fun iniciar() {
        if (iniciado) return
        iniciado = true
        // "Sticky": se o canal já chegou antes de registrarmos, o aviso vem na hora.
        Shizuku.addBinderReceivedListenerSticky { aoReceberCanal() }
        Shizuku.addRequestPermissionResultListener { codigo, resultado ->
            pedindoDesde = 0L
            Log.i(TAG, "resposta ao pedido ($codigo): ${if (resultado == PackageManager.PERMISSION_GRANTED) "AUTORIZADO" else "negado"}")
            if (codigo == CODIGO_PEDIDO && resultado == PackageManager.PERMISSION_GRANTED) testar()
        }
    }

    /**
     * Mantém o serviço de acessibilidade (AutorizaShizukuService) ligado.
     * O Android o desliga sempre que o app leva "force-stop", e o Shizuku faz isso ao revogar um app. Como o launcher
     * é a tela inicial, ele sobe de novo no próximo HOME e se religa aqui, escrevendo na lista de serviços ativos
     * (permitido porque o app tem WRITE_SECURE_SETTINGS, dada uma vez por adb).
     * Com Config.SHIZUKU_AUTORIZAR_TUDO = false isto não faz nada.
     */
    fun religarAcessibilidade(ctx: Context) {
        if (!Config.SHIZUKU_AUTORIZAR_TUDO) return
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
                Log.i(TAG, "servico de acessibilidade estava desligado; religado")
            }
            if (Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "sem WRITE_SECURE_SETTINGS: nao consigo religar o servico de acessibilidade")
        }
    }

    /**
     * Mantém a Depuração sem fio (adb pela rede) ligada. Em 31/10/2026 ela voltou DESLIGADA depois de um reinício
     * (no de 30/10 tinha persistido; não sei dizer por que mudou), e sem ela o computador não conecta na TV.
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

    /** true quando o Shizuku está rodando E este app está autorizado. */
    fun pronto(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    private fun aoReceberCanal() {
        Log.i(TAG, "canal do Shizuku recebido (versao do servidor: ${runCatching { Shizuku.getVersion() }.getOrNull()})")
        if (pronto()) testar() else verificar()
    }

    /**
     * Pede autorização se o Shizuku está rodando e ainda não temos. Chamado ao abrir o launcher (onResume),
     * então se a autorização for revogada no app Shizuku, o pedido volta sozinho.
     */
    fun verificar() {
        try {
            if (!Shizuku.pingBinder() || pronto()) return
            if (System.currentTimeMillis() - pedindoDesde < 60_000) return
            // Se você já negou uma vez, não insistimos.
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                Log.i(TAG, "pedido negado antes; nao insisto")
                return
            }
            pedindoDesde = System.currentTimeMillis()
            Log.i(TAG, "pedindo autorizacao ao Shizuku")
            Shizuku.requestPermission(CODIGO_PEDIDO)
        } catch (e: Throwable) {
            Log.e(TAG, "falha ao verificar autorizacao", e)
        }
    }

    /** Roda um comando de shell com a permissão do Shizuku e devolve a saída. Chamar fora da thread da tela. */
    fun executar(comando: String): String {
        // Na API 13.x o newProcess ficou privado (a recomendação é um UserService, bem mais código).
        // Reflexão abre o método; se uma versão futura removê-lo, cai no catch e vemos o erro no log.
        val metodo = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        metodo.isAccessible = true
        val processo = metodo.invoke(null, arrayOf("sh", "-c", comando), null, null) as Process
        val saida = processo.inputStream.bufferedReader().readText().trim()
        processo.waitFor()
        return saida
    }

    private fun testar() {
        thread {
            try {
                Log.i(TAG, "shell via Shizuku diz: ${executar("id")}")
            } catch (e: Throwable) {
                Log.e(TAG, "falha ao executar pelo Shizuku", e)
            }
        }
    }
}