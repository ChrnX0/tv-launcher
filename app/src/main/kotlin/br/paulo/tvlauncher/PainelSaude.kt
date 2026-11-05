package br.paulo.tvlauncher

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Aba "Saúde" do painel no navegador (05/11/2026, fila longa do brainstorm): como a TV está por dentro, em números,
 * e um botão "liberar memória". Útil num Chromecast com ~2 GB de RAM e swap quase cheio.
 *
 * De onde vem cada número:
 *  - RAM livre/total e "pouca memória": ActivityManager.getMemoryInfo (API pública, sem permissão);
 *  - swap: /proc/meminfo (o arquivo é legível por qualquer app);
 *  - armazenamento: StatFs da partição de dados (/data);
 *  - temperatura: o arquivo temp de cada /sys/class/thermal/thermal_zoneN. O SELinux costuma barrar app comum; aí pedimos ao nosso shell
 *    (uid 2000), com um comando fixo. Se nenhum ler, fica só o "estado térmico" do PowerManager (nenhum/leve/.../crítico);
 *  - quem mais segura memória: `dumpsys meminfo` pelo shell (o app sozinho só vê o próprio processo).
 *
 * "Liberar memória" = `am kill-all` pelo shell: o Android encerra só os processos em SEGUNDO PLANO que pode encerrar
 * com segurança (o app da frente, serviços de sistema e o launcher ficam). É o mesmo que o Android faz sozinho quando
 * falta RAM, só que antes. Não apaga dado nenhum; o app fechado abre de novo do zero.
 */
object PainelSaude {
    private const val CMD_TEMPERATURA = "for z in /sys/class/thermal/thermal_zone*; do echo \"\$(cat \$z/type 2>/dev/null) \$(cat \$z/temp 2>/dev/null)\"; done"

    fun estado(ctx: Context, comShell: Boolean): JSONObject {
        val o = JSONObject()
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        o.put("ramLivre", mi.availMem).put("ramTotal", mi.totalMem).put("poucaMemoria", mi.lowMemory).put("limite", mi.threshold)
        try {
            val meminfo = lerMeminfo(File("/proc/meminfo").readText())
            meminfo["SwapTotal"]?.let { o.put("swapTotal", it) }
            meminfo["SwapFree"]?.let { o.put("swapLivre", it) }
        } catch (_: Exception) {}
        try {
            val s = StatFs(Environment.getDataDirectory().path)
            o.put("discoLivre", s.availableBytes).put("discoTotal", s.totalBytes)
        } catch (_: Exception) {}
        o.put("ligadaHa", SystemClock.elapsedRealtime())
        try { o.put("termico", rotuloTermico(ctx.getSystemService(PowerManager::class.java).currentThermalStatus)) } catch (_: Exception) {}

        var zonas = lerZonasDireto()
        if (zonas.isEmpty() && comShell) zonas = GuardiaoShizuku.comando(ctx, CMD_TEMPERATURA)?.let { lerZonas(it) } ?: emptyList()
        if (zonas.isNotEmpty()) o.put("temperaturas", JSONArray().apply { for ((nome, c) in zonas) put(JSONObject().put("nome", nome).put("celsius", c)) })

        if (comShell) {
            val saida = GuardiaoShizuku.comando(ctx, "dumpsys meminfo")
            if (saida == null) o.put("semShell", true)
            else o.put("apps", JSONArray().apply {
                val pm = ctx.packageManager
                for (p in maioresProcessos(saida, 12)) {
                    val pacote = p.nome.substringBefore(':')
                    val rotulo = try { pm.getApplicationLabel(pm.getApplicationInfo(pacote, 0)).toString() } catch (_: Exception) { null }
                    put(JSONObject().put("processo", p.nome).put("nome", rotulo ?: p.nome).put("kb", p.kb))
                }
            })
        }
        return o
    }

    /** Encerra os processos em segundo plano e devolve quanto de RAM livre ganhou (bytes), ou null sem shell. */
    fun liberar(ctx: Context): Long? {
        val am = ctx.getSystemService(ActivityManager::class.java)
        fun livre() = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
        val antes = livre()
        GuardiaoShizuku.comando(ctx, "am kill-all") ?: return null
        Thread.sleep(1500)   // o Android leva um instante para devolver a memória dos processos encerrados
        return livre() - antes
    }

    // ---------------------------------------------------------------- leitura de texto (testada sem Android)

    /** "/proc/meminfo": "SwapTotal:  1048572 kB" -> SwapTotal = 1048572*1024 (bytes). */
    fun lerMeminfo(texto: String): Map<String, Long> {
        val m = HashMap<String, Long>()
        for (linha in texto.lineSequence()) {
            val r = Regex("""^(\w+):\s+(\d+)\s*kB""").find(linha) ?: continue
            m[r.groupValues[1]] = r.groupValues[2].toLong() * 1024
        }
        return m
    }

    private fun lerZonasDireto(): List<Pair<String, Double>> = try {
        val l = File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }?.mapNotNull { z ->
            try { "${File(z, "type").readText().trim()} ${File(z, "temp").readText().trim()}" } catch (_: Exception) { null }
        } ?: emptyList()
        lerZonas(l.joinToString("\n"))
    } catch (_: Exception) { emptyList() }

    /**
     * Linhas "tipo valor" -> (tipo, °C). O valor vem em milésimos de grau (45000 = 45 °C) na maioria dos aparelhos, às
     * vezes já em graus. Fora de 0..150 °C é leitura quebrada (sensor desligado devolve -273000 ou 0) e sai da lista.
     */
    fun lerZonas(texto: String): List<Pair<String, Double>> = texto.lineSequence().mapNotNull { linha ->
        val partes = linha.trim().split(Regex("\\s+"))
        if (partes.size < 2) return@mapNotNull null
        val bruto = partes.last().toDoubleOrNull() ?: return@mapNotNull null
        val c = if (bruto > 1000 || bruto < -1000) bruto / 1000.0 else bruto
        if (c <= 0.0 || c > 150.0) null else partes.dropLast(1).joinToString(" ") to Math.round(c * 10) / 10.0
    }.toList()

    class Processo(val nome: String, val kb: Long)

    /**
     * Da saída do `dumpsys meminfo`, a seção "Total PSS by process" (memória que cada processo realmente ocupa, com a
     * parte compartilhada dividida entre quem usa). Linha típica: "    123,456K: com.foo (pid 1234 / activities)".
     */
    fun maioresProcessos(saida: String, quantos: Int): List<Processo> {
        val l = ArrayList<Processo>()
        var dentro = false
        for (linha in saida.lineSequence()) {
            if (linha.startsWith("Total PSS by process")) { dentro = true; continue }
            if (!dentro) continue
            if (linha.isBlank()) { if (l.isNotEmpty()) break else continue }   // linha vazia = fim da seção
            val r = Regex("""^\s*([\d,]+)K: (\S+) \(pid \d+""").find(linha) ?: continue
            l.add(Processo(r.groupValues[2], r.groupValues[1].replace(",", "").toLong()))
        }
        return l.sortedByDescending { it.kb }.take(quantos)
    }

    fun rotuloTermico(s: Int) = when (s) {
        PowerManager.THERMAL_STATUS_NONE -> "normal"
        PowerManager.THERMAL_STATUS_LIGHT -> "morna"
        PowerManager.THERMAL_STATUS_MODERATE -> "quente"
        PowerManager.THERMAL_STATUS_SEVERE -> "muito quente"
        PowerManager.THERMAL_STATUS_CRITICAL, PowerManager.THERMAL_STATUS_EMERGENCY, PowerManager.THERMAL_STATUS_SHUTDOWN -> "crítica"
        else -> "desconhecido"
    }
}
