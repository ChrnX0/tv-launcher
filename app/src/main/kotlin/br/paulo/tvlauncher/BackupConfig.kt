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
 *  - "dns": os servidores de DNS cadastrados (restaurar NÃO aplica o DNS na TV: só devolve a lista);
 *  - "rotinas": as rotinas por horário (Rotinas.kt).
 * O que NÃO entra: o PIN e o registro do painel ("painel_seguro"/"painel_log", fora da lista de propósito), as
 * chaves do adb (arquivos em filesDir) e o cache do clima.
 *
 * Formato (versão 2): {"versao":2,"pacote":...,"criadoEm":ms,"arquivos":{nome:{chave:{"t":tipo,"v":valor}}}}.
 * O tipo é guardado porque o JSON não distingue Int de Long nem Float de Double, e o SharedPreferences sim.
 */
object BackupConfig {
    private const val TAG = "TvLauncher.Backup"
    val ARQUIVOS = listOf("launcher", "despertador", "dns", "rotinas")   // "rotinas": Rotinas.kt (PR #30); a HOME reagenda ao reabrir
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

    /**
     * Tipo certo das chaves que o código do launcher lê (conferido no código em 04/11/2026). Vale mesmo quando a chave
     * ainda não existe na TV: um "soneca_em" vindo como texto faria o despertador travar ao ler. Chave fora desta
     * tabela continua aceita se tiver o mesmo tipo de hoje na TV (ou não existir ainda).
     */
    private val TIPOS_CONHECIDOS = mapOf(
        "despertador" to mapOf("alarmes" to "s", "soneca_em" to "l", "soneca_id" to "i"),
        "launcher" to mapOf("ordem" to "s", "pilhas" to "s", "ocultos" to "ss", "tema" to "s", "perfil_ativo" to "s",
            "shizuku_modo" to "s", "shizuku_ativo" to "b"),
        "dns" to mapOf("salvos" to "ss", "caiu_de" to "s"),
    )

    /**
     * Os alarmes são um JSON dentro de um texto; o Despertador lê id, hora e minuto como números obrigatórios.
     * Confere aqui o mesmo formato, para um backup estragado não travar o despertador (e a HOME) depois.
     */
    private fun validarAlarmes(texto: String) {
        if (!ChecagensPainel.jsonRaso(texto)) throw IllegalArgumentException("despertador/alarmes: JSON aninhado demais")
        val arr = try { JSONArray(texto) } catch (e: org.json.JSONException) { throw IllegalArgumentException("despertador/alarmes não é uma lista JSON") }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: throw IllegalArgumentException("despertador/alarmes: item $i não é um objeto")
            for (campo in listOf("id", "hora", "minuto")) {
                try { o.getInt(campo) } catch (e: org.json.JSONException) { throw IllegalArgumentException("despertador/alarmes: item $i sem '$campo' numérico") }
            }
            if (o.getInt("hora") !in 0..23 || o.getInt("minuto") !in 0..59) throw IllegalArgumentException("despertador/alarmes: item $i com horário inválido")
        }
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
                try {
                    when (t) {
                        "b" -> item.getBoolean("v"); "i" -> item.getInt("v"); "l" -> item.getLong("v")
                        "f" -> item.getDouble("v"); "s" -> item.getString("v")
                        "ss" -> { val a = item.getJSONArray("v"); for (i in 0 until a.length()) a.getString(i) }
                    }
                } catch (e: org.json.JSONException) {
                    throw IllegalArgumentException("$nome/$chave: o valor não é do tipo '$t'")
                }
                val esperado = TIPOS_CONHECIDOS[nome]?.get(chave) ?: tipoDe(atuais[chave])
                if (esperado != null && esperado != t) throw IllegalArgumentException("$nome/$chave: devia ser '$esperado' e veio '$t'")
                if (nome == "despertador" && chave == "alarmes") validarAlarmes(item.getString("v"))
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
        if (texto.length > LIMITE_BYTES || !ChecagensPainel.jsonRaso(texto)) throw IllegalArgumentException("arquivo grande ou aninhado demais")
        val json = try { JSONObject(texto) } catch (e: org.json.JSONException) { throw IllegalArgumentException("não é um JSON válido") }
        val arquivos = validar(ctx, json)
        val anterior = exportar(ctx)
        arquivoAnterior(ctx).writeText(anterior)   // o estado de agora, para desfazer
        try {
            val n = aplicar(ctx, arquivos)
            Despertador.agendar(ctx)   // os alarmes restaurados precisam ser agendados de novo
            Log.i(TAG, "restaurado: $n chaves")
            return n
        } catch (e: Exception) {
            // Falhou no meio (um arquivo gravou e outro não): volta tudo ao estado de antes, para não ficar misturado.
            Log.w(TAG, "restaurar falhou no meio, voltando ao anterior: $e")
            try { aplicar(ctx, JSONObject(anterior).getJSONObject("arquivos")) } catch (e2: Exception) { Log.w(TAG, "e voltar também falhou: $e2") }
            throw e
        }
    }

    /** Grava os arquivos de preferências do JSON já validado. Cada um num commit só. Devolve quantas chaves gravou. */
    private fun aplicar(ctx: Context, arquivos: JSONObject): Int {
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
        return n
    }
}
