package org.olcbox.app.vpn.desktop

import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.data.model.RoutingMode
import org.olcbox.app.data.model.RoutingSettings
import org.olcbox.app.net.LocationKind
import org.olcbox.app.net.SessionFailover
import org.olcbox.app.net.SmartConnect
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
        isOlcrtc: Boolean,
        linePort: Int,
        frontPort: Int? = null,
        username: String = "ghost",
        password: String = "pw"
    ) = LineSupervision.endpointOf(
        isOlcrtc = isOlcrtc,
        linePort = linePort,
        frontPort = frontPort,
        username = username,
        password = password
    )

    // The SOCKS settings have a login here; a core never asked for it.
    @Test
    fun aSessionThatStartedOnACoreIsPointedAtItsPortWithNoLogin() {
        assertEquals(SessionEndpoint(10810, null), endpoint(isOlcrtc = false, linePort = 10810))
    }

    @Test
    fun aSessionThatStartedInARoomIsPointedAtTheEngineWithTheLoginFromTheSettings() {
        assertEquals(
            SessionEndpoint(10808, SocksLogin("ghost", "pw")),
            endpoint(isOlcrtc = true, linePort = 10808)
        )
        // A machine where none is set.
        assertNull(endpoint(isOlcrtc = true, linePort = 10808, username = "", password = "").login)
        // A username alone is a login already: whatever points at the engine
        // sends it, and the engine demands it, whenever the username is not blank.
        assertEquals(SocksLogin("ghost", ""), endpoint(isOlcrtc = true, linePort = 10808, password = "").login)
        assertNull(endpoint(isOlcrtc = true, linePort = 10808, username = " ").login)
    }

    // Proxy mode under rules: the system's proxy setting names the front, which
    // listens without a login, and the engine's port behind it is not the endpoint.
    @Test
    fun aRoomBehindAFrontIsPointedAtTheFrontWithNoLogin() {
        assertEquals(
            SessionEndpoint(10810, null),
            endpoint(isOlcrtc = true, linePort = 10808, frontPort = 10810)
        )
    }

    // A session that goes to another line by itself when its own has stopped
    // answering. Where it may go and when it is time are the common rule's
    // (SessionFailoverTest). What a desktop takes out of those lines, what a
    // no means for a move nobody asked for, and how the outage is counted are
    // pinned here.
    private val list = "https://example.org/sub/aaa"

    private fun core(name: String, id: String) = LocationEntry.from(
        storageId = id,
        location = LocationConfig(
            name = name,
            id = "x",
            kind = LocationKind.Vless,
            rawLink = "vless://d67b1637-4fee-4e0d-bc96-000000000000@1.2.3.4:443?type=tcp&security=reality" +
                "&sni=www.zoom.us&fp=chrome&pbk=abc&sid=14090023&flow=xtls-rprx-vision#$name"
        ),
        subscriptionUrl = list
    )

    private fun room(name: String, id: String) = LocationEntry.from(
        storageId = id,
        location = LocationConfig(name = name, id = "room-$id", key = "k".repeat(64)),
        subscriptionUrl = list
    )

    private val viaRu = core("DE via RU | 0.1320TON/GB", "de-via")
    private val direct = core("DE Direct | 0.0720TON/GB", "de-direct")
    private val deRoom = room("DE · olcRTC", "de-room")
    private val deWb = room("DE · olcRTC · WB", "de-wb")
    private val lines = listOf(viaRu, direct, deRoom, deWb)

    // What the common rule names: from a core, the country's other core and
    // then its rooms; from a room, the other room and then the cores.
    private val fromCore = SessionFailover.candidates(viaRu, lines)
    private val fromRoom = SessionFailover.candidates(deRoom, lines)

    private fun ids(steps: List<SmartConnect.Step>) = steps.map { it.entry.storageId }

    @Test
    fun theTestsOwnCandidatesAreTheOnesItSaysTheyAre() {
        assertEquals(listOf("de-direct", "de-room", "de-wb"), ids(fromCore))
        assertEquals(listOf("de-wb", "de-via", "de-direct"), ids(fromRoom))
    }

    // A refresh of the list hands the ids out again, and where two lines share
    // an entry address it can give the session's id to a line of another
    // country. The manager asks the rule about the entry the session was
    // started from, not about what the store keeps under that id, so the lines
    // to try stay in the country the session is in.
    @Test
    fun theLinesToTryAreThoseOfTheSessionsCountryWhateverItsIdNamesByNow() {
        val sameIdAnotherCountry = core("NL via RU | 0.1320TON/GB", "de-via")
        val refreshed = listOf(sameIdAnotherCountry, direct, deRoom, deWb)
        assertEquals(
            listOf("de-direct", "de-room", "de-wb"),
            ids(SessionFailover.candidates(viaRu, refreshed))
        )
        // Asked about the entry the store has under the id, it would be the
        // other country's rule: nothing here, and another country's lines in
        // a list that had any.
        assertEquals(emptyList(), ids(SessionFailover.candidates(sameIdAnotherCountry, refreshed)))
    }

    // Windows and macOS let every binary a line runs out of the tun, and the
    // proxy has no tun. The engine runs as the user there, so nothing asks
    // for a password whatever is stopped or started.
    @Test
    fun whereNothingAsksForAPasswordEveryLineOfTheRuleMayBeTriedInItsOrder() {
        for (mode in listOf(DesktopMode.WindowsTun, DesktopMode.MacTun, DesktopMode.SystemProxy)) {
            for (engine in FailedEngine.entries) for (candidates in listOf(fromCore, fromRoom)) {
                assertEquals(
                    LinesToTry(candidates),
                    LineSupervision.linesToTry(candidates, built(mode), engine),
                    "$mode $engine"
                )
            }
        }
    }

    // The engine runs as root there. A dialog that opens by itself, for a move
    // nobody asked for, is not acceptable; a core runs as the user and opens none.
    @Test
    fun inTheLinuxTunnelASessionOnACoreIsNotMovedIntoARoom() {
        val toTry = LineSupervision.linesToTry(fromCore, built(DesktopMode.LinuxTun), FailedEngine.None)
        assertEquals(listOf("de-direct"), ids(toTry.steps))
        assertEquals(2, toTry.roomsLeftOut)
        assertFalse(toTry.tunLetsNoOtherLineOut)
        assertFalse(toTry.rootEngineRuns)
    }

    // Its own restart opens the dialog at every attempt, so a room in its
    // place opens one more of the same and nothing new.
    @Test
    fun inTheLinuxTunnelARoomWhoseEngineIsDownMayGoAnywhere() {
        assertEquals(
            LinesToTry(fromRoom),
            LineSupervision.linesToTry(fromRoom, built(DesktopMode.LinuxTun), FailedEngine.Down)
        )
    }

    // Trying any line in its place begins with stopping an engine that runs as
    // root, and ends, when that line does not carry, in the dialog for the
    // session's own. So not even a core is tried, though it would ask for nothing.
    @Test
    fun inTheLinuxTunnelARoomWhoseEngineRunsStaysWhereItIs() {
        assertEquals(
            LinesToTry(emptyList(), rootEngineRuns = true),
            LineSupervision.linesToTry(fromRoom, built(DesktopMode.LinuxTun), FailedEngine.Running)
        )
    }

    // A macOS tun that started without the name of the physical interface
    // keeps one server out, by address. Neither a probe nor another line's
    // core gets out of it, so there is nothing to try, whatever else holds.
    @Test
    fun behindATunThatLetsOnlyItsFirstLineOutNothingIsTried() {
        val oneLine = built(DesktopMode.MacTun, letsEveryLineOut = false)
        for (candidates in listOf(fromCore, fromRoom)) for (engine in FailedEngine.entries) {
            assertEquals(
                LinesToTry(emptyList(), tunLetsNoOtherLineOut = true),
                LineSupervision.linesToTry(candidates, oneLine, engine),
                "$engine"
            )
        }
    }

    // A change the user asked for becomes a full restart when it cannot be
    // made behind the tun. A move nobody asked for becomes nothing: the three
    // answers about settings are never given, because a move applies no
    // settings, and the other two are a no.
    @Test
    fun aMoveIsJudgedByWhatTheSessionHoldsAndNeverBySettings() {
        for (mode in DesktopMode.entries) {
            assertEquals(LineChange.BehindTun, LineSupervision.moveBehindTun(built(mode), tunRunning = true), "$mode")
            assertEquals(
                LineChange.TunNotRunning,
                LineSupervision.moveBehindTun(built(mode), tunRunning = false),
                "$mode"
            )
        }
        assertEquals(
            LineChange.TunBuiltForOneLine,
            LineSupervision.moveBehindTun(built(DesktopMode.MacTun, letsEveryLineOut = false), tunRunning = true)
        )
    }

    private val t0 = 1_000_000L

    private fun due(outage: SessionFailover.Outage?, nowMs: Long) =
        SessionFailover.due(outage, nowMs, networkPresent = true, enabled = true)

    // The attempts of a line whose server is gone, as the desktop makes them:
    // each fails after its check, 16 s when nothing answers, and the waits
    // between them are 4 and 8 s by then.
    @Test
    fun theOutageBeginsWithTheFirstFailedAttemptAndIsOneFailureLongerWithEachOne() {
        val first = LineSupervision.afterFailedAttempt(null, t0, networkPresent = true)
        assertEquals(SessionFailover.Outage(startedAtMs = t0, failedAttempts = 1), first)
        assertFalse(due(first, t0))

        val second = LineSupervision.afterFailedAttempt(first, t0 + 20_000, networkPresent = true)
        assertEquals(SessionFailover.Outage(startedAtMs = t0, failedAttempts = 2), second)
        // Two attempts, and not yet half a minute.
        assertFalse(due(second, t0 + 20_000))

        val third = LineSupervision.afterFailedAttempt(second, t0 + 44_000, networkPresent = true)
        assertEquals(SessionFailover.Outage(startedAtMs = t0, failedAttempts = 3), third)
        assertTrue(due(third, t0 + 44_000))
    }

    // A machine with its cable out is not a dead server. What was counted
    // before is dropped, and the count begins again with the first attempt
    // that fails once the network is back.
    @Test
    fun timeWithoutANetworkIsNotOutage() {
        val counted = SessionFailover.Outage(startedAtMs = t0, failedAttempts = 5)
        assertNull(LineSupervision.afterFailedAttempt(counted, t0 + 60_000, networkPresent = false))
        assertNull(LineSupervision.afterFailedAttempt(null, t0 + 90_000, networkPresent = false))

        val back = LineSupervision.afterFailedAttempt(null, t0 + 120_000, networkPresent = true)
        assertEquals(SessionFailover.Outage(startedAtMs = t0 + 120_000, failedAttempts = 1), back)
        assertFalse(due(back, t0 + 120_000))
    }

    @Test
    fun aLookThatRanToItsEndPutsTheNextOneOffByFiveMinutes() {
        val counted = SessionFailover.Outage(startedAtMs = t0, failedAttempts = 3)
        val now = t0 + 60_000
        val looked = LineSupervision.afterLook(counted, now, ranToItsEnd = true, networkPresent = true)
        assertEquals(counted.copy(lastPassAtMs = now), looked)
        assertFalse(due(looked, now + SessionFailover.REPEAT_MS - 1))
        assertTrue(due(looked, now + SessionFailover.REPEAT_MS))

        // The attempts that fail meanwhile are counted, and do not bring it nearer.
        val later = LineSupervision.afterFailedAttempt(looked, now + 30_000, networkPresent = true)
        assertEquals(now, later?.lastPassAtMs)
        assertEquals(4, later?.failedAttempts)
        assertFalse(due(later, now + 30_000))
    }

    // Cut short by another request, or run with the network gone half way, it
    // tried nothing: the next failed attempt looks again.
    @Test
    fun aLookThatWasCutShortIsNotPutOnRecord() {
        val counted = SessionFailover.Outage(startedAtMs = t0, failedAttempts = 3)
        val now = t0 + 60_000
        assertEquals(counted, LineSupervision.afterLook(counted, now, ranToItsEnd = false, networkPresent = true))
        assertEquals(counted, LineSupervision.afterLook(counted, now, ranToItsEnd = true, networkPresent = false))
        assertTrue(due(LineSupervision.afterLook(counted, now, ranToItsEnd = false, networkPresent = true), now))
        assertNull(LineSupervision.afterLook(null, now, ranToItsEnd = true, networkPresent = true))
    }
}
