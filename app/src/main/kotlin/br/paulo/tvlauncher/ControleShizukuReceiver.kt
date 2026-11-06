package br.paulo.tvlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

/**
 * Comandos de automação do Shizuku embutido (item 5, como os "start/stop intents" do fork thedjchi, para Tasker,
 * MacroDroid, Automate…):
 *
 *   br.paulo.tvlauncher.shizuku.INICIAR  — sobe o servidor (e limpa um PARAR anterior)
 *   br.paulo.tvlauncher.shizuku.PARAR    — derruba o servidor e impede o guardião de religar até um INICIAR
 *   br.paulo.tvlauncher.shizuku.ESTADO   — responde "rodando uid=2000 versao=13" ou "parado" (resultData do broadcast)
 *
 * Quem pode mandar: o adb/sistema, e apps que tenham a permissão do Shizuku do launcher (API_V23 — a mesma que um
 * app cliente pede; o Android mostra o pedido ao usuário). Ver android:permission no manifest.
 *
 * São DOIS receivers com o mesmo código, porque um receiver só aceita UMA permissão no manifest:
 *  - [ControleShizukuReceiver] exige a API_V23 (apps de automação autorizados);
 *  - [ControleShizukuAdbReceiver] exige DUMP (só o adb/sistema têm).
 *
 * Teste por adb:  am broadcast -n br.paulo.tvlauncher/.ControleShizukuAdbReceiver -a br.paulo.tvlauncher.shizuku.ESTADO
 */
open class ControleShizukuReceiver : BroadcastReceiver() {
    companion object {
        const val INICIAR = "br.paulo.tvlauncher.shizuku.INICIAR"
        const val PARAR = "br.paulo.tvlauncher.shizuku.PARAR"
        const val ESTADO = "br.paulo.tvlauncher.shizuku.ESTADO"
        const val ROTULOS = "br.paulo.tvlauncher.ROTULOS"   // nomes dos apps para o painel (só adb)
        const val OCULTAR = "br.paulo.tvlauncher.OCULTAR"   // ocultar/reexibir um app pelo DO (só adb)
        const val REINICIAR = "br.paulo.tvlauncher.REINICIAR"   // reiniciar o processo do launcher (só adb)
        const val PAINEL_PIN = "br.paulo.tvlauncher.PAINEL_PIN"   // definir o PIN do painel e ligá-lo (só adb, para testes)
        const val RECADO = "br.paulo.tvlauncher.RECADO"   // recado na tela da TV (só adb; o painel usa /api/recado)
        const val CHUVA = "br.paulo.tvlauncher.CHUVA"     // verificar o aviso de chuva agora, ou simular (só adb, testes)
        const val LOCAL_CLIMA = "br.paulo.tvlauncher.LOCAL_CLIMA"   // lugar do clima (só adb; o painel usa /api/clima)
        const val ERROS = "br.paulo.tvlauncher.ERROS"           // listar o registro local de erros (só adb)
        const val TESTE_ERRO = "br.paulo.tvlauncher.TESTE_ERRO" // gravar um erro de teste no registro (só adb)
        const val ROTINA = "br.paulo.tvlauncher.ROTINA"         // rodar uma rotina agora / listar (só adb)
        const val DEBLOAT = "br.paulo.tvlauncher.DEBLOAT"       // candidatos da limpeza e o estado deles (só adb)
        const val IA_TESTE = "br.paulo.tvlauncher.IA_TESTE"     // confirmação de ações do catálogo, sem MCP (só adb)
        const val IA_ESTADO = "br.paulo.tvlauncher.IA_ESTADO"   // situação do último pedido (só adb)
        const val AUTO_DESLIGAR = "br.paulo.tvlauncher.AUTO_DESLIGAR"   // desligar sozinha: ver/definir/testar (só adb)
        const val PLAYER_FONTE = "br.paulo.tvlauncher.PLAYER_FONTE"     // conta do player: cadastrar/apagar (só adb)
        const val PLAYER_ESTADO = "br.paulo.tvlauncher.PLAYER_ESTADO"   // contas e canais do player, sem senha (só adb)
        const val HOME_TESTE = "br.paulo.tvlauncher.HOME_TESTE"   // ligar/desligar partes da HOME para medir a GPU (só adb)
        const val UNIFI = "br.paulo.tvlauncher.UNIFI"     // verificar aparelho novo no roteador agora (só adb, testes)
        private val fila = Executors.newSingleThreadExecutor()

        fun estado(): String = if (Shizuku.pingBinder()) "rodando uid=${Shizuku.getUid()} versao=${Shizuku.getVersion()}" else "parado"

        /** Modo SEMPRE (fica ligado, inclusive no boot) e sobe o servidor. Usado pelo INICIAR e pelas Configurações. */
        fun ligar(ctx: Context) {
            Config.definirModoShizuku(ctx, Config.ModoShizuku.SEMPRE)
            GuardiaoShizuku.garantir(ctx, "INICIAR")
        }

        /** Volta ao padrão SOB_DEMANDA e encerra o servidor agora (ele sobe de novo quando um app cliente abrir). */
        fun sobDemanda(ctx: Context) {
            Config.definirModoShizuku(ctx, Config.ModoShizuku.SOB_DEMANDA)
            fila.execute { try { if (Shizuku.pingBinder() && !Config.shizukuAtivo(ctx)) Shizuku.exit() } catch (_: Throwable) {} }
        }

        /** Modo DESLIGADO: o guardião para de religar e o servidor é encerrado (Shizuku.exit(), pelo próprio servidor). */
        fun desligar(ctx: Context) {
            Config.definirModoShizuku(ctx, Config.ModoShizuku.DESLIGADO)
            fila.execute {
                try { if (Shizuku.pingBinder()) Shizuku.exit() } catch (_: Throwable) {}
                ServidorShizuku.anotar(ctx, "[controle] desligado: ${estado()}")
            }
        }
    }

    override fun onReceive(recebido: Context, intent: Intent) {
        val ctx = recebido.applicationContext
        ServidorShizuku.anotar(ctx, "[controle] ${intent.action}")
        when (intent.action) {
            INICIAR -> {
                ligar(ctx)
                resultData = "iniciando"
            }
            PARAR -> {
                desligar(ctx)
                resultData = "parando"
            }
            ESTADO -> resultData = estado()
            // Só pelo adb: define o PIN do painel no navegador e o liga, sem precisar do controle remoto (testes).
            // Ex.: am broadcast -a br.paulo.tvlauncher.PAINEL_PIN --es pin 123456 -n br.paulo.tvlauncher/.ControleShizukuAdbReceiver
            // O "is" barra o receiver dos apps do Shizuku: um intent EXPLÍCITO ignora o filtro de ação do manifest.
            PAINEL_PIN -> if (this is ControleShizukuAdbReceiver) {
                val pin = intent.getStringExtra("pin")
                if (pin == null || !PinSeguro.valido(pin)) {
                    resultData = "pin inválido (6 a 12 dígitos)"
                } else {
                    val pendente = goAsync()   // PBKDF2 demora: fora da thread principal, segurando o broadcast
                    Thread {
                        try {
                            PainelArmazem.definirPin(ctx, pin)
                            PainelArmazem.definirLigado(ctx, true)
                            PainelWeb.recomecar(ctx)
                            Thread.sleep(500)   // a porta abre numa thread à parte
                            pendente.resultData = "ok ligado=${PainelWeb.ligado()} endereco=${PainelWeb.endereco()}"
                        } finally { pendente.finish() }
                    }.start()
                }
            }
            // Só pelo adb: testa a tela de confirmação e a execução do catálogo (ComandosIa). A "resposta" é o JSON das
            // ações, que passa pela mesma conferência que o servidor MCP vai usar. Ex.:
            //   am broadcast -a br.paulo.tvlauncher.IA_TESTE --es frase "abre a previsão"
            //     --es resposta '{"acoes":[{"tipo":"abrir_tela","tela":"previsao"}],"resposta":"ok"}'
            //     -n br.paulo.tvlauncher/.ControleShizukuAdbReceiver
            IA_TESTE -> if (this is ControleShizukuAdbReceiver) {
                val r = intent.getStringExtra("resposta")
                resultData = if (r == null) "falta --es resposta" else PedidoIa.propor(ctx, intent.getStringExtra("frase") ?: "teste", "adb", r).let {
                    "id=${it.id} situacao=${it.situacao} ${it.mensagem}"
                }
            }
            IA_ESTADO -> if (this is ControleShizukuAdbReceiver) {
                resultData = "ultimo=${PedidoIa.ultimo()?.json() ?: "nenhum"}"
            }
            // Só chega pelo receiver do adb (o filtro de ROTULOS está só nele): o painel do PC pergunta o nome real
            // dos apps, que o adb não sabe dizer. Resposta: JSON {pacote: nome}.
            ROTULOS -> resultData = try {
                val pm = ctx.packageManager
                org.json.JSONObject().apply {
                    for (ai in pm.getInstalledApplications(0)) put(ai.packageName, pm.getApplicationLabel(ai).toString())
                }.toString()
            } catch (e: Exception) { "{}" }
            // Só pelo adb (filtro só no receiver DUMP): ocultar/reexibir um app pelo Device Owner, para testar e desfazer
            // sem rebuild. Ex.: am broadcast -a br.paulo.tvlauncher.OCULTAR --es pacote <pkg> --ez oculto false
            //   -n br.paulo.tvlauncher/.ControleShizukuAdbReceiver
            OCULTAR -> {
                val pacote = intent.getStringExtra("pacote")
                val oculto = intent.getBooleanExtra("oculto", true)
                resultData = if (pacote.isNullOrBlank()) "falta --es pacote"
                else "mudou=${PrivilegioDO.ocultar(ctx, pacote, oculto)} oculto_agora=${PrivilegioDO.estaOculto(ctx, pacote)}"
            }
            // Só pelo adb: reinicia o processo do launcher (04/11/2026). O shell não consegue matá-lo (force-stop, am kill
            // e am crash não derrubam: ele segura o serviço de acessibilidade e é Device Owner), e o código recém-compilado
            // (cmd package compile) só vale num processo novo. O sistema sobe a HOME e religa a acessibilidade sozinho.
            // Só pelo adb. Ex.: am broadcast -a br.paulo.tvlauncher.RECADO --es texto "Jantar pronto" --ei segundos 30
            //   -n br.paulo.tvlauncher/.ControleShizukuAdbReceiver
            RECADO -> if (this is ControleShizukuAdbReceiver) {
                val t = Recado.limpar(intent.getStringExtra("texto"))
                resultData = if (t == null) "texto vazio ou com mais de ${Recado.MAX_CARACTERES} caracteres"
                else AvisoTv.mostrar(ctx, "Recado", t, Recado.duracao(intent.getIntExtra("segundos", 60)),
                    intent.getBooleanExtra("som", true), guardarSeApagada = true).name.lowercase()
            }
            // Só pelo adb: o endereço do clima (LocalClima.kt) pelo CEP e o número. Sem --es cep, apaga o endereço.
            // Ex.: am broadcast -a br.paulo.tvlauncher.LOCAL_CLIMA --es cep 14015060 --es numero 100
            //   -n br.paulo.tvlauncher/.ControleShizukuAdbReceiver
            LOCAL_CLIMA -> if (this is ControleShizukuAdbReceiver) {
                val cep = intent.getStringExtra("cep")
                if (cep.isNullOrBlank()) { LocalClima.limpar(ctx); resultData = "endereço apagado" }
                else {
                    val pendente = goAsync()   // ViaCEP + Nominatim: rede, fora da thread principal
                    Thread {
                        try {
                            pendente.resultData = LocalClima.definirPorCep(ctx, cep, intent.getStringExtra("numero")).fold(
                                { "ok ${LocalClima.descrever(it)} ${it.lat},${it.lon}" }, { "erro: ${it.message}" })
                        } finally { pendente.finish() }
                    }.start()
                }
            }
            // Só pelo adb: --ez simular true mostra o cartão de teste (sem consultar a previsão nem mexer no silêncio);
            // sem ele, roda a verificação de verdade e responde o que decidiu (ex.: "sem chuva à vista").
            CHUVA -> if (this is ControleShizukuAdbReceiver) {
                if (intent.getBooleanExtra("simular", false)) resultData = AvisoChuva.simular(ctx).name.lowercase()
                else {
                    val pendente = goAsync()   // a verificação faz HTTP: fora da thread principal
                    Thread { try { pendente.resultData = AvisoChuva.verificar(ctx) } finally { pendente.finish() } }.start()
                }
            }
            // Só pelo adb: desligar sozinha. Sem extras responde o valor atual. --ei minutos N grava (0, 30, 60, 90, 120,
            // 180, 240); --ez aviso true mostra o aviso de 60 s agora (teste: deixe correr para a TV apagar, ou aperte
            // um botão do controle para cancelar).
            AUTO_DESLIGAR -> if (this is ControleShizukuAdbReceiver) {
                if (intent.hasExtra("minutos")) AutoDesligar.definir(ctx, intent.getIntExtra("minutos", -1))
                if (intent.getBooleanExtra("aviso", false)) { AutoDesligar.iniciar(ctx); AutoDesligar.testarAviso() }
                resultData = "minutos=${AutoDesligar.minutos(ctx)}"
            }
            // Só pelo adb (06/11/2026), até o painel ganhar a aba do player. Cadastrar:
            //   --es tipo m3u --es endereco <link> [--es nome X] [--es guia <link xmltv>]
            //   --es tipo xtream --es endereco <servidor> --es usuario U --es senha S [--es nome X]
            // Apagar: --es apagar <id>. A resposta nunca repete o link nem a senha.
            PLAYER_FONTE -> if (this is ControleShizukuAdbReceiver) {
                val apagar = intent.getStringExtra("apagar")
                resultData = if (apagar != null) { if (PlayerArmazem.apagar(ctx, apagar)) "apagada" else "não existe" }
                else PlayerArmazem.adicionar(ctx, intent.getStringExtra("nome").orEmpty(), intent.getStringExtra("tipo").orEmpty(),
                    intent.getStringExtra("endereco").orEmpty(), intent.getStringExtra("usuario").orEmpty(),
                    intent.getStringExtra("senha").orEmpty(), intent.getStringExtra("guia").orEmpty())
                    .fold({ "ok id=${it.id} ${it.tipo} ${it.servidorVisivel}" }, { "erro: ${it.message}" })
            }
            // Só pelo adb: as contas (sem link nem senha) e, com --ez carregar true, baixa e conta canais e programas.
            PLAYER_ESTADO -> if (this is ControleShizukuAdbReceiver) {
                val pendente = goAsync()   // pode baixar lista e guia: fora da thread principal
                Thread {
                    try {
                        val fontes = PlayerArmazem.fontes(ctx)
                        val b = StringBuilder(fontes.joinToString("\n") { "${it.id} ${it.tipo} ${it.nome} ${it.servidorVisivel}" }.ifEmpty { "nenhuma conta" })
                        if (intent.getBooleanExtra("carregar", false)) {
                            val t0 = System.currentTimeMillis()
                            val canais = PlayerCarga.canais(ctx, forcar = true)
                            val t1 = System.currentTimeMillis()
                            val guia = PlayerCarga.guia(ctx, canais, forcar = true)
                            val agora = System.currentTimeMillis()
                            val comGuia = canais.count { it.guiaId.isNotEmpty() && guia.agora(it.guiaId, agora) != null }
                            b.append("\ncanais=${canais.size} em ${t1 - t0} ms; com programa agora=$comGuia; guia em ${agora - t1} ms")
                        }
                        pendente.resultData = b.toString()
                    } finally { pendente.finish() }
                }.start()
            }
            // Só pelo adb, para medir engasgo (06/11/2026): esconde partes da HOME até o processo reiniciar. Ex.:
            //   am broadcast -a br.paulo.tvlauncher.HOME_TESTE --ez capa false --ez destaque true --ez faixa true --ez fundo true
            //     -n br.paulo.tvlauncher/.ControleShizukuAdbReceiver      (sem extras: só mostra o estado)
            HOME_TESTE -> if (this is ControleShizukuAdbReceiver) {
                TesteHome.capa = intent.getBooleanExtra("capa", TesteHome.capa)
                TesteHome.destaque = intent.getBooleanExtra("destaque", TesteHome.destaque)
                TesteHome.faixa = intent.getBooleanExtra("faixa", TesteHome.faixa)
                TesteHome.fundo = intent.getBooleanExtra("fundo", TesteHome.fundo)
                TesteHome.aplicar?.invoke()
                resultData = TesteHome.run { "capa=$capa destaque=$destaque faixa=$faixa fundo=$fundo" }
            }
            UNIFI -> if (this is ControleShizukuAdbReceiver) {
                val pendente = goAsync()   // HTTP: fora da thread principal
                val antiga = intent.getBooleanExtra("antiga", false)   // --ez antiga true: testa a API antiga (sinal da TV)
                Thread { try { pendente.resultData = if (antiga) UnifiCliente.testarApiAntiga(ctx) else UnifiCliente.verificarNovos(ctx) }
                    finally { pendente.finish() } }.start()
            }
            REINICIAR -> {
                resultData = "reiniciando pid=${android.os.Process.myPid()}"
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    android.os.Process.killProcess(android.os.Process.myPid())
                }, 500)
            }
            // Só pelo adb (05/11/2026): o registro local de erros (RegistroErros.kt). Resposta: um erro por linha.
            ERROS -> resultData = RegistroErros.listar(ctx).joinToString("\n") { "${it.arquivo.name} ${it.resumo}" }.ifEmpty { "nenhum" }
            // Só pelo adb: testar o registro. Sem extra, grava um erro de mentira (nada cai). Com --ez derrubar true,
            // lança a exceção numa thread de verdade: o processo do launcher CAI (como um crash real) e o sistema o
            // sobe de novo, igual ao REINICIAR. Serve para provar que o tratador grava antes do Android fechar o app.
            TESTE_ERRO -> {
                val erro = IllegalStateException("teste do registro de erros (adb)")
                if (intent.getBooleanExtra("derrubar", false)) {
                    resultData = "derrubando pid=${android.os.Process.myPid()}"
                    Thread({ throw erro }, "teste-erro").start()
                } else {
                    RegistroErros.gravar(ctx, "adb", erro)
                    resultData = "gravado"
                }
            }
            // Só pelo adb (05/11/2026): sem extra, lista as rotinas e a próxima; com --ei id N, roda a rotina N agora.
            ROTINA -> {
                val id = intent.getIntExtra("id", 0)
                if (id > 0) { Rotinas.rodar(ctx, id); resultData = "rodou $id" }
                else resultData = Rotinas.paraJson(Rotinas.listar(ctx)) + " proxima=" + (Rotinas.proxima(ctx)?.let { "${it.first.id}@${it.second}" } ?: "nenhuma") +
                    " semTecla=${Rotinas.minutosSemTecla()}min"
            }
            // Só pelo adb (05/11/2026): para testar candidatos da limpeza (Debloat.kt) antes de liberá-los na TV.
            DEBLOAT -> resultData = Debloat.relatorio(ctx)
        }
    }
}

/** O mesmo controle, para o adb (permission=DUMP no manifest). */
class ControleShizukuAdbReceiver : ControleShizukuReceiver()
