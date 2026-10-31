package br.paulo.tvlauncher

import android.app.admin.DeviceAdminReceiver

/**
 * Faz o launcher poder ser "device owner" (dono do dispositivo): o nível mais alto de gerenciamento do Android,
 * o mesmo que empresas usam. Depois de ser device owner, o app pode, via DevicePolicyManager, instalar/desinstalar
 * apps em silêncio, conceder permissões a outros apps, travar configurações, etc.
 *
 * Esta classe fica vazia de propósito: o Android só exige que ela exista e esteja declarada no manifesto
 * (receiver com BIND_DEVICE_ADMIN + res/xml/device_admin.xml). Nenhuma política é aplicada por padrão.
 *
 * Ativar (uma vez, pelo computador; o Android recusa se houver contas na TV):
 *   adb shell dpm set-device-owner br.paulo.tvlauncher/.AdminReceiver
 * Desfazer: o APK é "testOnly" (ver o manifesto), então vale
 *   adb shell dpm remove-active-admin br.paulo.tvlauncher/.AdminReceiver
 */
class AdminReceiver : DeviceAdminReceiver()