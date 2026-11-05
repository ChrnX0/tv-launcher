package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BackupAutomaticoTest {
    @Test fun nomeSoAceitaCopias() {
        assertTrue(BackupAutomatico.nomeValido("backup-20261105.json"))
        assertFalse(BackupAutomatico.nomeValido("../backup-20261105.json"))
        assertFalse(BackupAutomatico.nomeValido("backup-2026115.json"))
        assertFalse(BackupAutomatico.nomeValido("backup-20261105.json.tmp"))
    }

    @Test fun semDataIgnoraSoOCriadoEm() {
        val a = "{\n  \"versao\": 2,\n  \"criadoEm\": 1700000000000,\n  \"arquivos\": {}\n}"
        val b = "{\n  \"versao\": 2,\n  \"criadoEm\": 1800000000000,\n  \"arquivos\": {}\n}"
        val c = "{\n  \"versao\": 2,\n  \"criadoEm\": 1800000000000,\n  \"arquivos\": {\"x\": 1}\n}"
        assertEquals(BackupAutomatico.semData(a), BackupAutomatico.semData(b))
        assertTrue(BackupAutomatico.semData(a) != BackupAutomatico.semData(c))
    }

    @Test fun podarGuardaAsSeteMaisNovasEApagaTemporarios() {
        val dir = Files.createTempDirectory("backups").toFile()
        for (d in 1..10) File(dir, "backup-202611%02d.json".format(d)).writeText("$d")
        File(dir, "backup-20261111.json.tmp").writeText("meio")
        File(dir, "outra-coisa.json").writeText("fica")
        BackupAutomatico.podar(dir, 7)
        val restam = dir.list()!!.sorted()
        assertEquals(listOf("backup-20261104.json", "backup-20261105.json", "backup-20261106.json", "backup-20261107.json",
            "backup-20261108.json", "backup-20261109.json", "backup-20261110.json", "outra-coisa.json"), restam)
        dir.deleteRecursively()
    }
}
