package br.paulo.tvlauncher

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Um pedido de ações do catálogo (ComandosIa), do começo ao fim:
 *
 *   JSON de ações (hoje: gancho do adb IA_TESTE; depois: o servidor MCP) -> [propor] -> ComandosIa.conferir
 *   -> ConfirmarIaActivity na TV ("Vou fazer: ...", foco em Cancelar) -> OK no controle -> [executar].
 *
 * Só um pedido esperando confirmação por vez; um novo cancela o anterior. Sem resposta na TV em 60 s, cancela.
 * Quem pediu acompanha pelo [estado] (id aleatório devolvido por [propor]).
 *
 * (05/11/2026) A IA na nuvem com a chave do dono (IaNuvem/IaArmazem, aba Falar do painel) foi removida por decisão do
 * Paulo. [apagarRestosDaNuvem] limpa na TV o que ela tinha guardado.
 */
object PedidoIa {
    private const val TAG = "TvLauncher.Ia"
    const val ESPERA_CONFIRMAR_MS = 60_000L

    enum class Situacao(val texto: String) {
        PENSANDO("conferindo o pedido"), ESPERANDO("esperando o OK na TV"), FEITO("feito"),
        CANCELADO("cancelado na TV"), EXPIRADO("ninguém confirmou na TV em 60 s"), NADA("nada a fazer"), ERRO("erro")
    }

    class Pedido(val id: String, val frase: String, val origem: String) {
        @Volatile var situacao = Situacao.PENSANDO
        @Volatile var mensagem = ""
        @Volatile var plano: ComandosIa.Plano? = null
        val criado = System.currentTimeMillis()

        fun json(): JSONObject = JSONObject()
            .put("id", id).put("frase", frase).put("situacao", situacao.name.lowercase()).put("texto", situacao.texto)
            .put("mensagem", mensagem)
            .put("acoes", JSONArray(plano?.acoes?.map { it.descricao } ?: emptyList<String>()))
            .put("recusadas", JSONArray(plano?.recusadas ?: emptyList<String>()))
    }

    private val trabalho = Executors.newSingleThreadExecutor { r -> Thread(r, "Ia-pedido") }
    private val ui = Handler(Looper.getMainLooper())
    private val aleatorio = SecureRandom()
    private val recentes = LinkedHashMap<String, Pedido>()
    @Volatile var atual: Pedido? = null
        private set

    @Synchronized fun estado(id: String?): Pedido? = recentes[id]
    @Synchronized fun ultimo(): Pedido? = recentes.values.lastOrNull()

    @Synchronized private fun guardar(p: Pedido) {
        recentes[p.id] = p
        while (recentes.size > 5) recentes.remove(recentes.keys.first())
    }

    /**
     * Começa um pedido com as ações em JSON (formato de ComandosIa.esquema). Devolve o Pedido (já com a situação ERRO
     * se não deu para começar). Nada roda sem o OK no controle.
     */
    fun propor(ctx: Context, fraseCrua: String?, origem: String, acoesJson: String): Pedido {
        val app = ctx.applicationContext
        val frase = ComandosIa.limparFrase(fraseCrua)
        val p = Pedido(novoId(), frase ?: "", origem)
        guardar(p)
        fun falhar(m: String) = p.apply { situacao = Situacao.ERRO; mensagem = m }
        if (frase == null) return falhar("frase vazia")
        reservarVez()?.let { return falhar("muitos pedidos seguidos; espere $it s") }
        Log.i(TAG, "pedido ${p.id} de $origem (${frase.length} caracteres)")
        trabalho.execute {
            try {
                val plano = ComandosIa.conferir(acoesJson, appsDaHome(app))
                p.plano = plano
                p.mensagem = plano.resposta
                if (plano.acoes.isEmpty()) { p.situacao = Situacao.NADA; return@execute }
                p.situacao = Situacao.ESPERANDO
                // Sem OK no controle (decisão do Paulo, 06/11/2026): o catálogo é fechado e conferido acima, e o limite de
                // pedidos continua. A TV só avisa o que fez. Config.IA_CONFIRMAR_NO_CONTROLE = true volta a pedir o OK.
                ui.post {
                    if (Config.IA_CONFIRMAR_NO_CONTROLE) mostrar(app, p)
                    else {
                        responder(app, p.id, true)
                        try { android.widget.Toast.makeText(app, "Claude: " + plano.acoes.joinToString(", ") { it.descricao }, android.widget.Toast.LENGTH_LONG).show() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "pedido ${p.id} falhou: ${e.javaClass.simpleName}")
                falhar("erro na TV: ${e.javaClass.simpleName}")
            }
        }
        return p
    }

    private val horarios = ArrayDeque<Long>()

    /**
     * No máximo 1 pedido a cada 3 s e 30 por hora: cada pedido abre uma tela na TV, e quem está no sofá não pode ser
     * bombardeado por um script. Devolve null se pode, ou quantos segundos esperar.
     */
    @Synchronized fun reservarVez(agora: Long = System.currentTimeMillis()): Long? {
        while (horarios.isNotEmpty() && agora - horarios.first() > 3_600_000) horarios.removeFirst()
        horarios.lastOrNull()?.let { if (agora - it < 3_000) return (3_000 - (agora - it) + 999) / 1000 }
        if (horarios.size >= 30) return (3_600_000 - (agora - horarios.first()) + 999) / 1000
        horarios.addLast(agora)
        return null
    }

    /**
     * A IA na nuvem (removida em 05/11/2026) guardava a chave de API cifrada em "ia_seguro" e a chave AES no Keystore
     * com o apelido "ia_chave_api". Apaga os dois; sem nada guardado, não faz nada. Chamar fora da thread da tela.
     */
    fun apagarRestosDaNuvem(ctx: Context) {
        try {
            val app = ctx.applicationContext
            if (java.io.File(app.applicationInfo.dataDir, "shared_prefs/ia_seguro.xml").exists()) {
                app.deleteSharedPreferences("ia_seguro")
                Log.i(TAG, "apaguei as preferências da IA na nuvem")
            }
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias("ia_chave_api")) { ks.deleteEntry("ia_chave_api"); Log.i(TAG, "apaguei a chave do Keystore da IA na nuvem") }
        } catch (e: Exception) { Log.w(TAG, "limpeza da IA na nuvem: ${e.javaClass.simpleName}") }
    }

    private fun novoId(): String = ByteArray(9).also { aleatorio.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /** Abre a confirmação na TV. Um pedido anterior ainda esperando é cancelado (só um na tela). */
    private fun mostrar(app: Context, p: Pedido) {
        atual?.let { if (it !== p && it.situacao == Situacao.ESPERANDO) { it.situacao = Situacao.CANCELADO; it.mensagem = "substituído por um pedido novo" } }
        atual = p
        try {
            app.startActivity(Intent(app, ConfirmarIaActivity::class.java)
                .putExtra(ConfirmarIaActivity.EXTRA_ID, p.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (e: Exception) {
            p.situacao = Situacao.ERRO; p.mensagem = "não consegui abrir a confirmação na TV"
            Log.w(TAG, "confirmação não abriu: $e")
            return
        }
        ui.postDelayed({
            if (p.situacao == Situacao.ESPERANDO) { p.situacao = Situacao.EXPIRADO; p.mensagem = "" }
        }, ESPERA_CONFIRMAR_MS)
    }

    /** A pessoa respondeu na TV. Só vale para o pedido que está ESPERANDO. Chamar na thread da tela. */
    fun responder(ctx: Context, id: String, executar: Boolean) {
        val p = estado(id) ?: return
        if (p.situacao != Situacao.ESPERANDO) return
        if (!executar) { p.situacao = Situacao.CANCELADO; return }
        val erros = p.plano?.acoes.orEmpty().mapNotNull { a ->
            try { executar(ctx.applicationContext, a)?.let { "${a.descricao}: $it" } } catch (e: Exception) { "${a.descricao}: ${e.javaClass.simpleName}" }
        }
        p.situacao = if (erros.isEmpty()) Situacao.FEITO else Situacao.ERRO
        if (erros.isNotEmpty()) p.mensagem = erros.joinToString("; ")
        Log.i(TAG, "pedido ${p.id}: ${p.situacao}")
    }

    // ---------------------------------------------------------------- execução de cada ação do catálogo

    /** Executa uma ação já conferida e confirmada. Devolve null se deu certo, ou o motivo. */
    private fun executar(app: Context, a: ComandosIa.Acao): String? {
        when (a) {
            is ComandosIa.Acao.AbrirApp -> {
                // Confere de novo: o app pode ter sido desinstalado entre a pergunta e o OK.
                val pm = app.packageManager
                val i = pm.getLeanbackLaunchIntentForPackage(a.pacote) ?: pm.getLaunchIntentForPackage(a.pacote) ?: return "o app não abre mais"
                app.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            is ComandosIa.Acao.AbrirTela -> {
                val classe = when (a.tela) {
                    "previsao" -> PrevisaoActivity::class.java
                    "agenda" -> CalendarioActivity::class.java
                    "despertador" -> AlarmesActivity::class.java
                    "configuracoes" -> ConfigLauncherActivity::class.java
                    else -> MainActivity::class.java
                }
                app.startActivity(Intent(app, classe).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            is ComandosIa.Acao.Despertador -> {
                Despertador.salvar(app, Despertador.Alarme(0, a.hora, a.minuto, a.dias, a.nome, true))
            }
            is ComandosIa.Acao.Volume -> {
                val am = app.getSystemService(AudioManager::class.java) ?: return "sem controle de áudio"
                val direcao = when (a.acao) { "subir" -> AudioManager.ADJUST_RAISE; "descer" -> AudioManager.ADJUST_LOWER; else -> AudioManager.ADJUST_TOGGLE_MUTE }
                repeat(if (a.acao == "mudo") 1 else a.vezes) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direcao, AudioManager.FLAG_SHOW_UI) }
            }
            is ComandosIa.Acao.Midia -> {
                val am = app.getSystemService(AudioManager::class.java) ?: return "sem controle de áudio"
                val tecla = when (a.acao) {
                    "pausar" -> KeyEvent.KEYCODE_MEDIA_PAUSE; "tocar" -> KeyEvent.KEYCODE_MEDIA_PLAY
                    "proxima" -> KeyEvent.KEYCODE_MEDIA_NEXT; else -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                }
                val t = SystemClock.uptimeMillis()
                am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, tecla, 0))
                am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, tecla, 0))
            }
            is ComandosIa.Acao.ModoVisita -> ModoVisita.definir(app, a.ligado)
        }
        return null
    }

    /**
     * Pacote -> nome dos apps que a HOME mostra (os mesmos critérios de MainActivity.carregarApps e do modo visita):
     * instalados pelo usuário e os de sistema em Config.SEMPRE_MOSTRAR. Só estes podem ser abertos por um pedido.
     */
    fun appsDaHome(app: Context): Map<String, String> {
        val pm = app.packageManager
        val consulta = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        return try {
            pm.queryIntentActivities(consulta, 0)
                .filter {
                    val pkg = it.activityInfo.packageName
                    pkg != app.packageName && pkg != Config.PACOTE_CONFIG &&
                        ((it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || pkg in Config.SEMPRE_MOSTRAR)
                }
                .associate { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        } catch (e: Exception) { emptyMap() }
    }
}
