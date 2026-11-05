package br.paulo.tvlauncher

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

/**
 * A "ficha" de cada app no painel no navegador: o que ele faz, se é seguro ocultar ou desinstalar, o que quebra e
 * de quem ele depende. Ver PainelWeb.kt.
 *
 * O Android NÃO informa "quem depende de quem". A ficha junta duas fontes, e a tela diz qual é qual:
 *  1. A tabela `assets/fichas-apps.json`, escrita para os apps desta TV (Sala, 04/11/2026) a partir do que se sabe
 *     de cada pacote. É conhecimento geral, não medição: um app fora dela aparece como "sem informação".
 *  2. O que a TV diz AGORA, calculado aqui: qual app é o teclado, a HOME, o protetor de tela, a VPN, o assistente,
 *     o navegador e os serviços de acessibilidade; quais apps usam uma biblioteca ou permissão que ele fornece.
 *     Qualquer um desses papéis sobe o risco para "essencial", mesmo que a tabela diga outra coisa.
 */
object PainelFicha {
    const val ESSENCIAL = "essencial"   // não mexer: a TV ou algo central quebra
    const val CUIDADO = "cuidado"       // dá para ocultar, mas alguma função para (a ficha diz qual)
    const val SEGURO = "seguro"         // ocultar ou desinstalar só afeta o próprio app
    const val SEM_INFO = "sem informação"

    private class Linha(val risco: String, val descricao: String, val efeito: String, val dependentes: List<String>)

    @Volatile private var tabela: Map<String, Linha>? = null

    private fun tabela(ctx: Context): Map<String, Linha> = tabela ?: synchronized(this) {
        tabela ?: try {
            val o = JSONObject(ctx.assets.open("fichas-apps.json").use { String(it.readBytes(), Charsets.UTF_8) })
            val m = HashMap<String, Linha>()
            for (p in o.keys()) {
                val x = o.getJSONObject(p)
                val dep = x.optJSONArray("dep")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
                m[p] = Linha(x.getString("r"), x.getString("d"), x.getString("q"), dep)
            }
            m
        } catch (e: Exception) { emptyMap() }.also { tabela = it }
    }

    /** "Overlay": um pacote sem código, só com ajustes de aparência e de configuração de outro app ou do sistema. */
    private fun overlay(ai: ApplicationInfo): Boolean = (ai.sourceDir ?: "").contains("/overlay/")

    // ---------------------------------------------------------------- papéis em uso agora

    /**
     * Pacote -> papéis que ele cumpre na TV agora (ex.: "teclado em uso"). Calculado a cada chamada, porque muda
     * quando alguém troca o teclado ou a VPN. Custo: alguns settings e duas consultas ao PackageManager.
     */
    fun papeis(ctx: Context): Map<String, List<String>> {
        val m = HashMap<String, MutableList<String>>()
        fun por(pacote: String?, papel: String) { if (!pacote.isNullOrBlank()) m.getOrPut(pacote) { ArrayList() }.add(papel) }
        val cr = ctx.contentResolver
        fun componentes(chave: String) = (Settings.Secure.getString(cr, chave) ?: "").split(':')
            .mapNotNull { it.substringBefore('/').takeIf { p -> p.isNotBlank() } }.distinct()

        por(ctx.packageName, "tela inicial (HOME) e dono do aparelho")
        por(Settings.Secure.getString(cr, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/'), "teclado em uso")
        componentes(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).forEach { por(it, "serviço de acessibilidade ligado") }
        componentes("screensaver_components").forEach { por(it, "protetor de tela em uso") }
        componentes("enabled_notification_listeners").forEach { por(it, "lê as notificações") }
        componentes("assistant").forEach { por(it, "assistente de voz") }
        componentes("voice_interaction_service").forEach { por(it, "assistente de voz") }
        if (Config.VPN_FIXA.isNotEmpty()) por(Config.VPN_FIXA, "VPN sempre ativa")
        try {
            val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
            dpm.activeAdmins?.forEach { if (it.packageName != ctx.packageName) por(it.packageName, "administrador do aparelho") }
        } catch (_: Exception) {}
        try {
            val r = ctx.packageManager.resolveActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://exemplo.com")),
                PackageManager.MATCH_DEFAULT_ONLY)
            r?.activityInfo?.packageName?.takeIf { it != "android" }?.let { por(it, "abre os links (navegador padrão)") }
        } catch (_: Exception) {}
        return m.mapValues { it.value.distinct() }
    }

    // ---------------------------------------------------------------- risco (para a lista) e ficha (para o detalhe)

    /** Só o nível de risco, barato o bastante para ir em cada linha da lista de apps. */
    fun risco(ctx: Context, ai: ApplicationInfo, papeis: Map<String, List<String>>): String {
        if (!papeis[ai.packageName].isNullOrEmpty() || PainelApps.protegido(ctx, ai.packageName)) return ESSENCIAL
        tabela(ctx)[ai.packageName]?.let { return it.risco }
        if (overlay(ai)) return ESSENCIAL
        return if (ai.flags and ApplicationInfo.FLAG_SYSTEM == 0) SEGURO else SEM_INFO
    }

    fun ficha(ctx: Context, pacote: String): JSONObject? {
        val pm = ctx.packageManager
        val ai = try { pm.getApplicationInfo(pacote, PackageManager.MATCH_DISABLED_COMPONENTS) } catch (e: PackageManager.NameNotFoundException) { return null }
        val papeis = papeis(ctx)
        val linha = tabela(ctx)[pacote]
        val sistema = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0
        val o = JSONObject().put("pacote", pacote).put("risco", risco(ctx, ai, papeis)).put("sistema", sistema)
        when {
            linha != null -> o.put("descricao", linha.descricao).put("efeito", linha.efeito).put("fonte", "lista do launcher")
            overlay(ai) -> o.put("descricao", "Ajuste de aparência ou de configuração de outro app ou do sistema, feito pelo fabricante. Não tem tela nem código próprio.")
                .put("efeito", "Não economiza nada ocultar, e o que ele ajusta (rede, Wi-Fi, Configurações...) pode voltar ao padrão errado.")
                .put("fonte", "regra do launcher para overlays")
            !sistema -> o.put("descricao", "App instalado por você.")
                .put("efeito", "Ocultar ou desinstalar afeta só ele, a não ser que algum app abaixo dependa dele.")
                .put("fonte", "regra do launcher para apps instalados")
            else -> o.put("descricao", "App do sistema que a lista do launcher ainda não conhece.")
                .put("efeito", "Sem informação. Na dúvida, não oculte: apps do sistema sem tela costumam servir a outros.")
                .put("fonte", "sem informação")
        }
        o.put("papeis", JSONArray(papeis[pacote] ?: emptyList<String>()))
        o.put("dependentesConhecidos", JSONArray(linha?.dependentes ?: emptyList<String>()))
        o.put("usadoPor", usadoPor(ctx, pacote, ai))
        return o
    }

    /**
     * Apps instalados que dependem DE FATO deste, segundo a própria TV: carregam o código dele como biblioteca
     * (sharedLibraryFiles aponta para o APK dele) ou pedem uma permissão que só ele define (ex.: a de "cobrança" da
     * Play Store). Até 40 nomes. Custo medido na fase 1: listar os 115 pacotes leva cerca de 0,5 s.
     */
    private fun usadoPor(ctx: Context, pacote: String, ai: ApplicationInfo): JSONArray {
        val pm = ctx.packageManager
        val definidas: Set<String> = try {
            pm.getPackageInfo(pacote, PackageManager.GET_PERMISSIONS).permissions?.map { it.name }?.toSet() ?: emptySet()
        } catch (e: Exception) { emptySet() }
        val apk = ai.sourceDir
        val saida = JSONArray()
        @Suppress("DEPRECATION")
        val todos: List<PackageInfo> = try { pm.getInstalledPackages(PackageManager.GET_PERMISSIONS) } catch (e: Exception) { emptyList() }
        for (pi in todos) {
            if (pi.packageName == pacote || saida.length() >= 40) continue
            val outro = pi.applicationInfo ?: continue
            val motivos = ArrayList<String>()
            if (apk != null && outro.sharedLibraryFiles?.any { it == apk } == true) motivos.add("usa o código dele")
            val usa = pi.requestedPermissions?.filter { it in definidas }.orEmpty()
            if (usa.isNotEmpty()) motivos.add("usa " + usa.take(3).joinToString { it.substringAfterLast('.') })
            if (motivos.isEmpty()) continue
            val nome = try { outro.loadLabel(pm).toString() } catch (e: Exception) { pi.packageName }
            saida.put(JSONObject().put("pacote", pi.packageName).put("nome", nome).put("como", motivos.joinToString("; ")))
        }
        return saida
    }
}
