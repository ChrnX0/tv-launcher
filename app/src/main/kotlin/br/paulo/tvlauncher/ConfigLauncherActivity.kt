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
        if (Config.VPN_FIXA.isEmpty()) (if (vpnAtiva()) "conectada" else "nenhuma") + " — o launcher não fixa VPN"
        else (if (vpnAtiva()) "conectada" else "desconectada") + " — app AdGuard VPN, sempre ativa · OK abre o app"

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

        // ---- Diagnóstico de 1 botão (04/11/2026): a 1ª linha, para ser o OK mais rápido quando "o app parou".
        val linhaDiagnostico = linha("Diagnosticar a TV", "internet, DNS, VPN e por que um app parou — tudo de uma vez") {
            startActivity(Intent(this, DiagnosticoActivity::class.java))
        }
        adicionar(linhaDiagnostico)
        // Sem isto a tela abria sem nenhuma linha com foco e o 1º OK do controle não fazia nada.
        linhaDiagnostico.post { linhaDiagnostico.requestFocus() }

        // ---- Aparência: o mesmo "trocar tema" que já existia no popup, só realocado para cá.
        secao("Aparência")
        lateinit var linhaTema: TextView
        linhaTema = linha("Tema: ${Tema.atual(this).nome}", "muda para o próximo visual") {
            Tema.proximo(this)
            linhaTema.text = "Tema: ${Tema.atual(this).nome}"
            // A MainActivity só relê o tema no onCreate: ela mesma percebe a troca e se recria ao voltar (ver onResume lá).
        }
        adicionar(linhaTema)
        // Pilhas automáticas (05/11/2026, Organizacao.agruparPorCategoria): a HOME relê ao voltar (preencherApps no onResume).
        lateinit var linhaPilhas: TextView
        fun detalhePilhas() = if (Organizacao.pilhasAutomaticas(this)) "ligadas: apps soltos juntam pela categoria (vídeo, música, jogo…) · OK desliga"
            else "desligadas · OK liga (as pilhas que você montou continuam)"
        linhaPilhas = linha("Pilhas automáticas", detalhePilhas()) {
            Organizacao.definirPilhasAutomaticas(this, !Organizacao.pilhasAutomaticas(this))
            linhaPilhas.detalhe(detalhePilhas())
        }
        adicionar(linhaPilhas)

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

        // ---- Modo visita (ModoVisita.kt): esconde perfil, agenda e os apps marcados abaixo, até desligar.
        // Na HOME, com o modo ligado, o OK na bolinha do perfil oferece "Sair" (o popup não abre).
        secao("Modo visita")
        lateinit var linhaVisita: TextView
        fun detalheVisita() = if (ModoVisita.ativo(this))
            "ligado — perfil, agenda e os apps marcados abaixo estão escondidos · OK desliga"
        else "desligado — OK esconde o perfil, a agenda e os apps marcados abaixo"
        linhaVisita = linha("Modo visita", detalheVisita()) {
            ModoVisita.definir(this, !ModoVisita.ativo(this))
            linhaVisita.detalhe(detalheVisita())
            // A HOME relê o modo no onResume (bolinha e apps), então não precisa avisar nada a ela.
        }
        adicionar(linhaVisita)
        // Rotinas por horário (05/11/2026): ligar, abrir um app ou desligar sozinha (ver Rotinas.kt).
        adicionar(linha("Rotinas", "ligar, abrir um app ou desligar a TV num horário") {
            startActivity(Intent(this, RotinasActivity::class.java))
        })
        val listaVisita = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lista.addView(listaVisita, LinearLayout.LayoutParams(MATCH, WRAP))
        carregarAppsDaVisita(listaVisita)

        // ---- Rede: DNS e teste de velocidade (tirados do popup principal, que agora só mostra o Wi-Fi em si).
        secao("Rede")
        // Se o DNS fixo caiu e o launcher voltou para o automático (Dns.voltarAoAutomaticoSeQuebrado), avisa aqui.
        val caiu = Dns.servidorQueCaiu(this)
        adicionar(linha("DNS privado", Dns.rotulo(this) + (if (caiu != null) " · $caiu não respondeu, voltei para o automático" else "")) {
            startActivity(Intent(this, DnsActivity::class.java))
        })
        adicionar(linha("VPN", detalheVpn()) {
            if (Config.VPN_FIXA.isEmpty()) return@linha
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

        // ---- Avisos na tela (05/11/2026): o aviso de chuva por cima de qualquer app (AvisoChuva.kt). O recado vem pelo
        // painel no navegador e não tem chave aqui: quem manda é quem tem o PIN.
        secao("Avisos")
        lateinit var linhaChuva: TextView
        fun detalheChuva() = if (AvisoChuva.ligado(this))
            "ligado · avisa por cima do app quando a chance passa de ${AvisoChuva.LIMIAR}% nas próximas ${AvisoChuva.HORAS_A_FRENTE} h · OK desliga"
        else "desligado · OK liga"
        linhaChuva = linha("Aviso de chuva", detalheChuva()) {
            AvisoChuva.definirLigado(this, !AvisoChuva.ligado(this))
            linhaChuva.detalhe(detalheChuva())
        }
        adicionar(linhaChuva)

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

        // ---- Painel no navegador (PainelWeb.kt): administrar a TV pelo celular/computador da casa, com PIN.
        secao("Painel no navegador")
        lateinit var linhaPainel: TextView
        lateinit var linhaPin: TextView
        fun detalhePainel(): String = when {
            !PainelArmazem.temPin(this) -> "desligado · defina um PIN abaixo para ligar"
            PainelArmazem.desligadoPorErros(this) -> "desligado sozinho: PIN errado muitas vezes · OK religa"
            PainelArmazem.ligado(this) && PainelWeb.ligado() ->
                "ligado · abra ${PainelWeb.endereco() ?: "(sem rede)"} no navegador · OK desliga"
            PainelArmazem.ligado(this) -> "ligado, mas a porta ${Config.PAINEL_PORTA} não abriu · OK desliga"
            else -> "desligado · OK liga"
        }
        linhaPainel = linha("Painel", detalhePainel()) {
            when {
                !PainelArmazem.temPin(this) -> linhaPainel.detalhe("defina um PIN primeiro (linha de baixo)")
                PainelArmazem.desligadoPorErros(this) -> {
                    PainelArmazem.definirDesligadoPorErros(this, false)
                    PainelArmazem.definirLigado(this, true)
                    PainelWeb.recomecar(this)
                }
                else -> {
                    PainelArmazem.definirLigado(this, !PainelArmazem.ligado(this))
                    PainelWeb.ajustar(this)
                }
            }
            if (PainelArmazem.temPin(this)) {
                linhaPainel.detalhe(detalhePainel())
                // A porta abre numa thread à parte: confere de novo daqui a pouco.
                ui.postDelayed({ if (!isDestroyed) linhaPainel.detalhe(detalhePainel()) }, 800)
            }
        }
        adicionar(linhaPainel)
        linhaPin = linha("PIN", if (PainelArmazem.temPin(this)) "definido · OK troca" else "não definido · OK define") {
            pedirPin("Novo PIN (${PinSeguro.MIN_DIGITOS} a ${PinSeguro.MAX_DIGITOS} dígitos)") { pin ->
                if (!PinSeguro.valido(pin)) { linhaPin.detalhe("PIN precisa ter de ${PinSeguro.MIN_DIGITOS} a ${PinSeguro.MAX_DIGITOS} números"); return@pedirPin }
                pedirPin("Digite o PIN de novo") { pin2 ->
                    if (pin2 != pin) { linhaPin.detalhe("os dois PINs não bateram · OK tenta de novo"); return@pedirPin }
                    linhaPin.detalhe("gravando…")
                    // PBKDF2 com 100 mil voltas leva um tempo num ARMv7: fora da thread da tela.
                    trabalho.execute {
                        PainelArmazem.definirPin(this, pin)
                        ui.post {
                            if (isDestroyed) return@post
                            // Definir o PIN já liga o painel (é para isso que se define) e derruba as sessões antigas.
                            PainelArmazem.definirLigado(this, true)
                            PainelWeb.recomecar(this)
                            linhaPin.detalhe("definido · OK troca")
                            linhaPainel.detalhe(detalhePainel())
                            ui.postDelayed({ if (!isDestroyed) linhaPainel.detalhe(detalhePainel()) }, 800)
                        }
                    }
                }
            }
        }
        adicionar(linhaPin)
        val acessos = PainelArmazem.resumoRegistro(this, 3)
        adicionar(info("Últimos acessos", if (acessos.isEmpty()) "nenhum ainda" else acessos.joinToString("\n")).apply { maxLines = 4 })
        // ---- Limpeza de apps de fábrica (Debloat.kt, 05/11/2026): só os apps já testados nesta TV, com desfazer.
        secao("Limpeza de apps de fábrica")
        val limpaveis = Debloat.disponiveis(this)
        if (limpaveis.isEmpty()) adicionar(info("Nenhum app liberado ainda", "só entram apps testados um a um nesta TV (ver Debloat.kt)"))
        limpaveis.forEach { item ->
            lateinit var linhaApp: TextView
            fun detalheApp() = if (PrivilegioDO.estaOculto(this, item.pacote)) "oculto — perde: ${item.perde} · OK mostra"
                else "visível — OK oculta (perde: ${item.perde})"
            linhaApp = linha(item.nome, detalheApp()) {
                val ocultar = !PrivilegioDO.estaOculto(this, item.pacote)
                if (Debloat.definir(this, item.pacote, ocultar)) linhaApp.detalhe(detalheApp())
                else linhaApp.detalhe("não consegui mudar (Device Owner inativo?)")
            }
            adicionar(linhaApp)
        }
        if (Debloat.ocultosPorAqui(this).isNotEmpty()) {
            lateinit var linhaDesfazer: TextView
            linhaDesfazer = linha("Mostrar todos de volta", "desfaz a limpeza (o protetor do Google continua oculto)") {
                linhaDesfazer.detalhe("${Debloat.desfazerTudo(this)} app(s) de volta — saia e entre para atualizar a lista")
            }
            adicionar(linhaDesfazer)
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
        // 04/11/2026: três modos (ver Config.ModoShizuku). O OK passa ao próximo: sob demanda -> sempre ligado -> desligado.
        fun detalheServidor(): String {
            val modo = Config.modoShizuku(this)
            val agora = if (rikka.shizuku.Shizuku.pingBinder())
                "rodando (uid ${rikka.shizuku.Shizuku.getUid()}, versão ${rikka.shizuku.Shizuku.getVersion()})" else "parado"
            val explica = when (modo) {
                Config.ModoShizuku.SOB_DEMANDA -> "sobe quando um app pede, desce após 5 min sem uso"
                Config.ModoShizuku.SEMPRE -> "sempre de pé, religa se cair"
                Config.ModoShizuku.DESLIGADO -> "não sobe"
            }
            return "${modo.rotulo} ($explica) · agora: $agora — OK troca o modo"
        }
        linhaServidor = linha("Servidor", detalheServidor()) {
            when (Config.modoShizuku(this)) {
                Config.ModoShizuku.SOB_DEMANDA -> ControleShizukuReceiver.ligar(this)   // pela fila do guardião
                Config.ModoShizuku.SEMPRE -> ControleShizukuReceiver.desligar(this)
                Config.ModoShizuku.DESLIGADO -> ControleShizukuReceiver.sobDemanda(this)
            }
            linhaServidor.detalhe(detalheServidor())
            ui.postDelayed({ if (!isDestroyed) { linhaServidor.detalhe(detalheServidor()); carregarClientes() } }, 10_000)
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
        // Licenças (05/11/2026): o texto fica em assets/licencas.txt, o mesmo que o repositório mostra.
        adicionar(linha("Licenças e créditos", "código, fontes, ícones e dados de terceiros usados aqui") {
            startActivity(Intent(this, LicencasActivity::class.java))
        })

        return raiz
    }

    // ---------------------------------------------------------------- Modo visita: apps escolhidos

    /**
     * Uma linha por app de TV instalado, para marcar quais somem no modo visita. A lista (PackageManager) é lida fora
     * da thread da tela; só o nome importa aqui, sem ícone, para ficar leve.
     */
    private fun carregarAppsDaVisita(destino: LinearLayout) {
        trabalho.execute {
            val pm = packageManager
            val consulta = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            val apps = try {
                pm.queryIntentActivities(consulta, 0)
                    // Os mesmos apps que a HOME mostra (MainActivity.carregarApps): os instalados pelo usuário e os de
                    // sistema que estão em SEMPRE_MOSTRAR.
                    .filter {
                        val pkg = it.activityInfo.packageName
                        pkg != packageName && pkg != Config.PACOTE_CONFIG &&
                            ((it.activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 ||
                                pkg in Config.SEMPRE_MOSTRAR)
                    }
                    .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                    .distinctBy { it.first }
                    .sortedBy { it.second.lowercase() }
            } catch (e: Exception) { emptyList() }
            ui.post {
                if (isDestroyed) return@post
                destino.removeAllViews()
                for ((pacote, rotulo) in apps) {
                    lateinit var linhaApp: TextView
                    fun detalheApp() = if (ModoVisita.escondeNaVisita(this, pacote))
                        "some no modo visita — OK mantém" else "aparece no modo visita — OK esconde"
                    linhaApp = linha(rotulo, detalheApp()) {
                        ModoVisita.alternar(this, pacote)
                        linhaApp.detalhe(detalheApp())
                    }
                    destino.addView(linhaApp, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
                }
            }
        }
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

    // ---------------------------------------------------------------- PIN do painel

    /**
     * Caixa para digitar o PIN com o teclado da TV (só números, escondido). [aoConfirmar] recebe o texto digitado.
     * AlertDialog do próprio Android: o controle remoto já sabe navegar nele (OK confirma, Voltar cancela).
     */
    private fun pedirPin(titulo: String, aoConfirmar: (String) -> Unit) {
        val campo = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(android.text.InputFilter.LengthFilter(PinSeguro.MAX_DIGITOS))
            setSingleLine()
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(titulo)
            .setView(campo)
            .setPositiveButton("OK") { _, _ -> aoConfirmar(campo.text.toString()) }
            .setNegativeButton("Cancelar", null)
            .show()
        campo.requestFocus()
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
