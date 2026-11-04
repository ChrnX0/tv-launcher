package br.paulo.tvlauncher

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Backup das configurações do launcher (04/11/2026), baixado e restaurado pelo painel no navegador (PainelWeb).
 * Veio do PR #9 (que puxava pelo painel do PC por adb); aqui ficou só a parte de montar e aplicar o JSON.
 *
 * O que entra: os arquivos de preferências [ARQUIVOS] inteiros:
 *  - "launcher": ordem e pilhas dos apps, ocultos, perfil ativo, tema, botões do controle, Shizuku...
 *  - "despertador": os alarmes;
 *  - "dns": os servidores de DNS cadastrados (restaurar NÃO aplica o DNS na TV: só devolve a lista).
 * O que NÃO entra: o PIN e o registro do painel ("painel_seguro"/"painel_log", fora da lista de propósito), as
 * chaves do adb (arquivos em filesDir) e o cache do clima.
 *
 * Formato (versão 2): {"versao":2,"pacote":...,"criadoEm":ms,"arquivos":{nome:{chave:{"t":tipo,"v":valor}}}}.
 * O tipo é guardado porque o JSON não distingue Int de Long nem Float de Double, e o SharedPreferences sim.
 */
object BackupConfig {
    private const val TAG = "TvLauncher.Backup"
    val ARQUIVOS = listOf("launcher", "despertador", "dns")
    const val LIMITE_BYTES = 512 * 1024   // um backup real tem poucos KB; isto barra lixo grande

    /** Cópia do estado de antes da última restauração (para desfazer: o painel deixa baixar). */
    fun arquivoAnterior(ctx: Context) = File(ctx.filesDir, "backup-antes-de-restaurar.json")

    private fun exportarArquivo(p: SharedPreferences): JSONObject {
        val saida = JSONObject()
        for ((chave, valor) in p.all) {
            val item = when (valor) {
                is Boolean -> JSONObject().put("t", "b").put("v", valor)
                is Int -> JSONObject().put("t", "i").put("v", valor)
                is Long -> JSONObject().put("t", "l").put("v", valor)
                is Float -> JSONObject().put("t", "f").put("v", valor.toDouble())
                is String -> JSONObject().put("t", "s").put("v", valor)
                is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(valor.map { it.toString() }))
                else -> continue
            }
            saida.put(chave, item)
        }
        return saida
    }

    /** O backup inteiro, em texto JSON. */
    fun exportar(ctx: Context): String {
        val arquivos = JSONObject()
        for (nome in ARQUIVOS) arquivos.put(nome, exportarArquivo(ctx.getSharedPreferences(nome, Context.MODE_PRIVATE)))
        return JSONObject().put("versao", 2).put("pacote", ctx.packageName)
            .put("criadoEm", System.currentTimeMillis()).put("arquivos", arquivos).toString(2)
    }

    /** Tipo de um valor já gravado, no mesmo código do JSON ("b", "i"...). */
    private fun tipoDe(valor: Any?): String? = when (valor) {
        is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; is String -> "s"; is Set<*> -> "ss"
        else -> null
    }

    /**
     * Confere o JSON INTEIRO antes de mexer em qualquer coisa: versão, pacote, só arquivos conhecidos, tipos
     * conhecidos e, para chave que já existe na TV, o MESMO tipo de hoje (um Int virando String faria a tela
     * que lê aquela chave travar com ClassCastException). Lança IllegalArgumentException com o motivo.
     */
    private fun validar(ctx: Context, json: JSONObject): JSONObject {
        if (json.optInt("versao") != 2) throw IllegalArgumentException("versão de backup desconhecida: ${json.opt("versao")}")
        if (json.optString("pacote") != ctx.packageName) throw IllegalArgumentException("backup de outro app: ${json.optString("pacote")}")
        val arquivos = json.optJSONObject("arquivos") ?: throw IllegalArgumentException("sem \"arquivos\"")
        for (nome in arquivos.keys()) {
            if (nome !in ARQUIVOS) throw IllegalArgumentException("arquivo desconhecido no backup: $nome")
            val atuais = ctx.getSharedPreferences(nome, Context.MODE_PRIVATE).all
            val prefs = arquivos.optJSONObject(nome) ?: throw IllegalArgumentException("\"$nome\" não é um objeto")
            for (chave in prefs.keys()) {
                val item = prefs.optJSONObject(chave) ?: throw IllegalArgumentException("$nome/$chave não é um objeto")
                val t = item.optString("t")
                if (t !in setOf("b", "i", "l", "f", "s", "ss")) throw IllegalArgumentException("$nome/$chave: tipo '$t' desconhecido")
                if (!item.has("v")) throw IllegalArgumentException("$nome/$chave: sem valor")
                // Confere que o valor é do tipo dito (getX lança JSONException se não for).
                when (t) {
                    "b" -> item.getBoolean("v"); "i" -> item.getInt("v"); "l" -> item.getLong("v")
                    "f" -> item.getDouble("v"); "s" -> item.getString("v")
                    "ss" -> { val a = item.getJSONArray("v"); for (i in 0 until a.length()) a.getString(i) }
                }
                val hoje = tipoDe(atuais[chave])
                if (hoje != null && hoje != t) throw IllegalArgumentException("$nome/$chave: era '$hoje' na TV e veio '$t'")
            }
        }
        return arquivos
    }

    /**
     * Restaura a partir do texto [texto]. Cada arquivo de preferências presente no backup é SUBSTITUÍDO inteiro, num
     * commit só; os que não estão no backup ficam como estão. Antes grava o estado atual em [arquivoAnterior].
     * Depois reagenda os alarmes. Quem chama reinicia o launcher (várias telas leem as preferências só ao abrir).
     * Devolve quantas chaves restaurou; lança IllegalArgumentException se o backup for inválido (nada é alterado).
     */
    fun importar(ctx: Context, texto: String): Int {
        val json = try { JSONObject(texto) } catch (e: org.json.JSONException) { throw IllegalArgumentException("não é um JSON válido") }
        val arquivos = validar(ctx, json)
        arquivoAnterior(ctx).writeText(exportar(ctx))   // o estado de agora, para desfazer
        var n = 0
        for (nome in arquivos.keys()) {
            val prefs = arquivos.getJSONObject(nome)
            val ed = ctx.getSharedPreferences(nome, Context.MODE_PRIVATE).edit().clear()
            for (chave in prefs.keys()) {
                val item = prefs.getJSONObject(chave)
                when (item.getString("t")) {
                    "b" -> ed.putBoolean(chave, item.getBoolean("v"))
                    "i" -> ed.putInt(chave, item.getInt("v"))
                    "l" -> ed.putLong(chave, item.getLong("v"))
                    "f" -> ed.putFloat(chave, item.getDouble("v").toFloat())
                    "s" -> ed.putString(chave, item.getString("v"))
                    "ss" -> { val a = item.getJSONArray("v"); ed.putStringSet(chave, (0 until a.length()).map { a.getString(it) }.toSet()) }
                }
                n++
            }
            if (!ed.commit()) throw IllegalStateException("não consegui gravar '$nome'")
        }
        Despertador.agendar(ctx)   // os alarmes restaurados precisam ser agendados de novo
        Log.i(TAG, "restaurado: $n chaves")
        return n
    }
}
