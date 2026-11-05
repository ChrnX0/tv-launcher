package br.paulo.tvlauncher

import android.accessibilityservice.AccessibilityService
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.widget.Toast

/**
 * "Desligar em X min" (05/11/2026, bloco 5 do brainstorm): o timer de sono da TV. Diferente do "desligar sozinha
 * sem uso" (PR #17), este desliga na hora marcada, com ou sem gente mexendo no controle.
 *
 * Quem liga: os atalhos de voz ("Ok Google, abrir Desligar em 30 minutos", ver AtalhoVozActivity).
 * Como: um alarme do Android (AlarmManager) acorda o [DesligamentoReceiver] 1 min antes (aviso na tela) e na hora
 * (apaga a tela; o Chromecast manda a TV para standby pelo HDMI-CEC, como o botão de ligar do controle).
 * Um alarme do sistema sobrevive ao launcher ser fechado pelo Android; só o reboot o apaga (e aí a TV já desligou).
 * A hora marcada fica nas preferências "launcher" ([CHAVE], milissegundos; 0 = nenhum).
 */
object Desligamento {
    private const val TAG = "TvLauncher.Desligar"
    const val CHAVE = "desligar_em"
    const val ACAO_AVISO = "br.paulo.tvlauncher.DESLIGAR_AVISO"
    const val ACAO_DESLIGAR = "br.paulo.tvlauncher.DESLIGAR_AGORA"
    private const val AVISO_MS = 60_000L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    /** Quando a TV vai desligar (ms desde 1970), ou null se não há timer de sono. */
    fun marcado(ctx: Context): Long? = prefs(ctx).getLong(CHAVE, 0).takeIf { it > System.currentTimeMillis() }

    /** Marca para daqui a [minutos]. Marcar de novo substitui o anterior (não soma). */
    fun agendar(ctx: Context, minutos: Int) {
        val em = System.currentTimeMillis() + minutos * 60_000L
        prefs(ctx).edit().putLong(CHAVE, em).apply()
        val am = ctx.getSystemService(AlarmManager::class.java)
        // setExactAndAllowWhileIdle: na hora exata mesmo com o aparelho em repouso (o app tem USE_EXACT_ALARM).
        if (minutos >= 2) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, em - AVISO_MS, pendente(ctx, ACAO_AVISO))
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, em, pendente(ctx, ACAO_DESLIGAR))
        Log.i(TAG, "a TV desliga em $minutos min")
    }

    /** Desmarca. Devolve true se havia algo marcado. */
    fun cancelar(ctx: Context): Boolean {
        val havia = marcado(ctx) != null
        prefs(ctx).edit().remove(CHAVE).apply()
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(pendente(ctx, ACAO_AVISO))
        am.cancel(pendente(ctx, ACAO_DESLIGAR))
        Log.i(TAG, "timer de sono cancelado (havia: $havia)")
        return havia
    }

    /** O PendingIntent de cada etapa: o mesmo (action igual) a cada chamada, por isso cancelar() acha o agendado. */
    private fun pendente(ctx: Context, acao: String) = PendingIntent.getBroadcast(
        ctx, acao.hashCode(), Intent(ctx, DesligamentoReceiver::class.java).setAction(acao),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** "30 minutos", "1 hora", "1 hora e 30 minutos": para os avisos na tela. */
    fun duracaoTexto(minutos: Int): String {
        val h = minutos / 60; val m = minutos % 60
        val horas = if (h == 1) "1 hora" else "$h horas"
        val mins = if (m == 1) "1 minuto" else "$m minutos"
        return when { h == 0 -> mins; m == 0 -> horas; else -> "$horas e $mins" }
    }

    internal fun telaAcesa(ctx: Context) = ctx.getSystemService(PowerManager::class.java).isInteractive

    /**
     * Apaga a tela pelo 1º caminho que funcionar: a ação de acessibilidade "bloquear tela" (Android 9+) e, se a tela
     * continuar acesa 3 s depois, o lockNow do Device Owner. Os dois só apagam a tela: nada se perde.
     */
    fun apagarTela(ctx: Context) {
        val app = ctx.applicationContext
        val ui = Handler(Looper.getMainLooper())
        val caminhos = listOf<Pair<String, () -> Boolean>>(
            "acessibilidade" to {
                AutorizaShizukuService.instancia?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) == true
            },
            "Device Owner" to { app.getSystemService(DevicePolicyManager::class.java).lockNow(); true },
        )
        fun tentar(i: Int) {
            if (i >= caminhos.size || !telaAcesa(app)) return
            val (nome, fazer) = caminhos[i]
            val aceitou = try { fazer() } catch (e: Exception) { Log.w(TAG, "$nome falhou: $e"); false }
            Log.i(TAG, "apagando a tela pela $nome: ${if (aceitou) "aceito" else "recusado"}")
            ui.postDelayed({ if (telaAcesa(app)) tentar(i + 1) else Log.i(TAG, "tela apagada pela $nome") }, if (aceitou) 3000 else 0)
        }
        tentar(0)
    }
}

/** Recebe os dois alarmes do [Desligamento]. Não exportado: só o próprio app (via AlarmManager) o chama. */
class DesligamentoReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        // Desmarcado entre o agendamento e agora (ou trocado por outro horário): ignora o alarme velho.
        val marcado = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).getLong(Desligamento.CHAVE, 0)
        if (marcado == 0L) return
        if (intent.action == Desligamento.ACAO_DESLIGAR && marcado - System.currentTimeMillis() > 30_000) return
        when (intent.action) {
            Desligamento.ACAO_AVISO -> if (Desligamento.telaAcesa(ctx))
                Toast.makeText(ctx, "A TV desliga em 1 minuto.\nPara não desligar: \"Ok Google, abrir Não desligar a TV\"", Toast.LENGTH_LONG).show()
            Desligamento.ACAO_DESLIGAR -> {
                ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().remove(Desligamento.CHAVE).apply()
                Desligamento.apagarTela(ctx)
            }
        }
    }
}
