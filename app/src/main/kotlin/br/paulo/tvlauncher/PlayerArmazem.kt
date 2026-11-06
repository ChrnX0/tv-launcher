package br.paulo.tvlauncher

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Player universal, passo 2 (06/11/2026): onde o player guarda as coisas dele na TV.
 *
 * - As contas (links de lista, usuário e senha do Xtream) ficam CIFRADAS (AES-GCM) com uma chave do Android Keystore,
 *   que nunca sai do aparelho, no arquivo files/player/contas.json. Ele fica fora das preferências "launcher", que o
 *   backup do painel exporta (BackupConfig): senha de IPTV não pode ir num JSON baixado. É arquivo, e não
 *   SharedPreferences, porque quem grava é o processo do launcher e quem lê é o do player (":player"), e cada processo
 *   guarda a sua própria cópia das preferências em memória (o player veria a conta velha).
 * - O último canal fica em files/player/ultimo.json (a HOME também lê). Antes do 06/11 era a preferência "player".
 * - Cache de canais e do guia: arquivos em files/player/, sem senha (ver PlayerDados.canaisParaJson).
 */
object PlayerArmazem {
    private const val TAG = "TvLauncher.Player"
    private const val APELIDO = "player_fontes"

    private fun arquivoContas(ctx: Context) = File(pasta(ctx), "contas.json")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("player", Context.MODE_PRIVATE)

    fun pasta(ctx: Context): File = File(ctx.filesDir, "player").apply { mkdirs() }

    // ---------------------------------------------------------------- contas

    /** Há conta cadastrada? (só olha se o arquivo existe: barato o bastante para a HOME chamar a cada desenho) */
    fun temContas(ctx: Context): Boolean = File(File(ctx.filesDir, "player"), "contas.json").exists()

    fun fontes(ctx: Context): List<FonteTv> {
        val f = arquivoContas(ctx)
        if (!f.exists()) return emptyList()
        val o = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return emptyList()
        val dados = o.optString("dados").ifEmpty { return emptyList() }
        val iv = o.optString("iv").ifEmpty { return emptyList() }
        val claro = runCatching { decifrar(dados, iv) }.onFailure { Log.w(TAG, "contas ilegíveis: ${it.javaClass.simpleName}") }.getOrNull()
        return PlayerDados.fontesDeJson(claro)
    }

    private fun gravar(ctx: Context, fontes: List<FonteTv>): Boolean = runCatching {
        val (dados, iv) = cifrar(PlayerDados.fontesParaJson(fontes))
        // Grava num temporário e troca de nome: se a TV desligar no meio, fica o arquivo antigo inteiro, não metade.
        val tmp = File(pasta(ctx), "contas.json.tmp")
        tmp.writeText(JSONObject().put("dados", dados).put("iv", iv).toString())
        tmp.renameTo(arquivoContas(ctx))
    }.onFailure { Log.w(TAG, "não gravou as contas: ${it.javaClass.simpleName}") }.getOrDefault(false)

    /** Cria uma conta nova (id gerado). Devolve a conta, ou o motivo de não ter criado. */
    fun adicionar(ctx: Context, nome: String, tipo: String, endereco: String, usuario: String = "", senha: String = "", guia: String = ""): Result<FonteTv> {
        val atuais = fontes(ctx)
        if (atuais.size >= 8) return Result.failure(IllegalStateException("já há 8 contas; apague uma antes"))
        val nova = PlayerDados.criar(UUID.randomUUID().toString().take(8), nome, tipo, endereco, usuario, senha, guia).getOrElse { return Result.failure(it) }
        return if (gravar(ctx, atuais + nova)) Result.success(nova) else Result.failure(IllegalStateException("não consegui gravar"))
    }

    fun apagar(ctx: Context, id: String): Boolean {
        val atuais = fontes(ctx)
        if (atuais.none { it.id == id }) return false
        PlayerCarga.apagarCache(ctx, id)
        return gravar(ctx, atuais.filter { it.id != id })
    }

    // ---------------------------------------------------------------- favoritos, recentes e categoria escolhida
    // Arquivos (e não prefs) pelo mesmo motivo do ultimo.json: quem grava é o player, mas a HOME pode querer ler
    // (ex.: uma linha "Favoritos" na HOME). Sem senha nem endereço: só os ids dos canais.

    fun favoritos(ctx: Context): Set<String> = PlayerLista.idsDeJson(lerArquivo(ctx, "favoritos.json"), PlayerLista.MAX_FAVORITOS).toSet()
    fun definirFavoritos(ctx: Context, ids: Set<String>) = gravarArquivo(ctx, "favoritos.json", PlayerLista.idsParaJson(ids))

    fun recentes(ctx: Context): List<String> = PlayerLista.idsDeJson(lerArquivo(ctx, "recentes.json"), PlayerLista.MAX_RECENTES)
    fun definirRecentes(ctx: Context, ids: List<String>) = gravarArquivo(ctx, "recentes.json", PlayerLista.idsParaJson(ids))

    /** Grupos escondidos pelo usuário e grupos adultos que ele liberou com o PIN (ver PlayerFiltro.ocultasEfetivas). */
    fun gruposEscondidos(ctx: Context): Set<String> = PlayerLista.idsDeJson(lerArquivo(ctx, "ocultas.json"), 500).toSet()
    fun definirGruposEscondidos(ctx: Context, g: Set<String>) = gravarArquivo(ctx, "ocultas.json", PlayerLista.idsParaJson(g))
    fun gruposLiberados(ctx: Context): Set<String> = PlayerLista.idsDeJson(lerArquivo(ctx, "liberadas.json"), 500).toSet()
    fun definirGruposLiberados(ctx: Context, g: Set<String>) = gravarArquivo(ctx, "liberadas.json", PlayerLista.idsParaJson(g))

    /** Onde parou em cada filme/episódio (chave = Filme.chave). */
    fun posicoes(ctx: Context): Map<String, Posicao> = Vod.posicoesDeJson(lerArquivo(ctx, "posicoes.json"))
    fun definirPosicoes(ctx: Context, p: Map<String, Posicao>) = gravarArquivo(ctx, "posicoes.json", Vod.posicoesParaJson(p))

    /** A última categoria escolhida na lista (só o player usa: preferência basta). */
    fun categoria(ctx: Context): String? = prefs(ctx).getString("categoria", null)
    fun definirCategoria(ctx: Context, chave: String) = prefs(ctx).edit().putString("categoria", chave).apply()

    private fun lerArquivo(ctx: Context, nome: String): String? = runCatching { File(pasta(ctx), nome).readText() }.getOrNull()

    /** Escrita atômica: grava ao lado e troca o nome (TV desligada no meio deixa o arquivo antigo inteiro). */
    private fun gravarArquivo(ctx: Context, nome: String, texto: String) {
        runCatching {
            val tmp = File(pasta(ctx), "$nome.tmp")
            tmp.writeText(texto)
            tmp.renameTo(File(pasta(ctx), nome))
        }.onFailure { Log.w(TAG, "não gravou $nome: ${it.javaClass.simpleName}") }
    }

    // ---------------------------------------------------------------- uso

    /**
     * O último canal assistido. Arquivo (files/player/ultimo.json), e não preferência: quem grava é o player (":player") e
     * quem também lê é a HOME, para o "Continuar" do disco da TV; preferência ficaria velha no processo da HOME.
     */
    data class Ultimo(val id: String, val numero: Int, val nome: String)

    fun ultimo(ctx: Context): Ultimo? = runCatching {
        val o = JSONObject(File(File(ctx.filesDir, "player"), "ultimo.json").readText())
        Ultimo(o.getString("id"), o.optInt("numero"), o.optString("nome").take(60))
    }.getOrNull()

    fun ultimoCanal(ctx: Context): String? = ultimo(ctx)?.id ?: prefs(ctx).getString("ultimo", null)

    fun definirUltimoCanal(ctx: Context, c: Canal) {
        runCatching {
            val tmp = File(pasta(ctx), "ultimo.json.tmp")
            tmp.writeText(JSONObject().put("id", c.id).put("numero", c.numero).put("nome", c.nome).toString())
            tmp.renameTo(File(pasta(ctx), "ultimo.json"))
        }
    }

    // ---------------------------------------------------------------- cifra (mesma receita do IaArmazem)

    private fun chave(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(APELIDO, null) as? SecretKey)?.let { return it }
        val gerador = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gerador.init(KeyGenParameterSpec.Builder(APELIDO, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gerador.generateKey()
    }

    private fun cifrar(texto: String): Pair<String, String> {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, chave()) }
        return Base64.encodeToString(c.doFinal(texto.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP) to
            Base64.encodeToString(c.iv, Base64.NO_WRAP)
    }

    private fun decifrar(dados: String, iv: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, chave(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return String(c.doFinal(Base64.decode(dados, Base64.NO_WRAP)), Charsets.UTF_8)
    }
}
