package br.paulo.clienteshizuku

import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.TextView
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

/**
 * Cliente de teste do Shizuku embutido no launcher.
 * Ao abrir: espera o binder; se ainda não tem permissão, pede (o launcher mostra a tela Permitir/Negar);
 * com permissão, roda `id` pelo servidor do Shizuku e mostra/loga a saída (tag TesteShizuku).
 */
class TesteActivity : Activity() {
    private lateinit var saida: TextView
    private val codigo = 7

    private fun mostrar(t: String) {
        Log.i("TesteShizuku", t)
        runOnUiThread { saida.append(t + "\n") }
    }

    private val aoReceberBinder = Shizuku.OnBinderReceivedListener { mostrar("binder recebido (servidor uid=${Shizuku.getUid()}, versao=${Shizuku.getVersion()})"); verificar() }
    private val aoResponder = Shizuku.OnRequestPermissionResultListener { req, res ->
        mostrar("resposta do pedido $req: ${if (res == PackageManager.PERMISSION_GRANTED) "PERMITIDO" else "NEGADO"}")
        if (res == PackageManager.PERMISSION_GRANTED) rodarId()
    }

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        saida = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 22f; gravity = Gravity.CENTER; setPadding(60, 60, 60, 60)
        }
        setContentView(saida)
        mostrar("Teste do Shizuku: esperando o binder…")
        Shizuku.addRequestPermissionResultListener(aoResponder)
        Shizuku.addBinderReceivedListenerSticky(aoReceberBinder)
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(aoReceberBinder)
        Shizuku.removeRequestPermissionResultListener(aoResponder)
    }

    private fun verificar() {
        when {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> { mostrar("ja tenho permissao"); rodarId() }
            Shizuku.shouldShowRequestPermissionRationale() -> mostrar("permissao NEGADA antes (revogue em Configuracoes do launcher)")
            else -> { mostrar("pedindo permissao…"); Shizuku.requestPermission(codigo) }
        }
    }

    /** Roda `id` no servidor. newProcess é privado na API 13 (o demo oficial também usa por reflexão). */
    private fun rodarId() = thread {
        try {
            val m = Shizuku::class.java.getDeclaredMethod("newProcess",
                Array<String>::class.java, Array<String>::class.java, String::class.java)
            m.isAccessible = true
            val p = m.invoke(null, arrayOf("id"), null, null) as Process
            val texto = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            mostrar("id pelo Shizuku: $texto")
        } catch (e: Throwable) {
            mostrar("falhou: ${e.cause ?: e}")
        }
    }
}
