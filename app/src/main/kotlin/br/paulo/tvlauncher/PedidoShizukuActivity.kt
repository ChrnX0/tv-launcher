package br.paulo.tvlauncher

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * Aberta pelo servidor do Shizuku quando um app cliente chama `Shizuku.requestPermission()`.
 * MARCO S1: só registra o pedido no log e fecha (o pedido fica sem resposta = negado). A tela com
 * Permitir/Negar operável pelo controle é o MARCO S2.
 */
class PedidoShizukuActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val e = intent?.extras
        Log.i(ServidorShizuku.TAG, "pedido de permissao recebido: ${e?.keySet()?.joinToString { "$it=${e.get(it)}" }}")
        finish()
    }
}
