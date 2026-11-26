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
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * LAN sharing on ghostlane-engine, the Core build's program at the pin (D5):
 * HostEngine starts the line's own core with CoreInbound.Lan on a private
 * address of this machine (the near end of a veth pair), DesktopLanProxy
 * starts no second process and binds no port of its own, and
 * scripts/desktop-checks/lan.sh sends a client from the far end, in a network
 * namespace of its own, through the line to Xray 26.4.25.
 *
 * Run by lan.sh (rust-desktop.yml), which writes what these tests read into
 * the file [CHECK_ENV] names, the program being GHOSTLANE_ENGINE_BINARY.
 * Anywhere else each test is skipped, and lan.sh fails a run in which one was
 * skipped or did not run.
 */
class DesktopLanProxyRealTest {
    private class Check(
        val link: String,
        val lanAddress: String,
        val lanPort: Int,
        val lanUser: String,
        val lanPass: String,
        val ready: File,
        val done: File,
    ) {
        companion object {
            fun read(file: File): Check {
                val o = Json.parseToJsonElement(file.readText()).jsonObject
                fun s(name: String) = o.getValue(name).jsonPrimitive.content
                fun i(name: String) = o.getValue(name).jsonPrimitive.int
                return Check(
                    link = s("link"),
                    lanAddress = s("lan_address"),
                    lanPort = i("lan_port"),
                    lanUser = s("lan_user"),
                    lanPass = s("lan_pass"),
                    ready = File(s("ready")),
                    done = File(s("done")),
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

    @Test
    fun aLanClientWithTheLoginIsCarriedByTheLinesCoreAndOneWithoutIsRefused(): Unit = runBlocking {
        val lanSettings = settings(check.lanPort)
        val proxy = DesktopLanProxy(::say, LanCores.rust(), settings = { lanSettings })
        val engine = HostEngine(engineHost, sessionParent = sessions)
        val ownLogin = SocksLogin("tun-user", "tun-pass")
        val ownPort = freeOwnPort(check.lanPort)
        val started = engine.line.start(request(ownPort, ownLogin))
        try {
            assertTrue(waitForListener("127.0.0.1", ownPort, started.alive), "the line's own port did not open\n${tail()}")
            assertTrue(waitForListener(check.lanAddress, check.lanPort, started.alive), "the LAN port did not open\n${tail()}")
            val upstream = SubscriptionFetchProxy("127.0.0.1", ownPort, ownLogin.username, ownLogin.password)
            val endpoint = proxy.start(lanSettings, upstream)
            assertEquals("${check.lanAddress}:${check.lanPort}", endpoint, tail())
            assertTrue(proxy.isRunning(), "DesktopLanProxy is active\n${tail()}")
            assertEquals(1, engine.line.runningProcesses().size, "only the line's own engine runs\n${tail()}")
            check.ready.writeText("ready")
            val deadline = System.nanoTime() + 240_000_000_000L
            while (!check.done.exists()) {
                if (System.nanoTime() > deadline) fail("the client did not finish in 240 s\n${tail()}")
                assertTrue(started.alive(), "the line's engine ended while the client was served\n${tail()}")
                assertTrue(proxy.isRunning(), "DesktopLanProxy stopped while the client was served\n${tail()}")
                Thread.sleep(200)
            }
            assertTrue(started.alive(), "the line's engine ended while the client was served\n${tail()}")
        } finally {
            proxy.stop()
            engine.line.stopNow()
        }
        assertFalse(proxy.isRunning(), "a stop ends LAN sharing")
        assertFalse(started.alive(), "a stop ends the line's engine")
        assertFalse(engine.line.anyRunning(), "no engine runs after the stop")
        assertEquals(0, started.exitCode(), "the engine exited cleanly\n${tail()}")
        assertFalse(portOpen(check.lanAddress, check.lanPort), "the LAN port is closed once the line stops")
    }

    @Test
    fun aPortAnotherProgramHoldsFailsTheLinesStart(): Unit = runBlocking {
        bindLanSocket().use { held ->
            val lanSettings = settings(held.localPort)
            DesktopLanProxy(::say, LanCores.rust(), settings = { lanSettings })
            val engine = HostEngine(engineHost, sessionParent = sessions)
            val ownPort = freeOwnPort(held.localPort)
            try {
                val failure = assertFailsWith<IllegalStateException> {
                    engine.line.start(request(ownPort, SocksLogin("tun-user", "tun-pass")))
                }
                assertTrue(
                    failure.message.orEmpty().startsWith("ghostlane-engine did not start the line: start: "),
                    "${failure.message}\n${tail()}",
                )
                assertFalse(engine.line.anyRunning())
            } finally {
                engine.line.stopNow()
            }
        }
    }

    private companion object {
        const val CHECK_ENV = "LAN_CHECK"
    }
}
