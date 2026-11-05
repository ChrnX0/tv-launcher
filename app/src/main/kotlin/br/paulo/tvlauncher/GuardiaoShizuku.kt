package br.paulo.tvlauncher

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Guardião do Shizuku embutido (MARCO S3, 04/11/2026): sobe o servidor sozinho e o mantém de pé.
 *
 * O que o fork thedjchi chama de "start on boot esperando o Wi-Fi" + "watchdog":
 *  - [garantir]: se o launcher não tem binder vivo, espera o Wi-Fi e a Depuração sem fio, conecta no adbd local pelo
 *    shell próprio, confere com `id` e sobe o `shizuku_server` ([ServidorShizuku]). Tenta de novo com esperas
 *    crescentes (logo após o boot a TV fica sobrecarregada e o adbd troca de porta: medido em 04/11/2026).
 *  - [vigiar]: uma vez por processo, fica de olho em três gatilhos: o binder do servidor morreu, o Wi-Fi voltou, e um
 *    relógio a cada 10 min. Em qualquer um, chama [garantir] (que não faz nada se o servidor já estiver respondendo).
 *
 * Tudo que fala com o adb passa por UMA fila ([fila]): o gerente ADB é único (singleton) e duas threads mexendo na
 * mesma conexão derrubavam uma à outra.
 *
 * Caminho de conexão: primeiro o autoConnect da libadb (mDNS _adb-tls-connect, porta que muda a cada boot); se falhar,
 * a porta fixa 5555 do modo TCP do adb (127.0.0.1:5555), que esta TV anuncia (_adb._tcp). Ver [conectar].
 */
object GuardiaoShizuku {
    private val fila = Executors.newSingleThreadExecutor { r -> Thread(r, "GuardiaoShizuku") }
    private val trabalhando = AtomicBoolean(false)
    private var vigiando = false
    private val principal = Handler(Looper.getMainLooper())
    private const val PERIODO_MS = 10 * 60_000L
    private val ESPERAS_S = listOf(0, 10, 30, 60, 120)   // antes de cada tentativa

    private fun anotar(ctx: Context, t: String) = ServidorShizuku.anotar(ctx, "[guardiao] $t")

    /** Garante o servidor de pé. Pode ser chamado de qualquer thread; o trabalho roda na fila do guardião. */
    fun garantir(ctx: Context, motivo: String) {
        if (!Config.SHIZUKU_AUTOMATICO) return
        val app = ctx.applicationContext
        if (!Config.shizukuAtivo(app)) return                   // desligado (padrão): não sobe nem religa o servidor
        if (Shizuku.pingBinder() && !precisaCurar(app)) return  // já está tudo de pé: não toca no adb
        if (!trabalhando.compareAndSet(false, true)) return     // já tem uma tentativa em curso
        fila.execute {
            try { executar(app, motivo) } catch (e: Throwable) {
                anotar(app, "erro inesperado: ${e.javaClass.simpleName}: ${e.message}")
            } finally { trabalhando.set(false) }
        }
    }

    /**
     * Roda UM comando no nosso shell (uid 2000), na mesma fila do guardião (uma conversa com o adb por vez), e espera
     * até 40 s. Usado pelo painel no navegador para o que o Device Owner não alcança (appops). Só para comandos
     * MONTADOS PELO CÓDIGO com partes já validadas, nunca texto vindo de fora. Devolve a saída, ou null sem shell.
     */
    fun comando(ctx: Context, cmd: String): String? {
        val app = ctx.applicationContext
        return try {
            fila.submit(java.util.concurrent.Callable {
                Privilegio.religarDepuracaoSemFio(app)
                ServidorShizuku.executar(app, AdbConnectionManager.obter(app), cmd)
            }).get(40, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
            anotar(app, "comando do painel falhou: ${e.javaClass.simpleName}"); null
        }
    }

    /** Usado pelo PareamentoReceiver: pareia (se veio código) e então garante, tudo na mesma fila. */
    fun parearEGarantir(ctx: Context, codigo: String?, porta: Int) {
        val app = ctx.applicationContext
        fila.execute {
            if (codigo != null && porta > 0) {
                try { anotar(app, "pair(127.0.0.1:$porta) => ${AdbConnectionManager.obter(app).pair("127.0.0.1", porta, codigo)}") }
                catch (e: Throwable) { anotar(app, "pair FALHOU: ${e.javaClass.simpleName}: ${e.message}") }
            }
        }
        garantirMesmoComBinder(app, "PAREAR")
    }

    /** Como [garantir], mas roda o `id` mesmo se o binder já estiver vivo (o PAREAR é um teste e quer ver o id). */
    private fun garantirMesmoComBinder(app: Context, motivo: String) {
        if (!trabalhando.compareAndSet(false, true)) return
        fila.execute {
            try { executar(app, motivo) } finally { trabalhando.set(false) }
        }
    }

    private fun executar(app: Context, motivo: String) {
        anotar(app, "inicio ($motivo)")
        for ((n, espera) in ESPERAS_S.withIndex()) {
            if (espera > 0) Thread.sleep(espera * 1000L)
            if (motivo != "PAREAR" && Shizuku.pingBinder() && !precisaCurar(app)) { anotar(app, "binder ja vivo"); return }
            if (!esperarWifi(app)) { anotar(app, "tentativa ${n + 1}: sem Wi-Fi"); continue }
            Privilegio.religarDepuracaoSemFio(app)
            val m = try { AdbConnectionManager.obter(app) } catch (e: Throwable) {
                anotar(app, "gerente ADB falhou: ${e.javaClass.simpleName}: ${e.message}"); return
            }
            if (!conectar(app, m, n + 1)) continue
            val id = ServidorShizuku.executar(app, m, "id")
            if (id.isNullOrBlank()) { anotar(app, "tentativa ${n + 1}: id sem resposta"); try { m.disconnect() } catch (_: Exception) {}; continue }
            anotar(app, "shell PROPRIO diz: $id")
            autocurar(app, m)
            // Shizuku desligado (padrão desde 04/11): o PAREAR/pareamento só prova o shell; não sobe o servidor.
            if (!Config.shizukuAtivo(app)) { anotar(app, "OK ($motivo) - shell ok; servidor do Shizuku desligado nas Configuracoes"); return }
            if (ServidorShizuku.iniciar(app, m)) { anotar(app, "OK ($motivo)"); return }
        }
        anotar(app, "desisti depois de ${ESPERAS_S.size} tentativas ($motivo)")
    }

    /** true se falta algo que só o shell concede (hoje: a janela flutuante). Faz o guardião passar no adb uma vez. */
    private fun precisaCurar(app: Context) = !android.provider.Settings.canDrawOverlays(app) ||
        Diagnostico.PERMISSOES_SHELL.any { app.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }

    /**
     * Com o shell (uid 2000) na mão, o launcher se concede o que o Device Owner não alcança e antes exigia o PC:
     *  - SYSTEM_ALERT_WINDOW (janela flutuante do pareamento sem PC, ver PareamentoNaTv);
     *  - WRITE_SECURE_SETTINGS (religar acessibilidade e depuração sem fio, DNS) — caso tenha sido revogada;
     *  - GET_USAGE_STATS (ordenar apps por uso);
     *  - READ_LOGS e DUMP (tela de Diagnóstico: ler os crashes de outros apps e por que o Android os fechou).
     *  Só escreve se ainda não estiver concedido.
     */
    private fun autocurar(app: Context, m: AdbConnectionManager) {
        val p = app.packageName
        if (!android.provider.Settings.canDrawOverlays(app)) ServidorShizuku.executar(app, m, "appops set $p SYSTEM_ALERT_WINDOW allow")
        if (app.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
            ServidorShizuku.executar(app, m, "pm grant $p android.permission.WRITE_SECURE_SETTINGS")
        val uso = ServidorShizuku.executar(app, m, "appops get $p GET_USAGE_STATS") ?: ""
        if (!uso.contains("allow")) ServidorShizuku.executar(app, m, "appops set $p GET_USAGE_STATS allow")
        for (perm in Diagnostico.PERMISSOES_SHELL)
            if (app.checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                ServidorShizuku.executar(app, m, "pm grant $p $perm")
        anotar(app, "autocura: sobreposicao=${android.provider.Settings.canDrawOverlays(app)}")
    }

    /** Espera até 60 s por um Wi-Fi conectado. */
    private fun esperarWifi(app: Context): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return false
        repeat(60) {
            if (cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) return true
            Thread.sleep(1000)
        }
        return false
    }

    /**
     * Conecta no adbd do próprio aparelho. 1º: autoConnect (mDNS, TLS) — é o caminho do pareamento. 2º: 127.0.0.1:5555
     * (modo TCP do adb, porta fixa), que não depende de descobrir a porta; só funciona se o adb estiver escutando nela.
     */
    private fun conectar(app: Context, m: AdbConnectionManager, n: Int): Boolean {
        if (m.isConnected) return true
        val tls = try { m.autoConnect(app, 15_000) } catch (e: Throwable) {
            anotar(app, "tentativa $n: autoConnect falhou: ${e.javaClass.simpleName}: ${e.message}"); false
        }
        anotar(app, "tentativa $n: autoConnect (mDNS/TLS) => $tls")
        if (tls) return true
        val tcp = try { m.connect("127.0.0.1", 5555) } catch (e: Throwable) {
            anotar(app, "tentativa $n: 127.0.0.1:5555 falhou: ${e.javaClass.simpleName}: ${e.message}"); false
        }
        anotar(app, "tentativa $n: 127.0.0.1:5555 (modo TCP) => $tcp")
        return tcp
    }

    /** Liga os três gatilhos do vigia. Só registra uma vez por processo. */
    fun vigiar(ctx: Context) {
        if (!Config.SHIZUKU_AUTOMATICO || vigiando) return
        vigiando = true
        val app = ctx.applicationContext
        // 1. O servidor morreu (ex.: alguém matou o processo): sobe de novo.
        Shizuku.addBinderDeadListener { principal.postDelayed({ garantir(app, "binder morreu") }, 3000) }
        // 2. O Wi-Fi voltou: a depuração sem fio volta junto, dá para (re)subir.
        app.getSystemService(ConnectivityManager::class.java)?.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { principal.postDelayed({ garantir(app, "Wi-Fi voltou") }, 5000) }
            })
        // 3. Relógio: a cada 10 min confere (barato: se o binder responde, não faz nada).
        val relogio = object : Runnable {
            override fun run() {
                garantir(app, "periodico")
                Thread { PrivilegioDO.reforcarVpn(app) }.start()   // e a VPN fixa (AdGuard), se o túnel caiu
                Thread { Dns.voltarAoAutomaticoSeQuebrado(app) }.start()   // e o DNS fixo, se parou de responder
                Atualizador.talvezVerificar(app)   // e uma versão nova no GitHub (a cada 6 h, só com a tela apagada)
                principal.postDelayed(this, PERIODO_MS)
            }
        }
        principal.postDelayed(relogio, PERIODO_MS)
        anotar(app, "vigiando (binder, Wi-Fi, a cada ${PERIODO_MS / 60_000} min)")
    }

    /** true se a Depuração sem fio está ligada (só para diagnóstico na tela de Configurações). */
    fun depuracaoLigada(ctx: Context) = Settings.Global.getInt(ctx.contentResolver, "adb_wifi_enabled", 0) == 1
}
