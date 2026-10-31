package br.paulo.tvlauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Sol coberto pela lua num eclipse solar. Não existe emoji para isso, então desenhamos em código.
 * A posição da lua vem de Eclipse.estado(): [definir] recebe o centro dela em raios do sol (a partir do centro do sol)
 * e o raio dela, também em raios do sol. A lua não é pintada: ela só "recorta" o sol (e os raios), como no céu.
 * Só aparece em eclipse real; o resto do tempo o ícone do clima é um emoji.
 */
class IconeEclipse(contexto: Context) : View(contexto) {
    private val tinta = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sol = Path()   // reaproveitados a cada desenho, para não criar objetos novos
    private val lua = Path()
    private var luaX = 3f      // longe do sol até chegar o primeiro valor real
    private var luaY = 0f
    private var raioLua = 1f

    fun definir(x: Float, y: Float, raio: Float) {
        luaX = x
        luaY = y
        raioLua = raio
        invalidate()
    }

    override fun onDraw(tela: Canvas) {
        val lado = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val raio = lado * 0.27f   // raio do disco do sol; o resto do espaço é dos raios
        val lx = cx + luaX * raio
        val ly = cy + luaY * raio
        val rl = raioLua * raio

        // Raios: 8 tracinhos ao redor do disco; os que ficam atrás da lua não aparecem.
        tinta.style = Paint.Style.STROKE
        tinta.strokeCap = Paint.Cap.ROUND
        tinta.strokeWidth = lado * 0.06f
        tinta.color = 0xFFFFB02E.toInt()
        for (i in 0 until 8) {
            val a = i * PI / 4
            val x0 = cx + (cos(a) * raio * 1.45f).toFloat()
            val y0 = cy + (sin(a) * raio * 1.45f).toFloat()
            val x1 = cx + (cos(a) * raio * 1.9f).toFloat()
            val y1 = cy + (sin(a) * raio * 1.9f).toFloat()
            if (hypot((x0 + x1) / 2 - lx, (y0 + y1) / 2 - ly) < rl) continue
            tela.drawLine(x0, y0, x1, y1, tinta)
        }

        // Disco do sol menos o disco da lua (sobra a parte do sol que ainda aparece), com degradê do centro (amarelo claro) para a borda (laranja).
        sol.reset()
        sol.addCircle(cx, cy, raio, Path.Direction.CW)
        lua.reset()
        lua.addCircle(lx, ly, rl, Path.Direction.CW)
        sol.op(lua, Path.Op.DIFFERENCE)
        tinta.style = Paint.Style.FILL
        tinta.shader = RadialGradient(cx, cy, raio, 0xFFFFE680.toInt(), 0xFFFFA726.toInt(), Shader.TileMode.CLAMP)
        tela.drawPath(sol, tinta)
        tinta.shader = null

        // Contorno bem fraco da lua, para se ler como "lua" também na parte que não cobre o sol.
        tinta.style = Paint.Style.STROKE
        tinta.strokeWidth = lado * 0.015f
        tinta.color = 0x33FFFFFF
        tela.drawCircle(lx, ly, rl, tinta)
    }
}
