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
 * Quem atende o "criar timer" do Android (AlarmClock.ACTION_SET_TIMER, com EXTRA_LENGTH em segundos e, às vezes,
 * EXTRA_MESSAGE com o nome) e os atalhos de voz da contagem.
 *
 * Por que os atalhos (05/11/2026, teste do Paulo na TV): "Ok Google, timer de 2 minutos" NÃO chega aqui. O Gemini
 * desta TV responde que "ainda não consegue alterar o timer neste dispositivo" e nem manda o SET_TIMER. Então, como
 * no "Desligar em 30 minutos", cada duração vira um "app" com nome falável (activity-alias no manifesto):
 * "Ok Google, abrir Avisar em 10 minutos". O nome evita "timer" e "contagem": com "abrir Contagem de 1 minuto" o
 * Gemini entendeu pedido de vídeo e mostrou contagens do YouTube (teste do Paulo, 05/11/2026).
 * "Ampulheta" (ou o SET_TIMER sem duração) abre a escolha da duração no controle; "Não avisar" cancela.
 */
class TimerActivity : Activity() {
    companion object {
        /** Alias do manifesto -> minutos. */
        val ATALHOS = mapOf("Avisar5" to 5, "Avisar10" to 10, "Avisar15" to 15, "Avisar20" to 20, "Avisar30" to 30)
        /** Opções da tela de escolha, em minutos. */
        val OPCOES = listOf(1, 3, 5, 10, 15, 20, 30, 45, 60)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val alias = intent.component?.className?.substringAfterLast('.') ?: ""
        val seg = when {
            alias in ATALHOS -> ATALHOS.getValue(alias) * 60
            alias == "NaoAvisar" -> { cancelar(); finish(); return }
            else -> intent.getIntExtra(AlarmClock.EXTRA_LENGTH, 0)
        }
        val nome = intent.getStringExtra(AlarmClock.EXTRA_MESSAGE)?.trim() ?: ""
        Log.i("TvLauncher.Timer", "pedido: alias=$alias length=$seg message='$nome'")
        if (seg in 1..24 * 3600) { marcar(seg, nome); finish(); return }
        setContentView(escolha())   // sem duração: escolher no controle
    }

    private fun marcar(seg: Int, nome: String) {
        Timer.iniciar(this, seg, nome)
        val titulo = if (nome.isNotEmpty()) " ($nome)" else ""
        Toast.makeText(applicationContext, "⏱ Aviso em ${Timer.duracaoTexto(seg)}$titulo", Toast.LENGTH_LONG).show()
    }

    private fun cancelar() = Toast.makeText(applicationContext,
        if (Timer.cancelar(this)) "Contagem cancelada." else "Não havia contagem correndo.", Toast.LENGTH_LONG).show()

    /** Uma caixa no meio da tela com um botão por duração (OK marca e fecha; Voltar fecha sem marcar). */
    private fun escolha(): android.view.View {
        fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
        fun fundo(foco: Boolean) = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(if (foco) 0x33FFFFFF else 0x14FFFFFF)
            setStroke(dp(2), if (foco) 0xD9FFFFFF.toInt() else 0x22FFFFFF)
        }
        fun botao(rotulo: String, acao: () -> Unit) = android.widget.TextView(this).apply {
            text = rotulo
            setTextColor(android.graphics.Color.WHITE)
            textSize = 20f
            gravity = android.view.Gravity.CENTER
            setPadding(dp(8), dp(14), dp(8), dp(14))
            isFocusable = true; isClickable = true
            background = fundo(false)
            setOnFocusChangeListener { v, f -> v.background = fundo(f) }
            setOnClickListener { acao(); finish() }
        }
        val grade = android.widget.GridLayout(this).apply { columnCount = 3 }
        OPCOES.forEach { min ->
            grade.addView(botao(if (min < 60) "$min min" else "1 hora") { marcar(min * 60, "") },
                android.widget.GridLayout.LayoutParams().apply { width = dp(150); setMargins(dp(8), dp(8), dp(8), dp(8)) })
        }
        val caixa = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(24), dp(32), dp(24))
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(0xF0101418.toInt()) }
            addView(android.widget.TextView(this@TimerActivity).apply {
                text = "⏱  Contagem regressiva"; setTextColor(android.graphics.Color.WHITE); textSize = 26f
                setPadding(0, 0, 0, dp(12))
            })
            addView(grade)
            if (Timer.ativo(this@TimerActivity) != null) addView(botao("Cancelar a contagem atual") { cancelar() },
                android.widget.LinearLayout.LayoutParams(dp(498), -2).apply { topMargin = dp(8) })
        }
        grade.getChildAt(3)?.post { grade.getChildAt(3).requestFocus() }   // foco em "10 min", a escolha mais comum
        return android.widget.FrameLayout(this).apply {
            addView(caixa, android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.CENTER))
        }
    }
}
