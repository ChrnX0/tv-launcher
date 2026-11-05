package br.paulo.tvlauncher

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku

/**
 * Aba "Shizuku" do painel no navegador (05/11/2026, fila longa do brainstorm): quem usa o Shizuku embutido, com
 * permitir/revogar, e o histórico de quando cada app fez o servidor subir (modo sob demanda).
 *
 * A decisão "permitido" mora no servidor (AutorizacoesShizuku): com o servidor parado, a lista mostra os apps que
 * PEDEM o Shizuku (lidos do PackageManager), mas sem o estado. Permitir dá poder de shell (uid 2000) ao app, então o
 * painel pede o PIN de novo para isso; revogar não precisa.
 */
object PainelShizuku {
    private const val PERMISSAO = "moe.shizuku.manager.permission.API_V23"
    private const val CHAVE_HIST = "shizuku_hist"
    const val MAX_HIST = 50

    /** Anota que [pacote] fez o servidor subir agora. Chamado pelo ShizukuSobDemanda. Guarda os [MAX_HIST] últimos. */
    fun anotarSubida(ctx: Context, pacote: String, quando: Long = System.currentTimeMillis()) {
        val p = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
        val novo = juntarHistorico(p.getString(CHAVE_HIST, "[]") ?: "[]", pacote, quando, MAX_HIST)
        p.edit().putString(CHAVE_HIST, novo).apply()
    }

    /** O histórico com a entrada nova no começo, cortado em [maximo]. Texto estragado recomeça do zero. */
    fun juntarHistorico(atual: String, pacote: String, quando: Long, maximo: Int): String {
        val antigo = try { JSONArray(atual) } catch (e: Exception) { JSONArray() }
        val saida = JSONArray().put(JSONObject().put("p", pacote).put("t", quando))
        for (i in 0 until minOf(antigo.length(), maximo - 1)) antigo.optJSONObject(i)?.let { saida.put(it) }
        return saida.toString()
    }

    fun estado(ctx: Context): JSONObject {
        val pm = ctx.packageManager
        val rodando = try { Shizuku.pingBinder() } catch (e: Throwable) { false }
        val o = JSONObject().put("modo", Config.modoShizuku(ctx).rotulo).put("rodando", rodando)
        val apps = JSONArray()
        @Suppress("DEPRECATION")
        val pedem = try {
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
                .filter { it.packageName != ctx.packageName && it.requestedPermissions?.contains(PERMISSAO) == true }
        } catch (e: Exception) { emptyList() }
        for (pi in pedem) {
            val ai = pi.applicationInfo ?: continue
            val a = JSONObject().put("pacote", pi.packageName).put("nome", try { ai.loadLabel(pm).toString() } catch (e: Exception) { pi.packageName })
            if (rodando) try { a.put("permitido", AutorizacoesShizuku.permitido(ai.uid)) } catch (e: Throwable) {}
            apps.put(a)
        }
        o.put("apps", apps)
        val hist = try { JSONArray(ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).getString(CHAVE_HIST, "[]")) } catch (e: Exception) { JSONArray() }
        for (i in 0 until hist.length()) hist.optJSONObject(i)?.let { h ->
            val p = h.optString("p")
            h.put("nome", try { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() } catch (e: Exception) { p })
        }
        return o.put("historico", hist)
    }

    /** Permite ([permitir] true) ou revoga. Devolve null se deu certo, ou o motivo. */
    fun mudar(ctx: Context, pacote: String, permitir: Boolean): String? {
        if (!ChecagensPainel.pacoteValido(pacote)) return "pacote inválido"
        if (!(try { Shizuku.pingBinder() } catch (e: Throwable) { false })) return "o servidor do Shizuku está parado: abra o app (sob demanda) ou ligue em Configurações > Shizuku"
        val uid = try { ctx.packageManager.getApplicationInfo(pacote, 0).uid } catch (e: Exception) { return "app não encontrado" }
        return try {
            if (permitir) AutorizacoesShizuku.permitir(uid) else AutorizacoesShizuku.revogar(uid); null
        } catch (e: Throwable) { "o servidor recusou: ${e.javaClass.simpleName}" }
    }
}
