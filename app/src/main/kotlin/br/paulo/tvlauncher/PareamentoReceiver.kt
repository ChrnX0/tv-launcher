package br.paulo.tvlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Pareamento / teste do shell próprio por adb (só o adb e o sistema podem disparar: permission=DUMP no manifest).
 * O código do pareamento aparece na TV só na PRIMEIRA vez. Depois de pareado, a nossa chave fica confiável e basta
 * disparar SEM --es codigo:
 *
 *   1ª vez:  am broadcast -n .../.PareamentoReceiver -a br.paulo.tvlauncher.PAREAR --es codigo 123456 --ei porta 37123
 *   depois:  am broadcast -n .../.PareamentoReceiver -a br.paulo.tvlauncher.PAREAR
 *
 * Desde o MARCO S3 o trabalho em si é do [GuardiaoShizuku] (fila única, tentativas com espera, `id` e servidor do
 * Shizuku). Este receiver só repassa. Resultado em files/shell_proprio.log e no logcat (TvLauncher.Shizuku).
 *
 * NÃO segura o broadcast (sem goAsync): em 04/11/2026, logo depois de um reboot, o `id` travou e o sistema matou o
 * launcher por ANR ao estourar o limite de 60 s do broadcast.
 */
class PareamentoReceiver : BroadcastReceiver() {
    companion object { const val ACAO = "br.paulo.tvlauncher.PAREAR" }

    override fun onReceive(recebido: Context, intent: Intent) {
        val ctx = recebido.applicationContext
        ServidorShizuku.anotar(ctx, "onReceive acao=${intent.action}")
        if (intent.action != ACAO) return
        GuardiaoShizuku.parearEGarantir(ctx, intent.getStringExtra("codigo"), intent.getIntExtra("porta", -1))
    }
}
