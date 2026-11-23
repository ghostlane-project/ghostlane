package org.olcbox.app.vpn.desktop.engine.host

import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.olcbox.app.net.LinkParser
import org.olcbox.app.net.TransportProbe
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The room check and the probes on ghostlane-engine itself, the Core build's
 * program at the pin, run as the app runs it: a child with a session file.
 *
 * The rooms are on the `none` carrier, the interop pipe: a room is a hub's
 * `tcp://host:port/name`, and the hub here is a stand-in on loopback that reads
 * each participant's JOIN and then holds the connection or ends the
 * conference. It is the only carrier these tests reach: no live carrier's API
 * is asked, nowhere. No olcRTC server is in the rooms, so a join never gets
 * past its hello, and what is asked is what the app does with an answer that
 * does not come: it joins the room the session names, says no, and says why.
 *
 * A line is a VLESS line over plain TCP on loopback whose far end is in this
 * process.
 *
 * Run by scripts/desktop-checks/probe.sh (rust-desktop.yml), the program being
 * [HostAssets.ENV]'s. Anywhere else each test is skipped, and probe.sh fails a
 * run in which one was skipped or did not run.
 */
class HostProbeRealTest {
    private lateinit var sessions: Path
    private lateinit var binary: Path

    @BeforeTest
    fun onlyUnderTheCheck() {
        Assume.assumeTrue("set by scripts/desktop-checks/probe.sh", !System.getenv(CHECK_ENV).isNullOrBlank())
        binary = Path.of(requireNotNull(System.getenv(HostAssets.ENV)) { "${HostAssets.ENV} names the program" })
        sessions = Files.createTempDirectory("host-probe-real")
    }

    @AfterTest
    fun clean() {
        if (::sessions.isInitialized) sessions.toFile().deleteRecursively()
    }

    private fun probe() = HostProbe(binary = { binary }, sessionParent = sessions)

    private fun room(hub: Hub, name: String, key: String = KEY) =
        HostProbe.Room(provider = "none", rooms = listOf(hub.url(name)), keyHex = key, deviceId = "probe-check")

    private fun leftOver() = sessions.toFile().listFiles().orEmpty().toList()

    // ---- the room check, against the none carrier ----

    @Test
    fun aCheckJoinsTheRoomTheLocationNamesAndSaysNoWhenNobodyAnswers() {
        val quiet = Hub(Then.Hold)
        quiet.use {
            val started = System.currentTimeMillis()
            val result = probe().check(room(quiet, "l5-check"), timeoutMs = 1_500)
            assertNull(result.millis, "$result")
            assertTrue(result.reason.orEmpty().contains("timed out"), "$result")
            assertTrue(System.currentTimeMillis() - started < 20_000)
            assertEquals("l5-check", quiet.joined().room, "it joined the room")
        }
        assertEquals(emptyList(), leftOver(), "a session file outlived its question")
    }

    @Test
    fun aRoomThatEndsAtOnceIsANoAtOnce() {
        Hub(Then.End).use { ending ->
            val started = System.currentTimeMillis()
            val result = probe().check(room(ending, "l5-ends"), timeoutMs = 15_000)
            assertNull(result.millis, "$result")
            assertNotNull(result.reason, "$result")
            assertTrue(System.currentTimeMillis() - started < 12_000, "it waited for the whole time")
            assertEquals("l5-ends", ending.joined().room)
        }
    }

    @Test
    fun aPingJoinsTheRoomToo() {
        Hub(Then.End).use { hub ->
            val result = probe().ping(room(hub, "l5-ping"), timeoutMs = 15_000, url = "http://127.0.0.1:9/")
            assertNull(result.millis, "$result")
            assertNotNull(result.reason, "$result")
            assertEquals("l5-ping", hub.joined().room)
        }
    }

    @Test
    fun aKeyTheEngineRefusesIsItsOwnSentenceAndNoJoin() {
        Hub(Then.Hold).use { hub ->
            val result = probe().check(room(hub, "l5-bad", key = "zz"), timeoutMs = 5_000)
            assertNull(result.millis)
            assertTrue(result.reason.orEmpty().startsWith("room: \$.key: "), "$result")
            assertFalse(result.reason.orEmpty().contains("zz,"), "no key in a sentence")
            hub.nobodyJoined()
        }
    }

    // ---- a line ----

    @Test
    fun aLineProbeIsAnsweredByTheLineAndOnlyByIt() {
        val origin = Origin()
        val line = LineServer()
        try {
            val url = "http://${origin.address}/generate_204"
            val result = probe().probe(coreOf(line.address, socksPort = 0), url, timeoutMs = 10_000, bindInterface = null)
            assertNotNull(result.millis, "$result")
            assertNull(result.reason)
            assertEquals(1, line.carried.get(), "it went by the line")
            assertEquals(emptyList(), leftOver())
        } finally {
            line.close()
            origin.close()
        }
    }

    @Test
    fun aDeadLineIsANoWithAReason() {
        val dead = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { InetSocketAddress("127.0.0.1", it.localPort) }
        val result = probe().probe(coreOf(dead, socksPort = 0), "http://127.0.0.1:9/", timeoutMs = 3_000, bindInterface = null)
        assertNull(result.millis, "$result")
        assertTrue(result.reason.orEmpty().isNotBlank(), "$result")
    }

    // ---- the starter smart connect gets ----

    @Test
    fun theStarterServesTheProbesPortBehindItsLoginAndStopsWithTheProbe(): Unit = runBlocking {
        val origin = Origin()
        val line = LineServer()
        try {
            val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            val config = coreOf(line.address, socksPort = port, login = "probe-user" to "probe-secret")
            val spec = requireNotNull(LinkParser.parse("vless://$USER@127.0.0.1:${line.address.port}?security=none&encryption=none&type=tcp#line"))
            val starter = probe().starter()
            val core = starter.start(spec, config, null)
            try {
                assertTrue(core.isRunning())
                assertEquals(
                    Origin.BODY.size,
                    Socks.fetch("127.0.0.1", port, "probe-user", "probe-secret", origin.address).size,
                    "the page came through the probe's port, with its login",
                )
                assertEquals(1, line.carried.get(), "it went by the line")
                assertFalse(Socks.accepts("127.0.0.1", port, "probe-user", "not-the-secret"), "a wrong login is refused")
            } finally {
                core.stop()
            }
            assertFalse(core.isRunning())
            assertEquals(emptyList(), leftOver(), "a session file outlived its probe")
            assertFalse(Socks.listening("127.0.0.1", port), "the probe's port is closed once it is stopped")
        } finally {
            line.close()
            origin.close()
        }
    }

    @Test
    fun theKeptStarterIsStoppedByTheSessionsStop(): Unit = runBlocking {
        val line = LineServer()
        try {
            val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            val spec = requireNotNull(LinkParser.parse("vless://$USER@127.0.0.1:${line.address.port}?security=none&encryption=none&type=tcp#line"))
            val engine = probe().probes()
            val kept: TransportProbe.Core = engine.kept.start(spec, coreOf(line.address, socksPort = port, login = "u" to "p"))
            assertTrue(kept.isRunning())
            engine.stopKept()
            assertFalse(kept.isRunning())
        } finally {
            line.close()
        }
    }

    // ---- stand-ins ----

    private enum class Then { Hold, End }

    /** One participant of a hub: the room its JOIN named. */
    private class Joined(val room: String)

    /**
     * An interop-pipe hub on loopback: frames of `u32 BE length | kind |
     * payload`, the first of a participant's a JOIN (kind 1) naming its room.
     */
    private class Hub(private val then: Then) : AutoCloseable {
        private val listener = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val joins = LinkedBlockingQueue<Joined>()
        private val open = java.util.Collections.synchronizedList(mutableListOf<Socket>())

        init {
            Thread({
                while (true) {
                    val stream = try { listener.accept() } catch (_: IOException) { break }
                    open += stream
                    Thread({ take(stream) }, "hub").apply { isDaemon = true; start() }
                }
            }, "hub-accept").apply { isDaemon = true; start() }
        }

        fun url(name: String) = "tcp://127.0.0.1:${listener.localPort}/$name"

        private fun take(stream: Socket) {
            try {
                val input = DataInputStream(stream.getInputStream())
                val (kind, room) = frame(input) ?: return
                check(kind == KIND_JOIN) { "a participant's first frame is its JOIN" }
                joins.add(Joined(String(room, Charsets.UTF_8)))
                if (then == Then.End) {
                    val reason = "the conference ended".toByteArray()
                    val out = stream.getOutputStream()
                    out.write(java.nio.ByteBuffer.allocate(4).putInt(1 + reason.size).array())
                    out.write(KIND_END)
                    out.write(reason)
                    out.flush()
                }
                while (frame(input) != null) Unit
            } catch (_: IOException) {
                // The engine left.
            }
        }

        private fun frame(input: DataInputStream): Pair<Int, ByteArray>? = try {
            val length = input.readInt()
            val kind = input.readUnsignedByte()
            Pair(kind, ByteArray(length - 1).also { input.readFully(it) })
        } catch (_: EOFException) {
            null
        }

        fun joined(): Joined = joins.poll(20, TimeUnit.SECONDS) ?: fail("nobody joined a room of the hub within 20 s")

        fun nobodyJoined() {
            val joined = joins.poll(300, TimeUnit.MILLISECONDS)
            assertNull(joined, "an unexpected join of room ${joined?.room}")
        }

        override fun close() {
            runCatching { listener.close() }
            synchronized(open) { open.forEach { runCatching { it.close() } } }
        }

        companion object {
            const val KIND_JOIN = 1
            const val KIND_END = 5
        }
    }

    /** An HTTP origin on loopback: every request is answered with [BODY]. */
    private class Origin : AutoCloseable {
        private val listener = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val address = InetSocketAddress("127.0.0.1", listener.localPort)

        init {
            Thread({
                while (true) {
                    val stream = try { listener.accept() } catch (_: IOException) { break }
                    Thread({
                        stream.use {
                            val input = it.getInputStream()
                            val head = StringBuilder()
                            while (!head.endsWith("\r\n\r\n")) {
                                val b = input.read()
                                if (b < 0) return@use
                                head.append(b.toChar())
                            }
                            val out = it.getOutputStream()
                            out.write("HTTP/1.1 200 OK\r\nContent-Length: ${BODY.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            out.write(BODY)
                            out.flush()
                        }
                    }, "origin").apply { isDaemon = true; start() }
                }
            }, "origin-accept").apply { isDaemon = true; start() }
        }

        override fun close() {
            runCatching { listener.close() }
        }

        companion object {
            val BODY = ByteArray(16 * 1024) { (it % 251).toByte() }
        }
    }

    /**
     * The far end of a VLESS line over plain TCP that carries each request to
     * its target and counts it: the header (version 0, the user's id, no
     * addons, a TCP command, an IPv4 target), the answer `00 00`, then bytes
     * both ways.
     */
    private class LineServer : AutoCloseable {
        private val listener = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val address = InetSocketAddress("127.0.0.1", listener.localPort)
        val carried = AtomicInteger()

        init {
            Thread({
                while (true) {
                    val client = try { listener.accept() } catch (_: IOException) { break }
                    Thread({ runCatching { carry(client) } }, "line").apply { isDaemon = true; start() }
                }
            }, "line-accept").apply { isDaemon = true; start() }
        }

        private fun carry(client: Socket) {
            client.use {
                val input = DataInputStream(it.getInputStream())
                val head = ByteArray(18)
                input.readFully(head)
                input.readFully(ByteArray(head[17].toInt() and 0xff))
                val request = ByteArray(4)
                input.readFully(request)
                check(request[0].toInt() == 1 && request[3].toInt() == 1) { "a TCP request to an IPv4 target" }
                val ip = ByteArray(4)
                input.readFully(ip)
                val port = ((request[1].toInt() and 0xff) shl 8) or (request[2].toInt() and 0xff)
                Socket(InetAddress.getByAddress(ip), port).use { target ->
                    it.getOutputStream().apply { write(byteArrayOf(0, 0)); flush() }
                    carried.incrementAndGet()
                    val up = Thread({ runCatching { input.copyTo(target.getOutputStream()); target.shutdownOutput() } }, "line-up")
                        .apply { isDaemon = true; start() }
                    runCatching { target.getInputStream().copyTo(it.getOutputStream()) }
                    runCatching { it.shutdownOutput() }
                    up.join(5_000)
                }
            }
        }

        override fun close() {
            runCatching { listener.close() }
        }
    }

    /** A SOCKS5 client with a login, as much as these tests ask of one. */
    private object Socks {
        fun listening(host: String, port: Int): Boolean =
            runCatching { Socket().use { it.connect(InetSocketAddress(host, port), 500) } }.isSuccess

        /** Whether the server takes the login. */
        fun accepts(host: String, port: Int, user: String, pass: String): Boolean = runCatching {
            Socket().use { s ->
                s.soTimeout = 5_000
                s.connect(InetSocketAddress(host, port), 2_000)
                login(s, user, pass)
            }
        }.getOrDefault(false)

        /** One HTTP GET of [target] through the server; the body. */
        fun fetch(host: String, port: Int, user: String, pass: String, target: InetSocketAddress): ByteArray =
            Socket().use { s ->
                s.soTimeout = 10_000
                s.connect(InetSocketAddress(host, port), 2_000)
                check(login(s, user, pass)) { "the login was refused" }
                val out = s.getOutputStream()
                val input = DataInputStream(s.getInputStream())
                val ip = target.address.address
                out.write(byteArrayOf(5, 1, 0, 1) + ip + byteArrayOf((target.port shr 8).toByte(), target.port.toByte()))
                out.flush()
                val reply = ByteArray(10)
                input.readFully(reply)
                check(reply[1].toInt() == 0) { "CONNECT was refused: ${reply[1]}" }
                out.write("GET / HTTP/1.1\r\nHost: origin\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                val all = readAll(input)
                val split = String(all, Charsets.ISO_8859_1).indexOf("\r\n\r\n")
                check(split >= 0) { "no HTTP answer" }
                all.copyOfRange(split + 4, all.size)
            }

        private fun login(s: Socket, user: String, pass: String): Boolean {
            val out = s.getOutputStream()
            val input = DataInputStream(s.getInputStream())
            out.write(byteArrayOf(5, 1, 2))
            out.flush()
            val method = ByteArray(2)
            input.readFully(method)
            if (method[1].toInt() != 2) return false
            val u = user.toByteArray()
            val p = pass.toByteArray()
            out.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p)
            out.flush()
            val status = ByteArray(2)
            input.readFully(status)
            return status[1].toInt() == 0
        }

        private fun readAll(input: InputStream): ByteArray {
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val n = try { input.read(chunk) } catch (_: IOException) { -1 }
                if (n < 0) break
                buffer.write(chunk, 0, n)
            }
            return buffer.toByteArray()
        }
    }

    /**
     * A core half: a SOCKS5 listener on [socksPort] (0: the system picks;
     * [login] makes it demand one) and a VLESS outbound to [line], tagged
     * `proxy`, over plain TCP.
     */
    private fun coreOf(line: InetSocketAddress, socksPort: Int, login: Pair<String, String>? = null): String {
        val settings = if (login == null) "" else
            ""","settings":{"auth":"password","accounts":[{"user":"${login.first}","pass":"${login.second}"}]}"""
        return """{"inbounds":[{"tag":"socks-in","protocol":"socks","listen":"127.0.0.1","port":$socksPort$settings}],""" +
            """"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"${line.address.hostAddress}","port":${line.port},""" +
            """"users":[{"id":"$USER","encryption":"none"}]}]},"streamSettings":{"network":"tcp"}}]}"""
    }

    private companion object {
        const val CHECK_ENV = "HOST_PROBE_CHECK"
        const val USER = "00010203-0405-0607-0809-0a0b0c0d0e0f"
        const val KEY = "4f6c635254432d74657374206b65792c206e6f7420612073656372657421212e"
    }
}
