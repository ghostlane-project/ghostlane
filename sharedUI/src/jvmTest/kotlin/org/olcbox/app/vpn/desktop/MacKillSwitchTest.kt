package org.olcbox.app.vpn.desktop

import org.olcbox.app.vpn.DesktopMode
import org.olcbox.app.vpn.KILL_SWITCH_HOLDS_TRAFFIC
import org.olcbox.app.vpn.KILL_SWITCH_HOLDS_TRAFFIC_MAC
import org.olcbox.app.vpn.VpnStatus
import org.olcbox.app.vpn.failureKeptAcrossAttempts
import org.olcbox.app.vpn.isKillSwitchHold
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

// What a Mac says while its kill switch holds traffic. The block itself is the
// tunnel helper's (TrafficHold.swift), and what the app keeps of it is in
// MacOsTunControllerTest.
class MacKillSwitchTest {
    private val everyStatus = listOf(
        VpnStatus.Disconnected,
        VpnStatus.Connecting,
        VpnStatus.Connected,
        VpnStatus.Reconnecting,
        VpnStatus.Stopping,
        VpnStatus.Error("the system-wide tunnel stopped unexpectedly")
    )

    @Test
    fun withNothingHeldEveryStatusIsShownAsItIs() {
        for (status in everyStatus) {
            assertSame(status, MacKillSwitch.shown(status, holdsTraffic = false), "$status")
        }
    }

    @Test
    fun whileTheBlockHoldsDisconnectedAndEveryErrorSayWhatHoldsTheTraffic() {
        val held = VpnStatus.Error(KILL_SWITCH_HOLDS_TRAFFIC_MAC)
        assertEquals(held, MacKillSwitch.shown(VpnStatus.Disconnected, holdsTraffic = true))
        assertEquals(held, MacKillSwitch.shown(VpnStatus.Error("the system-wide tunnel stopped unexpectedly"), holdsTraffic = true))
        assertEquals(held, MacKillSwitch.shown(held, holdsTraffic = true))
        for (status in listOf(VpnStatus.Connecting, VpnStatus.Connected, VpnStatus.Reconnecting, VpnStatus.Stopping)) {
            assertSame(status, MacKillSwitch.shown(status, holdsTraffic = true), "$status")
        }
    }

    // A session that ends without a Disconnect keeps its block when the next
    // thing is a tunnel again: its own died, or it is being restarted in full.
    // Changed to proxy mode it does not: a proxy has no tunnel for a block to
    // stand behind, and one left standing would refuse the proxy's own line
    // and leave a Mac with no network for having changed a setting.
    @Test
    fun theBlockIsKeptForTheNextTunnelAndForNothingElse() {
        assertTrue(MacKillSwitch.keptForWhatComesNext(switchOn = true, nextMode = DesktopMode.MacTun))
        assertFalse(MacKillSwitch.keptForWhatComesNext(switchOn = true, nextMode = DesktopMode.SystemProxy))
        // Switched off since the tunnel started: taken away then, and not put back.
        assertFalse(MacKillSwitch.keptForWhatComesNext(switchOn = false, nextMode = DesktopMode.MacTun))
        assertFalse(MacKillSwitch.keptForWhatComesNext(switchOn = false, nextMode = DesktopMode.SystemProxy))
    }

    // On Linux either way out is a password dialog, and its sentence says so.
    // A Mac's helper was approved once, when it was installed: its sentence
    // must not promise a dialog that never opens.
    @Test
    fun aMacsSentenceNamesNoPassword() {
        assertTrue("password" in KILL_SWITCH_HOLDS_TRAFFIC)
        assertFalse("password" in KILL_SWITCH_HOLDS_TRAFFIC_MAC)
        assertTrue(KILL_SWITCH_HOLDS_TRAFFIC.startsWith(KILL_SWITCH_HOLDS_TRAFFIC_MAC))
    }

    // The shared screen asks one question of the status, whichever system
    // raised it: the settings row reads on, the notice stays, "Lowest latency"
    // does not stop the session to measure.
    @Test
    fun eitherSentenceIsTheKillSwitchHolding() {
        assertTrue(VpnStatus.Error(KILL_SWITCH_HOLDS_TRAFFIC_MAC).isKillSwitchHold())
        assertTrue(MacKillSwitch.shown(VpnStatus.Disconnected, holdsTraffic = true).isKillSwitchHold())
        assertEquals(
            KILL_SWITCH_HOLDS_TRAFFIC_MAC,
            VpnStatus.Error(KILL_SWITCH_HOLDS_TRAFFIC_MAC).failureKeptAcrossAttempts()
        )
        for (status in everyStatus) {
            assertFalse(status.isKillSwitchHold(), "$status")
        }
    }
}
