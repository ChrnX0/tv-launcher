package br.paulo.tvlauncher

/**
 * Frases traduzidas para os `object` que não recebem um Context (IconesClima, Lua...). Usa o Context do app, guardado
 * em [LauncherApp.instancia] logo que o processo do launcher abre. O idioma vem do aparelho: português é o padrão
 * (res/values), inglês fica em res/values-en.
 */
fun txt(id: Int, vararg args: Any): String = LauncherApp.instancia.getString(id, *args)
