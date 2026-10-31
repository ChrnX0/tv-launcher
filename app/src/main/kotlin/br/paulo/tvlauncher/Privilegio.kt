package br.paulo.tvlauncher

import android.content.pm.PackageManager
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