plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 04/11/2026: o build não lê mais o local.properties (além do sdk.dir, que o Gradle lê sozinho). As credenciais da
// VPN própria (vpn.* ali dentro) iam em texto puro para o APK via BuildConfig; com a troca para o app AdGuard VPN
// elas saíram do build. O local.properties do Paulo continua como está.

android {
    namespace = "br.paulo.tvlauncher"
    // 36 (antes 34): exigido pelos módulos do Shizuku embutido (androidx.core 1.16 pede compileSdk >= 35).
    // Não muda o comportamento na TV: quem manda nisso é o targetSdk, que continua 34.
    compileSdk = 36

    defaultConfig {
        applicationId = "br.paulo.tvlauncher"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"

        // So a arquitetura do Chromecast: `getprop ro.product.cpu.abilist` na TV = armeabi-v7a,armeabi (32 bits, lido em
        // 04/11/2026). Sem isto o conscrypt entrava em 4 ABIs: ~7 MB de libs que a TV nunca carrega (medido no dist/).
        // Se um dia instalar numa TV de 64 bits, acrescente "arm64-v8a" aqui.
        ndk { abiFilters += listOf("armeabi-v7a") }
    }

    buildFeatures {
        buildConfig = true   // gera a classe BuildConfig (sem campos próprios desde que a VPN própria saiu)
    }

    // Extrai as .so para a pasta lib/arm do app instalado (como o app Shizuku original faz). Necessário porque o
    // servidor do Shizuku embutido roda FORA do app (app_process) e carrega a librish.so por caminho de arquivo:
    // System.load("<nativeLibraryDir>/librish.so"). Sem isto as .so ficam só dentro do APK e o servidor cai
    // (medido em 04/11/2026: UnsatisfiedLinkError ... lib/arm/librish.so not found).
    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    buildTypes {
        release {
            // Release encolhe o APK e remove código não usado. Assina com a chave debug só para instalar via adb.
            isMinifyEnabled = true
            isShrinkResources = false
            // proguard-rules.pro (04/11/2026): mantém o servidor do Shizuku, a libadb, o conscrypt e o sun-security, que são
            // achados por nome (app_process, reflexão, JNI). Sem elas o release quebrava o shell próprio e o Shizuku.
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
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
}
