package br.paulo.tvlauncher

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Backup das configurações do launcher (04/11/2026), para fora da TV: o painel do PC puxa por adb e guarda versões.
 *
 * O que entra: os arquivos de preferências [ARQUIVOS] inteiros:
 *  - "launcher": ordem e pilhas dos apps, ocultos, perfil ativo, tema, botões do controle, Shizuku...
 *  - "despertador": os alarmes;
 *  - "dns": os servidores de DNS que o Paulo cadastrou (restaurar NÃO aplica o DNS na TV: só devolve a lista).
 * O que NÃO entra: as chaves do adb (adb_key.pk8/x509, em filesDir, fora das preferências) e o cache do clima.
 *
 * Por que pela pasta "externa" do app (/sdcard/Android/data/br.paulo.tvlauncher/files/backup): o app não é
 * depurável, então o adb não lê a pasta privada (run-as não funciona); essa pasta o shell lê e escreve.
 * Disparo: só por adb (BACKUP / RESTAURAR no ControleShizukuAdbReceiver, protegido por DUMP).
 *
 * Formato (versão 2): {"versao":2,"pacote":...,"criadoEm":ms,"arquivos":{nome:{chave:{"t":tipo,"v":valor}}}}.
 * O tipo é guardado porque o JSON não distingue Int de Long nem Float de Double, e o SharedPreferences sim.
 */
object BackupConfig {
    private const val TAG = "TvLauncher.Backup"
    val ARQUIVOS = listOf("launcher", "despertador", "dns")

    private fun pasta(ctx: Context): File? = ctx.getExternalFilesDir("backup")?.apply { mkdirs() }
    fun arquivoBackup(ctx: Context): File? = pasta(ctx)?.let { File(it, "config.json") }
    fun arquivoRestaurar(ctx: Context): File? = pasta(ctx)?.let { File(it, "restaurar.json") }

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

    /** Grava o backup e devolve um resumo (ou o erro). */
    fun exportar(ctx: Context): String = try {
        val arquivos = JSONObject()
        var total = 0
        for (nome in ARQUIVOS) {
            val dados = exportarArquivo(ctx.getSharedPreferences(nome, Context.MODE_PRIVATE))
            arquivos.put(nome, dados); total += dados.length()
        }
        val json = JSONObject().put("versao", 2).put("pacote", ctx.packageName)
            .put("criadoEm", System.currentTimeMillis()).put("arquivos", arquivos)
        val destino = arquivoBackup(ctx) ?: throw IllegalStateException("sem pasta externa")
        val tmp = File(destino.path + ".tmp")
        tmp.writeText(json.toString(2))
        if (!tmp.renameTo(destino)) { destino.delete(); tmp.renameTo(destino) }
        Log.i(TAG, "backup: $total chaves em ${ARQUIVOS.size} arquivos -> ${destino.path}")
        "ok chaves=$total arquivo=${destino.path}"
    } catch (e: Exception) { Log.w(TAG, "backup falhou: $e"); "erro ${e.javaClass.simpleName}: ${e.message}" }

    /**
     * Aplica o restaurar.json (que o painel envia antes). Cada arquivo de preferências presente no backup é
     * SUBSTITUÍDO inteiro, num commit só; os que não estão no backup ficam como estão. Antes, grava o estado atual
     * (config.json). Depois reagenda os alarmes e pede para reiniciar o launcher (REINICIAR): várias telas leem as
     * preferências só ao abrir.
     */
    fun importar(ctx: Context): String = try {
        val origem = arquivoRestaurar(ctx) ?: throw IllegalStateException("sem pasta externa")
        if (!origem.exists()) throw IllegalStateException("não achei ${origem.path}")
        val json = JSONObject(origem.readText())
        if (json.optInt("versao") != 2) throw IllegalArgumentException("versão desconhecida: ${json.opt("versao")}")
        if (json.optString("pacote") != ctx.packageName) throw IllegalArgumentException("backup de outro app: ${json.optString("pacote")}")
        val arquivos = json.getJSONObject("arquivos")
        exportar(ctx)   // o estado de agora, antes de substituir
        var n = 0
        for (nome in arquivos.keys()) {
            if (nome !in ARQUIVOS) continue   // só os arquivos que sabemos o que são
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
                    else -> continue
                }
                n++
            }
            if (!ed.commit()) throw IllegalStateException("commit de '$nome' falhou")
        }
        origem.delete()
        Despertador.agendar(ctx)   // os alarmes restaurados precisam ser agendados de novo
        Log.i(TAG, "restaurado: $n chaves")
        "ok restauradas=$n (reinicie o launcher: REINICIAR)"
    } catch (e: Exception) { Log.w(TAG, "restaurar falhou: $e"); "erro ${e.javaClass.simpleName}: ${e.message}" }
}
