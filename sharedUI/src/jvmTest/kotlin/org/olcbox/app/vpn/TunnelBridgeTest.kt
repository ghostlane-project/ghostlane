package org.olcbox.app.vpn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// hev reads where its SOCKS server is once, at start. A core that comes back on
// another port is one hev no longer reaches, while the tunnel check, which asks
// the core's port directly, still passes: connected, and nothing loads.
class TunnelBridgeTest {
    private val core = BridgeTarget("127.0.0.1", 40001, "user", "pass")

    @Test
    fun aLiveBridgeOnTheSameTargetIsLeftAlone() {
        assertFalse(TunnelBridge.needsRestart(running = core, alive = true, wanted = core))
    }

    @Test
    fun anotherPortRestartsIt() {
        assertTrue(TunnelBridge.needsRestart(core, alive = true, wanted = core.copy(port = 40002)))
    }

    @Test
    fun anotherLoginRestartsIt() {
        assertTrue(TunnelBridge.needsRestart(core, alive = true, wanted = core.copy(password = "other")))
    }

    @Test
    fun aDeadOrNeverStartedBridgeRestarts() {
        assertTrue(TunnelBridge.needsRestart(core, alive = false, wanted = core))
        assertTrue(TunnelBridge.needsRestart(null, alive = false, wanted = core))
    }

    @Test
    fun theSessionKeepsItsPortUntilItIsReleased() {
        val drawn = ArrayDeque(listOf(40001, 40002))
        val port = SessionPort { drawn.removeFirst() }
        assertEquals(40001, port.acquire())
        assertEquals(40001, port.acquire())
        port.release()
        assertEquals(40002, port.acquire())
    }
}
