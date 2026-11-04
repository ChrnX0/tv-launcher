package br.paulo.tvlauncher

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.DropBoxManager
import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Diagnóstico de 1 botão" (04/11/2026): responde de uma vez "por que o app X parou?" sem PC nem logcat.
 * Cada função faz UMA verificação e devolve um [Item] pronto para a tela (DiagnosticoActivity). Todas fazem rede ou
 * leem o sistema: chamar FORA da thread da tela.
 *
 * Duas fontes para "o que aconteceu com o app":
 *  - DropBox do Android (DropBoxManager): guarda o texto de cada crash ("data_app_crash") e travada ("data_app_anr")
 *    de qualquer app, com a pilha do erro. Ler entradas de outros apps exige READ_LOGS.
 *  - ApplicationExitInfo (Android 11+): o motivo de cada vez que o Android encerrou o processo de um app — inclusive
 *    "falta de memória", que NÃO gera crash e é a causa mais comum de app fechando sozinho num Chromecast (2 GB).
 *    Consultar outro pacote exige DUMP.
 * READ_LOGS e DUMP não aparecem em tela de permissão: o launcher se concede pelo próprio shell (GuardiaoShizuku).
 */
object Diagnostico {
    /** Concedidas pelo shell em GuardiaoShizuku.autocurar. */
    val PERMISSOES_SHELL = listOf("android.permission.READ_LOGS", "android.permission.DUMP")

    /** Quanto para trás olhar crashes e encerramentos. */
    private const val JANELA_MS = 7L * 24 * 60 * 60 * 1000

    /** [nivel]: 0 = ok, 1 = atenção, 2 = falha. */
    class Item(val titulo: String, val detalhe: String, val nivel: Int)

    // ---------------------------------------------------------------- rede

    /** Wi-Fi: conectado, nome, sinal e latência (reaproveita o medidor do chip de rede, Rede.medir). */
    fun wifi(ctx: Context): Item {
        val r = Rede.medir(ctx)
        if (!r.conectado) return Item("Wi-Fi", "desconectado: a TV não está em nenhuma rede", 2)
        val latencia = if (r.ms >= 0) "${r.ms} ms até a internet" else "sem resposta da internet"
        return Item("Wi-Fi", "${r.nome} · sinal ${r.dbm} dBm · ${r.mbps} Mbps · $latencia", r.nivel)
    }

    /**
     * Internet de verdade: baixa a página de teste do Google (responde 204, vazia) pela rede ativa — a mesma que os
     * apps usam (com VPN ligada, passa pela VPN). Também diz se o Android "validou" o Wi-Fi.
     */
    fun internet(ctx: Context): Item {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val wifi = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        val validado = wifi != null && cm.getNetworkCapabilities(wifi)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val inicio = SystemClock.elapsedRealtime()
        val codigo = try {
            val c = URL("https://connectivitycheck.gstatic.com/generate_204").openConnection() as HttpURLConnection
            c.connectTimeout = 5000; c.readTimeout = 5000; c.useCaches = false
            try { c.responseCode } finally { c.disconnect() }
        } catch (e: Exception) { -1 }
        val ms = SystemClock.elapsedRealtime() - inicio
        val andro = if (validado) "o Android validou o Wi-Fi" else "o Android NÃO validou o Wi-Fi"
        return when (codigo) {
            204 -> Item("Internet", "funcionando (teste em $ms ms) · $andro", if (validado) 0 else 1)
            -1 -> Item("Internet", "o teste não chegou ao Google · $andro", 2)
            else -> Item("Internet", "resposta estranha ($codigo): rede pode estar pedindo login · $andro", 2)
        }
    }

    /** DNS: o que está configurado, se o DNS sobre TLS está ativo e se um nome resolve de fato (até ~9 s). */
    fun dns(ctx: Context): Item {
        val rotulo = Dns.rotulo(ctx).removePrefix("DNS: ")
        val e = Dns.atual(ctx)
        val tls = Dns.situacaoDaRede(ctx)
        val ms = Dns.testarResolucao(ctx)
        val caiu = Dns.servidorQueCaiu(ctx)
        val partes = mutableListOf(rotulo)
        if (e.modo == Dns.Modo.HOST) partes += if (tls.tlsAtivo) "criptografado ativo" else "criptografado NÃO ativo"
        partes += if (ms != null) "resolve nomes ($ms ms)" else "NÃO resolve nomes"
        if (caiu != null) partes += "$caiu caiu antes e voltei para o automático"
        val nivel = when {
            ms == null -> 2
            e.modo == Dns.Modo.HOST && !tls.tlsAtivo -> 1
            else -> 0
        }
        return Item("DNS", partes.joinToString(" · "), nivel)
    }

    /** VPN: qual app está fixado como "sempre ativa" e se o túnel está de pé (e validado). */
    fun vpn(ctx: Context): Item {
        val fixada = PrivilegioDO.vpnFixada(ctx)
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val tunel = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
        val validada = tunel != null && cm.getNetworkCapabilities(tunel)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val nomeApp = fixada?.let { rotuloDe(ctx, it) } ?: "nenhum app"
        return when {
            fixada == null && tunel == null -> Item("VPN", "nenhuma VPN fixada nem ligada", 0)
            tunel == null -> Item("VPN", "$nomeApp fixada, mas DESCONECTADA (a internet segue sem VPN)", 1)
            !validada -> Item("VPN", "$nomeApp conectada, mas o Android não validou o túnel", 1)
            else -> Item("VPN", "$nomeApp conectada e funcionando", 0)
        }
    }

    /** Uma frase com o culpado mais provável, olhando os itens na ordem em que um problema causa o outro. */
    fun veredito(wifi: Item, internet: Item, dns: Item, vpn: Item): Item = when {
        wifi.nivel == 2 && wifi.detalhe.startsWith("desconectado") -> Item("Resumo", "a TV está sem Wi-Fi", 2)
        internet.nivel == 2 && dns.nivel == 2 -> Item("Resumo",
            if (dns.detalhe.contains("caiu")) "o DNS não responde: a internet parece fora" else "a internet não está chegando (roteador ou operadora)", 2)
        dns.nivel == 2 -> Item("Resumo", "o DNS não resolve nomes: os apps não acham os servidores", 2)
        internet.nivel == 2 -> Item("Resumo", "o DNS funciona, mas a internet não responde", 2)
        vpn.nivel == 1 -> Item("Resumo", "rede ok; só a VPN está fora (os apps funcionam sem ela)", 1)
        wifi.nivel >= 1 || internet.nivel >= 1 || dns.nivel >= 1 -> Item("Resumo", "rede funcionando, com pontos de atenção abaixo", 1)
        else -> Item("Resumo", "rede ok. Se um app parou, veja o que aconteceu com ele abaixo", 0)
    }

    // ---------------------------------------------------------------- apps

    /** Um acontecimento com um app: crash, travada (ANR) ou encerramento pelo Android. [texto] = detalhe completo. */
    class Ocorrencia(val pacote: String, val quando: Long, val tipo: String, val resumo: String, val texto: String, val grave: Boolean)

    fun podeLerCrashes(ctx: Context) = PERMISSOES_SHELL.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Apps que aparecem no menu da TV (pacote -> nome), em ordem alfabética. */
    fun appsDaTv(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.distinct()
            .filter { it != ctx.packageName }
            .map { it to rotuloDe(ctx, it) }
            .sortedBy { it.second.lowercase() }
    }

    /** Todas as ocorrências dos últimos 7 dias, das duas fontes, por pacote, da mais nova para a mais velha. */
    fun ocorrencias(ctx: Context, pacotes: Collection<String>): Map<String, List<Ocorrencia>> {
        val desde = System.currentTimeMillis() - JANELA_MS
        val tudo = (doDropBox(ctx, desde) + pacotes.flatMap { encerramentos(ctx, it, desde) })
        return tudo.groupBy { it.pacote }.mapValues { (_, l) -> l.sortedByDescending { it.quando } }
    }

    private val ETIQUETAS = mapOf(
        "data_app_crash" to "travou com erro", "system_app_crash" to "travou com erro",
        "data_app_anr" to "parou de responder (ANR)", "system_app_anr" to "parou de responder (ANR)",
        "data_app_native_crash" to "travou com erro nativo", "system_app_native_crash" to "travou com erro nativo",
    )

    /** Lê crashes e ANRs do DropBox. Sem READ_LOGS o Android devolve só os do próprio launcher (ou nada). */
    private fun doDropBox(ctx: Context, desde: Long): List<Ocorrencia> {
        val db = ctx.getSystemService(DropBoxManager::class.java) ?: return emptyList()
        val saida = mutableListOf<Ocorrencia>()
        for ((etiqueta, tipo) in ETIQUETAS) {
            var t = desde
            var voltas = 0
            while (voltas++ < 300) {
                val e = try { db.getNextEntry(etiqueta, t) } catch (ex: SecurityException) { null } ?: break
                t = e.timeMillis
                val texto = try { e.getText(16 * 1024) } catch (ex: Exception) { null } finally { e.close() }
                texto?.let { lerEntrada(it) }?.let { (pacote, resumo) ->
                    saida += Ocorrencia(pacote, t, tipo, resumo, texto, grave = true)
                }
            }
        }
        return saida
    }

    /**
     * Do texto de uma entrada do DropBox tira (pacote, resumo). O cabeçalho tem linhas "Chave: valor"
     * (ex.: "Process: com.app:remote", "Subject: Input dispatching timed out") e, depois de uma linha em branco, a pilha
     * do erro; a 1ª linha da pilha é o erro em si ("java.lang.IllegalStateException: ..."). Público para teste.
     */
    fun lerEntrada(texto: String): Pair<String, String>? {
        val linhas = texto.lines()
        val processo = linhas.firstOrNull { it.startsWith("Process: ") }?.removePrefix("Process: ")?.trim() ?: return null
        val pacote = linhas.firstOrNull { it.startsWith("Package: ") }?.removePrefix("Package: ")?.substringBefore(' ')?.trim()
            ?: processo.substringBefore(':')
        val assunto = linhas.firstOrNull { it.startsWith("Subject: ") }?.removePrefix("Subject: ")?.trim()
        val fimCabecalho = linhas.indexOfFirst { it.isBlank() }
        val erro = if (fimCabecalho >= 0) linhas.drop(fimCabecalho + 1).firstOrNull { it.isNotBlank() }?.trim() else null
        val resumo = (assunto ?: erro ?: "sem detalhe").take(160)
        return pacote to resumo
    }

    /** Motivos de encerramento do Android (Android 11+). Sem DUMP só funciona para o próprio launcher. */
    private fun encerramentos(ctx: Context, pacote: String, desde: Long): List<Ocorrencia> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        val am = ctx.getSystemService(ActivityManager::class.java)
        val lista = try { am.getHistoricalProcessExitReasons(pacote, 0, 10) } catch (e: Exception) { return emptyList() }
        return lista.filter { it.timestamp >= desde }.mapNotNull { i ->
            val (tipo, grave) = motivo(i.reason) ?: return@mapNotNull null
            val memoria = if (i.pss > 0) " · usava ${i.pss / 1024} MB" else ""
            val desc = i.description?.takeIf { it.isNotBlank() }
            val texto = "Motivo: $tipo\nProcesso: ${i.processName}$memoria\nImportância: ${importancia(i.importance)}" +
                (desc?.let { "\nDetalhe do Android: $it" } ?: "")
            Ocorrencia(pacote, i.timestamp, tipo, (desc ?: tipo).take(160) + memoria, texto, grave)
        }
    }

    /** Motivo em português, e se é "problema" (grave) ou só informativo. null = normal (o usuário fechou etc.): esconde. */
    private fun motivo(r: Int): Pair<String, Boolean>? = when (r) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> "fechado por falta de memória" to true
        ApplicationExitInfo.REASON_CRASH -> "travou com erro" to true
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "travou com erro nativo" to true
        ApplicationExitInfo.REASON_ANR -> "parou de responder (ANR)" to true
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "não conseguiu iniciar" to true
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "fechado por usar recurso demais" to true
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "fechado porque um app do qual depende morreu" to true
        ApplicationExitInfo.REASON_SIGNALED -> "encerrado pelo sistema (sinal)" to false
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "reiniciado por mudança de permissão" to false
        else -> null   // EXIT_SELF, USER_REQUESTED, USER_STOPPED, OTHER, FREEZER etc.: fechamento normal
    }

    private fun importancia(i: Int) = when {
        i <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "estava na tela"
        i <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visível"
        i <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "rodando por trás"
        else -> "em segundo plano (cache)"
    }

    fun rotuloDe(ctx: Context, pacote: String): String = try {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pacote, 0)).toString()
    } catch (e: Exception) { pacote }
}
