package br.paulo.tvlauncher

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

/**
 * Shizuku SOB DEMANDA (04/11/2026, pedido do Paulo: "custo zero parado e sempre disponível").
 *
 * Um app cliente sem o servidor rodando não avisa ninguém (o ShizukuProvider é dele; o servidor é quem entrega o binder
 * ao subir: ShizukuService.sendBinderToClient, para todo pacote que pede API_V23). Então o gatilho é nosso:
 *  - o serviço de acessibilidade vê cada tela que abre (pacote) -> [janelaApareceu];
 *  - a nossa HOME, ao abrir um app pelo card -> [vaiAbrir] (começa ~1 s antes).
 * Se o pacote é cliente (pede moe.shizuku.manager.permission.API_V23), marca "cliente ativo" e chama o
 * GuardiaoShizuku.garantir. O servidor, ao subir, entrega o binder ao app (e o BinderSender reenvia quando ele volta
 * para a frente). Depois de [OCIOSO_MS] sem nenhum cliente na frente, o servidor é encerrado (Shizuku.exit()).
 *
 * Limite honesto: um cliente que trabalha em SEGUNDO PLANO por muito tempo perde o servidor depois de 5 min. Para esse
 * caso existe o modo SEMPRE (Configurações > Shizuku do launcher).
 */
object ShizukuSobDemanda {
    private const val PERMISSAO = "moe.shizuku.manager.permission.API_V23"
    const val OCIOSO_MS = 5 * 60_000L

    private val principal = Handler(Looper.getMainLooper())
    private val fila = Executors.newSingleThreadExecutor { r -> Thread(r, "ShizukuSobDemanda") }

    @Volatile private var clientes: Set<String> = emptySet()
    @Volatile private var sequencia = -1          // PackageManager.getChangedPackages: só recalcula se algo mudou
    @Volatile private var ultimoCliente = 0L      // elapsedRealtime da última vez que um cliente esteve na frente
    @Volatile private var pacoteNaFrente: String? = null
    private var desligarAgendado: Runnable? = null

    /** true se um app cliente esteve na frente há menos de [OCIOSO_MS] (ou está na frente agora). */
    fun clienteAtivo(): Boolean =
        pacoteNaFrente in clientes || (ultimoCliente > 0 && SystemClock.elapsedRealtime() - ultimoCliente < OCIOSO_MS)

    /** Chamado pelo serviço de acessibilidade a cada tela que abre. */
    fun janelaApareceu(ctx: Context, pacote: CharSequence?) {
        val p = pacote?.toString() ?: return
        if (p == "android" || p == "com.android.systemui") return   // diálogos e barras: não mudam quem está na frente
        pacoteNaFrente = p
        if (Config.modoShizuku(ctx) != Config.ModoShizuku.SOB_DEMANDA) return
        val app = ctx.applicationContext
        fila.execute { if (ehCliente(app, p)) principal.post { clienteNaFrente(app, p, "abriu") } }
    }

    /** Chamado pela HOME um instante antes de abrir um app: se for cliente, o servidor já começa a subir. */
    fun vaiAbrir(ctx: Context, pacote: String) {
        if (Config.modoShizuku(ctx) != Config.ModoShizuku.SOB_DEMANDA) return
        val app = ctx.applicationContext
        fila.execute { if (ehCliente(app, pacote)) principal.post { clienteNaFrente(app, pacote, "HOME abriu") } }
    }

    private fun clienteNaFrente(app: Context, pacote: String, como: String) {
        ultimoCliente = SystemClock.elapsedRealtime()
        if (!Shizuku.pingBinder()) ServidorShizuku.anotar(app, "[sob demanda] $pacote $como: subindo o servidor")
        GuardiaoShizuku.garantir(app, "sob demanda: $pacote")
        agendarDesligar(app)
    }

    private fun agendarDesligar(app: Context) {
        desligarAgendado?.let { principal.removeCallbacks(it) }
        desligarAgendado = Runnable { talvezDesligar(app) }.also { principal.postDelayed(it, OCIOSO_MS) }
    }

    private fun talvezDesligar(app: Context) {
        desligarAgendado = null
        if (Config.modoShizuku(app) != Config.ModoShizuku.SOB_DEMANDA) return
        if (pacoteNaFrente in clientes) {       // o cliente continua na frente (ficou parado numa tela só): espera mais
            ultimoCliente = SystemClock.elapsedRealtime(); agendarDesligar(app); return
        }
        val parado = SystemClock.elapsedRealtime() - ultimoCliente
        if (parado < OCIOSO_MS) { principal.postDelayed({ talvezDesligar(app) }, OCIOSO_MS - parado); return }
        ultimoCliente = 0L                      // antes do exit: o vigia (binder morreu) não pode religar
        fila.execute {
            try {
                if (Shizuku.pingBinder()) {
                    Shizuku.exit()
                    ServidorShizuku.anotar(app, "[sob demanda] ${OCIOSO_MS / 60_000} min sem cliente na frente: servidor encerrado")
                }
            } catch (_: Throwable) {}
        }
    }

    /** O pacote pede a permissão do Shizuku? Lista em cache; só recalcula quando algum app foi instalado/atualizado/removido. */
    private fun ehCliente(app: Context, pacote: String): Boolean {
        val pm = app.packageManager
        val mudou = sequencia < 0 || pm.getChangedPackages(sequencia) != null
        if (mudou) {
            sequencia = pm.getChangedPackages(0)?.sequenceNumber ?: 0
            clientes = try {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
                    .filter { it.packageName != app.packageName && it.requestedPermissions?.contains(PERMISSAO) == true }
                    .map { it.packageName }.toSet()
            } catch (e: Exception) { emptySet() }
            ServidorShizuku.anotar(app, "[sob demanda] apps clientes: ${clientes.ifEmpty { setOf("nenhum") }.joinToString()}")
        }
        return pacote in clientes
    }
}
