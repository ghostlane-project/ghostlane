package org.olcbox.app.vpn

import kotlinx.coroutines.test.runTest
import org.olcbox.app.data.datasource.LocationsDataSource
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import org.olcbox.app.net.SocksLogin
import org.olcbox.app.net.core.CoreInbound
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * LAN sharing on ghostlane-engine (D5): the listener is an inbound in the
 * session's own core when it holds one, so DesktopLanProxy starts no second
 * process and binds no port of its own while keeping the pinned-network and
 * credential guards.
 */
class DesktopLanProxyRustTest {
    private val upstream = SubscriptionFetchProxy("127.0.0.1", 10810, "internal", "secret")
    private val networkId = "192.168.50.1|00:11:22:33:44:55"
    private lateinit var previousSettings: () -> DesktopSocksProxySettings

    @BeforeTest
    fun saveSettings() {
        previousSettings = LanCores.settings
    }

    @AfterTest
    fun restoreSettings() {
        LanCores.settings = previousSettings
    }

    private fun sharing(
        address: String = "192.168.50.10",
        port: Int = 10818,
        network: String = networkId,
        username: String = "guest",
        password: String = "generated-password",
    ) = DesktopSocksProxySettings(
        shareOnLan = true,
        lanAddress = address,
        lanNetworkId = network,
        lanPort = port,
        lanUsername = username,
        lanPassword = password,
    )

    @Test
    fun aRustSessionStartsNoProcessOfItsOwnAndDoesNotBindTheLanPort() = runTest {
        if (DesktopPaths.os == DesktopOs.Windows) return@runTest
        var binaryAsked = 0
        val core = LanCores.session(binary = { binaryAsked++; Path.of("unused-ghostlane-engine") })
        // 192.168.50.10 is not an address on the runner: a ServerSocket bind to
        // it would fail, so passing proves DesktopLanProxy did not bind it.
        val proxy = DesktopLanProxy(
            onOutput = {},
            core = core,
            availableAddresses = { listOf("192.168.50.10") },
            readNetworkIdentity = { networkId },
        )
        assertFalse(proxy.isRunning())
        val endpoint = proxy.start(sharing(), upstream)
        assertEquals("192.168.50.10:10818", endpoint)
        assertTrue(proxy.isRunning())
        assertEquals(0, binaryAsked, "no second ghostlane-engine binary is resolved or started")
        proxy.stop()
        assertFalse(proxy.isRunning())
    }

    @Test
    fun aPortAlreadyHeldByTheSessionsCoreDoesNotFailRustLanProxy() = runTest {
        if (DesktopPaths.os == DesktopOs.Windows) return@runTest
        val localPrivate = DesktopLanProxy.privateAddresses().firstOrNull() ?: return@runTest
        ServerSocket().use { heldByCore ->
            heldByCore.reuseAddress = false
            heldByCore.bind(InetSocketAddress(localPrivate, 0))
            val heldPort = heldByCore.localPort
            if (heldPort == PacServerPort || heldPort == upstream.port) return@runTest

            val rust = DesktopLanProxy(
                onOutput = {},
                core = LanCores.session(binary = { Path.of("unused") }),
                availableAddresses = { listOf(localPrivate) },
                readNetworkIdentity = { networkId },
            )
            assertEquals("$localPrivate:$heldPort", rust.start(sharing(address = localPrivate, port = heldPort), upstream))
            assertTrue(rust.isRunning())
            rust.stop()

            // The run of its own, as a room or a line without the inbound gets it,
            // is held to the port check: a stranger's port is a sentence, not a listener.
            val chained = DesktopLanProxy(
                onOutput = {},
                core = LanCores.chain(binary = { Path.of("unused") }),
                availableAddresses = { listOf(localPrivate) },
                readNetworkIdentity = { networkId },
            )
            val clash = assertFailsWith<IllegalStateException> {
                chained.start(sharing(address = localPrivate, port = heldPort), upstream)
            }
            assertTrue("already in use" in clash.message.orEmpty(), clash.message)
            assertFalse(chained.isRunning())
        }
    }

    @Test
    fun pinnedNetworkMissingInterfaceBlankLoginAndClashingPortAreRefused() = runTest {
        if (DesktopPaths.os == DesktopOs.Windows) return@runTest
        fun proxy(addresses: List<String> = listOf("192.168.50.10"), identity: String? = networkId) = DesktopLanProxy(
            onOutput = {},
            core = LanCores.session(binary = { Path.of("unused") }),
            availableAddresses = { addresses },
            readNetworkIdentity = { identity },
        )

        assertFailsWith<IllegalArgumentException> { proxy().start(sharing(username = "", password = ""), upstream) }
        assertFailsWith<IllegalArgumentException> { proxy().start(sharing(password = " "), upstream) }
        assertFailsWith<IllegalStateException> { proxy(addresses = emptyList()).start(sharing(), upstream) }
        assertFailsWith<IllegalStateException> { proxy(identity = null).start(sharing(), upstream) }
        assertFailsWith<IllegalArgumentException> {
            proxy(identity = "192.168.50.1|ff:ee:dd:cc:bb:aa").start(sharing(), upstream)
        }
        assertFailsWith<IllegalArgumentException> { proxy().start(sharing(port = upstream.port), upstream) }
        assertFailsWith<IllegalArgumentException> {
            proxy(addresses = listOf("0.0.0.0")).start(sharing(address = "0.0.0.0"), upstream)
        }
        assertFailsWith<IllegalArgumentException> {
            proxy(addresses = listOf("203.0.113.5")).start(sharing(address = "203.0.113.5"), upstream)
        }

        assertEquals(
            "This is a different local network; select the LAN interface again before sharing",
            DesktopLanProxy.networkProblem(
                sharing(),
                availableAddresses = { listOf("192.168.50.10") },
                readNetworkIdentity = { "192.168.50.1|ff:ee:dd:cc:bb:aa" },
            ),
        )
        assertEquals(
            null,
            DesktopLanProxy.networkProblem(
                sharing().copy(shareOnLan = false),
                availableAddresses = { emptyList() },
                readNetworkIdentity = { null },
            ),
        )
    }

    /**
     * The manager is the only production place that hands its settings to the
     * proxy, and so to the line's core. Built here with a store that asks no
     * network, like the engine stand-ins of L4's watch tests: no session is
     * started, so no engine runs.
     */
    @Test
    fun theManagerPassesItsLanSettingsToTheLinesCore() {
        val repository = LocationsRepositoryImpl(object : LocationsDataSource {
            private var bundle = LocationBundleV4()
            override suspend fun loadLocationBundle(): LocationBundleV4 = bundle
            override suspend fun saveLocationBundle(bundle: LocationBundleV4) { this.bundle = bundle }
            override suspend fun loadLegacyLocations(): List<Pair<String, String>> = emptyList()
            override suspend fun loadLegacyActiveLocationId(): String? = null
        })
        val manager = DesktopVpnManager(repository)
        try {
            assertFalse(LanCores.currentSettings().shareOnLan, "a new manager starts with sharing off")
            val changed = sharing().copy(lanUsername = "new-guest", lanPassword = "new-password")
            manager.updateSocksProxySettings(changed)
            assertEquals(changed.normalized(), LanCores.currentSettings())
        } finally {
            runCatching { manager.close() }
        }
    }

    /** What the proxy's own registration does, with no manager in the test. */
    @Test
    fun aProxyRegistersItsSettingsWithLanCores() {
        var current = DesktopSocksProxySettings()
        DesktopLanProxy(onOutput = {}, core = LanCores.session(), settings = { current })
        assertFalse(LanCores.currentSettings().shareOnLan)
        current = sharing()
        assertEquals(current, LanCores.currentSettings())
    }

    /**
     * The running line, not a switch set somewhere else, says whether the LAN
     * listener belongs to the session's core. Any other case keeps the
     * engine run's own port check, including a line with different settings.
     */
    @Test
    fun theProxyBindsOnlyWhenNoRunningLineHoldsTheseExactLanSettings() = runTest {
        if (DesktopPaths.os == DesktopOs.Windows) return@runTest
        val localPrivate = DesktopLanProxy.privateAddresses().firstOrNull() ?: return@runTest
        ServerSocket().use { heldByStranger ->
            heldByStranger.reuseAddress = false
            heldByStranger.bind(InetSocketAddress(localPrivate, 0))
            val heldPort = heldByStranger.localPort
            if (heldPort == PacServerPort || heldPort == upstream.port) return@runTest

            val settings = sharing(address = localPrivate, port = heldPort)
            val proxy = DesktopLanProxy(
                onOutput = {},
                settings = { settings },
                readNetworkIdentity = { networkId },
            )
            val lan = CoreInbound.Lan(localPrivate, heldPort, SocksLogin(settings.lanUsername, settings.lanPassword))
            try {
                LanCores.lineStarted(lan)
                val held = runCatching { proxy.start(settings, upstream) }
                assertTrue(held.isSuccess, "${held.exceptionOrNull()?.message}")
                assertEquals("$localPrivate:$heldPort", held.getOrThrow())
                assertTrue(proxy.isRunning())

                proxy.stop()
                LanCores.lineStarted(
                    CoreInbound.Lan(localPrivate, heldPort, SocksLogin("guest", "another-password"))
                )
                val other = assertFailsWith<IllegalStateException> { proxy.start(settings, upstream) }
                assertTrue("already in use" in other.message.orEmpty(), other.message)

                LanCores.lineStopped()
                val clash = assertFailsWith<IllegalStateException> { proxy.start(settings, upstream) }
                assertTrue("already in use" in clash.message.orEmpty(), clash.message)
            } finally {
                proxy.stop()
                LanCores.lineStopped()
            }
        }
    }

    @Test
    fun theSessionsCoreOwnsNoListenerAndTheChainRunOwnsItsOwn() {
        val session = LanCores.session()
        val chain = LanCores.chain()
        assertEquals("ghostlane-engine", session.name)
        assertEquals("ghostlane-engine", chain.name)
        assertFalse(session.ownsListener)
        assertTrue(chain.ownsListener)
        assertFalse(session.isRunning())
        assertFalse(chain.isRunning())
        session.stopNow()
        chain.stopNow()
    }

    private companion object {
        const val PacServerPort = 10808
    }
}
