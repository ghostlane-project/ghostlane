package org.olcbox.app.vpn.desktop

import org.olcbox.app.vpn.DesktopMode
import org.olcbox.app.vpn.desktop.engine.DesktopRulesHome
import org.olcbox.app.vpn.desktop.engine.desktopRulesHome
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
        assertEquals(DesktopRulesHome.Nowhere, desktopRulesHome(needsRules = false))
    }

    @Test
    fun theRulesAskedForAreTheEnginesCoresInEveryMode() {
        assertEquals(DesktopRulesHome.Core, desktopRulesHome(needsRules = true))
    }
}
