package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FotosProtetorTest {
    @Test fun nomes() {
        assertTrue(FotosProtetor.nomeValido("praia 2025.jpg"))
        assertTrue(FotosProtetor.nomeValido("Férias_SP-1.JPEG"))
        assertFalse(FotosProtetor.nomeValido("../x.jpg"))
        assertFalse(FotosProtetor.nomeValido("a/b.jpg"))
        assertFalse(FotosProtetor.nomeValido(".oculta.jpg"))
        assertFalse(FotosProtetor.nomeValido("video.mp4"))
        assertFalse(FotosProtetor.nomeValido(".envio.tmp"))
    }

    @Test fun reducaoCabeNaTela() {
        assertEquals(1, FotosProtetor.reducao(1920, 1080, 1920, 1080))
        assertEquals(2, FotosProtetor.reducao(4000, 3000, 1920, 1080))
        assertEquals(1, FotosProtetor.reducao(800, 600, 1920, 1080))
    }
}
