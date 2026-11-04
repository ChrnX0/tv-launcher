package org.conscrypt;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;

/**
 * PONTE, não a conscrypt de verdade (04/11/2026; ver OpenSSLProvider.java). A libadb chama, no pareamento,
 * org.conscrypt.Conscrypt.exportKeyingMaterial(SSLSocket, String, byte[], int) por reflexão (RFC 5705: o segredo do
 * TLS que autentica o código de pareamento). Aqui isso vira a API PÚBLICA do Android para a mesma coisa,
 * android.net.ssl.SSLSockets.exportKeyingMaterial (API 29+; a TV é Android 14), em vez da oculta que é bloqueada.
 */
public final class Conscrypt {
    private Conscrypt() {}

    public static byte[] exportKeyingMaterial(SSLSocket socket, String label, byte[] context, int length) throws SSLException {
        return android.net.ssl.SSLSockets.exportKeyingMaterial(socket, label, context, length);
    }
}
