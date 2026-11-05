package br.paulo.tvlauncher

import br.paulo.tvlauncher.MenuApp.Acao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuAppTest {
    private val nosso = "br.paulo.tvlauncher"

    @Test fun appDeUsuarioPodeDesinstalar() =
        assertTrue(MenuApp.podeDesinstalar("com.netflix.ninja", nosso, sistema = false, protegido = false))

    @Test fun appDeFabricaNaoDesinstala() =
        assertFalse(MenuApp.podeDesinstalar("com.google.android.youtube.tv", nosso, sistema = true, protegido = false))

    @Test fun protegidoNaoDesinstala() =
        assertFalse(MenuApp.podeDesinstalar("com.adguard.vpn", nosso, sistema = false, protegido = true))

    // O próprio launcher (HOME, Device Owner, ADB embutido, acessibilidade) nunca, mesmo fora da lista de protegidos.
    @Test fun launcherNuncaDesinstala() =
        assertFalse(MenuApp.podeDesinstalar(nosso, nosso, sistema = false, protegido = false))

    @Test fun launcherEstaNosProtegidosFixos() = assertTrue(nosso in MenuApp.PROTEGIDOS_FIXOS)

    @Test fun launcherDoGoogleEInstaladorProtegidos() = assertTrue(
        listOf("com.google.android.apps.tv.launcherx", "com.android.packageinstaller", "com.google.android.gms")
            .all { it in MenuApp.PROTEGIDOS_FIXOS })

    @Test fun menuDeAppDesinstalavel() =
        assertEquals(listOf(Acao.MOVER, Acao.OCULTAR, Acao.DESINSTALAR), MenuApp.acoes(naPastaOcultos = false, desinstalavel = true))

    @Test fun menuDeAppDeSistemaSemDesinstalar() =
        assertEquals(listOf(Acao.MOVER, Acao.OCULTAR), MenuApp.acoes(naPastaOcultos = false, desinstalavel = false))

    @Test fun menuNaPastaOcultos() {
        assertEquals(listOf(Acao.MOSTRAR, Acao.DESINSTALAR), MenuApp.acoes(naPastaOcultos = true, desinstalavel = true))
        assertEquals(listOf(Acao.MOSTRAR), MenuApp.acoes(naPastaOcultos = true, desinstalavel = false))
    }
}
