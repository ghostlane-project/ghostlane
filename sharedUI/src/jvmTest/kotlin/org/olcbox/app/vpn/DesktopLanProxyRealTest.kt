package org.olcbox.app.vpn

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import java.io.File
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
 * LAN sharing on ghostlane-engine, the Core build's program at the pin: the
 * app's own DesktopLanProxy starts it on a private address of this machine
 * (the near end of a veth pair), in front of the session's port, and
 * scripts/desktop-checks/lan.sh sends a client from the far end, in a
 * network namespace of its own, through it. The script owns the servers and
 * the client; this test owns the proxy: it starts, tells the script it is
 * ready, waits until the client is done, and ends the proxy.
 *
 * Run by lan.sh (rust-desktop.yml), which writes what these tests read into
 * the file [CHECK_ENV] names, the program being the one GHOSTLANE_ENGINE_BINARY
 * names. Anywhere else each test is skipped, and lan.sh fails a run in
 * which one was skipped or did not run.
 */
class DesktopLanProxyRealTest {
    private class Check(
        val lanAddress: String,
        val lanPort: Int,
        val lanUser: String,
        val lanPass: String,
        val upstreamPort: Int,
        val upstreamUser: String,
        val upstreamPass: String,
        val ready: File,
        val done: File,
    ) {
        companion object {
            fun read(file: File): Check {
                val o = Json.parseToJsonElement(file.readText()).jsonObject
                fun s(name: String) = o.getValue(name).jsonPrimitive.content
                fun i(name: String) = o.getValue(name).jsonPrimitive.int
                return Check(
                    lanAddress = s("lan_address"), lanPort = i("lan_port"), lanUser = s("lan_user"), lanPass = s("lan_pass"),
                    upstreamPort = i("upstream_port"), upstreamUser = s("upstream_user"), upstreamPass = s("upstream_pass"),
                    ready = File(s("ready")), done = File(s("done")),
                )
            }
        }
    }

    private lateinit var check: Check
    private lateinit var sessions: Path
    private val log = mutableListOf<String>()

    private fun say(line: String) {
        synchronized(log) { log += line }
        println(line)
    }

    @BeforeTest
    fun onlyUnderTheCheck() {
        val file = System.getenv(CHECK_ENV)
        Assume.assumeTrue("set by scripts/desktop-checks/lan.sh", !file.isNullOrBlank())
        check = Check.read(File(file))
        sessions = Files.createTempDirectory("lan-proxy-real")
    }

    @AfterTest
    fun clean() {
        if (::sessions.isInitialized) sessions.toFile().deleteRecursively()
    }

    private fun proxy() = DesktopLanProxy(::say, LanCores.rust(::say, sessionParent = sessions))

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

    private fun upstream() = SubscriptionFetchProxy("127.0.0.1", check.upstreamPort, check.upstreamUser, check.upstreamPass)

    private fun tail(): String = synchronized(log) { log.takeLast(30).joinToString("\n") }

    private fun portOpen(host: String, port: Int): Boolean =
        runCatching { Socket().use { it.connect(InetSocketAddress(host, port), 500) } }.isSuccess

    @Test
    fun aLanClientWithTheLoginIsCarriedAndOneWithoutIsRefused(): Unit = runBlocking {
        val proxy = proxy()
        try {
            val endpoint = proxy.start(settings(check.lanPort), upstream())
            assertEquals("${check.lanAddress}:${check.lanPort}", endpoint, tail())
            assertTrue(proxy.isRunning(), "the engine is running\n${tail()}")
            check.ready.writeText("ready")
            val deadline = System.nanoTime() + 240_000_000_000L
            while (!check.done.exists()) {
                if (System.nanoTime() > deadline) fail("the client did not finish in 240 s\n${tail()}")
                assertTrue(proxy.isRunning(), "the engine ended while the client was served\n${tail()}")
                Thread.sleep(200)
            }
            assertTrue(proxy.isRunning(), "the engine ended while the client was served\n${tail()}")
        } finally {
            proxy.stop()
        }
        assertFalse(proxy.isRunning(), "a stop ends the engine")
        assertFalse(portOpen(check.lanAddress, check.lanPort), "the LAN port is closed once sharing stops")
    }

    @Test
    fun aPortAnotherProgramHoldsFailsTheStart(): Unit = runBlocking {
        val held = ServerSocket().apply {
            reuseAddress = false
            bind(InetSocketAddress(check.lanAddress, 0))
        }
        val proxy = proxy()
        try {
            val failure = assertFailsWith<IllegalStateException> { proxy.start(settings(held.localPort), upstream()) }
            assertTrue("already in use" in failure.message.orEmpty(), failure.message)
            assertFalse(proxy.isRunning())
        } finally {
            proxy.stop()
            held.close()
        }
    }

    private companion object {
        const val CHECK_ENV = "LAN_CHECK"
    }
}
