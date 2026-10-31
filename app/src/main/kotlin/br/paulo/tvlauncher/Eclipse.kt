package br.paulo.tvlauncher

import java.time.Instant
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Um eclipse solar visto do local do Config.kt. Os 3 horários são em UTC (horário de Brasília + 3 h):
 * [inicio] = 1º contato (a lua encosta no sol), [maximo] e [fim] = último contato (a lua solta do sol).
 * [magnitude] = fração do DIÂMETRO do sol coberta no máximo (0,778 = 77,8%).
 */
class EclipseSolar(inicio: String, maximo: String, fim: String, val magnitude: Double) {
    val inicioMs: Long = Instant.parse(inicio).toEpochMilli()
    val maximoMs: Long = Instant.parse(maximo).toEpochMilli()
    val fimMs: Long = Instant.parse(fim).toEpochMilli()
}

/**
 * Calcula, para um instante, onde a lua está em relação ao sol durante um eclipse da tabela do Config.kt.
 * Fora dos horários da tabela devolve null: sem eclipse real, o ícone do clima nunca muda.
 *
 * Modelo (geometria plana, unidade = raio do sol): o sol é um disco de raio 1 e a lua um disco de raio [RAZAO_LUA_SOL].
 * A lua passa em linha reta, a velocidade constante, com o centro a [dMin] do centro do sol no máximo.
 * Da magnitude sai dMin; dos horários de início/máximo/fim sai a velocidade (um lado de cada vez, então os
 * 3 horários da tabela batem exatamente). A parte coberta é a área da interseção dos dois discos.
 */
object Eclipse {
    /** [luaX]/[luaY]: centro da lua em raios do sol, a partir do centro do sol (x para a direita, y para baixo). [coberto]: fração da ÁREA do sol coberta (0 a 1). */
    class Estado(val luaX: Float, val luaY: Float, val raioLua: Float, val coberto: Double)

    // A tabela só traz a magnitude, não o tamanho aparente da lua. Com 1,0 o percentual errou, nos 4 eclipses
    // da tabela, no máximo 3,1 pontos para razões de 0,90 a 1,03 (conta feita em Python, fora do app).
    private const val RAZAO_LUA_SOL = 1.0

    fun estado(agoraMs: Long): Estado? {
        val e = Config.ECLIPSES_SOLARES.firstOrNull { agoraMs in it.inicioMs..it.fimMs } ?: return null
        val rl = RAZAO_LUA_SOL
        val dMin = 1 + rl - 2 * e.magnitude                       // distância dos centros no máximo
        val sMax = sqrt((1 + rl) * (1 + rl) - dMin * dMin)        // quanto a lua anda, de cada lado do máximo, entre os contatos
        val meia = if (agoraMs < e.maximoMs) e.maximoMs - e.inicioMs else e.fimMs - e.maximoMs
        val s = sMax * (agoraMs - e.maximoMs).toDouble() / meia   // -sMax no 1º contato, 0 no máximo, +sMax no último
        return Estado(s.toFloat(), dMin.toFloat(), rl.toFloat(), areaCoberta(hypot(s, dMin), rl))
    }

    /** Área da interseção do disco do sol (raio 1) com o da lua (raio [rl]), centros a distância [d], dividida pela área do sol. */
    private fun areaCoberta(d: Double, rl: Double): Double {
        if (d >= 1 + rl) return 0.0
        if (d <= abs(1 - rl)) return minOf(1.0, rl * rl)
        val a = acos(((d * d + 1 - rl * rl) / (2 * d)).coerceIn(-1.0, 1.0))
        val b = rl * rl * acos(((d * d + rl * rl - 1) / (2 * d * rl)).coerceIn(-1.0, 1.0))
        val c = 0.5 * sqrt(maxOf(0.0, (-d + 1 + rl) * (d + 1 - rl) * (d - 1 + rl) * (d + 1 + rl)))
        return (a + b - c) / PI
    }
}
