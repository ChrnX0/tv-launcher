import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// local.properties (fora do git): lido aqui, FORA do bloco android { } — lá dentro "java" é a extensão java do Gradle,
// não o pacote java.util, e o nome não resolve.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "br.paulo.tvlauncher"
    compileSdk = 34

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

        // Credenciais da VPN (AdGuard, IPsec/IKEv2): lidas do local.properties, que está no .gitignore — nunca vão
        // para o GitHub. Viram constantes em BuildConfig (classe gerada no build). Vazias = VPN não configurada.
        // Provisório: quando o painel web existir, elas passam a ser digitadas por lá e guardadas no aparelho.
        fun campo(chave: String) = "\"" + localProps.getProperty(chave, "").replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        buildConfigField("String", "VPN_SERVIDOR", campo("vpn.servidor"))
        // "ID do servidor remoto" do painel AdGuard: é a identidade IKE do Builder(serverAddr, identity) do Android
        // (verifiquei no javadoc do AOSP) — NÃO é o usuário do EAP, que é outro campo (setAuthUsernamePassword).
        buildConfigField("String", "VPN_ID_REMOTO", campo("vpn.idremoto"))
        buildConfigField("String", "VPN_USUARIO", campo("vpn.usuario"))
        buildConfigField("String", "VPN_SENHA", campo("vpn.senha"))
    }

    buildFeatures {
        buildConfig = true   // gera a classe BuildConfig com os campos acima
    }

    buildTypes {
        release {
            // Release encolhe o APK e remove código não usado. Assina com a chave debug só para instalar via adb.
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Antes eram zero dependências, de propósito (APK menor, menos RAM na TV). Até 03/11/2026 tinha a API do Shizuku
// aqui (app Shizuku desinstalado, dependência removida); hoje só o shell próprio:
dependencies {
    // Shell proprio MARCO 2: cliente ADB embutido (pareamento + TLS + shell). Licenca Apache-2.0 (opcao da dupla).
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("com.github.MuntashirAkon:sun-security-android:1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
}
