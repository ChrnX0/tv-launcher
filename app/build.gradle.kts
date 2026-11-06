// "java.util.Properties" escrito por extenso não funciona aqui: no script do Gradle, "java" é a extensão do plugin Java.
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 04/11/2026: o build não lê mais o local.properties (além do sdk.dir, que o Gradle lê sozinho). As credenciais da
// VPN própria (vpn.* ali dentro) iam em texto puro para o APK via BuildConfig; com a troca para o app AdGuard VPN
// elas saíram do build. O local.properties do Paulo continua como está.

// 05/11/2026: dados PESSOAIS (cidade, coordenadas, apps preferidos, eclipses da cidade) saíram do Config.kt, porque o
// repositório é público. Eles vêm do arquivo pessoal.properties, na raiz do repo, que NÃO vai para o git (.gitignore).
// Sem o arquivo (quem baixa do GitHub, ou o build do GitHub Actions), valem padrões neutros. Modelo comentado em
// pessoal.properties.exemplo. Cada valor vira um campo da classe BuildConfig, que o Config.kt lê.
val pessoal = Properties().apply {
    val f = rootProject.file("pessoal.properties")
    if (f.exists()) f.reader(Charsets.UTF_8).use { load(it) }   // UTF-8: acentos (ã, ç) saem certos
}
// Texto Java entre aspas, com \ e " escapados (senão um valor com aspas quebra a compilação do BuildConfig).
fun textoJava(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
fun pessoalTexto(chave: String, padrao: String = "") = textoJava(pessoal.getProperty(chave, padrao).trim())
fun pessoalNumero(chave: String, padrao: Double) = (pessoal.getProperty(chave)?.trim()?.toDoubleOrNull() ?: padrao).toString()

android {
    namespace = "br.paulo.tvlauncher"
    // 36 (antes 34): exigido pelos módulos do Shizuku embutido (androidx.core 1.16 pede compileSdk >= 35).
    // Não muda o comportamento na TV: quem manda nisso é o targetSdk, que continua 34.
    compileSdk = 36

    defaultConfig {
        applicationId = "br.paulo.tvlauncher"
        minSdk = 26
        targetSdk = 34
        // 04/11/2026: a atualização automática (GitHub Releases) precisa de um versionCode que sempre cresce, senão o
        // Android recusa a instalação ("downgrade"). Usamos os minutos desde 01/01/2026 na hora do build, no PC e no
        // GitHub: todo build novo é maior que qualquer um mais antigo, de qualquer branch. Antes era sempre 1.
        // Cabe num Int por uns 4 mil anos.
        versionCode = ((System.currentTimeMillis() - 1_767_225_600_000L) / 60_000L).toInt()
        versionName = "0.2"

        // So a arquitetura do Chromecast: `getprop ro.product.cpu.abilist` na TV = armeabi-v7a,armeabi (32 bits, lido em
        // 04/11/2026). Sem isto o conscrypt entrava em 4 ABIs: ~7 MB de libs que a TV nunca carrega (medido no dist/).
        // Se um dia instalar numa TV de 64 bits, acrescente "arm64-v8a" aqui.
        ndk { abiFilters += listOf("armeabi-v7a") }

        // Dados pessoais (ver pessoal.properties no topo). Padrões neutros entre aspas ou como número.
        buildConfigField("String", "CIDADE", pessoalTexto("cidade"))
        buildConfigField("double", "LATITUDE", pessoalNumero("latitude", 0.0))
        buildConfigField("double", "LONGITUDE", pessoalNumero("longitude", 0.0))
        buildConfigField("boolean", "USAR_FAHRENHEIT", (pessoal.getProperty("fahrenheit")?.trim() == "true").toString())
        buildConfigField("String", "ESTACAO_METAR", pessoalTexto("estacao_metar"))
        buildConfigField("String", "APPS_FIXOS", pessoalTexto("apps_fixos"))
        buildConfigField("String", "APPS_ORDEM", pessoalTexto("apps_ordem",
            "com.google.android.youtube.tv,com.google.android.youtube.tvmusic,com.netflix.ninja,com.android.vending"))
        buildConfigField("String", "BOTAO_NETFLIX", pessoalTexto("botao_netflix", "com.netflix.ninja"))
        buildConfigField("String", "ECLIPSES", pessoalTexto("eclipses"))
    }

    buildFeatures {
        buildConfig = true   // gera a classe BuildConfig (campos do pessoal.properties, ver defaultConfig)
    }

    // Extrai as .so para a pasta lib/arm do app instalado (como o app Shizuku original faz). Necessário porque o
    // servidor do Shizuku embutido roda FORA do app (app_process) e carrega a librish.so por caminho de arquivo:
    // System.load("<nativeLibraryDir>/librish.so"). Sem isto as .so ficam só dentro do APK e o servidor cai
    // (medido em 04/11/2026: UnsatisfiedLinkError ... lib/arm/librish.so not found).
    packaging {
        jniLibs { useLegacyPackaging = true }
        // 04/11/2026: tabelas da assinatura pós-quântica "Picnic" do bouncycastle (vem de carona numa dependência do
        // Shizuku/libadb). Eram 1,2 MB comprimidos (~2,3 MB reais) no APK e nada do launcher usa Picnic: o pareamento
        // e o TLS do adb usam SPAKE2 (libspake2) e conscrypt. Medido no release: lowmcL1/L3/L5.bin.properties.
        resources { excludes += "org/bouncycastle/pqc/crypto/picnic/**" }
    }

    // Chave de assinatura do release (04/11/2026). Se a variável ASSINATURA_ARQUIVO apontar para um keystore (no GitHub
    // ela vem do segredo ASSINATURA_KEYSTORE_B64), assina com ele. Senão, usa a chave debug desta máquina, como sempre.
    // A TV do Paulo continua com a chave debug do Dell e é atualizada pelo Dell (opção A, 04/11/2026). O segredo do
    // GitHub é uma chave PÚBLICA separada, para quem instala do zero; nunca o debug.keystore do Dell.
    val keystoreDoAmbiente = System.getenv("ASSINATURA_ARQUIVO")?.takeIf { it.isNotBlank() }
    signingConfigs {
        create("release") {
            if (keystoreDoAmbiente != null) {
                storeFile = file(keystoreDoAmbiente)
                storePassword = System.getenv("ASSINATURA_SENHA")?.trim()?.takeIf { it.isNotBlank() } ?: "android"           // padrão do keystore debug
                keyAlias = System.getenv("ASSINATURA_ALIAS")?.trim()?.takeIf { it.isNotBlank() } ?: "androiddebugkey"
                keyPassword = System.getenv("ASSINATURA_SENHA_CHAVE")?.trim()?.takeIf { it.isNotBlank() } ?: storePassword
            }
        }
    }

    buildTypes {
        release {
            // Release encolhe o APK e remove código não usado. Assina com a chave debug só para instalar via adb.
            isMinifyEnabled = true
            isShrinkResources = false
            // proguard-rules.pro (04/11/2026): mantém o servidor do Shizuku, a libadb, o conscrypt e o sun-security, que são
            // achados por nome (app_process, reflexão, JNI). Sem elas o release quebrava o shell próprio e o Shizuku.
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (keystoreDoAmbiente != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Kotlin 2.2 trocou o bloco kotlinOptions { } (dentro de android) por este.
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

// Antes eram zero dependências, de propósito (APK menor, menos RAM na TV). Até 03/11/2026 tinha a API do Shizuku
// aqui (app Shizuku desinstalado, dependência removida); hoje só o shell próprio:
dependencies {
    // Shell proprio MARCO 2: cliente ADB embutido (pareamento + TLS + shell). Licenca Apache-2.0 (opcao da dupla).
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("com.github.MuntashirAkon:sun-security-android:1.1")
    // conscrypt-android SAIU (04/11/2026, -1,28 MB de lib nativa): sem ela a libadb usa o TLS 1.3 do próprio Android
    // (SslUtils: SSLContext.getInstance("TLSv1.3"), Android 10+) e, no pareamento, a exportKeyingMaterial da
    // conscrypt da plataforma. Testado na TV: ver o commit.

    // Servidor do Shizuku EMBUTIDO (MARCO S1, 04/11/2026): as classes do servidor precisam estar no APK do launcher,
    // porque o processo shizuku_server é iniciado com `app_process -Djava.class.path=<este APK>`.
    // "provider" + "api" = o lado cliente/gerente: recebe o binder do servidor (Shizuku.pingBinder()).
    implementation(project(":server"))
    implementation(project(":provider"))
    implementation(project(":api"))

    // Perfil de compilação (04/11/2026). Medido na TV: o ArtService do Android 14 força o app de HOME para
    // "speed-profile", seja qual for o -m do `cmd package compile`, e sem perfil isso vira "verify" (o log do artd diz
    // "Merge skipped because there are no existing profiles"). Ou seja: o launcher rodava só interpretado/JIT.
    // O src/main/baseline-prof.txt vai dentro do APK, e este pacote grava o perfil na pasta do app quando ele abre
    // (ou pelo broadcast INSTALL_PROFILE, usado pelo build3.ps1). Depois o compile -m speed-profile passa a valer.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    // QR code (login do Tailscale e endereço do MCP na tela da TV e no painel). Só o núcleo, sem câmera.
    implementation("com.google.zxing:core:3.5.3")

    // Testes que rodam no computador (gradlew testReleaseUnitTest), sem TV: a parte do painel no navegador que não
    // usa o Android (PainelHttp, PainelSeguranca). Não entra no APK.
    testImplementation("junit:junit:4.13.2")
    // O org.json do android.jar é só casca nos testes do computador ("Stub!"): a IA (ComandosIa) precisa do de verdade.
    testImplementation("org.json:json:20240303")
    // Leitor de XML em fluxo para os testes do guia XMLTV (PlayerGuia.kt): na TV o Android já traz um (é o mesmo kxml).
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}

// Túnel para a internet (opção E, 05/11/2026): o programa Go em tunel/ (Tailscale embutido, tsnet) compilado para ARM
// 32 bits e posto no APK como "libtsnet.so". O nome .so é o truque: o Android extrai as libs para a pasta nativa do app
// (useLegacyPackaging acima), que é a única de onde um app pode EXECUTAR um binário. TunelTailscale.kt o roda de lá.
// Sem o Go instalado na máquina (ex.: o Dell hoje), o build segue sem o túnel e avisa; o resto do launcher não muda.
val pastaTunel = layout.buildDirectory.dir("tunel/jniLibs")
val compilarTunel = tasks.register("compilarTunel") {
    val fonte = rootProject.file("tunel")
    val saida = pastaTunel.get().file("armeabi-v7a/libtsnet.so").asFile
    inputs.dir(fonte)
    outputs.file(saida)
    doLast {
        saida.parentFile.mkdirs()
        val pb = ProcessBuilder("go", "build", "-trimpath", "-ldflags=-s -w", "-o", saida.absolutePath, ".")
            .directory(fonte).redirectErrorStream(true)
        pb.environment().putAll(mapOf("CGO_ENABLED" to "0", "GOOS" to "linux", "GOARCH" to "arm", "GOARM" to "7"))
        val codigo = try {
            val p = pb.start()
            val texto = p.inputStream.bufferedReader().readText()
            p.waitFor().also { if (it != 0) logger.warn("túnel: go build falhou:\n$texto") }
        } catch (e: java.io.IOException) {
            logger.warn("túnel: Go não está instalado; o APK sai SEM o túnel para a internet (MCP)")
            -1
        }
        if (codigo != 0) saida.delete()
    }
}
android.sourceSets["main"].jniLibs.srcDir(pastaTunel)
tasks.named("preBuild") { dependsOn(compilarTunel) }
