package br.paulo.tvlauncher

/**
 * Tudo que você pode querer mudar fica neste arquivo.
 * Depois de editar, é só recompilar e reinstalar o APK.
 */
object Config {
    // --- Clima (Open-Meteo). Os dados pessoais (cidade, coordenadas, unidade, estação) NÃO ficam mais aqui, porque o
    //     repositório é público (05/11/2026): vêm do pessoal.properties da raiz do repo (fora do git), pela classe
    //     BuildConfig que o Gradle gera. Sem o arquivo: sem cidade, coordenadas 0/0, Celsius, estação automática.
    //     Para mudar: edite o pessoal.properties (modelo em pessoal.properties.exemplo) e recompile.
    val CIDADE: String = BuildConfig.CIDADE
    val LATITUDE: Double = BuildConfig.LATITUDE
    val LONGITUDE: Double = BuildConfig.LONGITUDE
    val USAR_FAHRENHEIT: Boolean = BuildConfig.USAR_FAHRENHEIT
    // A localização de verdade vem da própria TV (Wi-Fi do Android, depois IP; ver Localizacao.kt). LATITUDE/LONGITUDE
    // acima só valem se as duas falharem. A estação de medição (aeroporto) é escolhida sozinha: a mais perto.
    // Para FIXAR uma, ponha o código ICAO em estacao_metar no pessoal.properties; vazio = automático.
    val ESTACAO_METAR: String = BuildConfig.ESTACAO_METAR
    // Minutos depois do PÔR do sol real (calculado por Astronomia.nascerEPor, não o "is_day" da Open-Meteo) em que
    // o ícone ainda considera "dia". A Open-Meteo marca noite no instante exato do pôr, sem contar o crepúsculo
    // civil (a claridade que sobra no céu depois). Flagrado em 03/11/2026 às 18:38: pôr às 18:24, ícone já de lua,
    // mas a foto da varanda mostrava céu claramente ainda claro. Ajuste este número se ainda trocar cedo/tarde demais.
    const val MARGEM_CREPUSCULO_MIN = 20

    // --- Quais apps aparecem: os instalados por você + estes pacotes de sistema (a Play Store).
    val SEMPRE_MOSTRAR = listOf("com.android.vending")

    // --- Ordem dos apps:
    //   1º os FIXOS (sempre no começo, na ordem desta lista);
    //   2º se ORDENAR_POR_USO_RECENTE = true, o que você usou mais recentemente;
    //   3º a ordem da lista ORDEM; 4º ordem alfabética.
    //   Se voce reorganizar na TV (segurar OK num app), a ordem SALVA vence tudo isto; use a seta para baixo no modo de mover para voltar ao automatico.
    // FIXOS e ORDEM vêm do pessoal.properties (apps_fixos / apps_ordem, pacotes separados por vírgula). Sem o arquivo:
    // nenhum fixo, e a ordem padrão YouTube, YouTube Music, Netflix, Play Store (ver app/build.gradle.kts).
    val FIXOS: List<String> = listaDe(BuildConfig.APPS_FIXOS)
    const val ORDENAR_POR_USO_RECENTE = true
    val ORDEM: List<String> = listaDe(BuildConfig.APPS_ORDEM)

    /** "a, b,c" -> [a, b, c]; texto vazio -> lista vazia. */
    private fun listaDe(csv: String) = csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    // --- Pacote das Configurações do sistema (abre pelo menu da bolinha do perfil, no canto superior direito).
    const val PACOTE_CONFIG = "com.android.tv.settings"

    // --- App aberto automaticamente quando a TV REINICIA (não quando acorda do standby). "" desliga.
    const val ABRIR_AO_LIGAR = ""   // desligado: o OneStream tem opcao propria de abrir ao reiniciar; ponha o pacote aqui so se quiser que o launcher faca isso

    // --- Serviço de acessibilidade (AutorizaShizukuService, hoje só trata teclas do controle remoto): true = o
    //     launcher RELIGA o serviço sozinho se o Android o desligar (ver Privilegio.religarAcessibilidade).
    //     Até 03/11/2026 esta chave também controlava a aprovação automática dos diálogos do Shizuku; esse app
    //     foi desinstalado e a lógica, removida — o nome ficou de antes, mas a função é só essa religada acima.
    const val SERVICO_TECLAS_SEMPRE_LIGADO = true

    // --- Device Owner (ver PrivilegioDO.kt) ---
    // FIXAR_PROTETOR: o app aponta o screensaver do sistema para o nosso ProtetorTela (aposenta o dreamx, ~59 MB).
    const val FIXAR_PROTETOR = true
    // Shell proprio (ver ShellProprio.kt) MARCO 1: so loga a chave do app e a porta do adb descoberta por mDNS. Diagnostico.
    // false desde 04/11/2026: quem conecta agora e o GuardiaoShizuku; o diagnostico ligava 2 buscas mDNS a cada onCreate
    // (inclusive na troca de tema) e listava aparelhos de outros da rede no log.
    const val SHELL_PROPRIO_DIAG = false
    // Shizuku embutido (MARCO S3, ver GuardiaoShizuku.kt): true = o launcher sobe o servidor do Shizuku sozinho (no boot e
    // ao abrir) e o religa se cair. false = so sobe por adb (PAREAR) ou pelo OK em Configuracoes > Shizuku do launcher.
    const val SHIZUKU_AUTOMATICO = true
    // "Shizuku ativo": o servidor so sobe (e o guardiao so religa) com isto ligado. DESLIGADO por padrao (04/11/2026):
    // o shizuku_server ocupa ~58 MB de PSS numa TV com o swap quase cheio, e hoje nenhum app usa. Liga/desliga em
    // Configuracoes > Shizuku do launcher > Servidor (OK) ou pelos intents INICIAR/PARAR (ControleShizukuReceiver).
    //
    // MODOS (04/11/2026, pedido do Paulo: "custo zero parado e sempre disponível"):
    //  - DESLIGADO: nunca sobe sozinho.
    //  - SOB_DEMANDA (padrão): sobe quando um app que pede o Shizuku (uses-permission API_V23) vai para a frente, e
    //    desce depois de 5 min sem nenhum deles na frente (ver ShizukuSobDemanda.kt).
    //  - SEMPRE: sobe no boot e o vigia religa se cair (o comportamento antigo do "ativo").
    enum class ModoShizuku(private val id: Int) {
        DESLIGADO(R.string.shz_modo_desligado), SOB_DEMANDA(R.string.shz_modo_sob), SEMPRE(R.string.shz_modo_sempre);
        val rotulo: String get() = txt(id)   // no idioma da TV
    }
    fun modoShizuku(ctx: android.content.Context): ModoShizuku {
        val p = ctx.getSharedPreferences("launcher", android.content.Context.MODE_PRIVATE)
        p.getString("shizuku_modo", null)?.let { s -> ModoShizuku.values().firstOrNull { it.name == s }?.let { return it } }
        // Migração: quem tinha ligado o "ativo" antigo continua sempre ligado; o resto passa a sob demanda.
        return if (p.getBoolean("shizuku_ativo", false)) ModoShizuku.SEMPRE else ModoShizuku.SOB_DEMANDA
    }
    fun definirModoShizuku(ctx: android.content.Context, modo: ModoShizuku) =
        ctx.getSharedPreferences("launcher", android.content.Context.MODE_PRIVATE).edit().putString("shizuku_modo", modo.name).apply()
    /** O servidor deve estar de pé AGORA? (SEMPRE; ou SOB_DEMANDA com um app cliente usado há pouco.) */
    fun shizukuAtivo(ctx: android.content.Context) = when (modoShizuku(ctx)) {
        ModoShizuku.SEMPRE -> true
        ModoShizuku.SOB_DEMANDA -> ShizukuSobDemanda.clienteAtivo()
        ModoShizuku.DESLIGADO -> false
    }
    // VPN_FIXA: app que o Device Owner fixa como "VPN sempre ativa" (sem lockdown: se cair, a TV segue com internet).
    // 04/11/2026: o app AdGuard VPN, decisão do Paulo — a VPN própria do launcher (IKEv2) foi aposentada.
    // "" = o launcher não mexe na VPN.
    // 04/11/2026 (noite): VAZIO, a pedido do Paulo. Com a AdGuard VPN sempre ativa, o 1-Stream (IPTV) parou de baixar a
    // lista de canais na TV da Sala; sem ela, voltou na hora (medido às 19:02). O app AdGuard VPN foi desinstalado da TV.
    // A causa ainda não foi investigada. Pista: o AdGuard VPN tinha sido atualizado pela Play Store em 03/11, 13:58.
    // Antes de pôr um pacote aqui de novo, resolver isso (ex.: o 1-Stream nas exclusões do app de VPN).
    const val VPN_FIXA = ""
    // Atualização automática pelo GitHub Releases (Atualizador.kt, 05/11/2026). Só instala APK do MESMO pacote e assinado
    // com a MESMA chave do launcher instalado; numa TV atualizada pelo PC (chave própria) o Release é sempre recusado.
    // Num fork: troque o repositório para o seu.
    const val ATUALIZACAO_AUTOMATICA = true
    const val ATUALIZACAO_REPO = "ChrnX0/tv-launcher"
    // OTIMIZAR_MEMORIA: ocultar os apps de APPS_OCULTAR pelo Device Owner, liberando a memoria que seguravam (reversivel).
    const val OTIMIZAR_MEMORIA = true
    // Apps que o launcher substitui e por isso oculta. Comecar so pelo dreamx (protetor de fabrica, trocado pelo nosso).
    // launcherx (04/11/2026): o launcher do Google já estava DESATIVADO (enabled=3), mas continuava "instalado" para os
    // outros apps. O seletor de contas do GMS (CHOOSE_ACCOUNT -> NoTouchAccountPickerActivity) via o pacote e mandava
    // para launcherx/.profile.chooser.ProfileChooserActivity -> ActivityNotFoundException -> gms.ui caía (151 crashes
    // no dropbox). OCULTO, o GMS usa o seletor próprio. Medido na TV, por am start do CHOOSE_ACCOUNT com a tela apagada:
    // antes, "Caused by: ActivityNotFoundException ... ProfileChooserActivity"; depois, "Displayed ...
    // NoTouchAccountPickerActivity +387ms", sem crash. Desfazer: tirar daqui E reexibir por adb:
    //   am broadcast -a br.paulo.tvlauncher.OCULTAR --es pacote com.google.android.apps.tv.launcherx --ez oculto false
    //     -n br.paulo.tvlauncher/.ControleShizukuAdbReceiver
    // ATUALIZAÇÃO (04/11/2026, mais tarde): o launcherx foi REATIVADO e saiu desta lista. Desativado ou oculto, ele quebra
    // o Gemini: a resposta da voz abre launcherx/.gemini.chat.ChatActivity -> ActivityNotFoundException -> "o Google
    // Assistente está com problema" (logcat do katniss, IntentStarterDelegate). A HOME continua nossa pela preferência
    // persistente do Device Owner (PrivilegioDO.fixarHome).
    val APPS_OCULTAR = listOf("com.google.android.apps.tv.dreamx")
    // Permissoes que o launcher se autoconcede pelo Device Owner (ver PrivilegioDO.conceder): permanentes, o usuario nao revoga.
    // So valem as declaradas no manifesto. Evita depender de `pm grant` por adb.
    // GET_ACCOUNTS desde 04/11/2026 (revisão): é "perigosa" (o DO pode fixar) e a foto/inicial do perfil depende dela;
    // antes só vinha de um `pm grant` por adb, que o sistema pode revogar.
    val PERMISSOES_FIXAS = listOf("android.permission.READ_CALENDAR", "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.GET_ACCOUNTS")

    // --- Depuração sem fio (adb pela rede, usado pelo computador para instalar e testar): true = o launcher a LIGA de novo
    //     sozinho quando a TV reinicia e sempre que abre, se o Android a tiver desligado. Custo: com ela ligada, a TV deixa uma porta de adb
    //     aberta na rede (só aceita computadores já pareados, com chave). false = fica como o Android deixar (ver Privilegio.religarDepuracaoSemFio).
    const val DEPURACAO_SEM_FIO_SEMPRE_LIGADA = true

    // --- Pilhas de cards: segurar ▲ por este tempo (ms) tira o foco da pilha e sobe para o topo (hora, clima, bolinha).
    //     Um toque rápido em ▲ continua trocando o app da frente da pilha.
    const val PILHA_SUBIR_MS = 500L

    // --- De quanto em quanto tempo cada informação do topo/rodapé é atualizada.
    const val CLIMA_A_CADA_MIN = 5L   // as fontes mudam a cada 15 min (modelo) e ~60 min (aeroporto, ou antes se o tempo virar); 5 min pega a mudança cedo
    const val REDE_A_CADA_SEG = 30L
    const val ECLIPSE_A_CADA_SEG = 60L   // so importa durante um eclipse solar: a lua anda no icone a cada tanto

    // --- Eclipses solares visíveis da SUA cidade. O ícone do clima só vira "sol coberto pela lua" dentro destes
    //     horários, com a cobertura do momento; fora deles nunca aparece. Como depende da cidade, a tabela vem do
    //     pessoal.properties (chave eclipses; formato e exemplo em pessoal.properties.exemplo). Fonte sugerida:
    //     timeanddate.com/eclipse/in/<país>/<cidade>. Sem a chave (ou com ela vazia), nunca aparece eclipse.
    //     Cada eclipse: 1º contato, máximo, último contato (UTC, ISO-8601) e a magnitude (fração do diâmetro coberta).
    val ECLIPSES_SOLARES: List<EclipseSolar> = eclipsesDe(BuildConfig.ECLIPSES)

    /** "ini,max,fim,mag; ini,max,fim,mag" -> lista. Um item malformado é ignorado (não derruba o launcher). */
    internal fun eclipsesDe(texto: String): List<EclipseSolar> = texto.split(';').mapNotNull { item ->
        val c = item.split(',').map { it.trim() }
        if (c.size != 4) return@mapNotNull null
        try { EclipseSolar(c[0], c[1], c[2], c[3].toDouble()) } catch (e: Exception) { null }
    }

    // --- Qualidade da rede: o ícone de Wi-Fi do menu do perfil fica verde, amarelo ou vermelho conforme
    //     estes limites. A cor segue a PIOR das três medidas (sinal, latência, velocidade do enlace).
    //     Sinal em dBm (mais perto de 0 = melhor), latência em ms, velocidade do enlace em Mbps.
    const val WIFI_DBM_BOM = -67      // sinal igual ou melhor que isto: verde (-67 e o minimo comum para video fluido)
    const val WIFI_DBM_OK = -75       // entre este e o BOM: amarelo; pior que este: vermelho
    const val LATENCIA_BOA_MS = 60    // até aqui: verde
    const val LATENCIA_OK_MS = 120    // até aqui: amarelo; acima: vermelho (sem internet = vermelho)
    const val LINK_BOM_MBPS = 60      // enlace igual ou acima disto: verde
    const val LINK_OK_MBPS = 25       // entre este e o BOM: amarelo; abaixo: vermelho
}
