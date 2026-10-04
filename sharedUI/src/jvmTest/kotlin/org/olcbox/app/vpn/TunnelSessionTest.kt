package org.olcbox.app.vpn

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The rule the Android service executes: inside a session nothing closes the
// interface, because closing it is the moment traffic goes around the VPN.
class TunnelSessionTest {
    private val all = TunSpec("all", emptySet(), emptySet())

    @Test
    fun everyRestartInsideATunSessionRunsBehindTheInterface() {
        for ((migration, restart) in listOf(true to false, false to true, true to true)) {
            assertTrue(
                TunnelSession.runsBehindTunnel(tunMode = true, interfaceHeld = true, isMigration = migration, isRestart = restart)
            )
        }
    }

    @Test
    fun aFirstStartAProxySessionAndAStartWithNoInterfaceDoNot() {
        assertFalse(TunnelSession.runsBehindTunnel(tunMode = true, interfaceHeld = true, isMigration = false, isRestart = false))
        assertFalse(TunnelSession.runsBehindTunnel(tunMode = false, interfaceHeld = true, isMigration = true, isRestart = true))
        assertFalse(TunnelSession.runsBehindTunnel(tunMode = true, interfaceHeld = false, isMigration = true, isRestart = true))
    }

    // Picking another server while the first connect is still being verified is
    // not a restart inside a session: nothing has carried traffic yet.
    @Test
    fun theHoldBeginsAtTheFirstVerifiedConnection() {
        assertFalse(TunnelSession.holdsInterface(interfaceUp = true, verified = false, startedBySystem = false))
        assertTrue(TunnelSession.holdsInterface(interfaceUp = true, verified = true, startedBySystem = false))
        assertFalse(TunnelSession.holdsInterface(interfaceUp = false, verified = true, startedBySystem = true))
    }

    @Test
    fun aStartByAndroidIsASessionFromItsFirstSecond() {
        assertTrue(TunnelSession.holdsInterface(interfaceUp = true, verified = false, startedBySystem = true))
    }

    @Test
    fun theInterfaceIsReplacedOnlyWhenWhatItWasBuiltWithChanged() {
        assertFalse(TunnelSession.needsHandover(held = all, wanted = all))
        assertFalse(TunnelSession.needsHandover(held = null, wanted = all))
        assertTrue(TunnelSession.needsHandover(all, all.copy(splitMode = "bypass", bypassApps = setOf("a.b"))))
        assertTrue(TunnelSession.needsHandover(all.copy(bypassApps = setOf("a.b")), all.copy(bypassApps = setOf("a.c"))))
    }

    @Test
    fun onlyAFirstConnectOfTheUsersMayEndInAnErrorWithTheInterfaceClosed() {
        assertTrue(TunnelSession.mayFailOpen(behindTunnel = false, isMigration = false, startedBySystem = false))
        assertFalse(TunnelSession.mayFailOpen(behindTunnel = true, isMigration = false, startedBySystem = false))
        assertFalse(TunnelSession.mayFailOpen(behindTunnel = false, isMigration = true, startedBySystem = false))
        assertFalse(TunnelSession.mayFailOpen(behindTunnel = false, isMigration = false, startedBySystem = true))
    }
}
