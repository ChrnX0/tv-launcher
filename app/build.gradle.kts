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

// Sem dependências extras de propósito: só o framework do Android. APK menor e menos RAM na TV.
