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

    // --- Botão de Configurações (fica no canto de baixo, separado da grade de apps).
    const val PACOTE_CONFIG = "com.android.tv.settings"

    // --- App aberto automaticamente quando a TV REINICIA (não quando acorda do standby). "" desliga.
    const val ABRIR_AO_LIGAR = ""   // desligado: o OneStream tem opcao propria de abrir ao reiniciar; ponha o pacote aqui so se quiser que o launcher faca isso

    // --- Shizuku: true = o serviço de acessibilidade aprova SOZINHO qualquer app que pedir acesso (cada um ganha shell).
    //     false = o diálogo do Shizuku aparece e você decide. Também dá para desligar em Configurações > Acessibilidade.
    //     Com true, o launcher também RELIGA o serviço sozinho se o Android o desligar (ver Privilegio.religarAcessibilidade).
    const val SHIZUKU_AUTORIZAR_TUDO = true

    // --- De quanto em quanto tempo cada informação do topo/rodapé é atualizada.
    const val CLIMA_A_CADA_MIN = 15L
    const val LUA_A_CADA_MIN = 30L   // fase da lua: recalculada no proprio aparelho, sem internet
    const val REDE_A_CADA_SEG = 30L
}
