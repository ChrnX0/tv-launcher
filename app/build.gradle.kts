plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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

// Antes eram zero dependências, de propósito (APK menor, menos RAM na TV). Agora só a API do Shizuku:
// "api" faz as chamadas; "provider" recebe o canal (binder) quando o Shizuku inicia. Custo medido: o APK foi de 1,0 MB para 1,8 MB.
dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // Shell proprio MARCO 2: cliente ADB embutido (pareamento + TLS + shell). Licenca Apache-2.0 (opcao da dupla).
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("com.github.MuntashirAkon:sun-security-android:1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
}
