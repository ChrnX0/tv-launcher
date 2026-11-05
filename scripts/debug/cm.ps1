cd $env:USERPROFILE\tv-launcher
git add app/src/main/AndroidManifest.xml app/src/main/kotlin/br/paulo/tvlauncher/
git status --short app/
$msg = @"
Popup do perfil em 2 colunas, tela de configuracoes, protetor de tela novo e previsao com o fundo do launcher

- PopupPerfil: grade Wi-Fi/Bluetooth, Launcher/Sistema, Audio; cards arredondados com icones (IconeCard.kt)
- ConfigLauncherActivity: tema, DNS, velocidade, permissoes, diagnostico e tempo do descanso de tela (via Device Owner)
- ProtetorTela: corrige o digito cortado da hora (LayoutParams explicito), hora em degrade, 4 quadrados (sol, umidade, chuva, fase da lua)
- MainActivity: guarda umidade, chance de chuva, codigo e lat/lon no cache "clima" para o protetor
- PrevisaoActivity: usa o mesmo fundo do launcher (criarFundo, sem o arco do sol)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KD8PPjeYXZXnNk8z36vbbb
"@
git commit -q -m $msg
git log --oneline -2
git status --short app/
