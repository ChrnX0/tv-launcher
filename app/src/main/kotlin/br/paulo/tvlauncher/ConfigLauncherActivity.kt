package br.paulo.tvlauncher

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

/**
 * "Configurações do launcher": tudo que é nosso (não do sistema) e não tinha lugar fixo antes — aparência, DNS,
 * teste de velocidade, status de permissões e diagnóstico. Aberta pelo card "Launcher" do popup do perfil (ver
 * PopupPerfil.kt). O estilo (cores, vidro, tipografia) é copiado do DnsActivity de propósito, para não acoplar as duas.
 */
class ConfigLauncherActivity : Activity() {
    private val trabalho = Executors.newSingleThreadExecutor()   // teste de velocidade não pode rodar na thread da tela
    private val ui = Handler(Looper.getMainLooper())

    private val textoClaro = 0xFFE8EEEA.toInt()
    private val textoSuave = 0xFF93A19A.toInt()
    private val destaque = 0xFF7FD6C2.toInt()
    private val fonteLeve = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val fonteMedia = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(montarTela())
    }

    override fun onDestroy() {
        super.onDestroy()
        trabalho.shutdownNow()
    }

    // ---------------------------------------------------------------- VPN
    // Desde 04/11/2026 a VPN é o app AdGuard VPN (decisão do Paulo); a VPN própria do launcher (IKEv2, Vpn.kt) foi
    // aposentada — o servidor da AdGuard recusava a autenticação dela. O launcher só fixa o app como "VPN sempre
    // ativa" pelo Device Owner (Config.VPN_FIXA, PrivilegioDO.aplicar) e mostra o estado aqui.

    /** Há uma rede VPN de pé agora? */
    private fun vpnAtiva(): Boolean = try {
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true }
    } catch (e: Exception) { false }

    private fun detalheVpn(): String =
        (if (vpnAtiva()) "conectada" else "desconectada") + " — app AdGuard VPN, sempre ativa · OK abre o app"

    // ---------------------------------------------------------------- tela

    private fun montarTela(): View {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = resources.getDrawable(R.drawable.fundo, theme)
            setPadding(dp(96), dp(48), dp(96), dp(32))
        }
        raiz.addView(texto(36f, textoClaro, fonteLeve).apply { text = "Configurações do launcher" })

        val lista = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(24), dp(4), dp(24), dp(4))
        }
        val rolagem = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            addView(lista)
        }
        raiz.addView(rolagem, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(16) })

        fun adicionar(v: View) = lista.addView(v, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
        fun secao(titulo: String) = lista.addView(texto(13f, textoSuave, fonteMedia).apply {
            text = titulo.uppercase(); letterSpacing = 0.12f; setPadding(dp(24), dp(14), 0, dp(6))
        })

        // ---- Aparência: o mesmo "trocar tema" que já existia no popup, só realocado para cá.
        secao("Aparência")
        lateinit var linhaTema: TextView
        linhaTema = linha("Tema: ${Tema.atual(this).nome}", "muda para o próximo visual") {
            Tema.proximo(this)
            linhaTema.text = "Tema: ${Tema.atual(this).nome}"
            // A MainActivity só relê o tema no onCreate: ela mesma percebe a troca e se recria ao voltar (ver onResume lá).
        }
        adicionar(linhaTema)

        // ---- Descanso de tela: OK passa para a próxima opção da lista (mesmo jeito do tema), e grava na hora.
        secao("Descanso de tela")
        val opcoes = listOf(60_000, 5 * 60_000, 10 * 60_000, 15 * 60_000, 30 * 60_000, Int.MAX_VALUE)
        fun rotuloTempo(ms: Int) = if (ms == Int.MAX_VALUE) "Nunca" else "${ms / 60_000} min"
        lateinit var linhaDescanso: TextView
        linhaDescanso = linha("Iniciar após: ${rotuloTempo(PrivilegioDO.tempoDescanso(this))}", "OK muda o tempo parado até o protetor aparecer") {
            val atual = PrivilegioDO.tempoDescanso(this)
            // Próxima opção depois da atual; se o valor atual não estiver na lista (mudado por fora), começa da primeira.
            val proximo = opcoes[(opcoes.indexOf(atual) + 1) % opcoes.size]
            if (PrivilegioDO.definirTempoDescanso(this, proximo)) {
                linhaDescanso.montarLinha("Iniciar após: ${rotuloTempo(proximo)}", "OK muda o tempo parado até o protetor aparecer")
            } else {
                linhaDescanso.detalhe("não consegui gravar (Device Owner inativo?)")
            }
        }
        adicionar(linhaDescanso)

        // ---- Rede: DNS e teste de velocidade (tirados do popup principal, que agora só mostra o Wi-Fi em si).
        secao("Rede")
        // Se o DNS fixo caiu e o launcher voltou para o automático (Dns.voltarAoAutomaticoSeQuebrado), avisa aqui.
        val caiu = Dns.servidorQueCaiu(this)
        adicionar(linha("DNS privado", Dns.rotulo(this) + (if (caiu != null) " · $caiu não respondeu, voltei para o automático" else "")) {
            startActivity(Intent(this, DnsActivity::class.java))
        })
        adicionar(linha("VPN", detalheVpn()) {
            val abrir = packageManager.getLeanbackLaunchIntentForPackage(Config.VPN_FIXA)
                ?: packageManager.getLaunchIntentForPackage(Config.VPN_FIXA)
            if (abrir != null) startActivity(abrir.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        lateinit var linhaVelocidade: TextView
        linhaVelocidade = linha("Testar velocidade da internet", "baixa 25 MB e mede") {
            linhaVelocidade.detalhe("Testando…")
            trabalho.execute {
                val r = Velocidade.medir()
                ui.post { if (!isDestroyed) linhaVelocidade.detalhe(r) }
            }
        }
        adicionar(linhaVelocidade)

        // ---- Apps ocultos: os que o launcher some pelo Device Owner (ver Config.APPS_OCULTAR/PrivilegioDO.ocultar) —
        // ocultar libera a memória que eles seguravam sem desinstalar, e dá pra mostrar de volta a qualquer momento.
        if (Config.APPS_OCULTAR.isNotEmpty()) {
            secao("Apps ocultos")
            Config.APPS_OCULTAR.forEach { pacote ->
                val rotulo = try {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pacote, 0)).toString()
                } catch (e: Exception) { pacote }
                lateinit var linhaApp: TextView
                fun detalheApp() = if (PrivilegioDO.estaOculto(this, pacote)) "oculto — OK mostra" else "visível — OK oculta"
                linhaApp = linha(rotulo, detalheApp()) {
                    val ocultoAntes = PrivilegioDO.estaOculto(this, pacote)
                    if (PrivilegioDO.ocultar(this, pacote, !ocultoAntes)) linhaApp.detalhe(detalheApp())
                    else linhaApp.detalhe("não consegui mudar (Device Owner inativo?)")
                }
                adicionar(linhaApp)
            }
        }

        // ---- Permissões: status do que o launcher usa para funcionar sem o computador por perto.
        secao("Permissões")
        val dono = PrivilegioDO.somosDonos(this)
        adicionar(info("Device Owner", if (dono) "ativo — controla apps e permissões do sistema" else "não ativo"))
        adicionar(info("Permissões fixas concedidas", Config.PERMISSOES_FIXAS.joinToString { it.substringAfterLast('.') }))

        // ---- Shizuku embutido (MARCO S2): estado do servidor e os apps que pediram acesso a ele. OK num app alterna
        // entre permitido e revogado. A lista é lida do servidor (binder) fora da thread da tela.
        secao("Shizuku do launcher")
        lateinit var linhaServidor: TextView
        // Desligado por padrão (04/11/2026, memória): só fica de pé quando alguém liga aqui (e continua ligado no boot).
        fun detalheServidor() = when {
            rikka.shizuku.Shizuku.pingBinder() ->
                "ligado e rodando (uid ${rikka.shizuku.Shizuku.getUid()}, versão ${rikka.shizuku.Shizuku.getVersion()}) — OK desliga"
            Config.shizukuAtivo(this) -> "ligado, subindo… — OK desliga"
            else -> "desligado (economiza memória) — OK liga"
        }
        linhaServidor = linha("Servidor", detalheServidor()) {
            if (Config.shizukuAtivo(this)) {
                ControleShizukuReceiver.desligar(this)
                linhaServidor.detalhe("desligando…")
                ui.postDelayed({ if (!isDestroyed) { linhaServidor.detalhe(detalheServidor()); carregarClientes() } }, 3_000)
            } else {
                ControleShizukuReceiver.ligar(this)   // pela fila do guardião (uma conexão adb por vez)
                linhaServidor.detalhe("ligando…")
                ui.postDelayed({ if (!isDestroyed) { linhaServidor.detalhe(detalheServidor()); carregarClientes() } }, 15_000)
            }
        }
        adicionar(linhaServidor)
        // Parear a depuração sem fio sem PC (ver PareamentoNaTv): abre as Opções do desenvolvedor com uma janela do
        // launcher por cima, que acha a porta sozinha e recebe o código pelo controle.
        lateinit var linhaParear: TextView
        linhaParear = linha("Parear sem PC", if (PareamentoNaTv.podeMostrar(this))
            "abre a tela de pareamento do Android com um teclado do launcher por cima"
        else "o serviço de acessibilidade do launcher está desligado") {
            if (!PareamentoNaTv.comecar(this)) linhaParear.detalhe("o serviço de acessibilidade do launcher está desligado")
        }
        adicionar(linhaParear)
        listaClientes =LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lista.addView(listaClientes, LinearLayout.LayoutParams(MATCH, WRAP))
        carregarClientes()

        // ---- Diagnóstico: números úteis para saber se algo por trás dos panos está ok, sem precisar do logcat.
        secao("Diagnóstico")
        val versao = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { null }
        adicionar(info("Versão do launcher", versao ?: "desconhecida"))
        adicionar(info("Depuração sem fio", if (Config.DEPURACAO_SEM_FIO_SEMPRE_LIGADA) "mantida ligada pelo launcher" else "como o Android deixar"))
        // Fora da thread da tela (revisão 04/11): na 1ª vez isto GERA a chave RSA-2048 (segundos num ARMv7).
        val linhaChave = info("Chave do shell próprio", "calculando…")
        adicionar(linhaChave)
        trabalho.execute {
            val fp = try { ShellProprio.impressaoDigital(this) } catch (e: Exception) { "erro: ${e.javaClass.simpleName}" }
            ui.post { if (!isDestroyed) linhaChave.detalhe(fp) }
        }

        return raiz
    }

    // ---------------------------------------------------------------- Shizuku: apps com acesso

    private lateinit var listaClientes: LinearLayout

    /** Lê do servidor os apps clientes (fora da thread da tela) e monta uma linha por app. */
    private fun carregarClientes() {
        listaClientes.removeAllViews()
        if (!rikka.shizuku.Shizuku.pingBinder()) return
        trabalho.execute {
            val apps = try { AutorizacoesShizuku.clientes() } catch (e: Throwable) {
                android.util.Log.w(ServidorShizuku.TAG, "lista de clientes falhou: $e"); null
            }
            ui.post {
                if (isDestroyed) return@post
                listaClientes.removeAllViews()
                if (apps.isNullOrEmpty()) {
                    listaClientes.addView(info("Apps com acesso", "nenhum app instalado pediu acesso ao Shizuku"))
                    return@post
                }
                for (pi in apps) {
                    val ai = pi.applicationInfo ?: continue
                    val rotulo = try { ai.loadLabel(packageManager).toString() } catch (e: Exception) { pi.packageName }
                    lateinit var linhaApp: TextView
                    fun detalheApp() = if (AutorizacoesShizuku.permitido(ai.uid)) "permitido — OK revoga" else "sem acesso — OK permite"
                    linhaApp = linha(rotulo, detalheApp()) {
                        try {
                            if (AutorizacoesShizuku.permitido(ai.uid)) AutorizacoesShizuku.revogar(ai.uid)
                            else AutorizacoesShizuku.permitir(ai.uid)
                            linhaApp.detalhe(detalheApp())
                        } catch (e: Throwable) { linhaApp.detalhe("não consegui mudar: ${e.javaClass.simpleName}") }
                    }
                    listaClientes.addView(linhaApp, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
                }
            }
        }
    }

    // ---------------------------------------------------------------- peças

    /** Uma linha clicável: título + detalhe, fica clara com foco (igual às linhas do popup e da tela de DNS). */
    private fun linha(titulo: String, detalhe: String, aoClicar: () -> Unit): TextView = TextView(this).apply {
        montarLinha(titulo, detalhe)
        isFocusable = true
        isClickable = true
        background = vidro(false)
        setOnClickListener { aoClicar() }
        setOnFocusChangeListener { v, temFoco ->
            v.background = vidro(temFoco)
            v.animate().scaleX(if (temFoco) 1.02f else 1f).scaleY(if (temFoco) 1.02f else 1f).setDuration(120).start()
        }
    }

    /** Uma linha só informativa, sem foco nem clique (o diagnóstico é para ler, não para apertar OK). */
    private fun info(titulo: String, detalhe: String): TextView = TextView(this).apply {
        montarLinha(titulo, detalhe)
        setPadding(dp(24), dp(10), dp(24), dp(10))
    }

    /** Título em uma linha (claro) e detalhe embaixo (suave), como span de dois tamanhos no mesmo TextView. */
    private fun TextView.montarLinha(titulo: String, detalhe: String) {
        val sb = android.text.SpannableStringBuilder()
        sb.append(titulo)
        sb.setSpan(android.text.style.AbsoluteSizeSpan(dp(20), false), 0, sb.length, 0)
        sb.setSpan(android.text.style.ForegroundColorSpan(textoClaro), 0, sb.length, 0)
        sb.setSpan(android.text.style.TypefaceSpan(fonteMedia), 0, sb.length, 0)
        val ini = sb.length
        sb.append("\n").append(detalhe)
        sb.setSpan(android.text.style.AbsoluteSizeSpan(dp(14), false), ini, sb.length, 0)
        sb.setSpan(android.text.style.ForegroundColorSpan(textoSuave), ini, sb.length, 0)
        text = sb
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(24), dp(12), dp(24), dp(12))
    }

    /** Troca só o detalhe (segunda linha) de uma linha já montada, sem perder o título. */
    private fun TextView.detalhe(novo: String) {
        val titulo = text.toString().substringBefore('\n')
        montarLinha(titulo, novo)
    }

    private fun dp(valor: Int): Int = (valor * resources.displayMetrics.density + 0.5f).toInt()

    private fun texto(sp: Float, cor: Int, fonte: Typeface? = null): TextView = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(cor)
        if (fonte != null) typeface = fonte
    }

    private fun vidro(focado: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(if (focado) 0x2EFFFFFF else 0x12FFFFFF)
        setStroke(dp(2), if (focado) 0xD9FFFFFF.toInt() else 0x1FFFFFFF)
    }
}
