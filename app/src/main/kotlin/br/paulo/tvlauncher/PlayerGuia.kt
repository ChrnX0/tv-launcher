package br.paulo.tvlauncher

import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Player universal, passo 1 (06/11/2026): o guia de programação (EPG) no formato XMLTV, o padrão das listas de IPTV.
 *
 * O arquivo costuma ser enorme (dezenas de MB, às vezes .gz). Por isso é lido em fluxo (XmlPullParser: um elemento
 * por vez, sem carregar o arquivo inteiro na memória) e só guarda o que interessa: os canais da lista do usuário,
 * numa janela de tempo curta. O parser vem de fora (na TV, android.util.Xml.newPullParser(); nos testes, o kxml2),
 * para esta classe rodar sem Android.
 */
data class Programa(
    val guiaId: String,      // o "channel" do XMLTV = o tvg-id do canal na lista
    val inicio: Long,        // milissegundos desde 1970 (UTC)
    val fim: Long,
    val titulo: String,
    val descricao: String = "",
    val categoria: String = "",
)

object Xmltv {
    /** Teto de programas guardados: ~200 canais x 24 h cabem com folga; um guia de 100 mil itens não. */
    const val MAX_PROGRAMAS = 60_000

    /** Abre o fluxo já descompactando se for .gz (os 2 primeiros bytes de um gzip são 1F 8B), seja qual for o nome. */
    fun abrir(entrada: InputStream): InputStream {
        val b = BufferedInputStream(entrada)
        b.mark(2)
        val p1 = b.read(); val p2 = b.read()
        b.reset()
        return if (p1 == 0x1f && p2 == 0x8b) GZIPInputStream(b) else b
    }

    /**
     * Lê os programas.
     * @param canais tvg-ids que interessam (vazio = todos)
     * @param desde / ate janela de tempo em ms: só entra programa que termina depois de `desde` e começa antes de `ate`
     */
    fun ler(
        parser: XmlPullParser,
        entrada: InputStream,
        canais: Set<String> = emptySet(),
        desde: Long = Long.MIN_VALUE,
        ate: Long = Long.MAX_VALUE,
    ): List<Programa> {
        // DOCDECL desligado de propósito: um guia baixado da internet não pode puxar entidades externas (ataque XXE).
        runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
        parser.setInput(entrada, null)   // null = respeita o encoding que o próprio arquivo declara
        val saida = ArrayList<Programa>()
        var evento = parser.eventType
        while (evento != XmlPullParser.END_DOCUMENT && saida.size < MAX_PROGRAMAS) {
            if (evento == XmlPullParser.START_TAG && parser.name == "programme") {
                val canal = parser.getAttributeValue(null, "channel").orEmpty()
                val inicio = data(parser.getAttributeValue(null, "start"))
                val fim = data(parser.getAttributeValue(null, "stop"))
                val quer = (canais.isEmpty() || canal in canais) && inicio != null && fim != null && fim > inicio &&
                    fim > desde && inicio < ate
                if (quer) lerPrograma(parser, canal, inicio!!, fim!!)?.let { saida += it } else pularElemento(parser)
            }
            evento = parser.next()
        }
        saida.sortWith(compareBy({ it.guiaId }, { it.inicio }))
        return saida
    }

    /** Lê title/desc/category de dentro de um <programme>; fica parado no </programme>. */
    private fun lerPrograma(p: XmlPullParser, canal: String, inicio: Long, fim: Long): Programa? {
        var titulo = ""; var desc = ""; var cat = ""
        val profundidade = p.depth
        var e = p.next()
        while (!(e == XmlPullParser.END_TAG && p.depth == profundidade) && e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG) {
                when (p.name) {
                    // O 1º título/descrição vence (o XMLTV pode repetir um por idioma).
                    "title" -> p.nextText().trim().let { if (titulo.isEmpty()) titulo = it }
                    "desc" -> p.nextText().trim().let { if (desc.isEmpty()) desc = it.take(600) }
                    "category" -> p.nextText().trim().let { if (cat.isEmpty()) cat = it }
                    else -> pularElemento(p)
                }
            }
            e = p.next()
        }
        return if (titulo.isEmpty()) null else Programa(canal, inicio, fim, titulo, desc, cat)
    }

    private fun pularElemento(p: XmlPullParser) {
        var nivel = 1
        while (nivel > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> nivel++
                XmlPullParser.END_TAG -> nivel--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /**
     * Data do XMLTV: "20261105215500 -0300" (fuso opcional; sem fuso = UTC, como manda o formato).
     * Feito à mão em vez de SimpleDateFormat: é chamado milhares de vezes e o SimpleDateFormat é lento e não é seguro
     * entre threads.
     */
    fun data(texto: String?): Long? {
        val t = texto?.trim() ?: return null
        if (t.length < 12 || !t.substring(0, 12).all { it.isDigit() }) return null
        val ano = t.substring(0, 4).toInt(); val mes = t.substring(4, 6).toInt(); val dia = t.substring(6, 8).toInt()
        val hora = t.substring(8, 10).toInt(); val min = t.substring(10, 12).toInt()
        val seg = if (t.length >= 14 && t[12].isDigit() && t[13].isDigit()) t.substring(12, 14).toInt() else 0
        if (mes !in 1..12 || dia !in 1..31 || hora > 23 || min > 59 || seg > 59) return null
        var offsetMin = 0
        val fuso = t.substringAfter(' ', "").trim()
        if (fuso.length == 5 && (fuso[0] == '+' || fuso[0] == '-') && fuso.substring(1).all { it.isDigit() }) {
            offsetMin = fuso.substring(1, 3).toInt() * 60 + fuso.substring(3, 5).toInt()
            if (fuso[0] == '-') offsetMin = -offsetMin
        }
        val dias = diasDesde1970(ano, mes, dia)
        return ((dias * 24 + hora) * 60 + min - offsetMin) * 60_000L + seg * 1000L
    }

    /** Dias entre 01/01/1970 e a data (calendário gregoriano). Fórmula de Howard Hinnant ("days_from_civil"). */
    private fun diasDesde1970(ano: Int, mes: Int, dia: Int): Long {
        val a = if (mes <= 2) ano - 1 else ano
        val era = (if (a >= 0) a else a - 399) / 400
        val anoDaEra = a - era * 400
        val diaDoAno = (153 * (mes + (if (mes > 2) -3 else 9)) + 2) / 5 + dia - 1
        val diaDaEra = anoDaEra * 365 + anoDaEra / 4 - anoDaEra / 100 + diaDoAno
        return era * 146097L + diaDaEra - 719468L
    }
}

/** Consultas rápidas no guia já lido: "o que passa agora" e "o que vem depois" num canal. */
class Guia(programas: List<Programa>) {
    private val porCanal: Map<String, List<Programa>> = programas.groupBy { it.guiaId }.mapValues { (_, l) -> l.sortedBy { it.inicio } }

    fun agora(guiaId: String, instante: Long): Programa? =
        porCanal[guiaId]?.firstOrNull { instante >= it.inicio && instante < it.fim }

    /** Os próximos `quantos` programas a partir de `instante` (o atual incluído, se houver). */
    fun proximos(guiaId: String, instante: Long, quantos: Int = 4): List<Programa> =
        porCanal[guiaId].orEmpty().filter { it.fim > instante }.take(quantos)

    /** Fração já assistida do programa atual (0..1), para a barrinha de progresso dos cards. */
    fun progresso(p: Programa, instante: Long): Float =
        ((instante - p.inicio).toFloat() / (p.fim - p.inicio)).coerceIn(0f, 1f)
}
