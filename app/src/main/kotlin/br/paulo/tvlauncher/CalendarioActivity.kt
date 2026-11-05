package br.paulo.tvlauncher

import android.Manifest
import android.accounts.Account
import android.app.Activity
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Agenda do perfil ativo (a conta Google escolhida na bolinha): abre com OK na data do topo.
 * Esquerda: o mês, com hoje marcado e um ponto nos dias com evento (◀ ▶ trocam o mês).
 * Direita: os eventos dos próximos 30 dias (▲ ▼ rolam).
 *
 * Os eventos vêm do armazenamento de agenda do próprio Android (CalendarContract), que o sincronizador da agenda
 * do Google preenche. Ao abrir, pedimos uma sincronização da conta: eventos novos podem levar alguns segundos.
 * Precisa de READ_CALENDAR (dada uma vez por adb: pm grant br.paulo.tvlauncher android.permission.READ_CALENDAR).
 */
private typealias Evento = Agenda.Evento

class CalendarioActivity : Activity() {
    private val textoClaro = 0xFFE8EEEA.toInt()
    private val textoSuave = 0xFF93A19A.toInt()
    private val destaque = 0xFF7FD6C2.toInt()
    private val fonteMedia = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val pt = Locale("pt", "BR")
    private val ui = Handler(Looper.getMainLooper())
    private val mes = Calendar.getInstance()   // o mês mostrado na grade
    private lateinit var tituloMes: TextView
    private lateinit var grade: GridLayout
    private lateinit var listaEventos: LinearLayout
    private lateinit var aviso: TextView
    private var conta: String? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        conta = Perfil.ativa(this)
        setContentView(montar())
        // Item 30 da revisão (04/11/2026): antes redesenhava UMA vez, 6 s depois de abrir, e o que a sincronização
        // trouxesse depois ficava de fora. Agora o provedor da agenda avisa a cada mudança enquanto a tela estiver
        // aberta (registrado ANTES de pedir a sincronização, para não perder o aviso). Redesenho agrupado: 1 s.
        try {
            contentResolver.registerContentObserver(CalendarContract.Events.CONTENT_URI, true, observador)
        } catch (e: Exception) { Log.w("TvLauncher.Agenda", "sem observador da agenda: $e") }
        sincronizar()
        desenhar()
    }

    private val redesenhar = Runnable { desenhar() }
    private val observador = object : android.database.ContentObserver(ui) {
        override fun onChange(selfChange: Boolean) {
            ui.removeCallbacks(redesenhar)
            ui.postDelayed(redesenhar, 1000)
        }
    }

    private fun montar(): FrameLayout {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(Grade.MARGEM), dp(Grade.TOPO), dp(Grade.MARGEM), dp(Grade.TOPO))
        }
        // Coluna do mês: colunas 1-5 da grade.
        val colunaMes = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tituloMes = texto(30f, Color.WHITE, fonteMedia)
        colunaMes.addView(tituloMes)
        colunaMes.addView(texto(15f, textoSuave).apply {
            text = (conta ?: "sem conta Google") + "\n◀ ▶ trocam o mês"
            setPadding(0, dp(4), 0, dp(18))
        })
        grade = GridLayout(this).apply { columnCount = 7 }
        colunaMes.addView(grade)
        raiz.addView(colunaMes, LinearLayout.LayoutParams(dp(Grade.largura(5)), ViewGroup.LayoutParams.WRAP_CONTENT))

        // Coluna dos eventos: colunas 7-12.
        val colunaEventos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        colunaEventos.addView(texto(22f, Color.WHITE, fonteMedia).apply { text = "Próximos 30 dias" })
        aviso = texto(15f, textoSuave).apply { setPadding(0, dp(8), 0, 0) }
        colunaEventos.addView(aviso)
        listaEventos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        colunaEventos.addView(ScrollView(this).apply {
            isFocusable = true   // recebe o foco: ▲▼ rolam a lista
            addView(listaEventos)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(400)).apply { topMargin = dp(12) })
        raiz.addView(colunaEventos, LinearLayout.LayoutParams(dp(Grade.largura(6)), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(Grade.COLUNA + 2 * Grade.VAO)   // pula a coluna 6
        })

        return FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF1B2326.toInt(), 0xFF0F1416.toInt(), 0xFF0A0D0E.toInt()))
            addView(raiz)
        }
    }

    /** Pede ao Android para sincronizar a agenda de TODAS as contas Google da TV (a do perfil ativo é a que mostramos). */
    private fun sincronizar() {
        try {
            Perfil.contas(this).forEach { nome ->
                val a = Account(nome, "com.google")
                ContentResolver.setIsSyncable(a, CalendarContract.AUTHORITY, 1)
                ContentResolver.setSyncAutomatically(a, CalendarContract.AUTHORITY, true)
                ContentResolver.requestSync(a, CalendarContract.AUTHORITY, Bundle().apply {
                    putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                    putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                })
            }
        } catch (e: Exception) {
            Log.w("TvLauncher.Agenda", "nao consegui pedir a sincronizacao: $e")
        }
    }

    // A consulta à agenda (contentResolver.query) roda FORA da thread da tela (revisão de 04/11/2026): na thread da
    // tela ela atrasava também as teclas do controle (o serviço de teclas divide o processo e a thread com o launcher).
    private val trabalho = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun desenhar() {
        val m = mes.clone() as Calendar
        trabalho.execute {
            val eventosMes = eventos(inicioDoMes(m), fimDoMes(m))
            val agora = System.currentTimeMillis()
            val proximos = eventos(agora, agora + 30L * 86_400_000)
            ui.post { if (!isDestroyed) { desenharMes(eventosMes); desenharProximos(proximos) } }
        }
    }

    /** Só a grade do mês (◀ ▶): consulta em segundo plano, desenha quando chegar. */
    private fun desenharSoMes() {
        val m = mes.clone() as Calendar
        trabalho.execute {
            val ev = eventos(inicioDoMes(m), fimDoMes(m))
            ui.post { if (!isDestroyed && m.get(Calendar.MONTH) == mes.get(Calendar.MONTH) && m.get(Calendar.YEAR) == mes.get(Calendar.YEAR)) desenharMes(ev) }
        }
    }

    private fun desenharProximos(proximos: List<Evento>) {
        listaEventos.removeAllViews()
        aviso.text = when {
            checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED ->
                "Sem permissão de agenda (pm grant br.paulo.tvlauncher android.permission.READ_CALENDAR)"
            conta == null -> "Nenhuma conta Google na TV"
            proximos.isEmpty() -> "Nada marcado (ou a agenda ainda está sincronizando)"
            else -> ""
        }
        var diaAnterior = ""
        val fmtDia = SimpleDateFormat("EEEE, d 'de' MMMM", pt)
        val fmtHora = SimpleDateFormat("HH:mm", pt)
        proximos.forEach { e ->
            val dia = fmtDia.format(e.inicio).replaceFirstChar { it.uppercase() }
            if (dia != diaAnterior) {
                listaEventos.addView(texto(14f, destaque, fonteMedia).apply { text = dia; setPadding(0, dp(14), 0, dp(4)) })
                diaAnterior = dia
            }
            listaEventos.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
                addView(android.view.View(this@CalendarioActivity).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(e.cor) }
                }, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(12) })
                addView(texto(16f, textoSuave).apply {
                    text = if (e.diaInteiro) "Dia todo" else fmtHora.format(e.inicio) + "–" + fmtHora.format(e.fim)
                }, LinearLayout.LayoutParams(dp(132), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(12) })
                addView(texto(17f, textoClaro).apply { text = e.titulo; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        }
    }

    /** A grade do mês: cabeçalho D S T Q Q S S, dias com ponto quando há evento, hoje num círculo. */
    private fun desenharMes(eventosMes: List<Evento>) {
        tituloMes.text = SimpleDateFormat("MMMM 'de' yyyy", pt).format(mes.time).replaceFirstChar { it.uppercase() }
        grade.removeAllViews()
        val lado = dp(Grade.largura(5)) / 7
        Despertador.NOMES_DIAS.forEach { grade.addView(celula(it, textoSuave, false, false, lado)) }
        val c = (mes.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
        repeat(c.get(Calendar.DAY_OF_WEEK) - 1) { grade.addView(celula("", textoSuave, false, false, lado)) }   // vazios antes do dia 1
        val hoje = Calendar.getInstance()
        // Todos os dias que o evento cobre, não só o 1º (revisão de 04/11/2026: viagem de 3 dias marcava só a ida).
        val iniMes = inicioDoMes(mes); val fimMes = fimDoMes(mes)
        val diasComEvento = HashSet<Int>()
        for (e in eventosMes) {
            val dia = Calendar.getInstance().apply { timeInMillis = maxOf(e.inicio, iniMes) }
            val ultimo = minOf(maxOf(e.fim - 1, e.inicio), fimMes - 1)
            while (dia.timeInMillis <= ultimo) { diasComEvento.add(dia.get(Calendar.DAY_OF_MONTH)); dia.add(Calendar.DAY_OF_MONTH, 1) }
        }
        for (d in 1..c.getActualMaximum(Calendar.DAY_OF_MONTH)) {
            val eHoje = hoje.get(Calendar.YEAR) == c.get(Calendar.YEAR) && hoje.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)
            grade.addView(celula(d.toString(), if (eHoje) Color.BLACK else textoClaro, eHoje, d in diasComEvento, lado))
            c.add(Calendar.DAY_OF_MONTH, 1)
        }
    }

    private fun celula(rotulo: String, cor: Int, hoje: Boolean, comEvento: Boolean, lado: Int) = FrameLayout(this).apply {
        layoutParams = GridLayout.LayoutParams().apply { width = lado; height = lado }
        addView(texto(17f, cor, if (hoje) fonteMedia else null).apply {
            text = rotulo
            gravity = Gravity.CENTER
            if (hoje) background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(destaque) }
        }, FrameLayout.LayoutParams(lado - dp(10), lado - dp(10), Gravity.CENTER))
        if (comEvento) addView(android.view.View(this@CalendarioActivity).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(destaque) }
        }, FrameLayout.LayoutParams(dp(5), dp(5), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(2) })
    }

    /** Ocorrências entre [de] e [ate], só das agendas visíveis da conta do perfil ativo, em ordem de início. */
    private fun eventos(de: Long, ate: Long): List<Evento> = Agenda.eventos(this, conta, de, ate)

    private fun inicioDoMes(c: Calendar) = (c.clone() as Calendar).apply {
        set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun fimDoMes(c: Calendar) = (c.clone() as Calendar).apply {
        set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.MONTH, 1)
    }.timeInMillis

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { mes.add(Calendar.MONTH, -1); desenharSoMes(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { mes.add(Calendar.MONTH, 1); desenharSoMes(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        try { contentResolver.unregisterContentObserver(observador) } catch (_: Exception) {}
        ui.removeCallbacksAndMessages(null)
        trabalho.shutdownNow()
        super.onDestroy()
    }

    private fun texto(sp: Float, cor: Int, fonte: Typeface? = null) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(cor)
        if (fonte != null) typeface = fonte
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}
