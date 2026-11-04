# Regras do R8 para o build release (04/11/2026). Sem elas o release quebra em silencio:

# Servidor do Shizuku embutido: o processo shizuku_server e iniciado por `app_process ... rikka.shizuku.server.ShizukuService`
# (nome da classe em texto, fora do app) e fala por AIDL/binder com o launcher e com os apps clientes.
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-keep class rikka.rish.** { *; }
-keep class rikka.parcelablelist.** { *; }
-keep class dev.rikka.** { *; }
-keep class rikka.hidden.** { *; }
-dontwarn rikka.**
-dontwarn moe.shizuku.**
-dontwarn dev.rikka.**
# As APIs escondidas do Android (stubs so de compilacao, "compileOnly hidden-stub") nao existem no classpath do R8.
-dontwarn android.**
-dontwarn com.android.internal.**

# Shell proprio: cliente ADB (libadb-android), TLS/SPAKE2 (conscrypt, JNI) e certificados (sun-security-android).
-keep class io.github.muntashirakon.** { *; }
-keep class org.conscrypt.** { *; }
-keep class android.sun.security.** { *; }
-dontwarn io.github.muntashirakon.**
-dontwarn org.conscrypt.**
-dontwarn android.sun.security.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
