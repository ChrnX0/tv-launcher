package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class PainelSaudeTest {
    @Test fun meminfoEmBytes() {
        val m = PainelSaude.lerMeminfo("MemTotal:        2000000 kB\nSwapTotal:       1048572 kB\nSwapFree:          10240 kB\nHugePages_Total:       0\n")
        assertEquals(1048572L * 1024, m["SwapTotal"])
        assertEquals(10240L * 1024, m["SwapFree"])
        assertEquals(null, m["HugePages_Total"])
    }

    @Test fun zonasEmMilesimosEGraus() {
        val z = PainelSaude.lerZonas("cpu-thermal 52300\ngpu 48\nsensor quebrado -273000\nvazio 0\nsem valor\n")
        assertEquals(listOf("cpu-thermal" to 52.3, "gpu" to 48.0), z)
    }

    @Test fun maioresProcessosDaSecaoPss() {
        val saida = """
Applications Memory Usage (in Kilobytes):
Uptime: 123 Realtime: 456

Total PSS by process:
    210,345K: com.google.android.apps.tv.launcherx (pid 1234 / activities)
     98,001K: system (pid 600)
    150,200K: com.onestream.player (pid 2222 / activities)
      5,100K: br.paulo.tvlauncher:outro (pid 3333)

Total PSS by OOM adjustment:
    500,000K: Native
""".trimIndent()
        val l = PainelSaude.maioresProcessos(saida, 3)
        assertEquals(listOf("com.google.android.apps.tv.launcherx", "com.onestream.player", "system"), l.map { it.nome })
        assertEquals(210345L, l[0].kb)
    }
}
