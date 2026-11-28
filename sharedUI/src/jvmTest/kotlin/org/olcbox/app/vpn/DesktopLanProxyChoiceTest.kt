package org.olcbox.app.vpn

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import org.olcbox.app.net.SocksLogin
import org.olcbox.app.net.core.CoreInbound
import org.olcbox.app.vpn.desktop.LineSupervision
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
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

/**
 * Which core carries LAN sharing in each session the desktop can be in, with
 * no engine run: the session's own core when it holds the LAN inbound, and the
 * engine run of [LanCores.chain] (here a stand-in that records what it is
 * given) in every case that does not. No case is handed to a Go core, and none
 * is left with no listener: the chain either serves or fails with a sentence
 * the manager logs and shows in the LAN block.
 */
class DesktopLanProxyChoiceTest {
    private val networkId = "192.168.50.1|00:11:22:33:44:55"
    private lateinit var previousSettings: () -> DesktopSocksProxySettings

    @BeforeTest
    fun save() {
        previousSettings = LanCores.settings
        LanCores.lineStopped()
    }

    @AfterTest
    fun restore() {
        LanCores.settings = previousSettings
        LanCores.lineStopped()
    }

    /** What stands in for the engine run: what it was given, and whether it runs. */
    private class RecordingChain : LanCore {
        var config: String? = null
        var starts = 0
        private var running = false
        override val name = "ghostlane-engine"
        override val ownsListener = true
        override suspend fun start(config: String) {
            this.config = config
            starts++
            running = true
        }
        override fun isRunning() = running
        override fun stopNow() { running = false }
        override fun program(): Path = Path.of("ghostlane-engine")
    }

    private class Setup(val address: String, val port: Int)

    /** A private address of this machine and a port on it nobody holds, or null where the runner has none. */
    private fun setup(): Setup? {
        if (DesktopPaths.os == DesktopOs.Windows) return null
        val address = DesktopLanProxy.privateAddresses().firstOrNull() ?: return null
        val port = ServerSocket().use { socket ->
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(address, 0))
            socket.localPort
        }
        return Setup(address, port).takeIf { port != 10808 }
    }

    private fun sharing(at: Setup) = DesktopSocksProxySettings(
        shareOnLan = true,
        lanAddress = at.address,
        lanNetworkId = networkId,
        lanPort = at.port,
        lanUsername = "guest",
        lanPassword = "generated-password",
    )

    private fun newProxy(chain: RecordingChain, at: Setup, log: MutableList<String> = mutableListOf()) = DesktopLanProxy(
        onOutput = { log += it },
        core = chain,
        availableAddresses = { listOf(at.address) },
        readNetworkIdentity = { networkId },
        proveOwnership = { _, _, _, _ -> },
    )

    private fun lanOf(at: Setup) = CoreInbound.Lan(at.address, at.port, SocksLogin("guest", "generated-password"))

    private fun assertChained(chain: RecordingChain, at: Setup, upstream: SubscriptionFetchProxy) {
        assertEquals(1, chain.starts, "the engine run is started once")
        val document = Json.parseToJsonElement(assertNotNull(chain.config)).jsonObject
        val lan = document.getValue("inbounds").jsonArray.map { it.jsonObject }.single { it["tag"]!!.jsonPrimitive.content == "lan-in" }
        assertEquals(at.address, lan.getValue("listen").jsonPrimitive.content)
        assertEquals(at.port.toString(), lan.getValue("port").jsonPrimitive.content)
        val way = document.getValue("outbounds").jsonArray.first().jsonObject.getValue("settings").jsonObject
        assertEquals(upstream.host, way.getValue("address").jsonPrimitive.content)
        assertEquals(upstream.port.toString(), way.getValue("port").jsonPrimitive.content)
    }

    @Test
    fun aCoreLineThatHoldsTheInboundCarriesLanInItsOwnCoreAndStartsNothing() = runTest {
        val at = setup() ?: return@runTest
        val chain = RecordingChain()
        val upstream = SubscriptionFetchProxy("127.0.0.1", 10810, "line", "secret")
        LanCores.lineStarted(lanOf(at))
        val proxy = newProxy(chain, at)
        assertEquals("${at.address}:${at.port}", proxy.start(sharing(at), upstream))
        assertEquals(0, chain.starts, "the line's core is the listener")
        assertTrue(proxy.isRunning())
        proxy.stop()
    }

    @Test
    fun aRoomHasNoLanInboundSoTheEngineRunIsChainedToItsPort() = runTest {
        val at = setup() ?: return@runTest
        val chain = RecordingChain()
        // What the manager leaves where a room starts: the mark, no inbound.
        LanCores.lineStarted(null, "a line is being started")
        val room = SubscriptionFetchProxy("127.0.0.1", 10812, "room-user", "room-pass")
        val proxy = newProxy(chain, at)
        proxy.start(sharing(at), room)
        assertChained(chain, at, room)
        proxy.stop()
        assertFalse(chain.isRunning())
    }

    @Test
    fun aLineWithNoCoreOfItsOwnNeverLeavesLanWithoutAListener() = runTest {
        // The tun modes: a line started by the helper or a tunnel the engine
        // does not hold says nothing of LAN, as does a line that was stopped.
        val at = setup() ?: return@runTest
        for (mode in listOf("a stopped line", "a line the helper started")) {
            val chain = RecordingChain()
            if (mode == "a stopped line") LanCores.lineStopped() else LanCores.lineStarted(null)
            val upstream = SubscriptionFetchProxy("127.0.0.1", 10810, "tun-user", "tun-pass")
            val proxy = newProxy(chain, at)
            proxy.start(sharing(at), upstream)
            assertChained(chain, at, upstream)
            proxy.stop()
        }
    }

    @Test
    fun aLanSwitchedOnAfterTheConnectIsChainedWhereTheLineWasStartedWithoutIt() = runTest {
        val at = setup() ?: return@runTest
        val chain = RecordingChain()
        // The line started with sharing off: its core holds no inbound, and no reason.
        LanCores.lineStarted(null)
        val upstream = SubscriptionFetchProxy("127.0.0.1", 10810)
        val proxy = newProxy(chain, at)
        proxy.start(sharing(at), upstream)
        assertChained(chain, at, upstream)
        proxy.stop()
    }

    @Test
    fun aLineThatHoldsOtherLanSettingsDoesNotCarryTheseAndTheEngineRunDoes() = runTest {
        val at = setup() ?: return@runTest
        val chain = RecordingChain()
        LanCores.lineStarted(CoreInbound.Lan(at.address, at.port, SocksLogin("guest", "an-older-password")))
        val upstream = SubscriptionFetchProxy("127.0.0.1", 10810, "line", "secret")
        val proxy = newProxy(chain, at)
        proxy.start(sharing(at), upstream)
        assertChained(chain, at, upstream)
        proxy.stop()
    }

    @Test
    fun aLanThatCannotBeCarriedIsRefusedWithASentenceAndLeavesNothingRunning() = runTest {
        val at = setup() ?: return@runTest
        val chain = RecordingChain()
        val upstream = SubscriptionFetchProxy("127.0.0.1", 10810, "line", "secret")
        val proxy = newProxy(chain, at)
        // The line left sharing out because the network changed; the proxy says so.
        LanCores.lineStarted(null, "This is a different local network; select the LAN interface again before sharing")
        val another = assertFailsWith<IllegalArgumentException> {
            DesktopLanProxy(
                onOutput = {},
                core = chain,
                availableAddresses = { listOf(at.address) },
                readNetworkIdentity = { "192.168.50.1|ff:ee:dd:cc:bb:aa" },
                proveOwnership = { _, _, _, _ -> },
            ).start(sharing(at), upstream)
        }
        assertEquals("This is a different local network; select the LAN interface again before sharing", another.message)
        // The port is a stranger's: the sentence is the port's, and nothing was started.
        ServerSocket().use { held ->
            held.reuseAddress = false
            held.bind(InetSocketAddress(at.address, 0))
            val clash = assertFailsWith<IllegalStateException> {
                proxy.start(sharing(at).copy(lanPort = held.localPort), upstream)
            }
            assertTrue("already in use" in clash.message.orEmpty(), clash.message)
        }
        assertEquals(0, chain.starts)
        assertFalse(proxy.isRunning())
    }

    @Test
    fun theChoiceIsLoggedWhenTheEngineRunCarriesLan() = runTest {
        val at = setup() ?: return@runTest
        val chain = RecordingChain()
        val log = mutableListOf<String>()
        val upstream = SubscriptionFetchProxy("127.0.0.1", 10810, "line", "secret")
        newProxy(chain, at, log).start(sharing(at), upstream)
        assertTrue(log.any { "holds no LAN inbound" in it && "ghostlane-engine" in it }, log.toString())
    }

    @Test
    fun aChangeOfLanRestartsTheLineOnlyWhenItsCoreHoldsTheInbound() {
        val at = Setup("192.168.50.10", 10818)
        val off = DesktopSocksProxySettings()
        val on = sharing(at)
        // A core line holding the inbound is started again with the new login; a room or
        // a line started without it is not touched: the engine run is stopped and started.
        assertTrue(LineSupervision.lanChangeRestartsEngine(on, off, engineRunsLan = true))
        assertFalse(LineSupervision.lanChangeRestartsEngine(on, off, engineRunsLan = false))
        assertFalse(LineSupervision.lanChangeRestartsEngine(on, on, engineRunsLan = true))
    }

    @Test
    fun noSessionsLanPathNamesAGoCoreOrTheirController() {
        val source = File("src/jvmMain/kotlin/org/olcbox/app/vpn/DesktopLanProxy.kt")
        val code = source.readLines()
            .filterNot { it.trim().startsWith("*") || it.trim().startsWith("/*") || it.trim().startsWith("//") }
            .joinToString("\n")
        for (name in listOf("DesktopSingBoxController", "SingBoxConfig", "resolveSingBoxBinary", "GoLanCore", "sing-box", "LanCores.go")) {
            assertFalse(name in code, "DesktopLanProxy.kt reaches $name")
        }
        assertNull(runCatching { Class.forName("org.olcbox.app.vpn.GoLanCore") }.getOrNull())
    }
}
