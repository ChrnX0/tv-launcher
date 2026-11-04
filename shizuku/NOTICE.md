# Código de terceiros: Shizuku (fork thedjchi)

Esta pasta traz, sem o app gerenciador (`manager`) e sem o `shell`, os módulos do servidor do Shizuku,
para o launcher embutir o servidor e fazer o papel de "gerente" sozinho, sem o app Shizuku instalado.

| Origem | Commit | Licença |
|---|---|---|
| https://github.com/thedjchi/Shizuku (módulos `server`, `starter`, `common`) | `15ade0e40042dc72f0e502d50b4cb16e3a19095e` | Apache-2.0 (`LICENSE`) |
| https://github.com/thedjchi/Shizuku-API, submódulo `api` (`aidl`, `shared`, `server-shared`, `rish`, `provider`, `api`) | `37ebcd3e45edf1d68975c70dd199350b434161f7` | MIT (`api/LICENSE`, Copyright (c) 2021 RikkaW) |

Copiado em 04/11/2026. Projeto original: RikkaApps/Shizuku (Apache-2.0).

## O que foi alterado em relação à origem

Tudo o que foi mexido está marcado com o comentário `// tv-launcher:` no arquivo, para dar para comparar
com a origem depois. Resumo:

- `server/build.gradle` e `starter/build.gradle`: `MANAGER_APPLICATION_ID` fixo em `"br.paulo.tvlauncher"`
  (na origem ele era lido do módulo `:manager`, que não veio).
- Os módulos foram registrados no `settings.gradle.kts` do launcher com os MESMOS nomes da origem (`:server`,
  `:aidl`, `:api`...), porque os `build.gradle` deles se referenciam por esses nomes.
- O que o `build.gradle` raiz da origem fazia para todos os módulos (compileSdk, NDK, Java 21) agora está no
  `build.gradle.kts` raiz do launcher, com compileSdk 36 e só a ABI armeabi-v7a.
- `api/rish` continua com o build nativo (a `librish.so` é obrigatória: o servidor a carrega ao iniciar e cai sem
  ela). Requer o NDK 29 e o CMake 3.31+ instalados no Android SDK da máquina que compila.
