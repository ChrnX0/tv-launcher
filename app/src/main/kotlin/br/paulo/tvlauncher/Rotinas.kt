package br.paulo.tvlauncher

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Rotinas por horário (05/11/2026, fila longa do brainstorm). Exemplos: "dias úteis 7h: ligar e abrir o app do
 * jornal", "23h: desligar se ninguém mexeu há 1 h". Mesmo jeito do Despertador (Despertador.kt):
 *  - os horários e os dias são iguais aos do alarme (dias = um bit por dia, bit 0 = domingo; 0 = uma vez só);
 *  - só UMA rotina fica agendada no Android por vez, a mais próxima; quando ela roda, agenda a seguinte. Por isso
 *    [agendar] é chamado no boot, ao abrir a HOME e sempre que uma rotina muda.
 *
 * Ações:
 *  - LIGAR: acende a tela (o Chromecast liga a TV pelo HDMI-CEC, como no despertador, testado em 03/11);
 *  - ABRIR: liga e abre um app;
 *  - DESLIGAR: apaga a tela (a TV entra em standby pelo HDMI-CEC). Com [Rotina.ocioso] > 0, só desliga se ninguém
 *    apertou o controle nos últimos N minutos (assim não corta o filme de quem está assistindo).
 *
 * Diferença do despertador: não toca som, não mostra tela própria e usa setExactAndAllowWhileIdle (não aparece o
 * ícone de "próximo alarme" do sistema).
 */
object Rotinas {
    private const val TAG = "TvLauncher.Rotinas"
    private const val PREFS = "rotinas"
    const val EXTRA_ID = "rotina_id"

    enum class Acao(val rotulo: String) { LIGAR("Ligar a TV"), ABRIR("Ligar e abrir um app"), DESLIGAR("Desligar a TV") }

    /** [ocioso]: só para DESLIGAR; minutos sem tecla do controle exigidos (0 = desliga de qualquer jeito). */
    data class Rotina(val id: Int, val hora: Int, val minuto: Int, val dias: Int, val acao: Acao,
                      val pacote: String = "", val ocioso: Int = 0, val ativa: Boolean = true)

    /** Opções do "só se ninguém mexeu há…", em minutos. */
    val OCIOSO_OPCOES = listOf(0, 30, 60, 120)

    // ---------------------------------------------------------------- guardar

    fun listar(ctx: Context): List<Rotina> =
        deJson(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("lista", "[]") ?: "[]")

    fun deJson(texto: String): List<Rotina> {
        val arr = try { JSONArray(texto) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull {
            try {
                Rotina(it.getInt("id"), it.getInt("hora").coerceIn(0, 23), it.getInt("minuto").coerceIn(0, 59),
                    it.optInt("dias") and 0b1111111, Acao.valueOf(it.getString("acao")), it.optString("pacote"),
                    it.optInt("ocioso"), it.optBoolean("ativa", true))
            } catch (e: Exception) { null }   // item estragado (backup antigo, versão futura): ignora só ele
        }.sortedWith(compareBy({ it.hora }, { it.minuto }))
    }

    fun paraJson(lista: List<Rotina>): String = JSONArray().apply {
        lista.forEach {
            put(JSONObject().put("id", it.id).put("hora", it.hora).put("minuto", it.minuto).put("dias", it.dias)
                .put("acao", it.acao.name).put("pacote", it.pacote).put("ocioso", it.ocioso).put("ativa", it.ativa))
        }
    }.toString()

    private fun gravar(ctx: Context, lista: List<Rotina>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("lista", paraJson(lista)).apply()
        agendar(ctx)
    }

    fun salvar(ctx: Context, r: Rotina): Rotina {
        val lista = listar(ctx).toMutableList()
        val nova = if (r.id == 0) r.copy(id = (lista.maxOfOrNull { it.id } ?: 0) + 1) else r
        lista.removeAll { it.id == nova.id }
        lista.add(nova)
        gravar(ctx, lista)
        return nova
    }

    fun apagar(ctx: Context, id: Int) = gravar(ctx, listar(ctx).filter { it.id != id })

    // ---------------------------------------------------------------- agendar

    /** Próxima vez de [r] depois de [agoraMs]; a mesma conta do despertador. */
    fun proximaVez(r: Rotina, agoraMs: Long = System.currentTimeMillis()): Long? =
        if (!r.ativa) null else Despertador.proximaVez(Despertador.Alarme(r.id, r.hora, r.minuto, r.dias, "", true), agoraMs)

    fun proxima(ctx: Context, agoraMs: Long = System.currentTimeMillis()): Pair<Rotina, Long>? =
        listar(ctx).mapNotNull { r -> proximaVez(r, agoraMs)?.let { r to it } }.minByOrNull { it.second }

    private fun pendente(ctx: Context, id: Int) = PendingIntent.getBroadcast(
        ctx, 3, Intent(ctx, RotinaReceiver::class.java).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun agendar(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val prox = proxima(ctx)
        if (prox == null) { am.cancel(pendente(ctx, 0)); return }
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, prox.second, pendente(ctx, prox.first.id))
            Log.i(TAG, "próxima: ${Despertador.horaTexto(Despertador.Alarme(0, prox.first.hora, prox.first.minuto, 0, "", true))} " +
                "${prox.first.acao} (id ${prox.first.id}) em ${(prox.second - System.currentTimeMillis()) / 60000} min")
        } catch (e: SecurityException) { Log.w(TAG, "sem permissão de alarme exato: $e") }
    }

    // ---------------------------------------------------------------- rodar

    /** Hora (relógio desde o boot) da última tecla do controle, vista pelo serviço de acessibilidade. */
    // 0 = ainda não sei (o processo acabou de subir). Na 1ª consulta conta a partir de agora: depois de um reinício do
    // launcher, prefere NÃO desligar a desligar no meio do filme de alguém.
    @Volatile private var ultimaTecla = 0L
    fun tecla() { ultimaTecla = SystemClock.elapsedRealtime() }
    fun minutosSemTecla(): Long {
        if (ultimaTecla == 0L) ultimaTecla = SystemClock.elapsedRealtime()
        return (SystemClock.elapsedRealtime() - ultimaTecla) / 60_000
    }

    /** Chamado pelo RotinaReceiver na hora marcada (e pelo adb, para testar). */
    fun rodar(ctx: Context, id: Int) {
        val r = listar(ctx).firstOrNull { it.id == id }
        if (r == null) { agendar(ctx); return }
        Log.i(TAG, "rodando rotina $id: ${r.acao}")
        when (r.acao) {
            Acao.LIGAR -> acordar(ctx, null)
            Acao.ABRIR -> acordar(ctx, r.pacote.ifBlank { null })
            Acao.DESLIGAR -> if (podeDesligar(r.ocioso, minutosSemTecla(), AutorizaShizukuService.instancia != null)) desligar(ctx)
                else Log.i(TAG, "não desliguei: alguém mexeu no controle há ${minutosSemTecla()} min (limite ${r.ocioso})")
        }
        // Uma vez só (sem dias marcados): desliga depois de rodar. Senão, só agenda a próxima.
        if (r.dias == 0) gravar(ctx, listar(ctx).map { if (it.id == id) it.copy(ativa = false) else it }) else agendar(ctx)
    }

    /**
     * Desligar com condição de ócio: sem o serviço de acessibilidade ninguém vê as teclas, então "ninguém mexeu" não
     * dá para saber; nesse caso NÃO desliga (desligaria no meio do filme). Sem condição (0), desliga sempre.
     */
    fun podeDesligar(ocioso: Int, minutosSemTecla: Long, vejoTeclas: Boolean) =
        ocioso <= 0 || (vejoTeclas && minutosSemTecla >= ocioso)

    private fun acordar(ctx: Context, pacote: String?) {
        // A tela própria acende a tela (setTurnScreenOn) e então abre o app. Vinda de segundo plano: liberado porque o
        // launcher tem a permissão de sobreposição (como o BootReceiver).
        try {
            ctx.startActivity(Intent(ctx, RotinaAcordarActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra("pacote", pacote))
        } catch (e: Exception) { Log.w(TAG, "não consegui acordar a TV: $e") }
    }

    /**
     * Apaga a tela: ação de acessibilidade "bloquear tela" (o mesmo que o botão de ligar do controle) e, se não houver o
     * serviço, o lockNow do Device Owner. Os dois mandam a TV para o standby pelo HDMI-CEC.
     */
    fun desligar(ctx: Context) {
        val app = ctx.applicationContext
        if (!app.getSystemService(PowerManager::class.java).isInteractive) return
        val pelaAcessibilidade = try {
            AutorizaShizukuService.instancia?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) == true
        } catch (e: Exception) { false }
        if (pelaAcessibilidade) return
        try { app.getSystemService(DevicePolicyManager::class.java).lockNow() } catch (e: Exception) { Log.w(TAG, "lockNow: $e") }
    }

    // ---------------------------------------------------------------- textos

    fun descricao(ctx: Context, r: Rotina): String = when (r.acao) {
        Acao.LIGAR -> "Ligar a TV"
        Acao.ABRIR -> "Ligar e abrir " + (rotuloApp(ctx, r.pacote) ?: "um app (escolha)")
        Acao.DESLIGAR -> "Desligar a TV" + if (r.ocioso > 0) " se ninguém mexeu há ${ociosoTexto(r.ocioso)}" else ""
    }

    fun ociosoTexto(min: Int) = when { min <= 0 -> "sempre"; min % 60 == 0 -> "${min / 60} h"; else -> "$min min" }

    fun rotuloApp(ctx: Context, pacote: String): String? = if (pacote.isBlank()) null else try {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pacote, 0)).toString()
    } catch (e: Exception) { pacote }
}

/** Recebe o alarme da rotina (não exportado: só o PendingIntent do próprio launcher chega aqui). */
class RotinaReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Rotinas.rodar(ctx.applicationContext, intent.getIntExtra(Rotinas.EXTRA_ID, 0))
    }
}
