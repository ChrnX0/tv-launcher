package br.paulo.tvlauncher

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * "Liberar RAM": pede ao Android para encerrar os processos em segundo plano dos apps instalados por você.
 * Não toca em apps de sistema nem no app que está na tela. O Android pode reabrir alguns sozinho depois.
 */
object Memoria {
    /** Devolve quantos MB de RAM livre ganhamos (nunca negativo). Bloqueia ~1,5 s: chame fora da thread da tela. */
    fun liberar(contexto: Context): Long {
        val gerente = contexto.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val antes = livreMb(gerente)
        for (app in contexto.packageManager.getInstalledApplications(0)) {
            val instaladoPorVoce = (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0
            if (instaladoPorVoce && app.packageName != contexto.packageName) {
                gerente.killBackgroundProcesses(app.packageName)
            }
        }
        Thread.sleep(1500)   // dá tempo do sistema recolher a memória antes de medir de novo
        return maxOf(0L, livreMb(gerente) - antes)
    }

    private fun livreMb(gerente: ActivityManager): Long {
        val info = ActivityManager.MemoryInfo()
        gerente.getMemoryInfo(info)
        return info.availMem / (1024L * 1024L)
    }
}
