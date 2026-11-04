pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        // Plugin do Shizuku (gera as "pontes" para APIs escondidas do Android); usado pelos módulos em shizuku/.
        id("dev.rikka.tools.refine") version "4.4.0"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
    // Catálogo com os mesmos nomes que o Shizuku usa nos build.gradle dele (libs.hidden.stub etc.).
    versionCatalogs {
        create("libs") {
            version("hidden-api", "4.4.0")
            library("hidden-compat", "dev.rikka.hidden", "compat").versionRef("hidden-api")
            library("hidden-stub", "dev.rikka.hidden", "stub").versionRef("hidden-api")
            version("refine", "4.4.0")
            library("refine-runtime", "dev.rikka.tools.refine", "runtime").versionRef("refine")
            library("refine-annotation", "dev.rikka.tools.refine", "annotation").versionRef("refine")
            library("refine-annotation-processor", "dev.rikka.tools.refine", "annotation-processor").versionRef("refine")
        }
    }
}

rootProject.name = "tv-launcher"
include(":app")

// Servidor do Shizuku embutido (fork thedjchi, ver shizuku/NOTICE.md). Os nomes dos projetos são os mesmos da
// origem (":server", ":aidl"...) porque os build.gradle deles se referenciam assim.
include(":server", ":starter", ":common")
project(":server").projectDir = file("shizuku/server")
project(":starter").projectDir = file("shizuku/starter")
project(":common").projectDir = file("shizuku/common")
for (m in listOf("aidl", "shared", "server-shared", "rish", "provider", "api")) {
    include(":$m")
    project(":$m").projectDir = file("shizuku/api/$m")
}

// App de teste do Shizuku embutido (cliente mínimo). Não é instalado no uso normal; ver ferramentas/cliente-shizuku.
include(":cliente-shizuku")
project(":cliente-shizuku").projectDir = file("ferramentas/cliente-shizuku")
