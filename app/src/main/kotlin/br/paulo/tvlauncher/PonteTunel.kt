package br.paulo.tvlauncher

import android.util.Base64
import android.util.Log
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ponte de saída do túnel (06/11/2026). Medido na Sala: o programa do túnel (Go estático, processo filho) abre conexões
 * direto no kernel e elas ficam em SYN-SENT para sempre, enquanto as do próprio launcher (Java) saem normalmente.
 * Hipótese: o Android só encaminha o tráfego de app que passa pela biblioteca de rede dele (marca da rede em cada socket).
 *
 * Então o túnel sai por aqui: um proxy HTTP CONNECT em 127.0.0.1 (porta aleatória), com senha aleatória a cada início,
 * só para a porta 443. Quem abre a conexão de verdade é este código Java, que o Android encaminha. O Tailscale usa
 * proxy para falar com o servidor de controle e com os DERP (relés); o UDP direto fica sem uso (tudo vai pelos relés).
 */
object PonteTunel {
    private const val TAG = "TvLauncher.Ponte"
    private const val MAX_CONEXOES = 48

    @Volatile private var servidor: ServerSocket? = null
    @Volatile var porta: Int = 0; private set
    @Volatile var senha: String = ""; private set
    private val abertas = AtomicInteger(0)

    /** Sobe (se ainda não estiver de pé) e devolve a URL para HTTPS_PROXY. Null se não conseguiu abrir a porta. */
    @Synchronized fun garantir(): String? {
        if (servidor?.isClosed == false) return url()
        return try {
            val s = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
            senha = ByteArray(18).also { SecureRandom().nextBytes(it) }.let { Base64.encodeToString(it, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING) }
            porta = s.localPort
            servidor = s
            Thread({ aceitar(s) }, "Ponte-aceitar").apply { isDaemon = true }.start()
            Log.i(TAG, "ponte em 127.0.0.1:$porta")
            url()
        } catch (e: IOException) { Log.w(TAG, "ponte não abriu: $e"); null }
    }

    private fun url() = "http://t:$senha@127.0.0.1:$porta"

    @Synchronized fun parar() { try { servidor?.close() } catch (_: IOException) {}; servidor = null }

    private fun aceitar(s: ServerSocket) {
        while (!s.isClosed) {
            val c = try { s.accept() } catch (e: IOException) { break }
            if (abertas.incrementAndGet() > MAX_CONEXOES) { abertas.decrementAndGet(); fechar(c); continue }
            Thread({ try { atender(c) } finally { abertas.decrementAndGet() } }, "Ponte-conexao").apply { isDaemon = true }.start()
        }
    }

    private fun atender(c: Socket) {
        var destino: Socket? = null
        try {
            c.soTimeout = 15_000
            val entrada = BufferedInputStream(c.getInputStream())
            val cabecalho = PonteRegras.lerCabecalho(entrada) ?: return responder(c.getOutputStream(), 400)
            val alvo = when (val r = PonteRegras.conferir(cabecalho, senha)) {
                is PonteRegras.Resultado.Recusa -> return responder(c.getOutputStream(), r.codigo)
                is PonteRegras.Resultado.Ok -> r
            }
            destino = Socket().apply { connect(InetSocketAddress(alvo.host, alvo.porta), 10_000) }
            c.soTimeout = 0
            c.getOutputStream().apply { write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray()); flush() }
            val d = destino
            val ida = Thread({ copiar(entrada, d.getOutputStream()); try { d.shutdownOutput() } catch (_: IOException) {} }, "Ponte-ida")
            ida.isDaemon = true; ida.start()
            copiar(d.getInputStream(), c.getOutputStream())
            ida.join(5_000)
        } catch (e: Exception) {
            try { if (destino == null) responder(c.getOutputStream(), 502) } catch (_: Exception) {}
        } finally { fechar(destino); fechar(c) }
    }

    private fun responder(o: OutputStream, codigo: Int) {
        try { o.write("HTTP/1.1 $codigo ${if (codigo == 407) "Proxy Authentication Required" else "Recusado"}\r\nContent-Length: 0\r\n\r\n".toByteArray()); o.flush() } catch (_: IOException) {}
    }

    private fun copiar(de: InputStream, para: OutputStream) {
        val buf = ByteArray(16 * 1024)
        try { while (true) { val n = de.read(buf); if (n < 0) break; para.write(buf, 0, n); para.flush() } } catch (_: IOException) {}
    }

    private fun fechar(s: Socket?) { try { s?.close() } catch (_: IOException) {} }
}

/** As regras da ponte, puras (teste JVM): só CONNECT, só porta 443, senha certa, nome de host comum. */
object PonteRegras {
    sealed class Resultado {
        class Ok(val host: String, val porta: Int) : Resultado()
        class Recusa(val codigo: Int) : Resultado()
    }

    /** Lê até a linha vazia (no máximo 8 KB). Null se passar disso ou a conexão fechar antes. */
    fun lerCabecalho(e: InputStream): List<String>? {
        val linhas = ArrayList<String>(); val atual = StringBuilder(); var total = 0
        while (true) {
            val b = e.read(); if (b < 0) return null
            if (++total > 8192) return null
            if (b == '\n'.code) {
                val l = atual.toString().trimEnd('\r'); atual.setLength(0)
                if (l.isEmpty()) return linhas
                linhas.add(l)
            } else atual.append(b.toChar())
        }
    }

    fun conferir(linhas: List<String>, senha: String): Resultado {
        val partes = linhas.firstOrNull()?.split(' ') ?: return Resultado.Recusa(400)
        if (partes.size != 3 || partes[0] != "CONNECT") return Resultado.Recusa(405)
        val auth = linhas.drop(1).firstOrNull { it.startsWith("Proxy-Authorization:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
        val esperado = "Basic " + java.util.Base64.getEncoder().encodeToString("t:$senha".toByteArray())
        if (senha.isEmpty() || auth == null || !igual(auth, esperado)) return Resultado.Recusa(407)
        val alvo = partes[1]
        val host = alvo.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
        val porta = alvo.substringAfterLast(':').toIntOrNull() ?: return Resultado.Recusa(400)
        if (porta != 443) return Resultado.Recusa(403)
        if (host.isEmpty() || host.length > 253 || !host.all { it.isLetterOrDigit() || it in ".-:" }) return Resultado.Recusa(400)
        // Nada de usar a ponte para alcançar a própria TV ou a rede de casa.
        if (host == "localhost" || host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.") || host == "::1") return Resultado.Recusa(403)
        return Resultado.Ok(host, porta)
    }

    /** Comparação em tempo constante. */
    private fun igual(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var d = 0; for (i in a.indices) d = d or (a[i].code xor b[i].code); return d == 0
    }
}
