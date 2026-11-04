' Inicia o painel dos Chromecasts sem janela. Uma copia fica em shell:startup (pasta Inicializar do Windows).
CreateObject("Wscript.Shell").Run "powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""C:\Users\rockx\tv-launcher\chromecast-painel\start.ps1""", 0, False
