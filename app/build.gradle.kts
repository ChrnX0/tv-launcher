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
}
