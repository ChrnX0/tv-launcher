package br.paulo.tvlauncher

/**
 * A grade da tela (como num launcher de verdade): tudo é posicionado em colunas e alturas fixas, não "onde o conteúdo couber".
 * Tela do Chromecast: 960 x 540 dp (1920 x 1080 px, densidade 2).
 *
 *   |48|col 50|24|col 50|24| ... 12 colunas ... |col 50|48|     48 + 12x50 + 11x24 + 48 = 960
 *
 * - MARGEM: margem de segurança de TV nas laterais (as TVs cortam um pouco da borda da imagem).
 * - Um card de app ocupa 3 colunas (198 dp): 4 cards + 3 vãos = 864 dp, exatamente a largura útil.
 * - Alturas em múltiplos de 8 dp (a "grade de base"): TOPO, LINHA_APPS.
 * Assim a borda esquerda da hora é a borda do 1º card, e a direita da bolinha é a do último card.
 */
object Grade {
    const val MARGEM = 48          // laterais
    const val COLUNA = 50
    const val VAO = 24             // espaço entre colunas (e entre cards)
    const val TOPO = 36            // onde começa a linha da hora
    const val LINHA_APPS = 328     // onde começa a linha de cards (topo do banner): embaixo, deixando o fundo (céu, pintura, mostrador) aparecer

    /** Onde começa a coluna [col] (1 a 12), a partir da borda esquerda da tela. */
    fun x(col: Int): Int = MARGEM + (col - 1) * (COLUNA + VAO)

    /** Largura de [n] colunas, contando os vãos entre elas. */
    fun largura(n: Int): Int = n * COLUNA + (n - 1) * VAO

    const val DISCO = 80           // diâmetro do ícone redondo de um app na linha principal
    const val VAO_DISCO = 32       // espaço entre discos: 8 x 80 + 7 x 32 = 864 (a largura útil)

    val CARD_LARGURA = largura(3)                  // 198
    val CARD_ALTURA = CARD_LARGURA * 9 / 16        // 111: banner de TV é 16:9
}
