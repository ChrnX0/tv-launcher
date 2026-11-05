package br.paulo.tvlauncher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebloatTest {
    @Test fun naoTestadoNaoAparece() = assertFalse(Debloat.permitido(Debloat.Item("x.y", "X", "nada")))

    @Test fun testadoAparece() = assertTrue(Debloat.permitido(Debloat.Item("x.y", "X", "nada", "05/11/2026")))

    @Test fun nuncaVenceAteTestado() =
        assertFalse(Debloat.permitido(Debloat.Item("com.google.android.apps.tv.launcherx", "Google TV", "tudo", "05/11/2026")))

    @Test fun listaNaoTemPacoteProibido() = assertTrue(Debloat.LISTA.none { it.pacote in Debloat.NUNCA })
}
