package br.paulo.tvlauncher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * O despertador do launcher: guarda os alarmes e agenda o próximo no Android.
 *
 * Só UM alarme fica agendado no sistema por vez: o mais próximo entre os ligados (e a soneca, se houver).
 * Quando ele toca (AlarmeTocandoActivity), agendamos o seguinte. Por isso [agendar] é chamado sempre que algo muda,
 * no boot (BootReceiver) e ao abrir o launcher: o Android esquece os alarmes agendados quando a TV reinicia.
 *
 * Usa AlarmManager.setAlarmClock: é o tipo "despertador" do Android, que acorda o aparelho mesmo em standby.
 */
object Despertador {
    private const val TAG = "TvLauncher.Alarme"
    private const val PREFS = "despertador"
    const val EXTRA_ID = "alarme_id"

    /**
     * [dias]: um bit por dia da semana (bit 0 = domingo ... bit 6 = sábado). 0 = toca uma vez só e se desliga.
     * [som]: índice em SomAlarme.NOMES. [rampa]: segundos para o volume subir do mínimo ao máximo (0 = sem rampa).
     * [id] identifica o alarme (a soneca e a tela que toca usam).
     * [bomDia]: ao apertar Parar, a TV abre a previsão e fala "Bom dia", a hora, o tempo e a agenda de hoje.
     * Ligado por padrão, inclusive nos alarmes salvos antes de existir (05/11/2026).
     */
    data class Alarme(val id: Int, val hora: Int, val minuto: Int, val dias: Int, val nome: String, val ativo: Boolean,
                      val som: Int = 0, val rampa: Int = 0, val bomDia: Boolean = true)

    val NOMES_DIAS = listOf("D", "S", "T", "Q", "Q", "S", "S")   // domingo a sábado

    fun listar(ctx: Context): List<Alarme> {
        val texto = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("alarmes", "[]") ?: "[]"
        val arr = try { JSONArray(texto) } catch (e: Exception) { JSONArray() }
        // Um item estragado (sem id/hora/minuto) é pulado, em vez de derrubar a HOME inteira (revisão do painel, 04/11/2026).
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull {
            try {
                Alarme(it.getInt("id"), it.getInt("hora"), it.getInt("minuto"), it.optInt("dias"), it.optString("nome"), it.optBoolean("ativo", true), it.optInt("som", 0), it.optInt("rampa", 0), it.optBoolean("bom_dia", true))
            } catch (e: org.json.JSONException) { null }
        }.sortedWith(compareBy({ it.hora }, { it.minuto }))
    }

    private fun gravar(ctx: Context, lista: List<Alarme>) {
        val arr = JSONArray()
        lista.forEach {
            arr.put(JSONObject().put("id", it.id).put("hora", it.hora).put("minuto", it.minuto)
                .put("dias", it.dias).put("nome", it.nome).put("ativo", it.ativo).put("som", it.som).put("rampa", it.rampa).put("bom_dia", it.bomDia))
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("alarmes", arr.toString()).apply()
        agendar(ctx)
    }

    /** Cria (id 0) ou substitui um alarme. Devolve o alarme salvo. */
    fun salvar(ctx: Context, a: Alarme): Alarme {
        val lista = listar(ctx).toMutableList()
        val novo = if (a.id == 0) a.copy(id = (lista.maxOfOrNull { it.id } ?: 0) + 1) else a
        lista.removeAll { it.id == novo.id }
        lista.add(novo)
        gravar(ctx, lista)
        return novo
    }

    fun apagar(ctx: Context, id: Int) = gravar(ctx, listar(ctx).filter { it.id != id })

    /** O alarme [id] tocou e foi parado: se era de uma vez só, desliga; agenda o próximo. */
    fun tocou(ctx: Context, id: Int) {
        limparSoneca(ctx)
        val a = listar(ctx).firstOrNull { it.id == id }
        if (a != null && a.dias == 0) gravar(ctx, listar(ctx).map { if (it.id == id) it.copy(ativo = false) else it })
        else agendar(ctx)
    }

    /** Soneca: o alarme [id] toca de novo daqui a [minutos]. */
    fun soneca(ctx: Context, id: Int, minutos: Int = 10) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("soneca_em", System.currentTimeMillis() + minutos * 60_000L).putInt("soneca_id", id).apply()
        agendar(ctx)
    }

    private fun limparSoneca(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("soneca_em").remove("soneca_id").apply()

    /** Próxima vez que [a] toca depois de [agoraMs], ou null se estiver desligado. */
    fun proximaVez(a: Alarme, agoraMs: Long = System.currentTimeMillis()): Long? {
        if (!a.ativo) return null
        val c = Calendar.getInstance().apply {
            timeInMillis = agoraMs
            set(Calendar.HOUR_OF_DAY, a.hora); set(Calendar.MINUTE, a.minuto)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        // Até 8 tentativas: hoje e os próximos 7 dias; vale o primeiro horário no futuro num dia marcado (ou qualquer dia, se for de uma vez).
        repeat(8) {
            val diaDaSemana = c.get(Calendar.DAY_OF_WEEK) - 1   // Calendar: domingo = 1
            if (c.timeInMillis > agoraMs && (a.dias == 0 || a.dias and (1 shl diaDaSemana) != 0)) return c.timeInMillis
            c.add(Calendar.DAY_OF_MONTH, 1)
        }
        return null
    }

    /** O próximo toque (alarme e quando), considerando a soneca. null = nada agendado. */
    fun proximo(ctx: Context): Pair<Alarme, Long>? {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lista = listar(ctx)
        val candidatos = lista.mapNotNull { a -> proximaVez(a)?.let { a to it } }.toMutableList()
        val sonecaEm = prefs.getLong("soneca_em", 0)
        if (sonecaEm > System.currentTimeMillis()) {
            lista.firstOrNull { it.id == prefs.getInt("soneca_id", -1) }?.let { candidatos.add(it to sonecaEm) }
        }
        return candidatos.minByOrNull { it.second }
    }

    /** (Re)agenda no Android o próximo toque. Sem nada para tocar, cancela o que houver. */
    fun agendar(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val prox = proximo(ctx)
        val toque = PendingIntent.getActivity(
            ctx, 1,
            Intent(ctx, AlarmeTocandoActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_ID, prox?.first?.id ?: 0),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (prox == null) {
            am.cancel(toque)
            Log.i(TAG, "nenhum alarme ligado")
            return
        }
        // O segundo PendingIntent é o que o sistema abre se alguém tocar no ícone de "próximo alarme": a lista.
        val mostrar = PendingIntent.getActivity(
            ctx, 2, Intent(ctx, AlarmesActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(prox.second, mostrar), toque)
            Log.i(TAG, "proximo: ${prox.first.hora}:${"%02d".format(prox.first.minuto)} (id ${prox.first.id}) em ${(prox.second - System.currentTimeMillis()) / 60000} min")
        } catch (e: SecurityException) {
            Log.w(TAG, "sem permissao de alarme exato: $e")
        }
    }

    /** "07:00" */
    fun horaTexto(a: Alarme) = "%02d:%02d".format(a.hora, a.minuto)

    /** "Todo dia", "Seg a sex", "Só uma vez", ou os dias marcados ("S T Q"). */
    fun diasTexto(ctx: Context, dias: Int): String = when (dias) {
        0 -> ctx.getString(R.string.dia_uma_vez)
        0b1111111 -> ctx.getString(R.string.dia_todo)
        0b0111110 -> ctx.getString(R.string.dia_seg_sex)
        0b1000001 -> ctx.getString(R.string.dia_fim)
        else -> ctx.resources.getStringArray(R.array.dias_curtos).filterIndexed { i, _ -> dias and (1 shl i) != 0 }.joinToString(" ")
    }
}
