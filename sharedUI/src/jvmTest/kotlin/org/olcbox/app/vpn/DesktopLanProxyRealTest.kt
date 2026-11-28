package org.olcbox.app.vpn

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import org.olcbox.app.net.LinkParser
import org.olcbox.app.net.Routing
import org.olcbox.app.net.SocksLogin
import org.olcbox.app.vpn.desktop.PacServer
import org.olcbox.app.vpn.desktop.engine.DesktopEngineHost
import org.olcbox.app.vpn.desktop.engine.LineRequest
import org.olcbox.app.vpn.desktop.engine.SessionHooks
import org.olcbox.app.vpn.desktop.engine.host.HostEngine
import org.olcbox.app.vpn.desktop.engine.host.HostLanChain
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * LAN sharing on ghostlane-engine, the Core build's program at the pin (D5),
 * in the three ways a session can carry it, each served to a client from the
 * far end of a veth pair in a network namespace of its own
 * (scripts/desktop-checks/lan.sh):
 *  - a core line in proxy mode holds the LAN inbound in its own core;
 *    DesktopLanProxy starts no second process and binds no port;
 *  - a room: its SOCKS port with its login (a stand-in for the room's port,
 *    as the olcRTC engine opens it) has no LAN inbound, so one engine run
 *    chained to that port carries LAN;
 *  - a line started without the inbound (what the Go fallback was for: LAN
 *    switched on after the connect, a tunnel the helper started): one engine
 *    run chained to the line's port.
 *
 * Run by lan.sh (rust-desktop.yml), which writes what these tests read into
 * the file [CHECK_ENV] names, the program being GHOSTLANE_ENGINE_BINARY.
 * Each test writes `ready.<case>` and waits for `done.<case>`. Anywhere else
 * each test is skipped, and lan.sh fails a run in which one was skipped or did
 * not run.
 */
class DesktopLanProxyRealTest {
    private class Check(
        val link: String,
        val lanAddress: String,
        val lanPorts: Map<String, Int>,
        val roomPort: Int,
        val roomUser: String,
        val roomPass: String,
        val targetIp: String,
        val pagePort: Int,
        val lanUser: String,
        val lanPass: String,
        val ready: String,
        val done: String,
    ) {
        fun ready(case: String) = File("$ready.$case")
        fun done(case: String) = File("$done.$case")

        companion object {
            fun read(file: File): Check {
                val o = Json.parseToJsonElement(file.readText()).jsonObject
                fun s(name: String) = o.getValue(name).jsonPrimitive.content
                fun i(name: String) = o.getValue(name).jsonPrimitive.int
                return Check(
                    link = s("link"),
                    lanAddress = s("lan_address"),
                    lanPorts = o.getValue("lan_ports").jsonObject.mapValues { it.value.jsonPrimitive.int },
                    roomPort = i("room_port"),
                    roomUser = s("room_user"),
                    roomPass = s("room_pass"),
                    targetIp = s("target"),
                    pagePort = i("page_port"),
                    lanUser = s("lan_user"),
                    lanPass = s("lan_pass"),
                    ready = s("ready"),
                    done = s("done"),
                )
            }
        }
    }

    private lateinit var check: Check
    private lateinit var sessions: Path
    private lateinit var previousSettings: () -> DesktopSocksProxySettings
    private val log = mutableListOf<String>()

    private fun say(line: String) {
        synchronized(log) { log += line }
        println(line)
    }

    private val engineHost = DesktopEngineHost(
        log = ::say,
        windowsTunOutput = {},
        session = object : SessionHooks {
            override fun lineDied(what: String) {}
            override fun wayOutChanged(interfaceName: String?) {}
        },
    )

    @BeforeTest
    fun onlyUnderTheCheck() {
        val file = System.getenv(CHECK_ENV)
        Assume.assumeTrue("set by scripts/desktop-checks/lan.sh", !file.isNullOrBlank())
        check = Check.read(File(file))
        sessions = Files.createTempDirectory("lan-proxy-real")
        previousSettings = LanCores.settings
    }

    @AfterTest
    fun clean() {
        if (::previousSettings.isInitialized) LanCores.settings = previousSettings
        if (::sessions.isInitialized) sessions.toFile().deleteRecursively()
    }

    private fun settings(port: Int): DesktopSocksProxySettings {
        val network = DesktopLanProxy.networkIdentity(check.lanAddress)
            ?: fail("this machine's default gateway has no neighbour entry: LAN sharing cannot pin the network")
        return DesktopSocksProxySettings(
            shareOnLan = true,
            lanAddress = check.lanAddress,
            lanNetworkId = network,
            lanPort = port,
            lanUsername = check.lanUser,
            lanPassword = check.lanPass,
        )
    }

    private fun request(port: Int, login: SocksLogin) = LineRequest(
        spec = requireNotNull(LinkParser.parse(check.link)) { "the check's link does not parse" },
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

    private fun freeOwnPort(avoid: Int): Int {
        repeat(8) {
            val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            if (port != avoid && port != PacServer.LOCAL_SOCKS_PORT) return port
        }
        error("could not draw a loopback port distinct from $avoid")
    }

    private fun bindLanSocket(): ServerSocket {
        repeat(8) {
            val socket = ServerSocket().apply {
                reuseAddress = false
                bind(InetSocketAddress(check.lanAddress, 0))
            }
            if (socket.localPort != PacServer.LOCAL_SOCKS_PORT) return socket
            socket.close()
        }
        error("could not bind a distinct LAN port on ${check.lanAddress}")
    }

    private fun tail(): String = synchronized(log) { log.takeLast(40).joinToString("\n") }

    private fun portOpen(host: String, port: Int): Boolean =
        runCatching { Socket().use { it.connect(InetSocketAddress(host, port), 500) } }.isSuccess

    private fun waitForListener(host: String, port: Int, alive: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 20_000L
        while (System.currentTimeMillis() < deadline) {
            if (!alive()) return false
            if (portOpen(host, port)) return true
            Thread.sleep(100)
        }
        return false
    }

    /** A fetch of the check's page through the proxy at [port] (the way the manager proves a listener is its own). */
    private fun fetchThrough(port: Int, user: String, pass: String): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 2_000)
            socket.soTimeout = 15_000
            val out = socket.getOutputStream()
            val input = socket.getInputStream()
            out.write(byteArrayOf(5, 1, 2)); out.flush()
            if (input.readNBytes(2)[1] != 2.toByte()) return@use false
            val u = user.toByteArray()
            val p = pass.toByteArray()
            out.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p); out.flush()
            if (input.readNBytes(2)[1] != 0.toByte()) return@use false
            val address = InetAddress.getByName(check.targetIp).address
            out.write(byteArrayOf(5, 1, 0, 1) + address + byteArrayOf((check.pagePort shr 8).toByte(), check.pagePort.toByte()))
            out.flush()
            val head = input.readNBytes(4)
            if (head[1] != 0.toByte()) return@use false
            input.readNBytes(if (head[3] == 4.toByte()) 18 else 6)
            out.write("GET /ping HTTP/1.0\r\nHost: lan-check\r\n\r\n".toByteArray()); out.flush()
            String(input.readAllBytes()).startsWith("HTTP/1.")
        }
    }.getOrDefault(false)

    /** DesktopLanProxy as the manager builds it, its listener's ownership proved by a fetch of the check's page. */
    private fun proxyOf(settings: DesktopSocksProxySettings, chain: HostLanChain) = DesktopLanProxy(
        ::say, chain, settings = { settings },
        proveOwnership = { listener, port, user, pass ->
            DesktopLanProxy.proveOwnershipOf(listener, port, user, pass) { p, u, w -> fetchThrough(p, u, w) }
        },
    )

    /** Tells lan.sh the listener is up, and holds it up until the client at the far end is done. */
    private fun serveUntilDone(case: String, alive: () -> Boolean, what: String) {
        check.ready(case).writeText("ready")
        val deadline = System.nanoTime() + 240_000_000_000L
        while (!check.done(case).exists()) {
            if (System.nanoTime() > deadline) fail("the client did not finish in 240 s\n${tail()}")
            assertTrue(alive(), "$what ended while the client was served\n${tail()}")
            Thread.sleep(200)
        }
        assertTrue(alive(), "$what ended while the client was served\n${tail()}")
    }

    @Test
    fun aCoreLineInProxyModeCarriesLanInItsOwnCoreAndNoSecondEngineRuns(): Unit = runBlocking {
        val port = check.lanPorts.getValue("core")
        val lanSettings = settings(port)
        val chain = HostLanChain(::say, sessionParent = sessions)
        val proxy = proxyOf(lanSettings, chain)
        val engine = HostEngine(engineHost, sessionParent = sessions)
        val ownLogin = SocksLogin("tun-user", "tun-pass")
        val ownPort = freeOwnPort(port)
        val started = engine.line.start(request(ownPort, ownLogin))
        try {
            assertTrue(waitForListener("127.0.0.1", ownPort, started.alive), "the line's own port did not open\n${tail()}")
            assertTrue(waitForListener(check.lanAddress, port, started.alive), "the LAN port did not open\n${tail()}")
            val upstream = SubscriptionFetchProxy("127.0.0.1", ownPort, ownLogin.username, ownLogin.password)
            val endpoint = proxy.start(lanSettings, upstream)
            assertEquals("${check.lanAddress}:$port", endpoint, tail())
            assertTrue(proxy.isRunning(), "DesktopLanProxy is active\n${tail()}")
            assertEquals(1, engine.line.runningProcesses().size, "only the line's own engine runs\n${tail()}")
            assertEquals(0, chain.runningProcesses().size, "no engine is run for LAN\n${tail()}")
            serveUntilDone("core", { started.alive() && proxy.isRunning() }, "the line's engine or DesktopLanProxy")
        } finally {
            proxy.stop()
            engine.line.stopNow()
        }
        assertFalse(proxy.isRunning(), "a stop ends LAN sharing")
        assertFalse(started.alive(), "a stop ends the line's engine")
        assertFalse(engine.line.anyRunning(), "no engine runs after the stop")
        assertEquals(0, started.exitCode(), "the engine exited cleanly\n${tail()}")
        assertFalse(portOpen(check.lanAddress, port), "the LAN port is closed once the line stops")
    }

    /**
     * A room's port is the olcRTC engine's: a SOCKS5 listener on loopback with
     * a login, in no core that could hold the LAN inbound. The stand-in lan.sh
     * runs is that port, and one engine run, chained to it, is all LAN needs.
     */
    @Test
    fun aRoomsPortCarriesLanThroughOneEngineRunChainedToIt(): Unit = runBlocking {
        val port = check.lanPorts.getValue("room")
        val lanSettings = settings(port)
        val chain = HostLanChain(::say, sessionParent = sessions)
        val proxy = proxyOf(lanSettings, chain)
        // What the manager leaves where a room starts: no core holds the inbound.
        LanCores.lineStarted(null, "a line is being started")
        try {
            val room = SubscriptionFetchProxy("127.0.0.1", check.roomPort, check.roomUser, check.roomPass)
            assertEquals("${check.lanAddress}:$port", proxy.start(lanSettings, room), tail())
            assertTrue(proxy.isRunning(), "DesktopLanProxy is active\n${tail()}")
            assertEquals(1, chain.runningProcesses().size, "one engine run carries LAN\n${tail()}")
            serveUntilDone("room", { chain.isRunning() && proxy.isRunning() }, "the engine run")
        } finally {
            proxy.stop()
            LanCores.lineStopped()
        }
        assertFalse(proxy.isRunning(), "a stop ends LAN sharing")
        assertFalse(chain.isRunning(), "a stop ends the engine run")
        assertFalse(portOpen(check.lanAddress, port), "the LAN port is closed once LAN sharing stops")
    }

    /**
     * The case the Go fallback was for: a core line whose core holds no LAN
     * inbound (LAN switched on after it was started; a tunnel the helper
     * started). It keeps running untouched, and one engine run is chained to
     * its port.
     */
    @Test
    fun aLineStartedWithoutTheInboundIsLeftAloneAndCarriesLanThroughOneEngineRun(): Unit = runBlocking {
        val port = check.lanPorts.getValue("line")
        val lanSettings = settings(port)
        val chain = HostLanChain(::say, sessionParent = sessions)
        val proxy = proxyOf(lanSettings, chain)
        val engine = HostEngine(engineHost, sessionParent = sessions, lanSettings = { DesktopSocksProxySettings() })
        val ownLogin = SocksLogin("tun-user", "tun-pass")
        val ownPort = freeOwnPort(port)
        val started = engine.line.start(request(ownPort, ownLogin))
        try {
            assertTrue(waitForListener("127.0.0.1", ownPort, started.alive), "the line's own port did not open\n${tail()}")
            assertNull(LanCores.currentLine().lan, "the line's core holds no LAN inbound")
            assertFalse(portOpen(check.lanAddress, port), "nothing listens on the LAN port yet")
            val upstream = SubscriptionFetchProxy("127.0.0.1", ownPort, ownLogin.username, ownLogin.password)
            assertEquals("${check.lanAddress}:$port", proxy.start(lanSettings, upstream), tail())
            assertTrue(proxy.isRunning(), "DesktopLanProxy is active\n${tail()}")
            assertEquals(1, engine.line.runningProcesses().size, "the line's engine runs once\n${tail()}")
            assertEquals(1, chain.runningProcesses().size, "one more engine run carries LAN\n${tail()}")
            serveUntilDone("line", { started.alive() && chain.isRunning() && proxy.isRunning() }, "an engine")
        } finally {
            proxy.stop()
            engine.line.stopNow()
        }
        assertFalse(chain.isRunning(), "a stop ends the engine run")
        assertFalse(started.alive(), "a stop ends the line's engine")
        assertFalse(portOpen(check.lanAddress, port), "the LAN port is closed once LAN sharing stops")
    }

    /**
     * A LAN port another program holds costs LAN sharing, not the connection.
     * The line starts without that inbound, the proxy's own port check then
     * fails, and the manager would show LAN sharing as unavailable.
     */
    @Test
    fun aPortAnotherProgramHoldsStartsTheLineButLeavesLanSharingUnavailable(): Unit = runBlocking {
        bindLanSocket().use { held ->
            val lanSettings = settings(held.localPort)
            val proxy = DesktopLanProxy(::say, HostLanChain(::say, sessionParent = sessions), settings = { lanSettings })
            val engine = HostEngine(engineHost, sessionParent = sessions)
            val ownPort = freeOwnPort(held.localPort)
            try {
                val started = runCatching { engine.line.start(request(ownPort, SocksLogin("tun-user", "tun-pass"))) }
                    .getOrNull()
                assertNotNull(started, "the line starts even when LAN's port is held\n${tail()}")
                assertTrue(started.alive(), "the line keeps serving without LAN sharing\n${tail()}")
                assertTrue(engine.line.anyRunning())
                assertNull(LanCores.currentLine().lan, "the line's core holds no LAN inbound")
                assertTrue(
                    "already in use" in LanCores.currentLine().reason.orEmpty(),
                    "the session says why LAN sharing was left out",
                )
                assertTrue(portOpen("127.0.0.1", ownPort), "the line's own port is up\n${tail()}")

                val upstream = SubscriptionFetchProxy("127.0.0.1", ownPort, "tun-user", "tun-pass")
                val unavailable = assertFailsWith<IllegalStateException> { proxy.start(lanSettings, upstream) }
                assertTrue("already in use" in unavailable.message.orEmpty(), "${unavailable.message}\n${tail()}")
                assertFalse(proxy.isRunning(), "LAN sharing is not left running on a stranger's port")
                assertTrue(started.alive(), "a failed LAN listener does not take the line down\n${tail()}")
            } finally {
                proxy.stop()
                engine.line.stopNow()
            }
        }
    }

    private companion object {
        const val CHECK_ENV = "LAN_CHECK"
    }
}
