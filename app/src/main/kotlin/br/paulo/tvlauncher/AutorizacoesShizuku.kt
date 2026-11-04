package br.paulo.tvlauncher

import android.content.pm.PackageInfo
import android.os.Parcel
import rikka.parcelablelist.ParcelableListSlice
import rikka.shizuku.Shizuku
import rikka.shizuku.server.ServerConstants

/**
 * Quem pode usar o Shizuku embutido (MARCO S2). Porte do AuthorizationManager do app Shizuku (fork thedjchi):
 * a decisão fica guardada NO SERVIDOR (shizuku_server), como flags por uid; o launcher, como gerente, só lê e muda.
 *
 * Tudo aqui fala com o binder do servidor; só funciona com `Shizuku.pingBinder() == true`.
 */
object AutorizacoesShizuku {
    // Mesmos valores do servidor (ShizukuConfig): o "permitido" e o "negado" ficam em bits de uma flag por uid.
    private const val FLAG_ALLOWED = 1 shl 1
    private const val FLAG_DENIED = 1 shl 2
    private const val MASK_PERMISSION = FLAG_ALLOWED or FLAG_DENIED

    /** Apps instalados que declaram a permissão do Shizuku (os "clientes"), em todos os usuários. */
    fun clientes(): List<PackageInfo> {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken("moe.shizuku.server.IShizukuService")
            data.writeInt(-1)   // -1 = todos os usuários
            Shizuku.getBinder()!!.transact(ServerConstants.BINDER_TRANSACTION_getApplications, data, reply, 0)
            reply.readException()
            @Suppress("UNCHECKED_CAST")
            (ParcelableListSlice.CREATOR.createFromParcel(reply) as ParcelableListSlice<PackageInfo>).list ?: emptyList()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    fun permitido(uid: Int): Boolean = (Shizuku.getFlagsForUid(uid, MASK_PERMISSION) and FLAG_ALLOWED) == FLAG_ALLOWED

    fun permitir(uid: Int) = Shizuku.updateFlagsForUid(uid, MASK_PERMISSION, FLAG_ALLOWED)

    /** Revoga: o app volta a "nunca perguntado" (ele pode pedir de novo, e a tela de pedido reaparece). */
    fun revogar(uid: Int) = Shizuku.updateFlagsForUid(uid, MASK_PERMISSION, 0)
}
