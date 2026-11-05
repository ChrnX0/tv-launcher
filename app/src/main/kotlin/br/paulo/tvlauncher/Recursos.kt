package br.paulo.tvlauncher

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings

/**
 * Modo sem Device Owner (05/11/2026, fila longa do brainstorm). Quem instala o launcher sem o passo do `dpm` (que exige
 * a TV sem contas Google e um PC uma vez) ainda tem a HOME, o clima, o despertador e o protetor. Cada poder extra vira
 * "se tiver": o código de cada um já confere antes de usar (PrivilegioDO.somosDonos, checkSelfPermission), e esta lista
 * mostra nas Configurações o que está ligado, o que falta e como ligar o que der sem PC.
 *
 * [Recurso.pedir]: o que o OK faz quando falta. [PEDIR_PERMISSAO] = pedir a permissão pela janela do Android (só serve
 * para as "perigosas"); uma Intent = abrir a tela das Configurações certa; null = só com PC (adb/dpm).
 */
object Recursos {
    const val PEDIR_PERMISSAO = "permissao"

    class Recurso(val nome: String, val ativo: Boolean, val sem: String, val pedir: Any?)

    /** Permissões "perigosas" que dá para pedir na própria TV quando o launcher não é Device Owner. */
    val PERIGOSAS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.GET_ACCOUNTS)

    fun tem(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun usoLiberado(ctx: Context): Boolean = try {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) { false }

    fun listar(ctx: Context): List<Recurso> {
        val dono = PrivilegioDO.somosDonos(ctx)
        return listOf(
            Recurso("Device Owner", dono, "sem ele: não oculta apps, não fixa a HOME nem a VPN, não muda o tempo do protetor", null),
            Recurso("Agenda", tem(ctx, Manifest.permission.READ_CALENDAR), "sem ela: o calendário não mostra eventos", PEDIR_PERMISSAO),
            Recurso("Localização", tem(ctx, Manifest.permission.ACCESS_FINE_LOCATION), "sem ela: o clima usa a cidade pelo IP e o Wi-Fi não mostra o nome da rede", PEDIR_PERMISSAO),
            Recurso("Contas", tem(ctx, Manifest.permission.GET_ACCOUNTS), "sem ela: a bolinha do perfil não mostra a inicial da conta", PEDIR_PERMISSAO),
            Recurso("Uso dos apps", usoLiberado(ctx), "sem ele: os apps não se ordenam pelo uso recente",
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)),
            Recurso("Sobreposição", Settings.canDrawOverlays(ctx), "sem ela: recados e o pareamento sem PC não aparecem por cima dos apps",
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${ctx.packageName}"))),
            Recurso("Configurações seguras", tem(ctx, "android.permission.WRITE_SECURE_SETTINGS"),
                "sem ela: não troca o DNS nem religa sozinho o serviço de teclas e a depuração sem fio (só com PC ou o shell do launcher)", null),
            Recurso("Serviço de teclas", AutorizaShizukuService.instancia != null, "sem ele: o botão HOME pode abrir o launcher do Google e as teclas Netflix/YouTube não remapeiam",
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
        )
    }

    /** As permissões perigosas que ainda faltam (para um único requestPermissions). */
    fun perigosasFaltando(ctx: Context): Array<String> = PERIGOSAS.filter { !tem(ctx, it) }.toTypedArray()

    /** Resumo de uma linha para o topo da seção. */
    fun resumo(ctx: Context): String {
        val l = listar(ctx)
        val faltam = l.count { !it.ativo }
        return if (faltam == 0) "tudo ligado" else "$faltam de ${l.size} recursos faltando · OK num item tenta ligar"
    }
}
