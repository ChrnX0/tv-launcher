// App de TESTE do Shizuku embutido (não vai para a TV no uso normal). É um cliente igual a qualquer app que usa o
// Shizuku: pede a permissão, recebe o binder e roda `id` pelo servidor. Serve para testar a tela de pedido (S2) e o
// caminho cliente -> servidor (S4) sem depender de app de terceiros. Uso: ver README.md nesta pasta.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.paulo.clienteshizuku"
    compileSdk = 36
    defaultConfig {
        applicationId = "br.paulo.clienteshizuku"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "teste"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":api"))
    implementation(project(":provider"))
}
