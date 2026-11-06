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

private const val PEDIDO_RECURSOS = 41   // código do requestPermissions dos recursos (Recursos.kt)

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

    /** Voltou das Configurações do Android (uso, sobreposição, acessibilidade): redesenha para mostrar o estado novo. */
    private var foiLigar = false
    override fun onRestart() {
        super.onRestart()
        if (foiLigar) { foiLigar = false; setContentView(montarTela()) }
    }

    @Deprecated("Activity simples, sem AndroidX")
    override fun onRequestPermissionsResult(codigo: Int, permissoes: Array<out String>, resultados: IntArray) {
        if (codigo == PEDIDO_RECURSOS) setContentView(montarTela())
    }

    private fun ligarRecurso(r: Recursos.Recurso) {
        val p = r.pedir
        if (p == Recursos.PEDIR_PERMISSAO) {
            val faltam = Recursos.perigosasFaltando(this)
            if (faltam.isNotEmpty()) requestPermissions(faltam, PEDIDO_RECURSOS)
        } else if (p is Intent) {
            // Algumas TVs não têm a tela de uso/sobreposição: cai nas Configurações gerais em vez de travar.
            foiLigar = true
            try { startActivity(p) } catch (e: Exception) {
                try { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) } catch (_: Exception) { foiLigar = false }
            }
        }
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
        if (Config.VPN_FIXA.isEmpty()) (if (vpnAtiva()) getString(R.string.cfg_vpn_conectada) else getString(R.string.cfg_vpn_nenhuma)) + getString(R.string.cfg_vpn_nao_fixa)
        else (if (vpnAtiva()) getString(R.string.cfg_vpn_conectada) else getString(R.string.cfg_vpn_desconectada)) + getString(R.string.cfg_vpn_adguard)

    // ---------------------------------------------------------------- tela

    private fun montarTela(): View {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = resources.getDrawable(R.drawable.fundo, theme)
            setPadding(dp(96), dp(48), dp(96), dp(32))
        }
        raiz.addView(texto(36f, textoClaro, fonteLeve).apply { text = getString(R.string.cfg_titulo) })

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
        val linhaDiagnostico = linha(getString(R.string.cfg_diag), getString(R.string.cfg_diag_det)) {
            startActivity(Intent(this, DiagnosticoActivity::class.java))
        }
        adicionar(linhaDiagnostico)
        // Sem isto a tela abria sem nenhuma linha com foco e o 1º OK do controle não fazia nada.
        linhaDiagnostico.post { linhaDiagnostico.requestFocus() }

        // ---- Aparência: o mesmo "trocar tema" que já existia no popup, só realocado para cá.
        secao(getString(R.string.cfg_sec_aparencia))
        lateinit var linhaTema: TextView
        linhaTema = linha(getString(R.string.cfg_tema, Tema.atual(this).nome), getString(R.string.cfg_tema_det)) {
            Tema.proximo(this)
            linhaTema.text = getString(R.string.cfg_tema, Tema.atual(this).nome)
            // A MainActivity só relê o tema no onCreate: ela mesma percebe a troca e se recria ao voltar (ver onResume lá).
        }
        adicionar(linhaTema)
        // Pilhas automáticas (05/11/2026, Organizacao.agruparPorCategoria): a HOME relê ao voltar (preencherApps no onResume).
        lateinit var linhaPilhas: TextView
        fun detalhePilhas() = if (Organizacao.pilhasAutomaticas(this)) getString(R.string.cfg_pilhas_on)
            else getString(R.string.cfg_pilhas_off)
        linhaPilhas = linha(getString(R.string.cfg_pilhas), detalhePilhas()) {
            Organizacao.definirPilhasAutomaticas(this, !Organizacao.pilhasAutomaticas(this))
            linhaPilhas.detalhe(detalhePilhas())
        }
        adicionar(linhaPilhas)

        // ---- Descanso de tela: OK passa para a próxima opção da lista (mesmo jeito do tema), e grava na hora.
        secao(getString(R.string.cfg_sec_descanso))
        val opcoes = listOf(60_000, 5 * 60_000, 10 * 60_000, 15 * 60_000, 30 * 60_000, Int.MAX_VALUE)
        fun rotuloTempo(ms: Int) = if (ms == Int.MAX_VALUE) getString(R.string.cfg_nunca) else getString(R.string.cfg_min, ms / 60_000)
        lateinit var linhaDescanso: TextView
        linhaDescanso = linha(getString(R.string.cfg_iniciar_apos, rotuloTempo(PrivilegioDO.tempoDescanso(this))), getString(R.string.cfg_descanso_det)) {
            val atual = PrivilegioDO.tempoDescanso(this)
            // Próxima opção depois da atual; se o valor atual não estiver na lista (mudado por fora), começa da primeira.
            val proximo = opcoes[(opcoes.indexOf(atual) + 1) % opcoes.size]
            if (PrivilegioDO.definirTempoDescanso(this, proximo)) {
                linhaDescanso.montarLinha(getString(R.string.cfg_iniciar_apos, rotuloTempo(proximo)), getString(R.string.cfg_descanso_det))
            } else {
                linhaDescanso.detalhe(getString(R.string.cfg_nao_gravou_do))
            }
        }
        adicionar(linhaDescanso)

        // Desligar sozinha (AutoDesligar): depois de X min sem ninguém apertar o controle, avisa por 60 s e apaga a TV.
        fun rotuloDesligar(m: Int) = if (m == 0) "Nunca" else if (m % 60 == 0) "${m / 60} h sem usar" else "$m min sem usar"
        val ajudaDesligar = "OK muda · avisa 60 s antes; qualquer botão cancela"
        lateinit var linhaDesligar: TextView
        linhaDesligar = linha("Desligar a TV: ${rotuloDesligar(AutoDesligar.minutos(this))}", ajudaDesligar) {
            val o = AutoDesligar.OPCOES
            val proximo = o[(o.indexOf(AutoDesligar.minutos(this)) + 1) % o.size]
            AutoDesligar.definir(this, proximo)
            linhaDesligar.montarLinha("Desligar a TV: ${rotuloDesligar(proximo)}", ajudaDesligar)
        }
        adicionar(linhaDesligar)

        // ---- Modo visita (ModoVisita.kt): esconde perfil, agenda e os apps marcados abaixo, até desligar.
        // Na HOME, com o modo ligado, o OK na bolinha do perfil oferece "Sair" (o popup não abre).
        secao(getString(R.string.cfg_visita))
        lateinit var linhaVisita: TextView
        fun detalheVisita() = if (ModoVisita.ativo(this))
            getString(R.string.cfg_visita_on)
        else getString(R.string.cfg_visita_off)
        linhaVisita = linha(getString(R.string.cfg_visita), detalheVisita()) {
            ModoVisita.definir(this, !ModoVisita.ativo(this))
            linhaVisita.detalhe(detalheVisita())
            // A HOME relê o modo no onResume (bolinha e apps), então não precisa avisar nada a ela.
        }
        adicionar(linhaVisita)
        // Rotinas por horário (05/11/2026): ligar, abrir um app ou desligar sozinha (ver Rotinas.kt).
        adicionar(linha(getString(R.string.cfg_rotinas), getString(R.string.cfg_rotinas_det)) {
            startActivity(Intent(this, RotinasActivity::class.java))
        })
        val listaVisita = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lista.addView(listaVisita, LinearLayout.LayoutParams(MATCH, WRAP))
        carregarAppsDaVisita(listaVisita)

        // ---- Atualização (Atualizador.kt): o launcher confere o GitHub a cada 6 h e instala sozinho com a tela apagada.
        secao(getString(R.string.cfg_sec_atualizacao))
        lateinit var linhaAtualiza: TextView
        linhaAtualiza = linha(getString(R.string.cfg_atualizar), getString(R.string.cfg_atualizar_det, versaoInstalada().toString(), Atualizador.estado(this))) {
            linhaAtualiza.detalhe(getString(R.string.cfg_procurando))
            // OK = a pessoa pediu: instala na hora se houver (o launcher reinicia sozinho no fim).
            Atualizador.verificar(this, instalar = true) { r -> runOnUiThread { linhaAtualiza.detalhe(r) } }
        }
        adicionar(linhaAtualiza)

        // ---- Rede: DNS e teste de velocidade (tirados do popup principal, que agora só mostra o Wi-Fi em si).
        secao(getString(R.string.cfg_sec_rede))
        // Se o DNS fixo caiu e o launcher voltou para o automático (Dns.voltarAoAutomaticoSeQuebrado), avisa aqui.
        val caiu = Dns.servidorQueCaiu(this)
        adicionar(linha(getString(R.string.cfg_dns), Dns.rotulo(this) + (if (caiu != null) getString(R.string.cfg_dns_caiu, caiu) else "")) {
            startActivity(Intent(this, DnsActivity::class.java))
        })
        adicionar(linha("VPN", detalheVpn()) {
            if (Config.VPN_FIXA.isEmpty()) return@linha
            val abrir = packageManager.getLeanbackLaunchIntentForPackage(Config.VPN_FIXA)
                ?: packageManager.getLaunchIntentForPackage(Config.VPN_FIXA)
            if (abrir != null) startActivity(abrir.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        lateinit var linhaVelocidade: TextView
        linhaVelocidade = linha(getString(R.string.cfg_velocidade), getString(R.string.cfg_velocidade_det)) {
            linhaVelocidade.detalhe(getString(R.string.cfg_testando))
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

        // ---- Clima (05/11/2026): a faixa de 9 dias e o endereço da casa (CEP + número; o launcher acha as coordenadas).
        lateinit var linhaFaixa: TextView
        fun detalheFaixa() = if (FaixaDias.ligada(this)) "ligada · OK desliga" else "desligada · OK liga"
        linhaFaixa = linha("Previsão de 9 dias na tela inicial", detalheFaixa()) {
            FaixaDias.definirLigada(this, !FaixaDias.ligada(this))
            linhaFaixa.detalhe(detalheFaixa())
        }
        adicionar(linhaFaixa)
        lateinit var linhaEndereco: TextView
        fun detalheEndereco() = LocalClima.ler(this)?.let { "${LocalClima.descrever(it)} · OK troca" }
            ?: "nenhum · a TV se acha pelo Wi-Fi; o endereço vale quando o Wi-Fi não acha · OK cadastra"
        linhaEndereco = linha("Endereço do clima", detalheEndereco()) {
            pedirEndereco { cep, numero ->
                linhaEndereco.detalhe("procurando o endereço…")
                trabalho.execute {
                    val r = LocalClima.definirPorCep(this, cep, numero)
                    ui.post { if (!isDestroyed) linhaEndereco.detalhe(r.fold({ detalheEndereco() }, { "não deu: ${it.message} · OK tenta de novo" })) }
                }
            }
        }
        adicionar(linhaEndereco)

        // ---- Apps ocultos: os que o launcher some pelo Device Owner (ver Config.APPS_OCULTAR/PrivilegioDO.ocultar) —
        // ocultar libera a memória que eles seguravam sem desinstalar, e dá pra mostrar de volta a qualquer momento.
        if (Config.APPS_OCULTAR.isNotEmpty()) {
            secao(getString(R.string.cfg_sec_ocultos))
            Config.APPS_OCULTAR.forEach { pacote ->
                val rotulo = try {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pacote, 0)).toString()
                } catch (e: Exception) { pacote }
                lateinit var linhaApp: TextView
                fun detalheApp() = if (PrivilegioDO.estaOculto(this, pacote)) getString(R.string.cfg_oculto) else getString(R.string.cfg_visivel)
                linhaApp = linha(rotulo, detalheApp()) {
                    val ocultoAntes = PrivilegioDO.estaOculto(this, pacote)
                    if (PrivilegioDO.ocultar(this, pacote, !ocultoAntes)) linhaApp.detalhe(detalheApp())
                    else linhaApp.detalhe(getString(R.string.cfg_nao_mudou_do))
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
        // ---- Claude pela internet (McpServidor + TunelTailscale, 05/11/2026). A chave do Tailscale se cola no painel (PIN).
        secao("Claude pela internet (MCP)")
        lateinit var linhaMcp: TextView
        fun detalheMcp(): String {
            val nome = TunelTailscale.nome(this)
            return when {
                !PainelArmazem.temPin(this) -> "desligado · defina o PIN do painel primeiro (é o login)"
                !McpServidor.ligado(this) -> "desligado · OK liga"
                TunelTailscale.urlLogin != null || TunelTailscale.urlAtivar != null -> "ligado, falta entrar no Tailscale: abra \"Conectar\" abaixo · OK desliga"
                TunelTailscale.rodando() && nome != null -> "ligado · https://$nome/mcp · OK desliga"
                else -> "ligado, túnel subindo${TunelTailscale.ultimoErro(this)?.let { " (último erro: $it)" } ?: ""} · OK desliga"
            }
        }
        linhaMcp = linha("Acesso pelo Claude", detalheMcp()) {
            if (!PainelArmazem.temPin(this)) { linhaMcp.detalhe(detalheMcp()); return@linha }
            McpServidor.definirLigado(this, !McpServidor.ligado(this))
            linhaMcp.detalhe(detalheMcp())
            ui.postDelayed({ if (!isDestroyed) linhaMcp.detalhe(detalheMcp()) }, 3000)
        }
        adicionar(linhaMcp)
        adicionar(linha("Conectar (QR code)", "passo a passo: login no Tailscale e endereço para o Claude, ChatGPT, Codex…") {
            startActivity(android.content.Intent(this, ConectarClaudeActivity::class.java))
        })
        lateinit var linhaRede: TextView
        fun detalheRede() = if (TunelTailscale.redeLigada(this)) "ligada: painel pelos aparelhos da conta Tailscale e lista deles para o Claude · OK desliga"
            else "desligada: só o MCP vai para a internet · OK liga (painel pelos aparelhos da conta Tailscale)"
        linhaRede = linha("Rede Tailscale", detalheRede()) {
            TunelTailscale.definirRede(this, !TunelTailscale.redeLigada(this)); linhaRede.detalhe(detalheRede())
        }
        adicionar(linhaRede)
        lateinit var linhaLogins: TextView
        linhaLogins = linha("Logins do Claude", "${McpServidor.logins(this)} ativo(s) · OK revoga todos") {
            McpServidor.revogar(this)
            linhaLogins.detalhe("${McpServidor.logins(this)} ativo(s) · revogados")
        }
        adicionar(linhaLogins)
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
        // Modo sem Device Owner (Recursos.kt): cada poder com ligado/faltando; OK num que falta tenta ligar sem PC.
        secao(getString(R.string.cfg_sec_ligado, Recursos.resumo(this)))
        for (r in Recursos.listar(this)) {
            val detalhe = if (r.ativo) getString(R.string.cfg_ligado) else r.sem + when (r.pedir) {
                null -> getString(R.string.cfg_so_pc)
                else -> getString(R.string.cfg_tenta_ligar)
            }
            adicionar(if (r.ativo || r.pedir == null) info((if (r.ativo) "✓ " else "✗ ") + r.nome, detalhe)
                else linha("✗ " + r.nome, detalhe) { ligarRecurso(r) })
        }
        if (PrivilegioDO.somosDonos(this))
            adicionar(info(getString(R.string.cfg_perm_fixas), Config.PERMISSOES_FIXAS.joinToString { it.substringAfterLast('.') }))

        // ---- Shizuku embutido (MARCO S2): estado do servidor e os apps que pediram acesso a ele. OK num app alterna
        // entre permitido e revogado. A lista é lida do servidor (binder) fora da thread da tela.
        secao(getString(R.string.cfg_sec_shizuku))
        lateinit var linhaServidor: TextView
        // Desligado por padrão (04/11/2026, memória): só fica de pé quando alguém liga aqui (e continua ligado no boot).
        // 04/11/2026: três modos (ver Config.ModoShizuku). O OK passa ao próximo: sob demanda -> sempre ligado -> desligado.
        fun detalheServidor(): String {
            val modo = Config.modoShizuku(this)
            val agora = if (rikka.shizuku.Shizuku.pingBinder())
                getString(R.string.cfg_shz_rodando, rikka.shizuku.Shizuku.getUid(), rikka.shizuku.Shizuku.getVersion()) else getString(R.string.cfg_shz_parado)
            val explica = when (modo) {
                Config.ModoShizuku.SOB_DEMANDA -> getString(R.string.cfg_shz_sob)
                Config.ModoShizuku.SEMPRE -> getString(R.string.cfg_shz_sempre)
                Config.ModoShizuku.DESLIGADO -> getString(R.string.cfg_shz_desligado)
            }
            return getString(R.string.cfg_shz_det, modo.rotulo, explica, agora)
        }
        linhaServidor = linha(getString(R.string.cfg_servidor), detalheServidor()) {
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
        linhaParear = linha(getString(R.string.cfg_parear), if (PareamentoNaTv.podeMostrar(this))
            getString(R.string.cfg_parear_det)
        else getString(R.string.cfg_acess_off)) {
            if (!PareamentoNaTv.comecar(this)) linhaParear.detalhe(getString(R.string.cfg_acess_off))
        }
        adicionar(linhaParear)
        listaClientes =LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lista.addView(listaClientes, LinearLayout.LayoutParams(MATCH, WRAP))
        carregarClientes()

        // ---- Diagnóstico: números úteis para saber se algo por trás dos panos está ok, sem precisar do logcat.
        secao(getString(R.string.cfg_sec_diagnostico))
        val versao = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { null }
        adicionar(info(getString(R.string.cfg_versao), versao ?: getString(R.string.cfg_desconhecida)))
        adicionar(info(getString(R.string.cfg_depuracao), if (Config.DEPURACAO_SEM_FIO_SEMPRE_LIGADA) getString(R.string.cfg_depuracao_on) else getString(R.string.cfg_depuracao_android)))
        // Fora da thread da tela (revisão 04/11): na 1ª vez isto GERA a chave RSA-2048 (segundos num ARMv7).
        val linhaChave = info(getString(R.string.cfg_chave), getString(R.string.cfg_calculando))
        adicionar(linhaChave)
        trabalho.execute {
            val fp = try { ShellProprio.impressaoDigital(this) } catch (e: Exception) { getString(R.string.cfg_erro, e.javaClass.simpleName) }
            ui.post { if (!isDestroyed) linhaChave.detalhe(fp) }
        }
        // Licenças (05/11/2026): o texto fica em assets/licencas.txt, o mesmo que o repositório mostra.
        adicionar(linha(getString(R.string.cfg_licencas), getString(R.string.cfg_licencas_det)) {
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
                        getString(R.string.cfg_some_visita) else getString(R.string.cfg_aparece_visita)
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
                    listaClientes.addView(info(getString(R.string.cfg_apps_acesso), getString(R.string.cfg_nenhum_pediu)))
                    return@post
                }
                for (pi in apps) {
                    val ai = pi.applicationInfo ?: continue
                    val rotulo = try { ai.loadLabel(packageManager).toString() } catch (e: Exception) { pi.packageName }
                    lateinit var linhaApp: TextView
                    fun detalheApp() = if (AutorizacoesShizuku.permitido(ai.uid)) getString(R.string.cfg_permitido) else getString(R.string.cfg_sem_acesso)
                    linhaApp = linha(rotulo, detalheApp()) {
                        try {
                            if (AutorizacoesShizuku.permitido(ai.uid)) AutorizacoesShizuku.revogar(ai.uid)
                            else AutorizacoesShizuku.permitir(ai.uid)
                            linhaApp.detalhe(detalheApp())
                        } catch (e: Throwable) { linhaApp.detalhe(getString(R.string.cfg_nao_mudou, e.javaClass.simpleName)) }
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

    /** CEP e número com o teclado numérico da TV (AlertDialog: OK confirma, Voltar cancela). CEP vazio apaga o endereço. */
    private fun pedirEndereco(aoConfirmar: (String, String) -> Unit) {
        val atual = LocalClima.ler(this)
        fun campo(dica: String, max: Int, valor: String) = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(max))
            hint = dica; setText(valor); setSingleLine()
        }
        val cep = campo("CEP (8 números)", 8, atual?.cep.orEmpty())
        val numero = campo("Número da casa (opcional)", 6, atual?.numero.orEmpty())
        val caixa = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
            addView(cep); addView(numero)
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Endereço do clima")
            .setView(caixa)
            .setPositiveButton("OK") { _, _ ->
                if (cep.text.isBlank()) { LocalClima.limpar(this); recreate() }
                else aoConfirmar(cep.text.toString(), numero.text.toString())
            }
            .setNegativeButton("Cancelar", null)
            .show()
        cep.requestFocus()
    }

    // ---------------------------------------------------------------- peças

    /** Uma linha clicável: título + detalhe, fica clara com foco (igual às linhas do popup e da tela de DNS). */
    @Suppress("DEPRECATION")
    private fun versaoInstalada(): Long = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    } catch (e: Exception) { 0L }

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
