package br.paulo.tvlauncher

/**
 * Tudo que você pode querer mudar fica neste arquivo.
 * Depois de editar, é só recompilar e reinstalar o APK.
 */
object Config {
    // --- Clima (Open-Meteo). Ao se mudar para a Flórida: troque cidade, latitude, longitude e USAR_FAHRENHEIT.
    const val CIDADE = "Ribeirão Preto"
    const val LATITUDE = -21.1775
    const val LONGITUDE = -47.8103
    const val USAR_FAHRENHEIT = false
    // A localização de verdade vem da própria TV (Wi-Fi do Android, depois IP; ver Localizacao.kt). LATITUDE/LONGITUDE
    // acima só valem se as duas falharem. A estação de medição (aeroporto) é escolhida sozinha: a mais perto.
    // Para FIXAR uma, ponha o código ICAO aqui (ex.: "SBRP"); "" = automático.
    const val ESTACAO_METAR = ""

    // --- Quais apps aparecem: os instalados por você + estes pacotes de sistema (a Play Store).
    val SEMPRE_MOSTRAR = listOf("com.android.vending")

    // --- Ordem dos apps:
    //   1º os FIXOS (sempre no começo, na ordem desta lista);
    //   2º se ORDENAR_POR_USO_RECENTE = true, o que você usou mais recentemente;
    //   3º a ordem da lista ORDEM; 4º ordem alfabética.
    //   Se voce reorganizar na TV (segurar OK num app), a ordem SALVA vence tudo isto; use a seta para baixo no modo de mover para voltar ao automatico.
    val FIXOS = listOf("com.onestream.player")
    const val ORDENAR_POR_USO_RECENTE = true
    val ORDEM = listOf(
        "com.onestream.player",
        "com.google.android.youtube.tv",
        "com.google.android.youtube.tvmusic",
        "com.amazon.amazonvideo.livingroom",
        "pl.solidexplorer2",
        "com.android.vending",
    )

    // --- Pacote das Configurações do sistema (abre pelo menu da bolinha do perfil, no canto superior direito).
    const val PACOTE_CONFIG = "com.android.tv.settings"

    // --- App aberto automaticamente quando a TV REINICIA (não quando acorda do standby). "" desliga.
    const val ABRIR_AO_LIGAR = ""   // desligado: o OneStream tem opcao propria de abrir ao reiniciar; ponha o pacote aqui so se quiser que o launcher faca isso

    // --- Shizuku: true = o serviço de acessibilidade aprova SOZINHO qualquer app que pedir acesso (cada um ganha shell).
    //     false = o diálogo do Shizuku aparece e você decide. Também dá para desligar em Configurações > Acessibilidade.
    //     Com true, o launcher também RELIGA o serviço sozinho se o Android o desligar (ver Privilegio.religarAcessibilidade).
    const val SHIZUKU_AUTORIZAR_TUDO = true

    // --- Depuração sem fio (adb pela rede, usado pelo computador para instalar e testar): true = o launcher a LIGA de novo
    //     sozinho quando a TV reinicia e sempre que abre, se o Android a tiver desligado. Custo: com ela ligada, a TV deixa uma porta de adb
    //     aberta na rede (só aceita computadores já pareados, com chave). false = fica como o Android deixar (ver Privilegio.religarDepuracaoSemFio).
    const val DEPURACAO_SEM_FIO_SEMPRE_LIGADA = true

    // --- Pilhas de cards: segurar ▲ por este tempo (ms) tira o foco da pilha e sobe para o topo (hora, clima, bolinha).
    //     Um toque rápido em ▲ continua trocando o app da frente da pilha.
    const val PILHA_SUBIR_MS = 750L

    // --- De quanto em quanto tempo cada informação do topo/rodapé é atualizada.
    const val CLIMA_A_CADA_MIN = 5L   // as fontes mudam a cada 15 min (modelo) e ~60 min (aeroporto, ou antes se o tempo virar); 5 min pega a mudança cedo
    const val LUA_A_CADA_MIN = 30L   // fase da lua: recalculada no proprio aparelho, sem internet
    const val REDE_A_CADA_SEG = 30L
    const val ECLIPSE_A_CADA_SEG = 60L   // so importa durante um eclipse solar: a lua anda no icone a cada tanto

    // --- Eclipses solares visíveis daqui (todos parciais). O ícone do clima só vira "sol coberto pela lua" dentro
    //     destes horários, com a cobertura do momento; fora deles nunca aparece. Fonte: timeanddate.com/eclipse/in/brazil/ribeirao-preto
    //     Horários em UTC (Brasília + 3 h): 1º contato, máximo, último contato; depois a magnitude (fração do diâmetro coberta).
    //     A tabela termina em 2034. Em outra cidade, troque as linhas (sem linhas, nunca aparece eclipse).
    val ECLIPSES_SOLARES = listOf(
        EclipseSolar("2027-02-06T14:34:50Z", "2027-02-06T16:25:17Z", "2027-02-06T18:02:41Z", 0.778),
        EclipseSolar("2028-01-26T13:08:24Z", "2028-01-26T14:32:31Z", "2028-01-26T15:58:05Z", 0.330),
        EclipseSolar("2032-05-09T11:51:25Z", "2032-05-09T12:18:47Z", "2032-05-09T12:47:31Z", 0.055),
        EclipseSolar("2034-09-12T15:47:18Z", "2034-09-12T17:26:12Z", "2034-09-12T18:52:30Z", 0.694),
    )

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
