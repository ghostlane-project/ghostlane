package org.olcbox.app.vpn

import kotlinx.coroutines.test.runTest
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
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
 * session's own core, so DesktopLanProxy starts no second process and binds
 * no port of its own while keeping the pinned-network and credential guards.
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
        val core = LanCores.rust(binary = { binaryAsked++; Path.of("unused-ghostlane-engine") })
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
                core = LanCores.rust(binary = { Path.of("unused") }),
                availableAddresses = { listOf(localPrivate) },
                readNetworkIdentity = { networkId },
            )
            assertEquals("$localPrivate:$heldPort", rust.start(sharing(address = localPrivate, port = heldPort), upstream))
            assertTrue(rust.isRunning())
            rust.stop()

            val go = DesktopLanProxy(
                onOutput = {},
                core = LanCores.go {},
                availableAddresses = { listOf(localPrivate) },
                readNetworkIdentity = { networkId },
            )
            val clash = assertFailsWith<IllegalStateException> {
                go.start(sharing(address = localPrivate, port = heldPort), upstream)
            }
            assertTrue("already in use" in clash.message.orEmpty(), clash.message)
            assertFalse(go.isRunning())
        }
    }

    @Test
    fun pinnedNetworkMissingInterfaceBlankLoginAndClashingPortAreRefused() = runTest {
        if (DesktopPaths.os == DesktopOs.Windows) return@runTest
        fun proxy(addresses: List<String> = listOf("192.168.50.10"), identity: String? = networkId) = DesktopLanProxy(
            onOutput = {},
            core = LanCores.rust(binary = { Path.of("unused") }),
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

    @Test
    fun theManagerPassesItsSettingsThroughToLanCores() {
        var current = DesktopSocksProxySettings()
        DesktopLanProxy(onOutput = {}, core = LanCores.rust(), settings = { current })
        assertFalse(LanCores.currentSettings().shareOnLan)
        current = sharing()
        assertEquals(current, LanCores.currentSettings())
    }

    @Test
    fun goStaysTheDefaultAndOwnsItsListenerWhileRustUsesTheSessionsCore() {
        val go = LanCores.go {}
        val rust = LanCores.rust()
        assertEquals("sing-box", LanCores.create {}.name)
        assertEquals("sing-box", go.name)
        assertEquals("ghostlane-engine", rust.name)
        assertTrue(go.ownsListener)
        assertFalse(rust.ownsListener)
        assertFalse(go.isRunning())
        assertFalse(rust.isRunning())
        go.stopNow()
        rust.stopNow()
    }

    private companion object {
        const val PacServerPort = 10808
    }
}
