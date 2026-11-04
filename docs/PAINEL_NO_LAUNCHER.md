# Painel dentro do launcher — ponto de partida (04/11/2026)

Visão do Paulo: o launcher, na TV, serve uma página web na rede de casa. Num navegador (PC ou celular) ele digita
"chromecast", entra com um PIN definido nas Configurações do launcher e administra a TV (apps, arquivos,
armazenamento, backup, tela). O PC é só o navegador. O `chromecast-painel` (Node, no PC) foi um desvio de
entendimento e vai ser aposentado quando este ficar pronto.

## Decisões do Paulo (04/11/2026)
- Fazer "pra valer" (com testes e proteções completos), numa sessão dedicada.
- Começar em HTTP; ligar HTTPS depois. Risco aceito: alguém capturando o tráfego da Wi-Fi vê o PIN.
- Roteador: UniFi UX7. Cadastrar "chromecast" → 192.168.1.153 no DNS local do UX7 (Settings › Routing › DNS /
  "DNS Records", conferir na versão do UniFi Network). Só vale para aparelhos que usam o DNS do roteador.

## Medições da fase 0 (na TV, 04/11/2026; código de teste numa branch local, sem push)
| # | Medição | Resultado |
|---|---|---|
| 1 | Instalar/desinstalar APK em silêncio pelo Device Owner (PackageInstaller) | ✅ `INSTALL_SUCCEEDED` / `DELETE_SUCCEEDED`, sem diálogo |
| 2 | `appops set MANAGE_EXTERNAL_STORAGE allow` | ❌ sem efeito: o launcher precisa DECLARAR a permissão no manifesto (depois o shell concede) |
| 3 | Captura de tela | ✅ `AccessibilityService.takeScreenshot` (`canTakeScreenshot=true`): 1920×1080 em 37 ms. `screencap` pelo shell: ~0,9 s |
| 4 | `DevicePolicyManager.canAdminGrantSensorsPermissions()` | ✅ `true` |
| 5 | `oom_score_adj` do launcher | 0 com a HOME na frente; 100 ("vis", preso pela acessibilidade) com outro app na frente: praticamente não é morto |
| 6 | TCP numa porta >1024 aberta pelo launcher, com a TV em standby | ✅ o PC recebeu a resposta de `http://192.168.1.153:8080` com a TV `Asleep` |
| 7 | Hostname no DHCP | ❌ IP FIXO (leaseDuration 0, DNS 1.1.1.2): a TV não se registra no roteador |

Outros limites medidos:
- Porta 80 impossível: `ip_unprivileged_port_start = 1024` (nem o shell abre). Usar 8080, ou seja: "chromecast:8080" ou um
  favorito/QR. Uma regra de redirecionamento 80→8080 só existiria no roteador.
- "chromecast", "chromecast.local" e o nome da TV não resolvem hoje.

## Já existe no launcher e vai ser reaproveitado
- `PrivilegioDO` (Device Owner): ocultar, conceder permissão, VPN, HOME etc.
- Shizuku embutido / shell uid 2000 (`GuardiaoShizuku`, `ServidorShizuku`): `pm`, `appops`, `screencap`, `dumpsys`.
- `BackupConfig.kt` (PR #9): exportar/importar as preferências `launcher`, `despertador` e `dns` em JSON v2.
- Comandos adb (DUMP) em `ControleShizukuReceiver`: ROTULOS, OCULTAR, REINICIAR, BACKUP, RESTAURAR.

## Esboço da 1ª versão (a validar na sessão dedicada)
- Servidor HTTP pequeno no processo do launcher (sem processo novo), porta 8080, liga/desliga + PIN nas Configurações.
- Sessão por cookie (HttpOnly, SameSite=Strict), bloqueio progressivo depois de PIN errado, aviso na TV a cada login,
  só rede local (recusar IPs fora de 192.168.0.0/16, 10/8 e 172.16/12), nada de CORS.
- Abas: Aplicativos (listar, instalar APK, congelar, desinstalar, permissões; launcher protegido), Arquivos,
  Armazenamento (por app; limpar cache), Backup (baixar/enviar o JSON), Tela (captura; ao vivo fica para depois).
- Antes de tudo: declarar `MANAGE_EXTERNAL_STORAGE` e o `canTakeScreenshot`; medir o custo de RAM do servidor parado.
