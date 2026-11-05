package br.paulo.tvlauncher

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.AlarmClock
import android.util.Log
import android.widget.Toast

/**
 * Timer da cozinha (05/11/2026, bloco 5 do brainstorm): "Ok Google, timer de 10 minutos".
 *
 * Por que é nosso: nesta TV o único app que atende o pedido padrão do Android de timer (AlarmClock.ACTION_SET_TIMER)
 * é um "stub" de fábrica (com.android.tv.frameworkpackagestubs, prioridade -1) que não faz nada. Medido na TV em
 * 05/11/2026 com `cmd package query-activities -a android.intent.action.SET_TIMER`. Declarando o mesmo pedido com
 * prioridade normal (0), o nosso vence sem perguntar, como já acontece com o despertador (SET_ALARM).
 *
 * Um timer só por vez (um novo substitui o anterior: na TV não faz sentido uma pilha de timers). Ele fica nas
 * preferências "timer" (fim em ms e o nome) e num alarme do Android tipo despertador (setAlarmClock), que acende a
 * tela mesmo em standby. Quando acaba, a tela do alarme (AlarmeTocandoActivity) toca em modo timer.
 * A HOME mostra quanto falta embaixo da data (ver MainActivity.atualizarTimer); OK ali cancela.
 */
object Timer {
    private const val TAG = "TvLauncher.Timer"
    private const val PREFS = "timer"

    data class Ativo(val fimMs: Long, val nome: String)

    fun ativo(ctx: Context): Ativo? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val fim = p.getLong("fim", 0)
        return if (fim > System.currentTimeMillis()) Ativo(fim, p.getString("nome", "") ?: "") else null
    }

    fun iniciar(ctx: Context, segundos: Int, nome: String) {
        val fim = System.currentTimeMillis() + segundos * 1000L
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("fim", fim).putString("nome", nome).apply()
        val am = ctx.getSystemService(AlarmManager::class.java)
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(fim, null), pendente(ctx))
        } catch (e: SecurityException) { Log.w(TAG, "sem permissão de alarme exato: $e") }
        Log.i(TAG, "timer de $segundos s ('$nome')")
    }

    /** Devolve true se havia um timer correndo. */
    fun cancelar(ctx: Context): Boolean {
        val havia = ativo(ctx) != null
        limpar(ctx)
        ctx.getSystemService(AlarmManager::class.java).cancel(pendente(ctx))
        return havia
    }

    /** Chamado quando o timer tocou (a tela já abriu): some da HOME. */
    fun limpar(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()

    // requestCode 3: o 1 e o 2 são do Despertador (o toque e o "mostrar"). Códigos iguais com o mesmo destino se
    // confundiriam: o Android acharia que é o mesmo PendingIntent e o timer apagaria o alarme do despertador.
    private fun pendente(ctx: Context) = PendingIntent.getActivity(
        ctx, 3,
        Intent(ctx, AlarmeTocandoActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(AlarmeTocandoActivity.EXTRA_TIMER, true),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** "4:05" (até 1 h) ou "1:04:05". */
    fun restanteTexto(ms: Long): String {
        val s = ((ms + 999) / 1000).coerceAtLeast(0)
        val h = s / 3600; val m = (s % 3600) / 60; val seg = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, seg) else "%d:%02d".format(m, seg)
    }

    /** "10 minutos", "1 hora e 30 minutos", "45 segundos": para a confirmação na tela. */
    fun duracaoTexto(segundos: Int): String {
        val h = segundos / 3600; val m = (segundos % 3600) / 60; val s = segundos % 60
        val partes = mutableListOf<String>()
        if (h > 0) partes += if (h == 1) "1 hora" else "$h horas"
        if (m > 0) partes += if (m == 1) "1 minuto" else "$m minutos"
        if (s > 0) partes += if (s == 1) "1 segundo" else "$s segundos"
        return partes.joinToString(" e ").ifEmpty { "0 segundos" }
    }
}

/**
 * Quem atende o "criar timer" do Android (o Gemini manda AlarmClock.ACTION_SET_TIMER com EXTRA_LENGTH em segundos e,
 * às vezes, EXTRA_MESSAGE com o nome). Não desenha nada: marca, confirma num aviso e fecha.
 * Sem duração (alguém abrindo "timer" sem dizer quanto) não há o que marcar: abre o despertador, que é o mais perto.
 */
class TimerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val seg = intent.getIntExtra(AlarmClock.EXTRA_LENGTH, 0)
        val nome = intent.getStringExtra(AlarmClock.EXTRA_MESSAGE)?.trim() ?: ""
        Log.i("TvLauncher.Timer", "SET_TIMER length=$seg message='$nome'")
        if (seg in 1..24 * 3600) {
            Timer.iniciar(this, seg, nome)
            val titulo = if (nome.isNotEmpty()) " ($nome)" else ""
            Toast.makeText(applicationContext, "⏱ Timer de ${Timer.duracaoTexto(seg)}$titulo", Toast.LENGTH_LONG).show()
        } else {
            startActivity(Intent(this, AlarmesActivity::class.java))
        }
        finish()
    }
}
