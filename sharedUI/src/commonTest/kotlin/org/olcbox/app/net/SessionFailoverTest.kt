package org.olcbox.app.net

import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where a session moves when its line dies, on the coordinator's own names
 * (see SmartConnectTest), and when it starts looking.
 */
class SessionFailoverTest {
    private val sub = "https://proofkit.org/sub/aaa"

    private fun reality(name: String, id: String, url: String = sub) = entry(
        id, name, LocationKind.Vless, url,
        "vless://d67b1637-4fee-4e0d-bc96-000000000000@1.2.3.4:443?type=tcp&security=reality&sni=www.zoom.us" +
            "&fp=chrome&pbk=abc&sid=14090023&flow=xtls-rprx-vision#$name"
    )

    private fun xhttp(name: String, id: String) = entry(
        id, name, LocationKind.Vless, sub,
        "vless://d67b1637-4fee-4e0d-bc96-000000000000@1.2.3.4:40023?type=xhttp&security=reality&encryption=none" +
            "&pbk=abc&sid=14090023&fp=chrome&sni=www.zoom.us&path=%2Fxhttp&host=www.zoom.us&mode=packet-up#$name"
    )

    private fun hy2(name: String, id: String) = entry(
        id, name, LocationKind.Hysteria2, sub,
        "hysteria2://pass@1.2.3.4:30023?sni=www.zoom.us&obfs=salamander&obfs-password=secret&insecure=0#$name"
    )

    private fun room(name: String, id: String, url: String = sub) = LocationEntry.from(
        storageId = id,
        location = LocationConfig(name = name, id = "room-$id", key = "k".repeat(64)),
        subscriptionUrl = url
    )

    private fun entry(id: String, name: String, kind: LocationKind, url: String, link: String) = LocationEntry.from(
        storageId = id,
        location = LocationConfig(name = name, id = "x", kind = kind, rawLink = link),
        subscriptionUrl = url
    )

    private val viaReality = reality("DE via RU | 0.1320TON/GB", "de-via-reality")
    private val viaHy2 = hy2("DE via RU | 0.1320TON/GB · Hysteria2", "de-via-hy2")
    private val viaXhttp = xhttp("DE via RU | 0.1320TON/GB · XHTTP", "de-via-xhttp")
    private val directReality = reality("DE Direct | 0.0720TON/GB", "de-direct-reality")
    private val directHy2 = hy2("DE Direct | 0.0720TON/GB · Hysteria2", "de-direct-hy2")
    private val deRoom = room("DE · olcRTC", "de-room")
    private val deWb = room("DE · olcRTC · WB", "de-wb")
    private val usReality = reality("US via RU | 0.1320TON/GB", "us-reality")
    private val usRoom = room("US · olcRTC", "us-room")
    private val otherListDe = reality("DE Direct | 0.0720TON/GB", "other-de", url = "https://proofkit.org/sub/bbb")

    // The list as a subscription gives it: the Hysteria2 line of the other exit before its Reality line.
    private val all = listOf(
        viaReality, viaHy2, viaXhttp, directHy2, directReality, deRoom, deWb, usReality, usRoom, otherListDe
    )

    private fun ids(steps: List<SmartConnect.Step>) = steps.map { it.entry.storageId }

    @Test fun theSameExitFirstThenTheCountrysOtherExitsThenItsRooms() {
        val steps = SessionFailover.candidates(viaReality, all)
        assertEquals(
            listOf("de-via-hy2", "de-via-xhttp", "de-direct-reality", "de-direct-hy2", "de-room", "de-wb"),
            ids(steps)
        )
        assertTrue(steps.take(4).all { it is SmartConnect.Step.Probe })
        assertTrue(steps.drop(4).all { it is SmartConnect.Step.Connect })
    }

    @Test fun theLineThatFailedIsNotAmongItsOwnCandidatesAndTheLastWinnerLeads() {
        val steps = SessionFailover.candidates(viaReality, all, lastKnownGood = "de-via-xhttp")
        assertFalse("de-via-reality" in ids(steps))
        assertEquals("de-via-xhttp", ids(steps).first())
    }

    @Test fun neverAnotherCountryAndNeverAnotherList() {
        val steps = ids(SessionFailover.candidates(viaReality, all))
        assertFalse("us-reality" in steps)
        assertFalse("us-room" in steps)
        assertFalse("other-de" in steps)
    }

    @Test fun inWhitelistModeTheRoomsGoFirst() {
        val steps = ids(SessionFailover.candidates(viaReality, all, whitelist = true))
        assertEquals(listOf("de-room", "de-wb"), steps.take(2))
        assertEquals("de-via-hy2", steps[2])
    }

    @Test fun aRoomMovesToTheCountrysOtherRoomsThenToItsCores() {
        val steps = SessionFailover.candidates(deRoom, all)
        assertEquals(
            listOf("de-wb", "de-via-reality", "de-via-hy2", "de-via-xhttp", "de-direct-reality", "de-direct-hy2"),
            ids(steps)
        )
        assertTrue(steps.first() is SmartConnect.Step.Connect)
        assertTrue(steps.drop(1).all { it is SmartConnect.Step.Probe })
    }

    @Test fun aListThatDoesNotNameItsCountriesMovesInsideOneExitOnly() {
        val a = reality("Frankfurt 1", "f1")
        val aHy2 = hy2("Frankfurt 1 · Hysteria2", "f1-hy2")
        val b = reality("Frankfurt 2", "f2")
        assertEquals(listOf("f1-hy2"), ids(SessionFailover.candidates(a, listOf(a, aHy2, b))))
        assertEquals(emptyList(), SessionFailover.candidates(b, listOf(a, aHy2, b)))
    }

    @Test fun aLoneLineHasNowhereToGo() {
        assertEquals(emptyList(), SessionFailover.candidates(usReality, listOf(usReality)))
    }

    private val t0 = 1_000_000L

    @Test fun itLooksAfterTwoFailedReconnectsAndHalfAMinute() {
        val two = SessionFailover.Outage(startedAtMs = t0, failedAttempts = 2)
        assertTrue(SessionFailover.due(two, t0 + 30_000, networkPresent = true, enabled = true))
        assertFalse(SessionFailover.due(two, t0 + 29_999, networkPresent = true, enabled = true))
        assertFalse(SessionFailover.due(two.copy(failedAttempts = 1), t0 + 60_000, networkPresent = true, enabled = true))
        assertFalse(SessionFailover.due(null, t0 + 60_000, networkPresent = true, enabled = true))
    }

    @Test fun notWithoutANetworkAndNotWithSmartConnectOff() {
        val outage = SessionFailover.Outage(startedAtMs = t0, failedAttempts = 5)
        assertFalse(SessionFailover.due(outage, t0 + 120_000, networkPresent = false, enabled = true))
        assertFalse(SessionFailover.due(outage, t0 + 120_000, networkPresent = true, enabled = false))
    }

    @Test fun whenNothingAnsweredItLooksAgainEveryFiveMinutes() {
        val looked = SessionFailover.Outage(startedAtMs = t0, failedAttempts = 3, lastPassAtMs = t0 + 40_000)
        assertFalse(SessionFailover.due(looked, t0 + 40_000 + 299_999, networkPresent = true, enabled = true))
        assertTrue(SessionFailover.due(looked, t0 + 40_000 + 300_000, networkPresent = true, enabled = true))
        assertEquals(4, looked.another().failedAttempts)
    }
}
