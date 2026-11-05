package br.paulo.tvlauncher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Tela invisível das rotinas (Rotinas.kt): acende a tela do Chromecast (o que liga a TV pelo HDMI-CEC, como o
 * despertador) e, se a rotina pede, abre um app. Depois some. Sem nada para abrir, fica a HOME na frente.
 */
class RotinaAcordarActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val pacote = intent.getStringExtra("pacote")
        val abrir = pacote?.let { packageManager.getLeanbackLaunchIntentForPackage(it) ?: packageManager.getLaunchIntentForPackage(it) }
            ?: Intent(this, MainActivity::class.java)
        // Um instante para a TV terminar de acordar antes de trocar de tela (sem isso, o app às vezes abre "atrás").
        window.decorView.postDelayed({
            try { startActivity(abrir.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { Log.w("TvLauncher.Rotinas", "abrir $pacote: $e") }
            finish()
        }, 1500)
    }
}
