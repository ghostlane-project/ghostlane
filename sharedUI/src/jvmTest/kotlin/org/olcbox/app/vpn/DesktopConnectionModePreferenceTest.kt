package org.olcbox.app.vpn

import org.olcbox.app.desktop.DesktopOs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DesktopConnectionModePreferenceTest {
    private val tun = DesktopConnectionModeOption(DesktopConnectionMode.Tun, "System-wide tunnel", "")
    private val proxy = DesktopConnectionModeOption(DesktopConnectionMode.Proxy, "Proxy", "")

    @Test fun thePickIsTheAnswerWhenItCanBe() {
        assertEquals(proxy, DesktopConnectionModePreference.effective(listOf(tun, proxy), DesktopConnectionMode.Proxy))
        assertEquals(tun, DesktopConnectionModePreference.effective(listOf(tun, proxy), DesktopConnectionMode.Tun))
    }

    @Test fun aTunnelNotYetApprovedFallsBackToTheProxy() {
        val unapproved = tun.copy(enabled = false, disabledReason = "Install the system-wide tunnel below first")
        assertEquals(proxy, DesktopConnectionModePreference.effective(listOf(unapproved, proxy), DesktopConnectionMode.Tun))
    }

    @Test fun theOnlyOptionIsTheAnswerEvenWhenItIsNotThePick() {
        assertEquals(tun, DesktopConnectionModePreference.effective(listOf(tun), DesktopConnectionMode.Proxy))
    }

    @Test fun routingAppliesInTheProxyAndMacAndWindowsTunnels() {
        assertNull(routingUnavailableReasonFor(DesktopOs.MacOS, DesktopConnectionMode.Tun))
        assertNull(routingUnavailableReasonFor(DesktopOs.MacOS, DesktopConnectionMode.Proxy))
        assertNull(routingUnavailableReasonFor(DesktopOs.Windows, DesktopConnectionMode.Proxy))
        assertNull(routingUnavailableReasonFor(DesktopOs.Windows, DesktopConnectionMode.Tun))
        assertNotNull(routingUnavailableReasonFor(DesktopOs.Linux, DesktopConnectionMode.Tun))
        assertNotNull(routingUnavailableReasonFor(DesktopOs.Linux, null))
    }
}
