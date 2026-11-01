package br.paulo.tvlauncher

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Fase da lua, calculada no próprio aparelho (sem internet).
 *
 * A lua repete o ciclo a cada ~29,53 dias (mês sinódico). Contamos o tempo desde uma lua nova conhecida
 * (6/1/2000, 18:14 UTC) e tiramos o resto da divisão por 29,53: isso é a "idade" da lua.
 * O erro é de algumas horas — suficiente para o nome da fase e a porcentagem iluminada.
 */
object Lua {
    private const val CICLO_DIAS = 29.530588853
    private const val LUA_NOVA_REFERENCIA_MS = 947_182_440_000L   // 2000-01-06 18:14 UTC

    private val NOMES = arrayOf(
        "Lua nova", "Lua crescente", "Quarto crescente", "Crescente gibosa",
        "Lua cheia", "Minguante gibosa", "Quarto minguante", "Lua minguante"
    )

    // Emojis na ordem das fases, como vistos do hemisfério norte (iluminada à direita enquanto cresce).
    private val EMOJIS = arrayOf("🌑", "🌒", "🌓", "🌔", "🌕", "🌖", "🌗", "🌘")

    /** Idade da lua em dias: 0 = nova, ~14,77 = cheia, volta a 0 em ~29,53. */
    fun idade(agoraMs: Long): Double {
        val dias = (agoraMs - LUA_NOVA_REFERENCIA_MS) / 86_400_000.0
        val resto = dias % CICLO_DIAS
        return if (resto < 0) resto + CICLO_DIAS else resto
    }

    /** Qual das 8 fases (0..7). Cada fase ocupa 1/8 do ciclo, centrada no seu ponto (por isso o +0,5). */
    private fun indiceDaFase(idade: Double): Int = (((idade / CICLO_DIAS) * 8) + 0.5).toInt() % 8

    /**
     * Só o emoji da fase. [latitude] negativa = hemisfério sul: lá a lua aparece espelhada
     * (cresce com o lado iluminado para a esquerda), então espelhamos o emoji.
     * Também é o ícone do clima à noite, no lugar do sol.
     */
    fun emoji(agoraMs: Long, latitude: Double): String {
        val i = indiceDaFase(idade(agoraMs))
        return if (latitude < 0) EMOJIS[(8 - i) % 8] else EMOJIS[i]
    }

    private val ARQUIVOS = arrayOf("moon-new", "moon-waxing-crescent", "moon-first-quarter", "moon-waxing-gibbous",
        "moon-full", "moon-waning-gibbous", "moon-last-quarter", "moon-waning-crescent")

    /** Chave do ícone-imagem da fase (ver IconesClima.kt); no hemisfério sul a fase é espelhada, como no emoji. */
    fun chaveIcone(agoraMs: Long, latitude: Double): String {
        val i = indiceDaFase(idade(agoraMs))
        return IconesClima.PREFIXO + (if (latitude < 0) ARQUIVOS[(8 - i) % 8] else ARQUIVOS[i])
    }

    /** Ex.: "🌔  Minguante gibosa  ·  75%  ·  nova em 10 d". */
    fun resumo(agoraMs: Long, latitude: Double): String {
        val idade = idade(agoraMs)
        val fracao = idade / CICLO_DIAS
        // Fração iluminada do disco: 0% na nova, 100% na cheia (curva de cosseno).
        val iluminada = ((1 - cos(2 * PI * fracao)) / 2 * 100).roundToInt()

        val metade = CICLO_DIAS / 2
        val diasParaCheia = if (idade < metade) metade - idade else CICLO_DIAS - idade + metade
        val diasParaNova = CICLO_DIAS - idade
        val proxima = if (diasParaCheia <= diasParaNova) {
            "cheia em ${ceil(diasParaCheia).toInt()} d"
        } else {
            "nova em ${ceil(diasParaNova).toInt()} d"
        }
        return "${emoji(agoraMs, latitude)}  ${NOMES[indiceDaFase(idade)]}  ·  $iluminada%  ·  $proxima"
    }

    /** Versão curta, para caber no meio do topo: "🌖  Minguante gibosa  ·  70%" (sem o "nova em 10 d"). */
    fun resumoCurto(agoraMs: Long, latitude: Double): String =
        resumo(agoraMs, latitude).substringBeforeLast("  ·  ")
}
