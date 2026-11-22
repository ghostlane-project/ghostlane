package org.olcbox.app.vpn.desktop.engine.host

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume
import org.olcbox.app.net.DirectDns
import org.olcbox.app.net.LinkParser
import org.olcbox.app.net.Routing
import org.olcbox.app.net.SocksLogin
import org.olcbox.app.net.core.CoreInbound
import org.olcbox.app.vpn.desktop.engine.DesktopEngineHost
import org.olcbox.app.vpn.desktop.engine.LineRequest
import org.olcbox.app.vpn.desktop.engine.SessionHooks
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ghostlane-engine itself, the Core build's program at the pin, as the line
 * of a desktop session in proxy mode, with the official Xray 26.4.25 as the
 * server: the app's own HostEngine writes the session, starts the program,
 * reads its events and stops it.
 *
 * Run by scripts/desktop-checks/proxy.sh (rust-desktop.yml), which starts the
 * server, the page behind it and the REALITY target, and writes what these
 * tests read into the file [CHECK_ENV] names, the program being
 * [HostAssets.ENV]'s. Anywhere else each test is skipped, and proxy.sh fails
 * a run in which one was skipped or did not run.
 *
 * The page is asked for at http://203.0.113.10/ (TEST-NET-3), which only the
 * server's redirect turns into the page: a request that did not take the
 * line goes nowhere.
 */
class HostEngineRealTest {
    private class Check(
        val link: String,
        val namedLink: String,
        val name: String,
        val address: String,
        val target: String,
        val targetPort: Int,
        val downBytes: Int,
        val downSha256: String,
        val upBytes: Int,
        val lanAddress: String,
        val outcomes: File,
    ) {
        companion object {
            fun read(file: File): Check {
                val o = Json.parseToJsonElement(file.readText()).jsonObject
                fun s(name: String) = o.getValue(name).jsonPrimitive.content
                fun i(name: String) = o.getValue(name).jsonPrimitive.int
                return Check(
                    link = s("link"), namedLink = s("named_link"), name = s("name"), address = s("address"),
                    target = s("target"), targetPort = i("target_port"),
                    downBytes = i("down_bytes"), downSha256 = s("down_sha256"), upBytes = i("up_bytes"),
                    lanAddress = s("lan_address"), outcomes = File(s("outcomes")),
                )
            }
        }
    }

    /** What a SOCKS5 or HTTP proxy said no with, after it was reached. */
    private class Refused(message: String) : IOException(message)

    private lateinit var check: Check
    private lateinit var sessions: Path
    private val log = mutableListOf<String>()

    private val host = DesktopEngineHost(
        log = { line -> synchronized(log) { log += line }; println(line) },
        windowsTunOutput = {},
        session = object : SessionHooks {
            override fun lineDied(what: String) {}
            override fun wayOutChanged(interfaceName: String?) {}
        },
    )

    @BeforeTest
    fun onlyUnderTheCheck() {
        val file = System.getenv(CHECK_ENV)
        Assume.assumeTrue("set by scripts/desktop-checks/proxy.sh", !file.isNullOrBlank())
        check = Check.read(File(file))
        sessions = Files.createTempDirectory("host-engine-real")
    }

    @AfterTest
    fun clean() {
        if (::sessions.isInitialized) sessions.toFile().deleteRecursively()
    }

    private fun engine(serverAddress: suspend (String, DirectDns?) -> String = HostServerNames::address) =
        HostEngine(host, serverAddress = serverAddress, sessionParent = sessions)

    private fun request(link: String, port: Int, login: SocksLogin? = null) = LineRequest(
        spec = requireNotNull(LinkParser.parse(link)) { "the check's link does not parse" },
        kind = "Vless",
        port = port,
        routing = Routing.Global,
        verboseLogs = false,
        login = login,
        serverResolver = null,
        autoDetectInterface = false,
        bindInterface = null,
        anotherPort = { error("a line of one process asks for no second port") },
    )

    @Test
    fun proxyModeCarriesTheLineBySocksAndByHttpConnect(): Unit = runBlocking {
        val port = freePort("127.0.0.1")
        val engine = engine()
        val started = engine.line.start(request(check.link, port))
        try {
            assertTrue(waitForPort(port, started.alive), "the engine's port did not open\n${logTail()}")
            val down = eventually { socks5("127.0.0.1", port, null).use { get(it, "/down") } }
            assertEquals(check.downBytes, down.size, "the download through SOCKS5")
            assertEquals(check.downSha256, sha256(down), "the download through SOCKS5")
            val up = pattern(check.upBytes)
            assertEquals(sha256(up), socks5("127.0.0.1", port, null).use { post(it, "/up", up) }, "the upload through SOCKS5")
            val connected = eventually { httpConnect(port).use { get(it, "/down") } }
            assertEquals(check.downSha256, sha256(connected), "the download through HTTP CONNECT")
            assertTrue(started.alive(), "the engine ended while it served\n${logTail()}")
        } finally {
            engine.line.stopNow()
        }
        assertFalse(started.alive(), "the engine outlived its stop")
        assertFalse(engine.line.anyRunning())
        assertEquals(0, started.exitCode(), "a stop that was asked for ends the engine with 0\n${logTail()}")
        assertEquals(emptyList(), sessions.toFile().listFiles().orEmpty().toList(), "a session file outlived its run")
        val said = logTail(200)
        assertTrue("ghostlane-engine: ended (stdin_closed)" in said, said)
        System.getenv("GHOSTLANE_CORE_COMMIT")?.takeIf { it.length >= 12 }?.let { pin ->
            assertTrue(" at ${pin.take(12)} " in said, "the engine does not say it is the pin ${pin.take(12)}\n$said")
        }
    }

    @Test
    fun theLoginTheSessionNamesIsDemandedByTheEngine(): Unit = runBlocking {
        val port = freePort("127.0.0.1")
        val login = SocksLogin("desktop-user", "desktop-" + token())
        val engine = engine()
        val started = engine.line.start(request(check.link, port, login))
        try {
            assertTrue(waitForPort(port, started.alive), "the engine's port did not open\n${logTail()}")
            assertFailsWith<Refused>("a client with no login was let in") { socks5("127.0.0.1", port, null).close() }
            assertFailsWith<Refused>("a wrong password was let in") {
                socks5("127.0.0.1", port, SocksLogin(login.username, "not-" + login.password)).close()
            }
            val down = eventually { socks5("127.0.0.1", port, login).use { get(it, "/down") } }
            assertEquals(check.downSha256, sha256(down))
        } finally {
            engine.line.stopNow()
        }
    }

    /**
     * A plain HTTP request for a URL on the proxy port, as an app pointed at
     * the port as an HTTP proxy sends it (the system's proxy setting is SOCKS
     * on every desktop), is forwarded over the line: the core's `socks`
     * inbound answers it as well, and the page comes back whole.
     */
    @Test
    fun aPlainHttpGetOnTheProxyPortIsForwarded(): Unit = runBlocking {
        val port = freePort("127.0.0.1")
        val engine = engine()
        val started = engine.line.start(request(check.link, port))
        try {
            assertTrue(waitForPort(port, started.alive), "the engine's port did not open\n${logTail()}")
            val page = eventually { plainGet(port) }
            assertEquals(check.downBytes, page.size, "the download by a plain GET")
            assertEquals(check.downSha256, sha256(page), "the download by a plain GET")
            check.outcomes.appendText("A plain HTTP GET for a URL on the proxy port: forwarded over the line, the page whole\n")
            assertTrue(started.alive(), "the engine ended over a plain GET\n${logTail()}")
        } finally {
            engine.line.stopNow()
        }
    }

    /**
     * The check's name is one no resolver knows: only the app's own answer
     * (here the test's) reaches the engine, as the address the line is
     * written with. Were the name handed to the engine, its core would ask
     * the system and get nothing.
     */
    @Test
    fun aServerNamedByAHostnameIsPinnedBeforeTheEngineStarts(): Unit = runBlocking {
        val asked = mutableListOf<String>()
        val engine = engine { name, _ ->
            asked += name
            if (name == check.name) check.address else error("not the check's name: $name")
        }
        val port = freePort("127.0.0.1")
        val started = engine.line.start(request(check.namedLink, port))
        try {
            assertEquals(listOf(check.name), asked)
            assertTrue(waitForPort(port, started.alive), "the engine's port did not open\n${logTail()}")
            val down = eventually { socks5("127.0.0.1", port, null).use { get(it, "/down") } }
            assertEquals(check.downSha256, sha256(down))
        } finally {
            engine.line.stopNow()
        }
        assertTrue("the server's name resolved to an address" in logTail(200))
    }

    /**
     * LAN sharing's inbound in the same core, as the builder writes it, on a
     * private address of this machine: a client with no login and one with a
     * wrong password are refused there; the login passes, over the line.
     */
    @Test
    fun theLanInboundRefusesAClientWithNoLogin() {
        val own = freePort("127.0.0.1")
        val lanPort = freePort(check.lanAddress)
        val login = SocksLogin("lan-user", "lan-" + token())
        val spec = requireNotNull(LinkParser.parse(check.link))
        val plan = CoreInbound.Plan(CoreInbound.Proxy(own), CoreInbound.Lan(check.lanAddress, lanPort, login))
        val core = CoreInbound.build(spec, plan)
        val run = HostProcess.run(
            HostAssets.resolve(),
            HostSession.text(core),
            onEvent = { event -> HostEvents.logLine(event)?.let { host.log("ghostlane-engine: $it") } },
            onStderr = { host.log("ghostlane-engine: $it") },
            parent = sessions,
        )
        try {
            val ready = assertIs<HostProcess.Start.Ready>(run.awaitStart(20_000), "the engine did not serve\n${logTail()}")
            assertEquals(setOf(CoreInbound.TAG, CoreInbound.LAN_TAG), ready.inbounds.map { it.tag }.toSet())
            assertFailsWith<Refused>("a LAN client with no login was let in") { socks5(check.lanAddress, lanPort, null).close() }
            assertFailsWith<Refused>("a LAN client with a wrong password was let in") {
                socks5(check.lanAddress, lanPort, SocksLogin(login.username, "not-" + login.password)).close()
            }
            val down = eventually { socks5(check.lanAddress, lanPort, login).use { get(it, "/down") } }
            assertEquals(check.downSha256, sha256(down), "the download through the LAN inbound")
        } finally {
            run.stop()
        }
        assertFalse(run.isAlive())
    }

    /** The engine's own sentence reaches the start's failure: here a port another program holds. */
    @Test
    fun aSessionTheEngineRefusesFailsTheStartWithItsSentence(): Unit = runBlocking {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { taken ->
            val engine = engine()
            val failed = assertFailsWith<IllegalStateException> { engine.line.start(request(check.link, taken.localPort)) }
            assertTrue(
                failed.message.orEmpty().startsWith("ghostlane-engine did not start the line: start: "),
                "${failed.message}\n${logTail()}"
            )
            assertFalse(engine.line.anyRunning())
        }
        assertEquals(emptyList(), sessions.toFile().listFiles().orEmpty().toList(), "a session file outlived its run")
    }

    // ---------------------------------------------------------------- clients

    /**
     * A SOCKS5 CONNECT to the check's target through [proxyHost]:[proxyPort],
     * offering [login] or no login. The socket is ready for the stream. Once
     * the proxy is reached, whatever keeps the CONNECT from being granted is
     * [Refused].
     */
    private fun socks5(proxyHost: String, proxyPort: Int, login: SocksLogin?): Socket {
        val socket = Socket()
        socket.connect(InetSocketAddress(proxyHost, proxyPort), 5_000)
        socket.soTimeout = 30_000
        try {
            val out = socket.getOutputStream()
            val input = DataInputStream(socket.getInputStream())
            out.write(byteArrayOf(5, 1, if (login == null) 0 else 2))
            out.flush()
            input.readUnsignedByte()
            val method = input.readUnsignedByte()
            if (method == 0xff) throw Refused("no method the client offers is accepted")
            if (login != null) {
                if (method != 2) throw Refused("the server chose method $method, not the login")
                val user = login.username.toByteArray()
                val pass = login.password.toByteArray()
                out.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass)
                out.flush()
                input.readUnsignedByte()
                if (input.readUnsignedByte() != 0) throw Refused("the login is refused")
            } else if (method != 0) {
                throw Refused("the server chose method $method")
            }
            val address = InetAddress.getByName(check.target).address
            out.write(byteArrayOf(5, 1, 0, 1) + address + byteArrayOf((check.targetPort shr 8).toByte(), check.targetPort.toByte()))
            out.flush()
            val reply = ByteArray(4)
            input.readFully(reply)
            if (reply[1].toInt() != 0) throw Refused("CONNECT answered ${reply[1]}")
            when (reply[3].toInt()) {
                1 -> input.readFully(ByteArray(6))
                4 -> input.readFully(ByteArray(18))
                3 -> input.readFully(ByteArray(input.readUnsignedByte() + 2))
                else -> throw Refused("CONNECT answered an address of type ${reply[3]}")
            }
            return socket
        } catch (e: Refused) {
            socket.close()
            throw e
        } catch (e: EOFException) {
            socket.close()
            throw Refused("the server closed the connection in the handshake")
        } catch (e: SocketException) {
            socket.close()
            throw Refused("the server cut the connection in the handshake: ${e.message}")
        }
    }

    /** An HTTP CONNECT to the check's target through the engine's port, with no login. */
    private fun httpConnect(port: Int): Socket {
        val socket = Socket()
        socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
        socket.soTimeout = 30_000
        val target = "${check.target}:${check.targetPort}"
        socket.getOutputStream().apply {
            write("CONNECT $target HTTP/1.1\r\nHost: $target\r\n\r\n".toByteArray())
            flush()
        }
        val head = readHead(socket.getInputStream())
        if (!head.startsWith("HTTP/1.") || head.split(' ').getOrNull(1) != "200") {
            socket.close()
            throw Refused("CONNECT answered ${head.lineSequence().first()}")
        }
        return socket
    }

    /** The page by a plain HTTP GET for its URL, sent to the engine's port as to an HTTP proxy. */
    private fun plainGet(port: Int): ByteArray = Socket().use { socket ->
        socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
        socket.soTimeout = 30_000
        socket.getOutputStream().apply {
            write("GET http://${check.target}/down HTTP/1.1\r\nHost: ${check.target}\r\nConnection: close\r\n\r\n".toByteArray())
            flush()
        }
        readBody(socket.getInputStream())
    }

    private fun get(socket: Socket, path: String): ByteArray {
        socket.getOutputStream().apply {
            write("GET $path HTTP/1.1\r\nHost: ${check.target}\r\nConnection: close\r\n\r\n".toByteArray())
            flush()
        }
        return readBody(socket.getInputStream())
    }

    private fun post(socket: Socket, path: String, body: ByteArray): String {
        socket.getOutputStream().apply {
            write(
                ("POST $path HTTP/1.1\r\nHost: ${check.target}\r\nContent-Type: application/octet-stream\r\n" +
                    "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray()
            )
            write(body)
            flush()
        }
        return String(readBody(socket.getInputStream())).trim()
    }

    /** The status line and headers, up to the empty line. */
    private fun readHead(input: InputStream): String {
        val head = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val b = input.read()
            if (b < 0) throw EOFException("the answer ended in its head")
            head.write(b)
            matched = if (b == "\r\n\r\n"[matched].code) matched + 1 else if (b == '\r'.code) 1 else 0
        }
        return head.toString(Charsets.ISO_8859_1)
    }

    private fun readBody(input: InputStream): ByteArray {
        val head = readHead(input)
        val status = head.lineSequence().first()
        if (status.split(' ').getOrNull(1) != "200") throw IOException("the page answered $status")
        val length = head.lineSequence()
            .firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toInt()
        val body = DataInputStream(input)
        return if (length != null) ByteArray(length).also { body.readFully(it) } else input.readBytes()
    }

    // ---------------------------------------------------------------- the rest

    private fun <T> eventually(tries: Int = 3, body: () -> T): T {
        var last: IOException? = null
        repeat(tries) {
            try {
                return body()
            } catch (e: IOException) {
                last = e
                Thread.sleep(1_000)
            }
        }
        fail("failed $tries times, last: ${last?.message}\n${logTail()}", last)
    }

    private fun waitForPort(port: Int, alive: () -> Boolean, timeoutMs: Long = 20_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!alive()) return false
            val open = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) } }.isSuccess
            if (open) return true
            Thread.sleep(100)
        }
        return false
    }

    private fun freePort(address: String): Int =
        ServerSocket(0, 1, InetAddress.getByName(address)).use { it.localPort }

    private fun logTail(lines: Int = 40): String = synchronized(log) { log.takeLast(lines).joinToString("\n") }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun pattern(size: Int): ByteArray = ByteArray(size) { (it * 31 + 7).toByte() }

    private fun token(): String = ByteArray(8).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

    private companion object {
        /** The file proxy.sh writes the check's links, names and page into. */
        const val CHECK_ENV = "HOST_ENGINE_CHECK"
    }
}
