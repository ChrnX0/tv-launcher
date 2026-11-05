package br.paulo.tvlauncher

import android.content.Context
import android.media.AudioManager
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * As ferramentas que o servidor MCP oferece ao Claude (opção E, 05/11/2026).
 *
 * Regra: LEITURA responde na hora e não tem dado pessoal (nada de arquivos, contas, senhas, conteúdo da agenda de
 * outra pessoa). AÇÃO só pelo catálogo fechado (ComandosIa) e só com OK no controle (PedidoIa + ConfirmarIaActivity).
 * Não existe ferramenta de shell, instalar, desinstalar, DNS, VPN nem rede.
 */
class McpFerramentas(ctx: Context) : McpProtocolo.Ferramentas {
    private val app = ctx.applicationContext

    override fun lista(): JSONArray = LISTA

    override fun chamar(nome: String, argumentos: JSONObject): String = when (nome) {
        "estado_tv" -> estadoTv().toString()
        "clima" -> clima().toString()
        "diagnostico_rede" -> diagnostico().toString()
        "historico_rede" -> HistoricoRede.json(app, argumentos.optInt("horas", 24).coerceIn(1, 168)).toString()
        "aparelhos_rede" -> aparelhos().toString()
        "aparelhos_tailscale" -> if (!TunelTailscale.redeLigada(app)) throw McpProtocolo.ErroFerramenta("a opção Rede Tailscale está desligada nas Configurações da TV")
            else TunelTailscale.pares ?: throw McpProtocolo.ErroFerramenta("a lista ainda não chegou (o túnel manda a cada minuto)")
        "apps" -> JSONObject(PedidoIa.appsDaHome(app) as Map<*, *>).toString()
        "agenda_hoje" -> Agenda.resumoHoje(app) ?: "nada na agenda hoje (ou a agenda não está ligada)"
        "pedir_acoes" -> pedir(argumentos).toString()
        "ver_pedido" -> (PedidoIa.estado(argumentos.optString("id")) ?: throw McpProtocolo.ErroFerramenta("pedido não encontrado (só os 5 últimos ficam)")).json().toString()
        else -> throw McpProtocolo.ErroFerramenta("ferramenta desconhecida")
    }

    private fun estadoTv(): JSONObject {
        val pm = app.getSystemService(PowerManager::class.java)
        val am = app.getSystemService(AudioManager::class.java)
        return JSONObject()
            .put("tela_ligada", pm?.isInteractive ?: JSONObject.NULL)
            .put("app_na_frente", ShizukuSobDemanda.pacoteNaFrente ?: JSONObject.NULL)
            .put("volume", am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: JSONObject.NULL)
            .put("volume_max", am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: JSONObject.NULL)
            .put("mudo", am?.isStreamMute(AudioManager.STREAM_MUSIC) ?: JSONObject.NULL)
    }

    /** O que a HOME mostrou por último (ela grava em "clima" a cada atualização). */
    private fun clima(): JSONObject {
        val c = app.getSharedPreferences("clima", Context.MODE_PRIVATE)
        if (!c.contains("em")) throw McpProtocolo.ErroFerramenta("a TV ainda não buscou o clima")
        return JSONObject()
            .put("temperatura_c", c.getInt("temp", 0))
            .put("chovendo", c.getBoolean("chovendo", false))
            .put("codigo_wmo", c.getInt("codigo", -1))
            .put("dia", c.getBoolean("eDia", true))
            .put("umidade_pct", c.getInt("umidade", -1).takeIf { it >= 0 } ?: JSONObject.NULL)
            .put("chance_chuva_pct", c.getInt("chuva", 0))
            .put("atualizado_ha_min", (System.currentTimeMillis() - c.getLong("em", 0)) / 60_000)
    }

    private fun diagnostico(): JSONObject {
        val w = Diagnostico.wifi(app); val i = Diagnostico.internet(app); val d = Diagnostico.dns(app); val v = Diagnostico.vpn(app)
        fun item(x: Diagnostico.Item) = JSONObject().put("titulo", x.titulo).put("detalhe", x.detalhe).put("nivel", x.nivel)
        return JSONObject().put("wifi", item(w)).put("internet", item(i)).put("dns", item(d)).put("vpn", item(v))
            .put("veredito", item(Diagnostico.veredito(w, i, d, v)))
    }

    private fun aparelhos(): JSONObject {
        val l = try { UnifiCliente.ler(app) } catch (e: Exception) {
            throw McpProtocolo.ErroFerramenta("roteador não respondeu (ou não está configurado no painel): ${e.javaClass.simpleName}")
        }
        val lista = JSONArray()
        for (a in l.aparelhos) lista.put(JSONObject().put("nome", a.nome).put("ip", a.ip ?: JSONObject.NULL)
            .put("tipo", a.tipo).put("desde", a.desde ?: JSONObject.NULL))
        return JSONObject().put("quantos", l.aparelhos.size).put("aparelhos", lista)
    }

    private fun pedir(a: JSONObject): JSONObject {
        val acoes = a.optJSONArray("acoes") ?: throw McpProtocolo.ErroFerramenta("falta \"acoes\"")
        val motivo = a.optString("motivo").ifBlank { "pedido do Claude" }
        val p = PedidoIa.propor(app, motivo, "mcp", JSONObject().put("acoes", acoes).put("resposta", "").toString())
        if (p.situacao == PedidoIa.Situacao.ERRO) throw McpProtocolo.ErroFerramenta(p.mensagem)
        return p.json().put("dica", "a TV mostra o pedido; chame ver_pedido com este id em alguns segundos")
    }

    companion object {
        private fun ferramenta(nome: String, descricao: String, props: JSONObject = JSONObject(), obrigatorios: List<String> = emptyList()) =
            JSONObject().put("name", nome).put("description", descricao)
                .put("inputSchema", JSONObject().put("type", "object").put("properties", props)
                    .apply { if (obrigatorios.isNotEmpty()) put("required", JSONArray(obrigatorios)) })
                .put("annotations", JSONObject().put("readOnlyHint", nome != "pedir_acoes"))

        val LISTA: JSONArray = JSONArray()
            .put(ferramenta("estado_tv", "Tela ligada ou dormindo, qual app está na frente e o volume."))
            .put(ferramenta("clima", "Temperatura, chuva e umidade que a tela inicial mostrou por último."))
            .put(ferramenta("diagnostico_rede", "Wi-Fi, internet, DNS e VPN da TV agora, com um veredito."))
            .put(ferramenta("historico_rede", "Histórico de rede da TV (quedas, latência, DNS, sinal) nas últimas N horas.",
                JSONObject().put("horas", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 168))))
            .put(ferramenta("aparelhos_rede", "Aparelhos conectados no roteador UniFi da casa (nome, IP, tipo)."))
            .put(ferramenta("aparelhos_tailscale", "Aparelhos da conta Tailscale do dono (nome, sistema, online). Só com a opção Rede Tailscale ligada na TV."))
            .put(ferramenta("apps", "Apps que a tela inicial mostra (pacote -> nome). Só estes podem ser abertos."))
            .put(ferramenta("agenda_hoje", "Resumo dos compromissos de hoje do perfil atual da TV."))
            .put(ferramenta("pedir_acoes",
                "Pede ações à TV: abrir app, abrir tela, criar despertador, volume, mídia, modo visita. NÃO executa sozinha: " +
                    "a TV mostra o pedido e só faz com OK no controle (60 s). Máximo ${ComandosIa.MAX_ACOES} ações.",
                JSONObject()
                    .put("acoes", ComandosIa.esquema(openApi = false).getJSONObject("properties").getJSONObject("acoes"))
                    .put("motivo", JSONObject().put("type", "string").put("description", "frase curta mostrada na TV")),
                listOf("acoes")))
            .put(ferramenta("ver_pedido", "Situação de um pedido feito com pedir_acoes.",
                JSONObject().put("id", JSONObject().put("type", "string")), listOf("id")))
    }
}
