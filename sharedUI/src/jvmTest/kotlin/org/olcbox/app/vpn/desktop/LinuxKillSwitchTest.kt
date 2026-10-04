package org.olcbox.app.vpn.desktop

import org.olcbox.app.vpn.KILL_SWITCH_HOLDS_TRAFFIC
import org.olcbox.app.vpn.VpnStatus
import org.olcbox.app.vpn.isKillSwitchHold
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

// What the Linux desktop says, and reads off the routing table, while its kill
// switch holds traffic. The scripts that put the block in and take it out are
// pinned in DesktopProxyModeTest.
class LinuxKillSwitchTest {
    private val everyStatus = listOf(
        VpnStatus.Disconnected,
        VpnStatus.Connecting,
        VpnStatus.Connected,
        VpnStatus.Reconnecting,
        VpnStatus.Stopping,
        VpnStatus.Error("TUN process exited unexpectedly (code 137)")
    )

    // Everywhere but a Linux desktop that is holding, which is nearly everywhere.
    @Test
    fun withNothingHeldEveryStatusIsShownAsItIs() {
        for (status in everyStatus) {
            assertSame(status, LinuxKillSwitch.shown(status, holdsTraffic = false), "$status")
        }
    }

    // "Disconnected" over a machine with no network gives no reason, and the
    // error that ended the session gives the wrong one: what the user has to
    // know is that traffic is held, and how to let it out.
    @Test
    fun whileTheBlockHoldsDisconnectedAndEveryErrorSayWhatHoldsTheTraffic() {
        val held = VpnStatus.Error(KILL_SWITCH_HOLDS_TRAFFIC)
        assertEquals(held, LinuxKillSwitch.shown(VpnStatus.Disconnected, holdsTraffic = true))
        assertEquals(held, LinuxKillSwitch.shown(VpnStatus.Error("TUN process exited unexpectedly (code 137)"), holdsTraffic = true))
        assertEquals(held, LinuxKillSwitch.shown(VpnStatus.Error("No active location"), holdsTraffic = true))
        assertEquals(held, LinuxKillSwitch.shown(held, holdsTraffic = true))
    }

    // A reconnect over the block says Connecting and a teardown says Stopping:
    // something is being done, and the status is what says so. The sentence
    // is back when it ends with the block still standing.
    @Test
    fun whileTheBlockHoldsAStatusThatSaysSomethingIsBeingDoneIsLeftAlone() {
        for (status in listOf(VpnStatus.Connecting, VpnStatus.Connected, VpnStatus.Reconnecting, VpnStatus.Stopping)) {
            assertSame(status, LinuxKillSwitch.shown(status, holdsTraffic = true), "$status")
        }
    }

    // The settings row reads on while this is what the screen shows, whatever
    // is stored: switching it off is how the block is taken away.
    @Test
    fun onlyItsOwnSentenceCountsAsHolding() {
        assertTrue(VpnStatus.Error(KILL_SWITCH_HOLDS_TRAFFIC).isKillSwitchHold())
        assertTrue(LinuxKillSwitch.shown(VpnStatus.Disconnected, holdsTraffic = true).isKillSwitchHold())
        for (status in everyStatus) {
            assertFalse(status.isKillSwitchHold(), "$status")
        }
    }

    // `ip route show table 51820`, as iproute2 prints it.
    @Test
    fun theBlocksRouteIsReadOffTheTunsTable() {
        // The tun is up: both routes, and the tun's own wins.
        assertTrue(
            LinuxKillSwitch.routeStands(
                "default dev olcbox0 scope link \ndefault dev olcboxks0 scope link metric 4294967295 \n"
            )
        )
        // The tun is gone and the block is what is left.
        assertTrue(LinuxKillSwitch.routeStands("default dev olcboxks0 scope link metric 4294967295 \n"))
        // A tunnel started without the switch, or on a kernel with no dummy
        // device: the tun's name begins as the dummy's does and is not it.
        assertFalse(LinuxKillSwitch.routeStands("default dev olcbox0 scope link \n"))
        // A route on the dummy that is not the default blocks nothing.
        assertFalse(LinuxKillSwitch.routeStands("10.0.0.0/8 dev olcboxks0 scope link \n"))
        assertFalse(LinuxKillSwitch.routeStands(""))
        assertFalse(LinuxKillSwitch.routeStands("Error: ipv4: FIB table does not exist.\nDump terminated\n"))
    }
}
