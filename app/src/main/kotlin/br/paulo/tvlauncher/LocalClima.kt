package br.paulo.tvlauncher

import android.content.Context

/**
 * O endereço da casa para o clima (05/11/2026): o Paulo digita o CEP e o número (Configurações do launcher ou painel) e
 * o launcher acha as coordenadas sozinho (Endereco.kt). Não há coordenada digitada à mão nem fixa no código.
 *
 * Ordem da localização (Localizacao.kt): Wi-Fi do Android > ESTE endereço > IP da internet > o último ponto que deu certo.
 * Guardado nas preferências "launcher" (entra no backup do painel) e só na TV: nunca vai para o git nem para o APK.
 * Pelo adb (teste):
 *   am broadcast -a br.paulo.tvlauncher.LOCAL_CLIMA --es cep 14015060 --es numero 100 -n br.paulo.tvlauncher/.ControleShizukuAdbReceiver
 *   (sem --es cep = apaga o endereço)
 */
object LocalClima {
    private const val PREFS = "launcher"
    const val CHAVE_NOME = "clima_local_nome"
    const val CHAVE_LAT = "clima_local_lat"
    const val CHAVE_LON = "clima_local_lon"
    const val CHAVE_CEP = "clima_local_cep"
    const val CHAVE_NUMERO = "clima_local_numero"
    const val MAX_NOME = 40

    class Lugar(val nome: String, val lat: Double, val lon: Double, val cep: String = "", val numero: String = "")

    /** O endereço cadastrado (já com as coordenadas), ou null se ninguém cadastrou. */
    fun ler(ctx: Context): Lugar? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Guardado como texto: Float perderia casas decimais (e o SharedPreferences não tem Double).
        val lat = p.getString(CHAVE_LAT, null)?.toDoubleOrNull() ?: return null
        val lon = p.getString(CHAVE_LON, null)?.toDoubleOrNull() ?: return null
        if (!coordenadaValida(lat, lon)) return null
        return Lugar(p.getString(CHAVE_NOME, null).orEmpty(), lat, lon, p.getString(CHAVE_CEP, null).orEmpty(), p.getString(CHAVE_NUMERO, null).orEmpty())
    }

    fun gravar(ctx: Context, lugar: Lugar) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(CHAVE_NOME, lugar.nome).putString(CHAVE_LAT, lugar.lat.toString()).putString(CHAVE_LON, lugar.lon.toString())
            .putString(CHAVE_CEP, lugar.cep).putString(CHAVE_NUMERO, lugar.numero)
            .apply()
    }

    fun limpar(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(CHAVE_NOME).remove(CHAVE_LAT).remove(CHAVE_LON).remove(CHAVE_CEP).remove(CHAVE_NUMERO).apply()
    }

    /** CEP + número -> coordenadas (rede: fora da thread da tela) e grava. Devolve o lugar gravado ou o erro. */
    fun definirPorCep(ctx: Context, cep: String?, numero: String?): Result<Lugar> =
        Endereco.buscar(cep, numero) { Endereco.httpGet(it) }.map { a ->
            Lugar(a.nome, a.lat, a.lon, Endereco.limparCep(cep).orEmpty(), Endereco.limparNumero(numero).orEmpty())
                .also { gravar(ctx, it) }
        }

    /** "Centro · CEP 14015-060, nº 100" (para as telas). */
    fun descrever(l: Lugar): String {
        val cep = if (l.cep.length == 8) "CEP ${l.cep.take(5)}-${l.cep.drop(5)}" else ""
        return listOf(l.nome, listOf(cep, l.numero.takeIf { it.isNotEmpty() }?.let { "nº $it" }.orEmpty()).filter { it.isNotEmpty() }.joinToString(", "))
            .filter { it.isNotEmpty() }.joinToString(" · ")
    }

    fun coordenadaValida(lat: Double, lon: Double) =
        !lat.isNaN() && !lon.isNaN() && lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)

    /** Nome limpo (uma linha, sem caracteres de controle, até [MAX_NOME]); vazio vira "Local escolhido". */
    fun limparNome(nome: String?): String {
        val n = nome.orEmpty().filter { !it.isISOControl() }.trim().take(MAX_NOME)
        return n.ifEmpty { "Local escolhido" }
    }
}
