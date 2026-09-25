package org.olcbox.app.vpn.desktop

import org.olcbox.app.vpn.DesktopMode
import org.olcbox.app.vpn.DesktopRulesHome
import org.olcbox.app.vpn.desktopRulesHome
import org.olcbox.app.vpn.macOsModeFor
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopModeSelectionTest {

    @Test
    fun onlyAnApprovedDaemonEarnsTunMode() {
        assertEquals(DesktopMode.MacTun, macOsModeFor(MacOsTunnelDaemon.Registration.Enabled))
    }

    @Test
    fun everyOtherDaemonStateKeepsTodaysProxyBehaviour() {
        // A user who never installs the daemon must see no change at all — not a
        // failure on connect, not a prompt, nothing.
        for (state in listOf(
            MacOsTunnelDaemon.Registration.NotRegistered,
            MacOsTunnelDaemon.Registration.RequiresApproval,
            MacOsTunnelDaemon.Registration.NotFound,
            MacOsTunnelDaemon.Registration.Unsupported,
        )) {
            assertEquals(DesktopMode.SystemProxy, macOsModeFor(state), "state $state")
        }
    }

    @Test
    fun withNoRulesAskedForNothingRoutes() {
        for (mode in DesktopMode.entries) for (olcrtc in listOf(true, false)) {
            assertEquals(DesktopRulesHome.Nowhere, desktopRulesHome(mode, olcrtc, needsRules = false), "$mode olcrtc=$olcrtc")
        }
    }

    @Test
    fun theProxyAndTheMacTunnelRouteEveryServer() {
        for (olcrtc in listOf(true, false)) {
            // For a room the proxy's rules live in the sing-box front: it also does what
            // the engine cannot, "only blocked sites" and a tunnel rule under a whole TLD.
            assertEquals(DesktopRulesHome.Core, desktopRulesHome(DesktopMode.SystemProxy, olcrtc, needsRules = true))
            assertEquals(DesktopRulesHome.Daemon, desktopRulesHome(DesktopMode.MacTun, olcrtc, needsRules = true))
        }
    }

    @Test
    fun theLinuxTunnelRoutesOnlyInRoomsWhereTheEngineRunsAsRoot() {
        assertEquals(DesktopRulesHome.Engine, desktopRulesHome(DesktopMode.LinuxTun, isOlcrtc = true, needsRules = true))
        // The cores run as the user; their direct sockets would enter the tunnel.
        assertEquals(DesktopRulesHome.Nowhere, desktopRulesHome(DesktopMode.LinuxTun, isOlcrtc = false, needsRules = true))
    }

    @Test
    fun theWindowsTunnelCarriesEverything() {
        for (olcrtc in listOf(true, false)) {
            assertEquals(DesktopRulesHome.Nowhere, desktopRulesHome(DesktopMode.WindowsTun, olcrtc, needsRules = true))
        }
    }
}
