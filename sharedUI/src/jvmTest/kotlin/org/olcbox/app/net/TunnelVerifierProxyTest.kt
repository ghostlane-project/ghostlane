package org.olcbox.app.net

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The verifier decides whether the app is allowed to say "connected", so the part
 * that carries the probe has to work, not just the part that parses the answer.
 *
 * It is driven here through a real SOCKS5 proxy — the same shape olcRTC exposes,
 * with a username and password — because credentials do not travel with the proxy
 * description: they are applied through the platform authenticator. A verifier that
 * skips that step fails every olcRTC connection while reporting the tunnel dead.
 */
class TunnelVerifierProxyTest {

    private val closeables = mutableListOf<Closeable>()

    @AfterTest
    fun tearDown() {
        closeables.forEach { runCatching { it.close() } }
    }

    private fun traceServer(body: String): HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/cdn-cgi/trace") { exchange ->
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
            closeables += Closeable { stop(0) }
        }

    private fun socksProxy(username: String? = null, password: String? = null): FakeSocksProxy =
        FakeSocksProxy(username, password).also { closeables += it }

    private fun probeUrl(server: HttpServer) =
        "http://127.0.0.1:${server.address.port}/cdn-cgi/trace"

    /**
     * The system-tunnel probe: no proxy, because on iOS the tunnel is the
     * system's and our own request already rides it. This is the detector for
     * a tunnel that reports itself up and carries nothing (ghostlane#27), so
     * what matters is that it answers from a live endpoint and says nothing
     * when there is none.
     */
    @Test
    fun theSystemTunnelProbeAnswersWithoutAProxy() {
        val target = traceServer("ip=203.0.113.9\nloc=NL\n")

        val exit = runBlocking {
            TunnelVerifier.verifySystemTunnel(probeUrls = listOf(probeUrl(target)))
        }

        assertEquals("203.0.113.9", exit?.ip)
        assertEquals("NL", exit?.country)
    }

    @Test
    fun theSystemTunnelProbeReportsNothingWhenNoEndpointAnswers() {
        val dead = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).also { it.close() }

        val exit = runBlocking {
            TunnelVerifier.verifySystemTunnel(
                timeoutMs = 1_000,
                probeUrls = listOf("http://127.0.0.1:${dead.localPort}/cdn-cgi/trace")
            )
        }

        assertNull(exit)
    }

    @Test
    fun probeTravelsThroughAnAuthenticatedProxy() {
        val target = traceServer("ip=2a09:bac5:42f3:174b::252:63\nloc=JP\n")
        val proxy = socksProxy(username = "olcbox", password = "s3cret")

        val exit = runBlocking {
            TunnelVerifier.verify(
                socksHost = "127.0.0.1",
                socksPort = proxy.port,
                username = "olcbox",
                password = "s3cret",
                probeUrls = listOf(probeUrl(target))
            )
        }

        assertEquals("2a09:bac5:42f3:174b::252:63", exit?.ip)
        assertEquals("JP", exit?.country)
    }

    @Test
    fun probeTravelsThroughAProxyThatWantsNoLogin() {
        // The sing-box and Xray cores listen without credentials, which is the path
        // every vless/hysteria2/xhttp connection takes.
        val target = traceServer("ip=203.0.113.7\nloc=DE\n")
        val proxy = socksProxy()

        val exit = runBlocking {
            TunnelVerifier.verify(
                socksHost = "127.0.0.1",
                socksPort = proxy.port,
                probeUrls = listOf(probeUrl(target))
            )
        }

        assertEquals("203.0.113.7", exit?.ip)
        assertEquals("DE", exit?.country)
    }

    @Test
    fun rejectedCredentialsReadAsNoTraffic() {
        val target = traceServer("ip=203.0.113.7\nloc=DE\n")
        val proxy = socksProxy(username = "olcbox", password = "s3cret")

        val exit = runBlocking {
            TunnelVerifier.verify(
                socksHost = "127.0.0.1",
                socksPort = proxy.port,
                username = "olcbox",
                password = "wrong",
                timeoutMs = 3_000,
                probeUrls = listOf(probeUrl(target))
            )
        }

        assertNull(exit, "a proxy that refuses the login cannot have carried the probe")
    }

    @Test
    fun anUnreachableEndpointFallsThroughToTheNextOne() {
        // Some networks and exits blackhole 1.1.1.1 while the tunnel around it is
        // perfectly alive, so one dead endpoint must not condemn the connection.
        val target = traceServer("ip=203.0.113.7\nloc=DE\n")
        val proxy = socksProxy()

        val exit = runBlocking {
            TunnelVerifier.verify(
                socksHost = "127.0.0.1",
                socksPort = proxy.port,
                timeoutMs = 3_000,
                probeUrls = listOf("http://127.0.0.1:1/cdn-cgi/trace", probeUrl(target))
            )
        }

        assertEquals("203.0.113.7", exit?.ip)
    }

    @Test
    fun aDeadProxyReadsAsNoTraffic() {
        // Nothing listening: the shape of a core that died right after start.
        val free = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

        val exit = runBlocking {
            TunnelVerifier.verify(
                socksHost = "127.0.0.1",
                socksPort = free,
                timeoutMs = 2_000,
                probeUrls = listOf("http://127.0.0.1:1/cdn-cgi/trace")
            )
        }

        assertNull(exit)
    }
}

/**
 * A SOCKS5 proxy that is just complete enough to carry one HTTP request: method
 * negotiation, optional username/password, CONNECT, then a byte pipe. Written here
 * rather than pulled in as a dependency so the test has no network and no fixtures.
 */
private class FakeSocksProxy(
    private val username: String?,
    private val password: String?
) : Closeable {

    private val server = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort

    init {
        thread(isDaemon = true, name = "fake-socks") {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { serve(client) } }
            }
        }
    }

    override fun close() {
        runCatching { server.close() }
    }

    private fun serve(client: Socket) = client.use {
        val input = client.getInputStream()
        val output = client.getOutputStream()

        // Greeting: version, method count, methods.
        val greeting = input.readExactly(2)
        require(greeting[0] == VERSION) { "not a SOCKS5 greeting" }
        input.readExactly(greeting[1].toInt() and 0xFF)

        val wantsAuth = username != null
        output.write(byteArrayOf(VERSION, if (wantsAuth) AUTH_USERPASS else AUTH_NONE))
        output.flush()

        if (wantsAuth) {
            input.readExactly(1) // sub-negotiation version
            val user = String(input.readExactly(input.readExactly(1).first().toInt() and 0xFF))
            val pass = String(input.readExactly(input.readExactly(1).first().toInt() and 0xFF))
            val ok = user == username && pass == password
            output.write(byteArrayOf(1, if (ok) 0 else 1))
            output.flush()
            if (!ok) return
        }

        // Request: version, command, reserved, address type.
        val header = input.readExactly(4)
        require(header[1] == CMD_CONNECT) { "only CONNECT is supported" }
        val host = when (val type = header[3]) {
            ADDR_IPV4 -> input.readExactly(4).joinToString(".") { (it.toInt() and 0xFF).toString() }
            ADDR_DOMAIN -> String(input.readExactly(input.readExactly(1).first().toInt() and 0xFF))
            ADDR_IPV6 -> InetAddress.getByAddress(input.readExactly(16)).hostAddress
            else -> error("unsupported address type $type")
        }
        val portBytes = input.readExactly(2)
        val targetPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

        val upstream = Socket(host, targetPort)
        // Success, bound to 0.0.0.0:0 — clients ignore the reported bind address.
        output.write(byteArrayOf(VERSION, 0, 0, ADDR_IPV4, 0, 0, 0, 0, 0, 0))
        output.flush()

        upstream.use {
            val pump = thread(isDaemon = true) { runCatching { input.copyInto(upstream.getOutputStream()) } }
            runCatching { upstream.getInputStream().copyInto(output) }
            pump.join(2_000)
        }
    }

    private fun InputStream.readExactly(count: Int): ByteArray {
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = read(buffer, read, count - read)
            if (n < 0) error("proxy connection closed after $read of $count bytes")
            read += n
        }
        return buffer
    }

    private fun InputStream.copyInto(sink: OutputStream) {
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            sink.write(buffer, 0, n)
            sink.flush()
        }
    }

    private companion object {
        const val VERSION: Byte = 5
        const val AUTH_NONE: Byte = 0
        const val AUTH_USERPASS: Byte = 2
        const val CMD_CONNECT: Byte = 1
        const val ADDR_IPV4: Byte = 1
        const val ADDR_DOMAIN: Byte = 3
        const val ADDR_IPV6: Byte = 4
    }
}
