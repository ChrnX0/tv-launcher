package br.paulo.tvlauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Sol parcialmente coberto pela lua (um eclipse parcial): o ícone do clima "poucas nuvens / parcialmente nublado" de dia.
 * Não existe emoji para isso, então desenhamos em código: raios, disco do sol com um degradê e, por cima,
 * um disco escuro (a lua) deslocado para baixo e para a direita.
 */
class IconeEclipse(contexto: Context) : View(contexto) {
    private val tinta = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(tela: Canvas) {
        val lado = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val raio = lado * 0.27f   // raio do disco do sol; o resto do espaço é dos raios

        // Raios: 8 tracinhos ao redor do disco.
        tinta.style = Paint.Style.STROKE
        tinta.strokeCap = Paint.Cap.ROUND
        tinta.strokeWidth = lado * 0.06f
        tinta.color = 0xFFFFB02E.toInt()
        for (i in 0 until 8) {
            val a = i * PI / 4
            tela.drawLine(
                cx + (cos(a) * raio * 1.45f).toFloat(), cy + (sin(a) * raio * 1.45f).toFloat(),
                cx + (cos(a) * raio * 1.9f).toFloat(), cy + (sin(a) * raio * 1.9f).toFloat(), tinta
            )
        }

        // Disco do sol, com degradê do centro (amarelo claro) para a borda (laranja).
        tinta.style = Paint.Style.FILL
        tinta.shader = RadialGradient(cx, cy, raio, 0xFFFFE680.toInt(), 0xFFFFA726.toInt(), Shader.TileMode.CLAMP)
        tela.drawCircle(cx, cy, raio, tinta)
        tinta.shader = null

        // Lua: disco escuro (cor do fundo da tela) cobrindo uma parte do sol e dos raios, com uma borda fina clara.
        val lx = cx + raio * 0.8f
        val ly = cy + raio * 0.5f
        tinta.color = 0xFF0E1311.toInt()
        tela.drawCircle(lx, ly, raio * 1.05f, tinta)
        tinta.style = Paint.Style.STROKE
        tinta.strokeWidth = lado * 0.015f
        tinta.color = 0x55FFFFFF
        tela.drawCircle(lx, ly, raio * 1.05f, tinta)
    }
}
