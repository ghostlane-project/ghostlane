package org.olcbox.app.vpn

import org.olcbox.app.data.model.RoutingMode
import org.olcbox.app.desktop.DesktopOs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test fun onlyTheWindowsTunnelCannotRoute() {
        assertNull(routingUnavailableReasonFor(DesktopOs.MacOS, DesktopConnectionMode.Tun))
        assertNull(routingUnavailableReasonFor(DesktopOs.MacOS, DesktopConnectionMode.Proxy))
        assertNull(routingUnavailableReasonFor(DesktopOs.Windows, DesktopConnectionMode.Proxy))
        assertNull(routingUnavailableReasonFor(DesktopOs.Linux, DesktopConnectionMode.Tun))
        assertNull(routingUnavailableReasonFor(DesktopOs.Linux, null))
        assertEquals(WINDOWS_TUNNEL_CARRIES_ALL, routingUnavailableReasonFor(DesktopOs.Windows, DesktopConnectionMode.Tun))
        assertEquals(WINDOWS_TUNNEL_CARRIES_ALL, routingUnavailableReasonFor(DesktopOs.Windows, null))
    }

    /** The Linux tunnel routes in olcRTC rooms only, and says so; "only blocked sites" would change nothing there. */
    @Test fun linuxSaysItRoutesInRoomsAndOffersNoBlockedOnly() {
        assertEquals(LINUX_TUNNEL_ROOMS_ONLY, routingNoteFor(DesktopOs.Linux))
        assertEquals(RoutingMode.entries - RoutingMode.BlockedOnly, routingModesFor(DesktopOs.Linux))
        for (os in listOf(DesktopOs.MacOS, DesktopOs.Windows, DesktopOs.Other)) {
            assertNull(routingNoteFor(os), "$os")
            assertEquals(RoutingMode.entries, routingModesFor(os), "$os")
        }
    }

    /** A kill switch is offered where the app owns one: a switch shown elsewhere would do nothing. */
    @Test fun aKillSwitchIsOfferedWhereTheAppOwnsOne() {
        assertTrue(killSwitchOfferedOn(DesktopOs.Linux))
        for (os in listOf(DesktopOs.Windows, DesktopOs.Other)) {
            assertFalse(killSwitchOfferedOn(os), "$os")
            assertFalse(killSwitchOfferedOn(os, macTunnelInstalled = true), "$os")
        }
    }

    /** The Mac's block is its tunnel helper's to hold, and stands behind a tunnel only the helper can start. */
    @Test fun aMacOffersItOnceItsTunnelHelperIsInstalled() {
        assertFalse(killSwitchOfferedOn(DesktopOs.MacOS))
        assertTrue(killSwitchOfferedOn(DesktopOs.MacOS, macTunnelInstalled = true))
    }
}
