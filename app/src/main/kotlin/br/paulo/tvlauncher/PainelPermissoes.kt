package br.paulo.tvlauncher

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Permissões de cada app, no painel no navegador (ver PainelWeb.kt). São dois tipos, por caminhos diferentes:
 *
 *  1. Permissões "perigosas" (localização, microfone, câmera, contatos...): o Device Owner troca em processo
 *     (DevicePolicyManager.setPermissionGrantState). Três estados: concedida e travada, negada e travada, ou
 *     "o app pede" (padrão, quem decide é a pessoa na TV).
 *  2. Acessos especiais (sobrepor a outros apps, uso dos apps, instalar apps, todos os arquivos, alterar
 *     configurações): são "appops", que o Device Owner NÃO alcança. Só o shell troca (`appops set`), então passam
 *     pelo nosso shell embutido (GuardiaoShizuku.comando), com o comando montado aqui a partir de uma tabela fixa.
 *
 * As demais (internet, Bluetooth...) o Android concede na instalação e ninguém troca: aparecem só para consulta.
 */
object PainelPermissoes {

    /** Um acesso especial: a permissão que o app declara, o nome do appop (para ler e para o shell) e o rótulo. */
    private class Especial(val permissao: String, val opLer: String, val opShell: String, val rotulo: String)

    private val ESPECIAIS = listOf(
        Especial("android.permission.SYSTEM_ALERT_WINDOW", "android:system_alert_window", "SYSTEM_ALERT_WINDOW", "Mostrar por cima de outros apps"),
        Especial("android.permission.PACKAGE_USAGE_STATS", "android:get_usage_stats", "GET_USAGE_STATS", "Ver o uso dos outros apps"),
        Especial("android.permission.REQUEST_INSTALL_PACKAGES", "android:request_install_packages", "REQUEST_INSTALL_PACKAGES", "Instalar outros apps"),
        Especial("android.permission.MANAGE_EXTERNAL_STORAGE", "android:manage_external_storage", "MANAGE_EXTERNAL_STORAGE", "Acesso a todos os arquivos"),
        Especial("android.permission.WRITE_SETTINGS", "android:write_settings", "WRITE_SETTINGS", "Alterar configurações do sistema"),
        Especial("android.permission.SCHEDULE_EXACT_ALARM", "android:schedule_exact_alarm", "SCHEDULE_EXACT_ALARM", "Alarmes no horário exato"),
    )

    private fun info(ctx: Context, pacote: String): PackageInfo? = try {
        ctx.packageManager.getPackageInfo(pacote, PackageManager.GET_PERMISSIONS)
    } catch (e: PackageManager.NameNotFoundException) { null }

    private fun perigosa(pi: PermissionInfo) = pi.protection == PermissionInfo.PROTECTION_DANGEROUS

    private fun rotulo(pm: PackageManager, pi: PermissionInfo?, nome: String): String {
        val r = try { pi?.loadLabel(pm)?.toString() } catch (e: Exception) { null }
        // Sem rótulo, o Android devolve o próprio nome: aí mostra só a parte final ("CAMERA").
        return if (r.isNullOrBlank() || r == nome) nome.substringAfterLast('.') else r
    }

    fun listar(ctx: Context, pacote: String): JSONObject? {
        val pm = ctx.packageManager
        val p = info(ctx, pacote) ?: return null
        val pedidas = p.requestedPermissions ?: emptyArray()
        val flags = p.requestedPermissionsFlags ?: IntArray(0)
        val perigosas = JSONArray()
        val outras = JSONArray()
        val especiaisPedidas = HashSet<String>()
        for ((i, nome) in pedidas.withIndex()) {
            val pi = try { pm.getPermissionInfo(nome, 0) } catch (e: PackageManager.NameNotFoundException) { null }
            if (ESPECIAIS.any { it.permissao == nome }) { especiaisPedidas.add(nome); continue }
            val concedida = i < flags.size && flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
            if (pi != null && perigosa(pi)) {
                val politica = when (PrivilegioDO.estadoPermissao(ctx, pacote, nome)) {
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED -> "conceder"
                    DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED -> "negar"
                    else -> "padrao"
                }
                perigosas.put(JSONObject().put("nome", nome).put("rotulo", rotulo(pm, pi, nome))
                    .put("concedida", concedida).put("politica", politica))
            } else if (pi != null) {
                outras.put(JSONObject().put("nome", nome).put("rotulo", rotulo(pm, pi, nome)).put("concedida", concedida))
            }
            // pi == null: permissão que nenhum app instalado define (de outro app que não está na TV): nada a mostrar.
        }
        val especiais = JSONArray()
        val uid = p.applicationInfo?.uid ?: -1
        for (e in ESPECIAIS) {
            if (e.permissao !in especiaisPedidas) continue
            especiais.put(JSONObject().put("op", e.opShell).put("rotulo", e.rotulo).apply { estadoEspecial(ctx, pacote, uid, e, this) })
        }
        return JSONObject()
            .put("pacote", pacote)
            .put("podeMudar", !PainelApps.protegido(ctx, pacote))
            .put("perigosas", perigosas)
            .put("especiais", especiais)
            .put("outras", outras)
    }

    /**
     * Lê o appop. "padrão" quer dizer que o Android decide pela permissão de instalação (que para estes acessos quase
     * sempre é "não", exceto apps do sistema). Grava "estado" (permitido/negado) e "padrao" (true se não foi mexido).
     */
    private fun estadoEspecial(ctx: Context, pacote: String, uid: Int, e: Especial, o: JSONObject) {
        val modo = try {
            ctx.getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(e.opLer, uid, pacote)
        } catch (x: Exception) { -1 }
        when (modo) {
            AppOpsManager.MODE_ALLOWED, AppOpsManager.MODE_FOREGROUND -> o.put("estado", "permitido").put("padrao", false)
            AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED -> o.put("estado", "negado").put("padrao", false)
            AppOpsManager.MODE_DEFAULT -> o.put("estado",
                if (ctx.packageManager.checkPermission(e.permissao, pacote) == PackageManager.PERMISSION_GRANTED) "permitido" else "negado"
            ).put("padrao", true)
            else -> o.put("estado", "desconhecido").put("padrao", true)
        }
    }

    /** Troca uma permissão perigosa. [acao] = conceder | negar | padrao. Devolve null se deu certo, ou o motivo. */
    fun mudar(ctx: Context, pacote: String, permissao: String, acao: String): String? {
        if (PainelApps.protegido(ctx, pacote)) return "as permissões deste app não se mexem pelo painel (o sistema ou o launcher dependem dele)"
        val p = info(ctx, pacote) ?: return "app não encontrado"
        if (permissao !in (p.requestedPermissions ?: emptyArray())) return "o app não pede essa permissão"
        val pi = try { ctx.packageManager.getPermissionInfo(permissao, 0) } catch (e: PackageManager.NameNotFoundException) { null }
        if (pi == null || !perigosa(pi)) return "essa permissão não se troca (o Android concede na instalação)"
        val estado = when (acao) {
            "conceder" -> DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            "negar" -> DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
            "padrao" -> DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
            else -> return "ação inválida"
        }
        if (!PrivilegioDO.somosDonos(ctx)) return "o launcher não é mais o dono do aparelho (Device Owner)"
        if (!PrivilegioDO.definirPermissao(ctx, pacote, permissao, estado))
            // Medido em outros aparelhos: o Android 12+ pode recusar conceder câmera/microfone/localização pelo admin.
            return "o Android recusou (algumas, como câmera e microfone, ele só deixa a pessoa conceder na TV)"
        return null
    }

    /** Troca um acesso especial pelo shell. [acao] = permitir | negar | padrao. Bloqueia até ~40 s. */
    fun mudarEspecial(ctx: Context, pacote: String, op: String, acao: String): String? {
        if (PainelApps.protegido(ctx, pacote)) return "os acessos deste app não se mexem pelo painel (o sistema ou o launcher dependem dele)"
        if (!ChecagensPainel.pacoteValido(pacote)) return "pacote inválido"
        val e = ESPECIAIS.firstOrNull { it.opShell == op } ?: return "acesso desconhecido"
        val p = info(ctx, pacote) ?: return "app não encontrado"
        if (e.permissao !in (p.requestedPermissions ?: emptyArray())) return "o app não pede esse acesso"
        val modo = when (acao) { "permitir" -> "allow"; "negar" -> "ignore"; "padrao" -> "default"; else -> return "ação inválida" }
        // Tudo aqui veio de tabela fixa ou foi validado (pacote): o comando não tem nenhum texto livre do navegador.
        GuardiaoShizuku.comando(ctx, "appops set --uid $pacote ${e.opShell} $modo")
            ?: return "o shell do launcher não respondeu (a depuração sem fio está ligada?)"
        // Confere lendo de volta, em vez de confiar na saída do comando.
        val o = JSONObject()
        estadoEspecial(ctx, pacote, p.applicationInfo?.uid ?: -1, e, o)
        val esperado = when (acao) { "permitir" -> "permitido"; "negar" -> "negado"; else -> null }
        return if (esperado == null || o.optString("estado") == esperado) null else "o Android não aplicou (ficou ${o.optString("estado")})"
    }
}
