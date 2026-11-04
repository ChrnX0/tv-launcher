package br.paulo.tvlauncher

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED
import rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_IS_ONETIME

/**
 * Pedido de permissão de um app cliente do Shizuku embutido (MARCO S2).
 *
 * Quem abre esta tela é o servidor `shizuku_server`, quando um app chama `Shizuku.requestPermission()`. Ele manda
 * uid, pid, requestCode e o ApplicationInfo do app. A resposta volta ao servidor por
 * `Shizuku.dispatchPermissionConfirmationResult` — e o servidor só aceita essa resposta vinda do gerente (este app).
 *
 * Operável pelo controle: o foco começa em "Negar" (o seguro), ◀ ▶ trocam, OK confirma. Voltar = negar.
 * Se o protetor de tela entrar, o pedido continua esperando (não nega sozinho).
 * "Permitir sempre" fica gravado no servidor e aparece em Configurações do launcher › Apps com acesso ao Shizuku,
 * onde dá para revogar.
 */
class PedidoShizukuActivity : Activity() {
    private var respondido = false
    private var uid = -1
    private var pid = -1
    private var requestCode = -1

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        uid = intent.getIntExtra("uid", -1)
        pid = intent.getIntExtra("pid", -1)
        requestCode = intent.getIntExtra("requestCode", -1)
        val ai: ApplicationInfo? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra("applicationInfo", ApplicationInfo::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra("applicationInfo")
        Log.i(ServidorShizuku.TAG, "pedido de permissao: uid=$uid pid=$pid requestCode=$requestCode pacote=${ai?.packageName}")
        if (uid < 0 || pid < 0 || ai == null || !Shizuku.pingBinder()) {
            Log.w(ServidorShizuku.TAG, "pedido invalido ou servidor fora do ar; fechando")
            return finish()
        }

        val tema = Tema.atual(this)
        val nome = ai.loadLabel(packageManager).toString()

        fun botao(texto: String, aoClicar: () -> Unit) = TextView(this).apply {
            text = texto
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Fontes.uiMedia(this@PedidoShizukuActivity, tema)
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(14), dp(28), dp(14))
            isFocusable = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused),
                    GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(0x55FFFFFF) })
                addState(intArrayOf(),
                    GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(0x1AFFFFFF) })
            }
            setOnClickListener { aoClicar() }
        }

        val negar = botao("Negar") { responder(false) }
        val permitir = botao("Permitir sempre") { responder(true) }

        val painel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(28), dp(32), dp(24))
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(0xF01C2024.toInt()) }
            addView(ImageView(this@PedidoShizukuActivity).apply { setImageDrawable(ai.loadIcon(packageManager)) },
                LinearLayout.LayoutParams(dp(64), dp(64)).apply { bottomMargin = dp(14) })
            addView(TextView(this@PedidoShizukuActivity).apply {
                text = "Permitir que $nome use os poderes de sistema do launcher?"
                setTextColor(Color.WHITE)
                textSize = 24f
                gravity = Gravity.CENTER
                typeface = Fontes.uiMedia(this@PedidoShizukuActivity, tema)
            })
            addView(TextView(this@PedidoShizukuActivity).apply {
                text = "Com isso o app consegue fazer o que o adb faz: instalar e remover apps, mudar configurações " +
                    "do sistema e mais. Só permita apps em que você confia. Dá para revogar depois em " +
                    "Configurações do launcher."
                setTextColor(0xCCFFFFFF.toInt())
                textSize = 16f
                gravity = Gravity.CENTER
                typeface = Fontes.ui(this@PedidoShizukuActivity, tema)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10); bottomMargin = dp(22)
            })
            addView(LinearLayout(this@PedidoShizukuActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(negar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(16) })
                addView(permitir)
            })
        }

        setContentView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(0xCC000000.toInt())
            addView(painel, LinearLayout.LayoutParams(dp(640), ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        negar.requestFocus()   // o padrão é o seguro
    }

    private fun responder(permitido: Boolean) {
        if (respondido) return
        respondido = true
        val dados = Bundle().apply {
            putBoolean(REQUEST_PERMISSION_REPLY_ALLOWED, permitido)
            putBoolean(REQUEST_PERMISSION_REPLY_IS_ONETIME, false)
        }
        try {
            Shizuku.dispatchPermissionConfirmationResult(uid, pid, requestCode, dados)
            Log.i(ServidorShizuku.TAG, "resposta ao pedido uid=$uid: ${if (permitido) "PERMITIDO" else "negado"}")
        } catch (e: Throwable) {
            Log.w(ServidorShizuku.TAG, "dispatchPermissionConfirmationResult falhou: $e")
        }
        finish()
    }

    // Voltar = negar.
    @Deprecated("API antiga, mas é a que existe em Activity (sem AndroidX)")
    override fun onBackPressed() {
        responder(false)
    }

    // A tela foi fechada sem escolha (ex.: o sistema a destruiu) = negar, para o app que pediu não ficar esperando.
    // NÃO usar onStop para isso: medido em 04/11/2026, o protetor de tela entrou com o pedido aberto, chamou onStop
    // e o pedido foi negado sozinho, sem ninguém ter escolhido. Com o protetor, a tela só fica escondida; ao acordar
    // a TV ela continua lá esperando a resposta.
    override fun onDestroy() {
        super.onDestroy()
        if (!respondido && isFinishing) responder(false)
    }
}
