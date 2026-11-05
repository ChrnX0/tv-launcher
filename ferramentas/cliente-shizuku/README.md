# Cliente de teste do Shizuku embutido

App mínimo que se comporta como qualquer app cliente do Shizuku (declara `moe.shizuku.manager.permission.API_V23`,
tem o `ShizukuProvider`, pede a permissão e roda um comando). Existe para testar o servidor embutido no launcher
sem depender de app de terceiros. **Não é instalado na TV no uso normal.**

O demo oficial do Shizuku-API não compila com o AGP atual (falta `namespace` no `demo-hidden-api-stub`, e o
`proguardFiles [...]` do `build.gradle` dele não é aceito); por isso este, que é bem menor.

## Uso (no PC, pasta do repo)

```powershell
.\gradlew.bat :cliente-shizuku:assembleDebug
adb -s <ip-da-tv>:<porta> install -r -t ferramentas\cliente-shizuku\build\outputs\apk\debug\cliente-shizuku-debug.apk
adb -s <ip-da-tv>:<porta> shell am start -n br.paulo.clienteshizuku/.TesteActivity
adb -s <ip-da-tv>:<porta> logcat -s TesteShizuku
```

Sem permissão, ele pede e o launcher mostra a tela "Permitir / Negar". Com permissão, ele roda `id` pelo servidor
e o log mostra `id pelo Shizuku: uid=2000(shell)...`. Revogar: Configurações do launcher › Shizuku do launcher.

Para tirar da TV: `adb shell pm uninstall br.paulo.clienteshizuku`.
