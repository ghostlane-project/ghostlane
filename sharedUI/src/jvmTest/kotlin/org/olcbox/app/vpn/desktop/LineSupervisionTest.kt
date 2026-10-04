package org.olcbox.app.vpn.desktop

import org.olcbox.app.data.model.RoutingMode
import org.olcbox.app.data.model.RoutingSettings
import org.olcbox.app.net.SocksLogin
import org.olcbox.app.vpn.DesktopMode
import org.olcbox.app.vpn.DesktopSocksProxySettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The rule the desktop manager executes when a process of a connected session
// dies. Taking the tun down to recover is the moment traffic leaves directly, so
// which deaths are recovered behind it, and how often, are pinned here.
class LineSupervisionTest {
    @Test
    fun aDeadCoreOrEngineIsRestartedBehindTheTun() {
        assertTrue(LineSupervision.restartsBehindTun(DeadProcess.Core))
        assertTrue(LineSupervision.restartsBehindTun(DeadProcess.Engine))
    }

    // Its routes went with it: there is nothing left to restart behind.
    @Test
    fun theTunsOwnDeathEndsTheSession() {
        assertFalse(LineSupervision.restartsBehindTun(DeadProcess.Tun))
    }

    @Test
    fun theWaitDoublesFromTwoSecondsAndStaysAtThirty() {
        assertEquals(
            listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            (0..5).map(LineSupervision::backoffMs)
        )
    }

    // A session may last for days, and a wait computed by the shift alone wraps:
    // nothing at attempt 62, two seconds again at 64. The cap is applied first.
    @Test
    fun aLongOutageKeepsTheCap() {
        for (attempt in listOf(62, 63, 64, 1_000_000, Int.MAX_VALUE)) {
            assertEquals(30_000L, LineSupervision.backoffMs(attempt), "attempt $attempt")
        }
    }

    @Test
    fun aNegativeAttemptIsTheFirst() {
        assertEquals(2_000L, LineSupervision.backoffMs(-1))
        assertEquals(2_000L, LineSupervision.backoffMs(Int.MIN_VALUE))
    }

    // Another location chosen while a session runs. Taking the tun down for it
    // is the same moment as taking it down to recover, so when the line alone
    // is replaced, and when the whole session has to be, is pinned here too.
    private val socks = DesktopSocksProxySettings(port = 10808, username = "ghost", password = "pw")
    private val routing = RoutingSettings(mode = RoutingMode.BypassRussia, directRules = listOf("example.org"))

    private fun built(mode: DesktopMode, letsEveryLineOut: Boolean = true) =
        SessionBuild(mode = mode, routing = routing, socks = socks, letsEveryLineOut = letsEveryLineOut)

    @Test
    fun anotherLocationInAHeldSessionIsAnotherLineBehindWhatItHolds() {
        for (mode in DesktopMode.entries) {
            assertEquals(
                LineChange.BehindTun,
                LineSupervision.changeOfLine(built(mode), mode, routing, socks, tunRunning = true),
                "$mode"
            )
        }
    }

    // A connect that never carried traffic holds nothing, and neither does a
    // manager that was never connected.
    @Test
    fun withoutASessionThereIsNothingToStayBehind() {
        assertEquals(
            LineChange.NoSession,
            LineSupervision.changeOfLine(null, DesktopMode.MacTun, routing, socks, tunRunning = true)
        )
    }

    @Test
    fun anotherModeIsAFullRestart() {
        assertEquals(
            LineChange.ModeChanged,
            LineSupervision.changeOfLine(built(DesktopMode.MacTun), DesktopMode.SystemProxy, routing, socks, true)
        )
        assertEquals(
            LineChange.ModeChanged,
            LineSupervision.changeOfLine(built(DesktopMode.SystemProxy), DesktopMode.WindowsTun, routing, socks, true)
        )
    }

    // Compared whole. Which part of the routing is in which tun is not worked
    // out: any difference from what the session started with rebuilds it.
    @Test
    fun anyChangeOfTheRoutingSettingsIsAFullRestart() {
        val changed = listOf(
            routing.copy(mode = RoutingMode.Global),
            routing.copy(mode = RoutingMode.BlockedOnly),
            routing.copy(verboseDebugLogs = true),
            routing.copy(olcrtcChromeDtls = true),
            routing.copy(directRules = emptyList()),
            routing.copy(tunnelRules = listOf("example.net"))
        )
        for (now in changed) for (mode in DesktopMode.entries) {
            assertEquals(
                LineChange.RoutingChanged,
                LineSupervision.changeOfLine(built(mode), mode, now, socks, tunRunning = true),
                "$mode $now"
            )
        }
    }

    // The port and the login are what a room's session was pointed at. The LAN
    // half of the same settings is applied on the spot and restarts nothing.
    @Test
    fun aChangeOfTheSocksListenerIsAFullRestartAndOfTheLanHalfIsNot() {
        val mode = DesktopMode.WindowsTun
        for (now in listOf(
            socks.copy(port = 10900),
            socks.copy(username = "other"),
            socks.copy(password = "other"),
            socks.copy(host = "127.0.0.2")
        )) {
            assertEquals(
                LineChange.SocksChanged,
                LineSupervision.changeOfLine(built(mode), mode, routing, now, tunRunning = true),
                "$now"
            )
        }
        val lanOnly = socks.copy(
            shareOnLan = true, lanAddress = "192.168.1.5", lanNetworkId = "gw", lanPort = 10999,
            lanUsername = "lan", lanPassword = "lanpw"
        )
        assertEquals(
            LineChange.BehindTun,
            LineSupervision.changeOfLine(built(mode), mode, routing, lanOnly, tunRunning = true)
        )
    }

    // Its routes went with it, so the machine's traffic already leaves
    // directly: there is nothing to change a line behind.
    @Test
    fun aTunThatIsNotRunningIsRebuilt() {
        for (mode in listOf(DesktopMode.LinuxTun, DesktopMode.WindowsTun, DesktopMode.MacTun)) {
            assertEquals(
                LineChange.TunNotRunning,
                LineSupervision.changeOfLine(built(mode), mode, routing, socks, tunRunning = false),
                "$mode"
            )
        }
    }

    // A macOS tun that found no interface to bind its direct sockets to keeps
    // one server out, by address. A core for another server would dial into it.
    @Test
    fun aTunThatLetsOnlyItsFirstLineOutIsRebuilt() {
        assertEquals(
            LineChange.TunBuiltForOneLine,
            LineSupervision.changeOfLine(
                built(DesktopMode.MacTun, letsEveryLineOut = false),
                DesktopMode.MacTun, routing, socks, tunRunning = true
            )
        )
    }

    // The endpoint is what the tun, or the system's proxy setting, was pointed
    // at when the session started. Every later line has to be exactly this.
    private fun endpoint(
        mode: DesktopMode,
        isOlcrtc: Boolean,
        linePort: Int,
        frontPort: Int? = null,
        username: String = "ghost",
        password: String = "pw"
    ) = LineSupervision.endpointOf(
        mode = mode,
        isOlcrtc = isOlcrtc,
        linePort = linePort,
        frontPort = frontPort,
        username = username,
        password = password
    )

    // The SOCKS settings have a login here; a core never asked for it.
    @Test
    fun aSessionThatStartedOnACoreIsPointedAtItsPortWithNoLogin() {
        for (mode in DesktopMode.entries) {
            assertEquals(SessionEndpoint(10810, null), endpoint(mode, isOlcrtc = false, linePort = 10810), "$mode")
        }
    }

    @Test
    fun aSessionThatStartedInARoomIsPointedAtTheEngineWithTheLoginFromTheSettings() {
        for (mode in listOf(DesktopMode.WindowsTun, DesktopMode.MacTun, DesktopMode.SystemProxy)) {
            assertEquals(
                SessionEndpoint(10808, SocksLogin("ghost", "pw")),
                endpoint(mode, isOlcrtc = true, linePort = 10808),
                "$mode"
            )
            // A machine where none is set.
            assertNull(endpoint(mode, isOlcrtc = true, linePort = 10808, username = "", password = "").login, "$mode")
            // A username alone is a login already: the tun's outbound sends it,
            // and the engine demands it, whenever the username is not blank.
            assertEquals(
                SocksLogin("ghost", ""),
                endpoint(mode, isOlcrtc = true, linePort = 10808, password = "").login,
                "$mode"
            )
            assertNull(endpoint(mode, isOlcrtc = true, linePort = 10808, username = " ").login, "$mode")
        }
    }

    // hev's config carries no login, so on Linux the tun was never pointed at
    // one, whatever the settings say.
    @Test
    fun theLinuxTunIsPointedAtAPortWithNoLogin() {
        assertEquals(
            SessionEndpoint(10808, null),
            endpoint(DesktopMode.LinuxTun, isOlcrtc = true, linePort = 10808)
        )
    }

    // Proxy mode under rules: the system's proxy setting names the front, which
    // listens without a login, and the engine's port behind it is not the endpoint.
    @Test
    fun aRoomBehindAFrontIsPointedAtTheFrontWithNoLogin() {
        assertEquals(
            SessionEndpoint(10810, null),
            endpoint(DesktopMode.SystemProxy, isOlcrtc = true, linePort = 10808, frontPort = 10810)
        )
    }
}
