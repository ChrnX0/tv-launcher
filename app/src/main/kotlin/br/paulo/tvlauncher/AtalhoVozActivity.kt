package br.paulo.tvlauncher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast

/**
 * Atalhos de voz (05/11/2026, bloco 5 do brainstorm). O Gemini da TV não conhece os comandos do launcher, mas sabe
 * "abrir <nome de app>". Então cada comando vira um "app" com nome falável no manifesto (um activity-alias que
 * aponta para ESTA tela), e "Ok Google, abrir Desligar em 30 minutos" cai aqui. A mesma ideia do "Boletim do Tempo".
 *
 * Esta tela não desenha nada: olha por qual nome foi aberta (o componente do alias), faz o comando e fecha.
 * Os aliases não aparecem na linha de apps do launcher (ela pula o nosso próprio pacote), só no menu de apps da TV.
 */
class AtalhoVozActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val alias = intent.component?.className?.substringAfterLast('.') ?: ""
        Log.i("TvLauncher.Voz", "atalho de voz: $alias")
        when (alias) {
            "VozDesligar30" -> desligarEm(30)
            "VozDesligar60" -> desligarEm(60)
            "VozNaoDesligar" -> avisar(if (Desligamento.cancelar(this)) "Pronto: a TV não vai mais desligar sozinha." else "Não havia desligamento marcado.")
            "VozDiagnostico" -> startActivity(Intent(this, DiagnosticoActivity::class.java))
            // No modo visita a agenda (do perfil ativo) não abre, igual ao OK na data da HOME.
            "VozAgenda" -> if (ModoVisita.ativo(this)) avisar("A agenda está fechada no modo visita.")
                else startActivity(Intent(this, CalendarioActivity::class.java))
        }
        finish()
    }

    private fun desligarEm(minutos: Int) {
        Desligamento.agendar(this, minutos)
        avisar("A TV desliga em ${Desligamento.duracaoTexto(minutos)}.")
    }

    private fun avisar(texto: String) = Toast.makeText(applicationContext, texto, Toast.LENGTH_LONG).show()
}
