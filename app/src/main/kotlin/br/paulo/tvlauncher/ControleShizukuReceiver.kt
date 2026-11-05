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
        const val IA_TESTE = "br.paulo.tvlauncher.IA_TESTE"     // confirmação da IA com resposta falsa, sem nuvem (só adb)
        const val IA_ESTADO = "br.paulo.tvlauncher.IA_ESTADO"   // situação da IA e do último pedido (só adb)
        const val RECADO = "br.paulo.tvlauncher.RECADO"   // recado na tela da TV (só adb; o painel usa /api/recado)
        const val CHUVA = "br.paulo.tvlauncher.CHUVA"     // verificar o aviso de chuva agora, ou simular (só adb, testes)
        const val UNIFI = "br.paulo.tvlauncher.UNIFI"     // verificar aparelho novo no roteador agora (só adb, testes)
        const val DEBLOAT = "br.paulo.tvlauncher.DEBLOAT"       // candidatos da limpeza e o estado deles (só adb)
        const val ROTINA = "br.paulo.tvlauncher.ROTINA"         // rodar uma rotina agora / listar (só adb)
        const val ERROS = "br.paulo.tvlauncher.ERROS"           // listar o registro local de erros (só adb)
        const val TESTE_ERRO = "br.paulo.tvlauncher.TESTE_ERRO" // gravar um erro de teste no registro (só adb)
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
            // Só pelo adb: testa a tela de confirmação e a execução SEM nuvem e sem chave. A "resposta" faz o papel do
            // JSON da IA e passa pela mesma conferência (ComandosIa.conferir). Ex.:
            //   am broadcast -a br.paulo.tvlauncher.IA_TESTE --es frase "abre a previsão"
            //     --es resposta '{"acoes":[{"tipo":"abrir_tela","tela":"previsao"}],"resposta":"ok"}'
            //     -n br.paulo.tvlauncher/.ControleShizukuAdbReceiver
            IA_TESTE -> if (this is ControleShizukuAdbReceiver) {
                val r = intent.getStringExtra("resposta")
                resultData = if (r == null) "falta --es resposta" else PedidoIa.pedir(ctx, intent.getStringExtra("frase") ?: "teste", "adb", r).let {
                    "id=${it.id} situacao=${it.situacao} ${it.mensagem}"
                }
            }
            IA_ESTADO -> if (this is ControleShizukuAdbReceiver) {
                resultData = "ligada=${IaArmazem.ligada(ctx)} provedor=${IaArmazem.provedor(ctx).id} chave=${IaArmazem.finalDaChave(ctx) ?: "nenhuma"} modelo=${IaArmazem.modelo(ctx)} " +
                    "ultimo=${PedidoIa.ultimo()?.json() ?: "nenhum"}"
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
            // Só pelo adb: --ez simular true mostra o cartão de teste (sem consultar a previsão nem mexer no silêncio);
            // sem ele, roda a verificação de verdade e responde o que decidiu (ex.: "sem chuva à vista").
            CHUVA -> if (this is ControleShizukuAdbReceiver) {
                if (intent.getBooleanExtra("simular", false)) resultData = AvisoChuva.simular(ctx).name.lowercase()
                else {
                    val pendente = goAsync()   // a verificação faz HTTP: fora da thread principal
                    Thread { try { pendente.resultData = AvisoChuva.verificar(ctx) } finally { pendente.finish() } }.start()
                }
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
            // Só pelo adb (05/11/2026): para testar candidatos da limpeza (Debloat.kt) antes de liberá-los na TV.
            DEBLOAT -> resultData = Debloat.relatorio(ctx)
            // Só pelo adb (05/11/2026): sem extra, lista as rotinas e a próxima; com --ei id N, roda a rotina N agora.
            ROTINA -> {
                val id = intent.getIntExtra("id", 0)
                if (id > 0) { Rotinas.rodar(ctx, id); resultData = "rodou $id" }
                else resultData = Rotinas.paraJson(Rotinas.listar(ctx)) + " proxima=" + (Rotinas.proxima(ctx)?.let { "${it.first.id}@${it.second}" } ?: "nenhuma") +
                    " semTecla=${Rotinas.minutosSemTecla()}min"
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
        }
    }
}

/** O mesmo controle, para o adb (permission=DUMP no manifest). */
class ControleShizukuAdbReceiver : ControleShizukuReceiver()
