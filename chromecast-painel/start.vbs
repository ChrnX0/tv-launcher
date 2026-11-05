' Inicia o painel dos Chromecasts sem janela. Uma copia fica em shell:startup (pasta Inicializar do Windows).
' A copia em shell:startup nao sabe onde o repo esta: usa %USERPROFILE%\tv-launcher (troque se o seu clone estiver em outra pasta).
Set sh = CreateObject("Wscript.Shell")
sh.Run "powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File """ & sh.ExpandEnvironmentStrings("%USERPROFILE%") & "\tv-launcher\chromecast-painel\start.ps1""", 0, False
