// Arquivo raiz: declara as versões dos plugins. O app em si é configurado em app/build.gradle.kts.
// Versões alinhadas com o Shizuku embutido (shizuku/): AGP 8.11.1 e Kotlin 2.2.20 (antes: 8.5.2 e 1.9.24).
plugins {
    id("com.android.application") version "8.11.1" apply false
    id("com.android.library") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
}

// Configuração comum dos módulos do Shizuku (na origem isto ficava no build.gradle raiz do repositório deles).
subprojects {
    if (path == ":app") return@subprojects
    plugins.withId("com.android.library") {
        extensions.configure<com.android.build.gradle.LibraryExtension> {
            compileSdk = 36
            buildToolsVersion = "36.0.0"
            // NDK para a parte nativa do Shizuku (librish.so, que o servidor carrega ao iniciar). Só compila para a
            // arquitetura da TV (armeabi-v7a, 32 bits), igual ao abiFilters do app.
            ndkVersion = "29.0.14206865"
            defaultConfig {
                minSdk = 24
                ndk { abiFilters += listOf("armeabi-v7a") }
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_21
                targetCompatibility = JavaVersion.VERSION_21
            }
        }
    }
}
