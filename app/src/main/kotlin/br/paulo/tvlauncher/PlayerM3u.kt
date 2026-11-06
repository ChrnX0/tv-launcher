package br.paulo.tvlauncher

/**
 * Player universal, passo 1 (06/11/2026): o que o player sabe de um canal, e a leitura de listas M3U.
 *
 * Só lógica pura (sem Android), para testar no computador (app/src/test/.../PlayerM3uTest.kt).
 * O launcher NÃO traz lista nem fonte nenhuma: só lê a que o usuário cadastrar (repo público, ver o estudo).
 */
data class Canal(
    val id: String,            // chave estável do canal dentro da conta (tvg-id, stream_id do Xtream ou a própria URL)
    val nome: String,
    val numero: Int,           // número para digitar no controle/celular (tvg-chno, ou a ordem na lista)
    val grupo: String = "",    // "group-title" da lista (ex.: Esportes)
    val logo: String = "",     // URL do logotipo (tvg-logo), pode vir vazia
    val url: String,           // endereço do vídeo (http/https; rtmp/udp ficam de fora, ver M3u.urlAceita)
    val guiaId: String = "",   // tvg-id: liga o canal ao guia XMLTV
    val agenteUsuario: String = "",  // algumas listas exigem um "User-Agent" próprio (#EXTVLCOPT:http-user-agent)
)

object M3u {
    /** Teto de canais por lista: uma lista gigante (100 mil linhas) não pode derrubar a TV de 2 GB. */
    const val MAX_CANAIS = 20_000

    /**
     * Lê o texto de uma lista M3U/M3U8 "estendida" e devolve os canais na ordem do arquivo.
     *
     * Formato (uma entrada = duas linhas, mais linhas de opção opcionais no meio):
     *   #EXTINF:-1 tvg-id="c1" tvg-logo="http://..." group-title="Notícias",Canal 1
     *   http://servidor/canal1.m3u8
     * Linhas desconhecidas são ignoradas, em vez de dar erro: as listas de verdade vêm cheias de variações.
     */
    fun ler(texto: String): List<Canal> {
        val canais = ArrayList<Canal>()
        val numerosUsados = HashSet<Int>()
        var info: Map<String, String>? = null   // atributos do último #EXTINF, esperando a linha da URL
        var nome = ""
        var grupoExtgrp = ""                    // #EXTGRP: outra forma de dizer o grupo
        var agente = ""
        // removePrefix("﻿"): arquivo salvo no Windows às vezes começa com a "marca de ordem de bytes" (BOM)
        for (bruta in texto.removePrefix("﻿").lineSequence()) {
            val linha = bruta.trim()            // trim também tira o \r das linhas com fim CRLF
            when {
                linha.isEmpty() -> {}
                linha.startsWith("#EXTINF", ignoreCase = true) -> {
                    val (attrs, n) = lerExtinf(linha)
                    info = attrs; nome = n; grupoExtgrp = ""; agente = ""
                }
                linha.startsWith("#EXTGRP:", ignoreCase = true) -> grupoExtgrp = linha.substring(8).trim()
                linha.startsWith("#EXTVLCOPT:http-user-agent=", ignoreCase = true) ->
                    agente = linha.substringAfter('=').trim()
                linha.startsWith("#") -> {}     // #EXTM3U, #KODIPROP etc.: não usamos
                else -> {
                    val a = info ?: continue    // URL solta, sem #EXTINF antes: pula
                    info = null
                    if (!urlAceita(linha)) continue
                    if (canais.size >= MAX_CANAIS) break
                    // Número: tvg-chno quando vem válido e ainda livre; senão, o próximo número livre.
                    val pedido = a["tvg-chno"]?.toIntOrNull()?.takeIf { it in 1..99_999 && it !in numerosUsados }
                    val numero = pedido ?: proximoLivre(numerosUsados, canais.size + 1)
                    numerosUsados += numero
                    val guiaId = a["tvg-id"].orEmpty()
                    canais += Canal(
                        id = guiaId.ifEmpty { linha },
                        nome = nome.ifEmpty { a["tvg-name"].orEmpty() }.ifEmpty { "Canal $numero" },
                        numero = numero,
                        grupo = a["group-title"].orEmpty().ifEmpty { grupoExtgrp },
                        logo = a["tvg-logo"].orEmpty().takeIf { urlAceita(it) }.orEmpty(),
                        url = linha,
                        guiaId = guiaId,
                        agenteUsuario = agente,
                    )
                }
            }
        }
        // Dois canais com o mesmo tvg-id (comum: versão HD e SD) não podem ter a mesma chave.
        return desduplicarIds(canais)
    }

    /** Só http e https: o Media3 toca esses, e um "file://" numa lista baixada da internet poderia ler arquivo da TV. */
    fun urlAceita(url: String): Boolean {
        val u = url.trim().lowercase()
        return (u.startsWith("http://") || u.startsWith("https://")) && u.length > 10 && ' ' !in u
    }

    /**
     * Separa os atributos (chave="valor") e o nome que vem depois da última vírgula FORA das aspas.
     * Ex.: `#EXTINF:-1 tvg-name="A, B" group-title="X",Nome do canal` -> ({tvg-name=A, B, group-title=X}, "Nome do canal")
     */
    internal fun lerExtinf(linha: String): Pair<Map<String, String>, String> {
        val corpo = linha.substringAfter(':', "")
        val attrs = HashMap<String, String>()
        var i = 0
        var dentroAspas = false
        var virgula = -1
        // 1ª volta: acha a vírgula que separa atributos do nome (a primeira fora de aspas)
        while (i < corpo.length) {
            val c = corpo[i]
            if (c == '"') dentroAspas = !dentroAspas
            else if (c == ',' && !dentroAspas) { virgula = i; break }
            i++
        }
        val parteAttrs = if (virgula >= 0) corpo.substring(0, virgula) else corpo
        val nome = if (virgula >= 0) corpo.substring(virgula + 1).trim() else ""
        // 2ª volta: chave="valor" (aspas duplas). Regex simples basta aqui porque o valor não tem aspas dentro.
        Regex("""([A-Za-z0-9_-]+)\s*=\s*"([^"]*)"""").findAll(parteAttrs).forEach {
            attrs[it.groupValues[1].lowercase()] = it.groupValues[2].trim()
        }
        return attrs to nome
    }

    private fun proximoLivre(usados: Set<Int>, inicio: Int): Int {
        var n = inicio
        while (n in usados) n++
        return n
    }

    private fun desduplicarIds(canais: List<Canal>): List<Canal> {
        val vistos = HashMap<String, Int>()
        return canais.map { c ->
            val vezes = vistos.merge(c.id, 1, Int::plus)!!   // quantas vezes este id já apareceu (contando este)
            if (vezes == 1) c else c.copy(id = "${c.id}#$vezes")
        }
    }
}
