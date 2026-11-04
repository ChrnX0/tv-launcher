package org.conscrypt;

import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.SecureRandom;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLContextSpi;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

/**
 * PONTE, não a conscrypt de verdade (04/11/2026). O launcher deixou de embutir a conscrypt-android (1,28 MB de lib
 * nativa). A libadb-android procura esta classe por nome (SslUtils: Class.forName("org.conscrypt.OpenSSLProvider")) e,
 * se a acha, usa o caminho "conscrypt embutida", que no pareamento chama org.conscrypt.Conscrypt.exportKeyingMaterial
 * (ver Conscrypt.java ao lado). Sem esta ponte ela cai no caminho da plataforma, que chama
 * com.android.org.conscrypt.Conscrypt.exportKeyingMaterial por reflexão, uma API OCULTA que o Android 14 bloqueia
 * (medido na TV: "Accessing hidden method ... (blocked, reflection, denied)" e o pareamento falhava).
 *
 * Este provedor só repassa o SSLContext "TLSv1.3" ao TLS do próprio Android (a conscrypt da plataforma). Os sockets
 * criados são os da plataforma, que a API pública android.net.ssl.SSLSockets aceita.
 */
public final class OpenSSLProvider extends Provider {
    public OpenSSLProvider() {
        super("TvLauncherTlsPonte", 1.0, "Repassa o TLSv1.3 ao TLS do Android (ponte para a libadb)");
        put("SSLContext.TLSv1.3", ContextoTls.class.getName());
    }

    /** SSLContextSpi que delega tudo a SSLContext.getInstance("TLSv1.3") da plataforma. */
    public static final class ContextoTls extends SSLContextSpi {
        private final SSLContext real;

        public ContextoTls() throws NoSuchAlgorithmException {
            real = SSLContext.getInstance("TLSv1.3");
        }

        @Override protected void engineInit(KeyManager[] km, TrustManager[] tm, SecureRandom sr) throws KeyManagementException {
            real.init(km, tm, sr);
        }
        @Override protected SSLSocketFactory engineGetSocketFactory() { return real.getSocketFactory(); }
        @Override protected SSLServerSocketFactory engineGetServerSocketFactory() { return real.getServerSocketFactory(); }
        @Override protected SSLEngine engineCreateSSLEngine() { return real.createSSLEngine(); }
        @Override protected SSLEngine engineCreateSSLEngine(String host, int port) { return real.createSSLEngine(host, port); }
        @Override protected SSLSessionContext engineGetServerSessionContext() { return real.getServerSessionContext(); }
        @Override protected SSLSessionContext engineGetClientSessionContext() { return real.getClientSessionContext(); }
        @Override protected SSLParameters engineGetDefaultSSLParameters() { return real.getDefaultSSLParameters(); }
        @Override protected SSLParameters engineGetSupportedSSLParameters() { return real.getSupportedSSLParameters(); }
    }
}
