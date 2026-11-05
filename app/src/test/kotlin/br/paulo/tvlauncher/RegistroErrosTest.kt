package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RegistroErrosTest {
    @Test fun resumoPegaACausaMaisFunda() {
        val e = RuntimeException("por fora", IllegalStateException("o motivo de verdade\nsegunda linha"))
        assertEquals("IllegalStateException: o motivo de verdade", RegistroErros.resumo(e))
    }

    @Test fun resumoSemMensagem() = assertEquals("NullPointerException", RegistroErros.resumo(NullPointerException()))

    @Test fun textoTemCabecalhoEPilha() {
        val t = RegistroErros.montarTexto(0L, "0.2 (1)", "Android 14 · TV", "main", IllegalArgumentException("x"))
        assertTrue(t.contains("Versão do launcher: 0.2 (1)"))
        assertTrue(t.contains("Thread: main"))
        assertTrue(t.contains("Erro: IllegalArgumentException: x"))
        assertTrue(t.contains("at br.paulo.tvlauncher.RegistroErrosTest"))
    }

    @Test fun nomeOrdenaPeloTempo() {
        val a = RegistroErros.nomeArquivo(1_000_000L)
        val b = RegistroErros.nomeArquivo(2_000_000_000_000L)
        assertTrue(a < b)
    }

    @Test fun podarMantemOsMaisNovos() {
        val dir = Files.createTempDirectory("erros").toFile()
        for (i in 1..15) File(dir, RegistroErros.nomeArquivo(i * 1000L)).writeText("$i")
        File(dir, "outro.txt").writeText("não é erro")
        RegistroErros.podar(dir, 10)
        val restam = dir.listFiles()!!.filter { it.name.startsWith("erro-") }.map { it.readText().toInt() }.sorted()
        assertEquals((6..15).toList(), restam)
        assertTrue(File(dir, "outro.txt").exists())
        dir.deleteRecursively()
    }
}
