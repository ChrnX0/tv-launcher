package br.paulo.tvlauncher

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * O que o painel no navegador grava na TV.
 *
 * Arquivo de preferências SEPARADO, "painel_seguro", e não o "launcher": o backup (BackupConfig) exporta e
 * substitui o "launcher" inteiro. Se o PIN morasse lá, o resumo dele iria no JSON baixado e uma restauração
 * apagaria o PIN (correção do plano, seção 3). O registro de acessos fica em "painel_log", também fora do backup.
 * O app tem allowBackup=false: nada disto vai para a nuvem do Google.
 */
object PainelArmazem {
    private const val ARQUIVO = "painel_seguro"
    private const val ARQUIVO_LOG = "painel_log"
    private const val MAX_LOG = 40

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE)

    fun temPin(ctx: Context): Boolean = prefs(ctx).contains("pin_resumo")

    /** Grava o novo PIN (só o resumo PBKDF2). Lento (~100 mil voltas): chamar fora da thread da tela. */
    fun definirPin(ctx: Context, pin: String) {
        require(PinSeguro.valido(pin)) { "PIN precisa ter de ${PinSeguro.MIN_DIGITOS} a ${PinSeguro.MAX_DIGITOS} dígitos" }
        val sal = PinSeguro.novoSal()
        val resumo = PinSeguro.resumo(pin, sal, PinSeguro.ITERACOES)
        prefs(ctx).edit()
            .putString("pin_sal", Base64.encodeToString(sal, Base64.NO_WRAP))
            .putString("pin_resumo", Base64.encodeToString(resumo, Base64.NO_WRAP))
            .putInt("pin_iteracoes", PinSeguro.ITERACOES)
            .putInt("falhas", 0)
            .putBoolean("desligado_por_erros", false)
            .commit()
    }

    /** Lento (PBKDF2): chamar fora da thread da tela. */
    fun conferirPin(ctx: Context, pin: String): Boolean {
        val p = prefs(ctx)
        val sal = p.getString("pin_sal", null) ?: return false
        val resumo = p.getString("pin_resumo", null) ?: return false
        val iteracoes = p.getInt("pin_iteracoes", PinSeguro.ITERACOES)
        return try {
            PinSeguro.confere(pin, Base64.decode(sal, Base64.NO_WRAP), iteracoes, Base64.decode(resumo, Base64.NO_WRAP))
        } catch (e: IllegalArgumentException) { false }
    }

    /** O dono quer o painel ligado (chave das Configurações). Não liga sozinho sem PIN: ver [podeLigar]. */
    fun ligado(ctx: Context): Boolean = prefs(ctx).getBoolean("ligado", false)
    fun definirLigado(ctx: Context, sim: Boolean) { prefs(ctx).edit().putBoolean("ligado", sim).apply() }

    /** Desligado sozinho por PIN errado demais (ver ControleTentativas). Só as Configurações da TV religam. */
    fun desligadoPorErros(ctx: Context): Boolean = prefs(ctx).getBoolean("desligado_por_erros", false)
    fun definirDesligadoPorErros(ctx: Context, sim: Boolean) {
        val ed = prefs(ctx).edit().putBoolean("desligado_por_erros", sim)
        if (!sim) ed.putInt("falhas", 0)   // religar pelas Configurações zera a contagem de PIN errado
        ed.apply()
    }

    fun podeLigar(ctx: Context): Boolean = ligado(ctx) && temPin(ctx) && !desligadoPorErros(ctx)

    fun falhas(ctx: Context): Int = prefs(ctx).getInt("falhas", 0)
    fun definirFalhas(ctx: Context, n: Int) { prefs(ctx).edit().putInt("falhas", n).apply() }

    // ---------------------------------------------------------------- registro de acessos

    /** Uma linha no registro (data, IP, ação, resultado). Guarda as [MAX_LOG] mais recentes. */
    @Synchronized fun registrar(ctx: Context, ip: String, acao: String, resultado: String) {
        val p = ctx.getSharedPreferences(ARQUIVO_LOG, Context.MODE_PRIVATE)
        val atual = try { JSONArray(p.getString("itens", "[]")) } catch (e: Exception) { JSONArray() }
        val novo = JSONArray()
        novo.put(JSONObject().put("quando", System.currentTimeMillis()).put("ip", ip).put("acao", acao).put("resultado", resultado))
        for (i in 0 until minOf(atual.length(), MAX_LOG - 1)) novo.put(atual.get(i))
        p.edit().putString("itens", novo.toString()).apply()
    }

    /** O registro, do mais novo ao mais velho. */
    fun registro(ctx: Context): JSONArray {
        val p = ctx.getSharedPreferences(ARQUIVO_LOG, Context.MODE_PRIVATE)
        return try { JSONArray(p.getString("itens", "[]")) } catch (e: Exception) { JSONArray() }
    }

    /** As [n] linhas mais recentes em texto curto, para a tela de Configurações. */
    fun resumoRegistro(ctx: Context, n: Int): List<String> {
        val itens = registro(ctx)
        val formato = SimpleDateFormat("dd/MM HH:mm", Locale.forLanguageTag("pt-BR"))
        return (0 until minOf(n, itens.length())).map {
            val o = itens.getJSONObject(it)
            "${formato.format(Date(o.optLong("quando")))} ${o.optString("ip")} · ${o.optString("acao")} · ${o.optString("resultado")}"
        }
    }
}
