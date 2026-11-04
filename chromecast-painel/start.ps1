# Sobe o painel dos Chromecasts (http://localhost:9000) - mesmo padrao do C:\Users\rockx\ws-scrcpy\start.ps1.
# Chamado pelo start.vbs (pasta Inicializar do Windows), sem janela. Log em cache\painel.log.
# 04/11/2026: criado para o "auto-start do painel" (pendencia do CLAUDE.md).
$env:Path = 'C:\Users\rockx\android-sdk\platform-tools;' + $env:Path   # o server.js chama "adb" pelo PATH
Set-Location 'C:\Users\rockx\tv-launcher\chromecast-painel'
# Nao sobe duas vezes: se a porta 9000 ja tem dono, sai.
if (Get-NetTCPConnection -LocalPort 9000 -State Listen -ErrorAction SilentlyContinue) { exit }
node server.js *>> 'C:\Users\rockx\tv-launcher\chromecast-painel\cache\painel.log'
