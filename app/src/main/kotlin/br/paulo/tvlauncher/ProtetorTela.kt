package br.paulo.tvlauncher

import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.view.View
import android.widget.ImageView
import android.widget.TextClock
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * O protetor de tela do launcher (um DreamService do Android). Existe para APOSENTAR o protetor de fábrica
 * (com.google.android.apps.tv.dreamx), que media ~59 MB residentes: apontamos o protetor do sistema para este,
 * que é leve — relógio com degradê, data, o clima e 4 indicadores (umidade, sol, chance de chuva, fase da lua).
 * Sol e lua são calculados no aparelho (Astronomia.kt, Lua.kt), sem rede; o resto vem do SharedPreferences
 * "clima" que o MainActivity já mantém atualizado (ver buscarClima() em MainActivity.kt).
 *
 * Anti-burn-in: a coluna inteira se desloca alguns dp a cada minuto (a TV da sala é LCD, mas um painel parado por horas
 * ainda sofre retenção de imagem; e de quebra gasta menos luz). A tela fica escurecida (setScreenBright(false)).
 * Qualquer tecla fecha (setInteractive(false): o toque/clique acorda e encerra o sonho).
 */
class ProtetorTela : DreamService() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var coluna: LinearLayout
    private var passo = 0

    private val deslocar = object : Runnable {
        override fun run() {
            // Caminho suave em "oito" lento: 8 posições ao longo de ~8 min, deslocamento de até ±dp(20).
            passo = (passo + 1) % 8
            val dx = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)[passo] * dp(20)
            val dy = intArrayOf(-1, -1, 0, 1, 1, 1, 0, -1)[passo] * dp(14)
            coluna.animate().translationX(dx.toFloat()).translationY(dy.toFloat()).setDuration(2000).start()
            ui.postDelayed(this, 60_000)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false        // qualquer interação encerra o sonho
        isFullscreen = true
        isScreenBright = false       // tela escurecida: descanso de verdade

        // "sans-serif-rounded" existe como variação do Roboto Flex desde o Android 12; onde não existir, o
        // sistema cai sozinho na fonte padrão (sem crash) e só a hora deixa de ficar arredondada.
        val fonteRelogio = Typeface.create("sans-serif-rounded", Typeface.NORMAL)
        val fonteMedia = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val fonteLeve = Typeface.create("sans-serif-light", Typeface.NORMAL)

        coluna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // ---- hora, com degradê (as mesmas cores que o app já usa para chuva/sol: azul frio -> branco -> amarelo quente)
        val relogio = TextClock(this).apply {
            format12Hour = "HH:mm"; format24Hour = "HH:mm"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 128f)
            setTextColor(0xFFE8EEEA.toInt())   // cor sólida até o degradê entrar, no 1º layout
            typeface = fonteRelogio
            includeFontPadding = false
            gravity = Gravity.CENTER
        }
        // O bug do dígito cortado era exatamente a falta deste LayoutParams explícito: sem ele, esta view (única
        // entre as da coluna) herdava MATCH_PARENT de largura dentro de uma coluna que só mede WRAP_CONTENT — uma
        // combinação instável de medida do Android que cortava a borda direita do texto (confirmado com
        // uiautomator: o texto "11:49" estava certo, só o desenho é que clipava).
        coluna.addView(relogio, LinearLayout.LayoutParams(-2, -2))
        relogio.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (relogio.width > 0) {
                relogio.paint.shader = LinearGradient(0f, 0f, relogio.width.toFloat(), 0f,
                    intArrayOf(0xFF9FD8FF.toInt(), 0xFFE8EEEA.toInt(), 0xFFFFC98A.toInt()), null, Shader.TileMode.CLAMP)
                relogio.invalidate()
            }
        }

        // ---- data
        coluna.addView(TextClock(this).apply {
            format12Hour = "EEEE, d 'de' MMMM"; format24Hour = "EEEE, d 'de' MMMM"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(0x99E8EEEA.toInt())
            typeface = fonteMedia
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })

        // ---- clima + 4 indicadores: num bloco próprio, refeito a cada 15 min (ver [atualizarDados]).
        blocoDados = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        coluna.addView(blocoDados, LinearLayout.LayoutParams(-2, -2))
        montarDados(fonteMedia, fonteLeve)

        // Mesmo grafite do tema Escuro (ver Fundos.kt: FundoEscuro), simplificado para o sonho ficar barato.
        // Com fotos enviadas pelo painel (FotosProtetor.kt), elas ficam por trás, com um véu escuro para o relógio ler.
        fotos = FotosProtetor.listar(this).shuffled()
        setContentView(FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF0E1317.toInt(), 0xFF0A0D10.toInt(), 0xFF07090B.toInt()))
            if (fotos.isNotEmpty()) {
                for (v in camadas) addView(v.apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }, FrameLayout.LayoutParams(-1, -1))
                addView(View(this@ProtetorTela).apply {
                    background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x66000000, 0x33000000, 0x99000000.toInt()))
                }, FrameLayout.LayoutParams(-1, -1))
            }
            addView(coluna, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        })
        if (fotos.isNotEmpty()) ui.post(trocarFoto)
        ui.postDelayed(deslocar, 60_000)
        ui.postDelayed(atualizarDados, ATUALIZAR_MS)
    }

    // ---------------------------------------------------------------- fotos (FotosProtetor.kt)
    private var fotos: List<java.io.File> = emptyList()
    private val camadas by lazy { arrayOf(ImageView(this), ImageView(this)) }
    private var indiceFoto = -1
    private var camadaAtual = 0
    private val carregador = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Decodifica a próxima foto fora da thread da tela e esmaece da atual para ela em 2 s. */
    private val trocarFoto = object : Runnable {
        override fun run() {
            if (fotos.isEmpty()) return
            indiceFoto = (indiceFoto + 1) % fotos.size
            val f = fotos[indiceFoto]
            val m = resources.displayMetrics
            carregador.execute {
                val bmp = FotosProtetor.carregar(f, m.widthPixels, m.heightPixels)
                ui.post {
                    if (carregador.isShutdown) return@post   // o protetor já fechou enquanto a foto carregava
                    if (bmp != null) {
                        val entra = camadas[1 - camadaAtual]
                        val sai = camadas[camadaAtual]
                        entra.setImageBitmap(bmp)
                        entra.animate().alpha(1f).setDuration(2000).start()
                        sai.animate().alpha(0f).setDuration(2000).withEndAction { sai.setImageDrawable(null) }.start()   // solta a memória
                        camadaAtual = 1 - camadaAtual
                    }
                    ui.postDelayed(this, FotosProtetor.TROCA_MS)
                }
            }
        }
    }

    // Antes, tudo abaixo era lido UMA vez ao entrar no sonho (revisão de 04/11/2026): num protetor de 6 h o clima e o
    // "fresco < 3 h" ficavam os da entrada, e depois da meia-noite sol e lua eram os do dia anterior. Agora o bloco é
    // refeito a cada 15 min: sol/lua recalculados, clima relido (e escondido quando passa de 3 h).
    private val ATUALIZAR_MS = 15 * 60_000L
    private lateinit var blocoDados: LinearLayout
    private val atualizarDados = object : Runnable {
        override fun run() {
            montarDados(Typeface.create("sans-serif-medium", Typeface.NORMAL), Typeface.create("sans-serif-light", Typeface.NORMAL))
            ui.postDelayed(this, ATUALIZAR_MS)
        }
    }

    private fun montarDados(fonteMedia: Typeface, fonteLeve: Typeface) {
        blocoDados.removeAllViews()
        android.util.Log.i("TvLauncher.Protetor", "dados do protetor (re)montados")
        val coluna = blocoDados   // o código abaixo era da coluna principal; agora escreve no bloco
        // ---- clima: ícone + temperatura (só com medida de até 3h; "melhor calar que mentir um número velho")
        val clima = getSharedPreferences("clima", MODE_PRIVATE)
        val frescoMs = System.currentTimeMillis() - clima.getLong("em", 0)
        val temClima = clima.contains("temp") && frescoMs < 3 * 60 * 60_000L
        if (temClima) {
            val temp = clima.getInt("temp", 0)
            val chovendo = clima.getBoolean("chovendo", false)
            val codigo = clima.getInt("codigo", 0)
            val eDia = clima.getBoolean("eDia", true)
            coluna.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(IconesClima.imagem(this@ProtetorTela, IconesClima.chave(codigo, eDia, chovendo)),
                    LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(8) })
                addView(TextView(this@ProtetorTela).apply {
                    text = "$temp°" + if (chovendo) "  ·  chovendo" else ""
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                    setTextColor(0x99E8EEEA.toInt())
                    typeface = fonteLeve
                })
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
        }

        // ---- 4 indicadores (ordem pedida: sol, umidade, chuva, lua). No lugar da "bateria" (o Chromecast não
        // tem): umidade, que combina tematicamente com os outros 3 (todos dados de clima/céu) — a alternativa
        // seria o sinal de Wi-Fi, mais "útil" no sentido literal de status do aparelho, mas isso já vive no
        // popup de perfil e quebraria a harmonia dos 4 cartões.
        //
        // Tamanho FIXO e igual nos 4 (dp(132) x dp(132)), não largura por peso dentro de um container
        // WRAP_CONTENT: essa combinação é o mesmo tipo de medida instável que causava o corte no relógio — aqui
        // ela não cortava nada, mas media cada quadrado pelo tamanho do seu próprio conteúdo (texto do rótulo,
        // nº de linhas do valor), daí "Sol" com duas linhas (nascer e pôr) saía mais alto que os outros 3.
        val lat = clima.getFloat("lat", Config.LATITUDE.toFloat()).toDouble()
        val lon = clima.getFloat("lon", Config.LONGITUDE.toFloat()).toDouble()
        val agoraMs = System.currentTimeMillis()
        val hora = SimpleDateFormat("HH:mm", Locale.getDefault())
        val solPar = Astronomia.nascerEPor(agoraMs, lat, lon)
        val solTexto = if (solPar != null) "↑ ${hora.format(Date(solPar.first))}\n↓ ${hora.format(Date(solPar.second))}" else "—"
        val umidadeTexto = if (temClima && clima.getInt("umidade", -1) >= 0) "${clima.getInt("umidade", -1)}%" else "—"
        val chuvaTexto = if (temClima && clima.getInt("chuva", -1) >= 0) "${clima.getInt("chuva", -1)}%" else "—"
        // resumoCurto = "🌔  Minguante gibosa  ·  75%": a 1ª parte (sem o emoji) é o nome da fase, que vira o rótulo.
        val luaPartes = Lua.resumoCurto(agoraMs, lat).split("  ·  ")
        val luaIluminada = luaPartes.getOrElse(1) { "—" }
        val luaFase = luaPartes[0].substringAfter("  ").trim().ifEmpty { "Lua" }

        fun tile(emoji: String, valor: String, rotulo: String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(30).toFloat()
                setColor(0x26FFFFFF)
            }
            addView(TextView(this@ProtetorTela).apply { text = emoji; setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f); gravity = Gravity.CENTER })
            addView(TextView(this@ProtetorTela).apply {
                text = valor; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setTextColor(0xE6E8EEEA.toInt())
                typeface = fonteMedia; gravity = Gravity.CENTER; maxLines = 2; setPadding(0, dp(6), 0, 0)
                setLineSpacing(0f, 0.95f)   // nascer/pôr em 2 linhas; o quadrado tem altura fixa, então não desalinha os outros
            })
            addView(TextView(this@ProtetorTela).apply {
                text = rotulo.uppercase(); setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f); setTextColor(0x80E8EEEA.toInt())
                letterSpacing = 0.06f; gravity = Gravity.CENTER; maxLines = 2; setPadding(dp(8), dp(3), dp(8), 0)   // nome da fase da lua pode ser longo
            })
        }

        val itens = listOf(
            "🌅" to (solTexto to "Sol"),
            "💧" to (umidadeTexto to "Umidade"),
            "🌧" to (chuvaTexto to "Chuva"),
            Lua.emoji(agoraMs, lat) to (luaIluminada to luaFase),
        )
        val ladoTile = dp(132)
        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        itens.forEachIndexed { i, (emoji, dados) ->
            val (valor, rotulo) = dados
            tiles.addView(tile(emoji, valor, rotulo), LinearLayout.LayoutParams(ladoTile, ladoTile).apply { if (i > 0) marginStart = dp(12) })
        }
        coluna.addView(tiles, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(28) })
    }

    override fun onDetachedFromWindow() {
        ui.removeCallbacks(deslocar)
        ui.removeCallbacks(atualizarDados)
        ui.removeCallbacks(trocarFoto)
        carregador.shutdownNow()
        camadas.forEach { it.setImageDrawable(null) }
        super.onDetachedFromWindow()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}
