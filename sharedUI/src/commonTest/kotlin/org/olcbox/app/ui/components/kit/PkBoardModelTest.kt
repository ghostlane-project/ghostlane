package org.olcbox.app.ui.components.kit

import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.SubscriptionSort
import org.olcbox.app.net.LocationKind
import org.olcbox.app.net.OlcrtcSlots
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PkBoardModelTest {

    // ── wire shape ─────────────────────────────────────────────────────────

    @Test
    fun anOlcrtcRoomNamesItsCarrier() {
        val config = LocationConfig(
            id = "room-1",
            key = "k",
            kind = LocationKind.Olcrtc,
            bypassProvider = "telemost",
            transport = "vp8channel"
        )
        assertEquals("a Telemost media session", wireShape(config))
    }

    @Test
    fun realityIsTheOneThatLooksLikeARealSite() {
        val config = LocationConfig(
            kind = LocationKind.Vless,
            rawLink = "vless://11111111-1111-1111-1111-111111111111@example.com:443" +
                "?security=reality&pbk=abcdef&sni=www.microsoft.com&fp=chrome#NL"
        )
        assertEquals("a TLS handshake to a real website", wireShape(config))
    }

    @Test
    fun hysteria2IsUdp() {
        val config = LocationConfig(
            kind = LocationKind.Hysteria2,
            rawLink = "hysteria2://pass@example.com:443?obfs=salamander&obfs-password=x#NL"
        )
        assertEquals("obfuscated QUIC over UDP", wireShape(config))
    }

    @Test
    fun aVlessLinkWithNoRealityKeyIsPlainHttps() {
        val config = LocationConfig(
            kind = LocationKind.Vless,
            rawLink = "vless://11111111-1111-1111-1111-111111111111@example.com:443" +
                "?security=tls&sni=example.com#DE"
        )
        assertEquals("ordinary HTTPS", wireShape(config))
    }

    @Test
    fun nothingSelectedStillReadsAsASentence() {
        assertEquals("an encrypted tunnel", wireShape(null))
    }

    // ── latency ────────────────────────────────────────────────────────────

    @Test
    fun theRoomCarryingTheTunnelIsNeverReportedUnreachable() {
        // Reported from a phone on a bad link: every server read "offline",
        // including the room the traffic was going through. A probe is timed
        // through a connection, so a bad connection fails every probe — that is a
        // fact about the attempt, not about the node.
        val reading = pingReading(
            pingMs = null,
            isMeasuring = false,
            failed = true,
            connectedHere = true
        )
        assertEquals(PkPingState.Unmeasured, reading.state)
        assertEquals("—", reading.label)
    }

    @Test
    fun aProbeThatGotNothingSaysSoWithoutCondemningTheServer() {
        val reading = pingReading(null, isMeasuring = false, failed = true, connectedHere = false)
        assertEquals(PkPingState.NoAnswer, reading.state)
        assertEquals("no ping", reading.label)
    }

    @Test
    fun aMeasurementIsStillJustANumber() {
        assertEquals(
            PkPing("38 ms", PkPingState.Measured),
            pingReading(38, isMeasuring = false, failed = false, connectedHere = false)
        )
        assertEquals(
            PkPingState.Measuring,
            pingReading(38, isMeasuring = true, failed = false, connectedHere = false).state
        )
    }

    @Test
    fun nothingMeasuredYetIsADashRatherThanAVerdict() {
        val reading = pingReading(null, isMeasuring = false, failed = false, connectedHere = false)
        assertEquals(PkPingState.Unmeasured, reading.state)
        assertEquals("—", reading.label)
    }

    // ── seats ──────────────────────────────────────────────────────────────

    @Test
    fun aRoomDrawsOnePipPerSeat() {
        val display = seatDisplay(OlcrtcSlots(slots_total = 8, slots_free = 5))
        val pips = (display as SeatDisplay.Pips).seats
        assertEquals(8, pips.size)
        assertEquals(3, pips.count { it == SeatState.Taken })
        assertEquals(5, pips.count { it == SeatState.Free })
        assertEquals(0, pips.count { it == SeatState.Mine })
    }

    @Test
    fun yourOwnSeatIsAlwaysDrawnFirst() {
        val display = seatDisplay(
            OlcrtcSlots(slots_total = 8, slots_free = 2),
            mine = true
        )
        val pips = (display as SeatDisplay.Pips).seats
        assertEquals(SeatState.Mine, pips.first())
        assertEquals(1, pips.count { it == SeatState.Mine })
        assertEquals(5, pips.count { it == SeatState.Taken })
        assertEquals(2, pips.count { it == SeatState.Free })
    }

    @Test
    fun holdsSlotNeverPaintsASeat() {
        // The bug an iPhone found: every room in the list drew a lime seat and
        // read 1 / 8 with nobody in any of them. holds_slot answers "does this key
        // occupy a slot", one server list issues one key across all its rooms, and
        // presence lingers five minutes after a session — so it was true
        // everywhere. Only the app's own connection state paints a seat.
        val slots = OlcrtcSlots(slots_total = 8, slots_free = 8, holds_slot = true)
        val pips = (seatDisplay(slots, mine = false) as SeatDisplay.Pips).seats
        assertTrue(pips.all { it == SeatState.Free }, "an empty room draws no seat")
        assertEquals("0 / 8", seatCountText(slots))
    }

    @Test
    fun aSeatIsOnlyYoursOnceTheServerHasCountedSomebody() {
        // Just joined, the count has not caught up: an empty room for one more
        // poll is honest, a seat invented to match our own optimism is not.
        val notYetCounted = OlcrtcSlots(slots_total = 8, slots_free = 8)
        val pips = (seatDisplay(notYetCounted, mine = true) as SeatDisplay.Pips).seats
        assertTrue(pips.all { it == SeatState.Free })

        val counted = OlcrtcSlots(slots_total = 8, slots_free = 7)
        val seated = (seatDisplay(counted, mine = true) as SeatDisplay.Pips).seats
        assertEquals(SeatState.Mine, seated.first())
    }

    @Test
    fun theCountIsTheServersFigureAndNothingElse() {
        assertEquals("3 / 8", seatCountText(OlcrtcSlots(slots_total = 8, slots_free = 5)))
        assertEquals(
            "3 / 8",
            seatCountText(OlcrtcSlots(slots_total = 8, slots_free = 5, holds_slot = true))
        )
        assertNull(seatCountText(null))
        assertNull(seatCountText(OlcrtcSlots(slots_total = 0, slots_free = 0)))
    }

    @Test
    fun aFullRoomBlocksUnlessWeAreInIt() {
        val full = OlcrtcSlots(slots_total = 8, slots_free = 0)
        assertTrue(roomIsBlocked(full, mine = false))
        assertFalse(roomIsBlocked(full, mine = true))
        // holds_slot does not get a vote, however loudly it claims otherwise.
        assertTrue(roomIsBlocked(full.copy(holds_slot = true), mine = false))
        assertFalse(roomIsBlocked(OlcrtcSlots(slots_total = 8, slots_free = 1), mine = false))
        assertFalse(roomIsBlocked(null, mine = false))
    }

    @Test
    fun capacityLoweredBelowUseDoesNotDrawNegativeSeats() {
        val display = seatDisplay(OlcrtcSlots(slots_total = 4, slots_free = 9))
        val pips = (display as SeatDisplay.Pips).seats
        assertEquals(4, pips.size)
        assertTrue(pips.all { it == SeatState.Free })
    }

    @Test
    fun aBigNodeBecomesABarRatherThanABarcode() {
        val display = seatDisplay(OlcrtcSlots(slots_total = 64, slots_free = 16))
        val bar = display as SeatDisplay.Bar
        assertEquals(0.75f, bar.fraction)
        assertEquals(false, bar.mine)
    }

    @Test
    fun somethingWithNoSeatsDrawsNothing() {
        assertEquals(SeatDisplay.None, seatDisplay(null))
        assertEquals(SeatDisplay.None, seatDisplay(OlcrtcSlots(slots_total = 0, slots_free = 0)))
    }

    @Test
    fun freeTextCountsDownAndThenSaysFull() {
        assertEquals("5 free", seatFreeText(OlcrtcSlots(slots_total = 8, slots_free = 5)))
        assertEquals("full", seatFreeText(OlcrtcSlots(slots_total = 8, slots_free = 0)))
        assertNull(seatFreeText(null))
    }

    // ── occupancy history ──────────────────────────────────────────────────

    @Test
    fun oneSampleDrawsItsLevelRatherThanNothing() {
        // This asserted the opposite until a phone showed what it meant: no
        // second sample arrives for forty-five seconds, so every card was blank
        // for the first three quarters of a minute and read as broken. One
        // reading is a true statement about the level, which is what the height
        // of the line says.
        val flat = sparklinePoints(listOf(0.5f), 54f, 16f)
        assertEquals(2, flat.size)
        assertEquals(0f, flat[0].x)
        assertEquals(54f, flat[1].x)
        assertEquals(flat[0].y, flat[1].y)
    }

    @Test
    fun aRoomStandingStillDrawsHigherThanAnEmptyOne() {
        // "The line is flat like the patient is dead" — a steady room and an
        // empty one are both flat, and only the height tells them apart.
        val busy = sparklinePoints(listOf(0.75f, 0.75f), 54f, 16f)
        val empty = sparklinePoints(listOf(0f, 0f), 54f, 16f)
        assertTrue(busy[0].y < empty[0].y, "a fuller room must sit higher")
    }

    @Test
    fun noSamplesAtAllDrawsNothing() {
        assertTrue(sparklinePoints(emptyList(), 54f, 16f).isEmpty())
    }

    @Test
    fun aFullerRoomDrawsHigher() {
        val points = sparklinePoints(listOf(0f, 1f), 54f, 16f)
        assertEquals(2, points.size)
        assertEquals(0f, points[0].x)
        assertEquals(54f, points[1].x)
        assertTrue(points[1].y < points[0].y, "1.0 must sit above 0.0")
    }

    @Test
    fun theLineIsInsetSoAStrokeAtTheEdgeIsNotClipped() {
        val points = sparklinePoints(listOf(1f, 1f), 54f, 16f, inset = 2f)
        assertTrue(points.all { it.y >= 2f }, "top of the line stays inside the box")
    }

    @Test
    fun historyKeepsTheMostRecentSamplesOnly() {
        var history = emptyList<Float>()
        repeat(OCCUPANCY_HISTORY_LIMIT + 5) {
            history = appendOccupancy(history, OlcrtcSlots(slots_total = 8, slots_free = 4))
        }
        assertEquals(OCCUPANCY_HISTORY_LIMIT, history.size)
        assertTrue(history.all { it == 0.5f })
    }

    @Test
    fun theTraceRecordsTheSameOccupancyTheSeatsShow() {
        val slots = OlcrtcSlots(slots_total = 8, slots_free = 8, holds_slot = true)
        assertEquals(listOf(0f), appendOccupancy(null, slots))
        assertEquals(
            listOf(3f / 8f),
            appendOccupancy(null, OlcrtcSlots(slots_total = 8, slots_free = 5))
        )
    }

    @Test
    fun anUnmeteredNodeRecordsZeroRatherThanDividingByIt() {
        val history = appendOccupancy(null, OlcrtcSlots(slots_total = 0, slots_free = 0))
        assertEquals(listOf(0f), history)
    }

    // ── the action bar ─────────────────────────────────────────────────────

    private fun action(
        requiresSetup: Boolean = false,
        isConnected: Boolean = false,
        isConnecting: Boolean = false,
        selectedIsRoom: Boolean = true,
        selectedIsFull: Boolean = false,
        exitName: String? = "Netherlands"
    ) = boardAction(
        requiresSetup, isConnected, isConnecting, selectedIsRoom, selectedIsFull, exitName
    )

    @Test
    fun theButtonNamesWhatItWillJoin() {
        assertEquals(PkAction("TAKE A SEAT IN NETHERLANDS", PkActionKind.Go), action())
    }

    @Test
    fun somethingWithoutSeatsIsConnectedToRatherThanSatIn() {
        assertEquals(
            PkAction("CONNECT VIA NETHERLANDS", PkActionKind.Go),
            action(selectedIsRoom = false)
        )
    }

    @Test
    fun aLiveSessionOffersToLeaveTheRoomItIsIn() {
        assertEquals(
            PkAction("LEAVE NETHERLANDS", PkActionKind.Stop),
            action(isConnected = true)
        )
    }

    @Test
    fun connectingBeatsConnected() {
        // Both flags are set for a moment while a session tears down and rebuilds.
        assertEquals(
            PkAction("CANCEL", PkActionKind.Busy),
            action(isConnected = true, isConnecting = true)
        )
    }

    @Test
    fun aFullRoomIsNeverOfferedAsSomethingToJoin() {
        assertEquals(PkAction("ROOM IS FULL", PkActionKind.Blocked), action(selectedIsFull = true))
    }

    @Test
    fun aFullRoomYouAreAlreadyInStillOffersToLeaveIt() {
        assertEquals(
            PkAction("LEAVE NETHERLANDS", PkActionKind.Stop),
            action(isConnected = true, selectedIsFull = true)
        )
    }

    @Test
    fun nothingImportedAsksForAServerList() {
        assertEquals(
            PkAction("ADD SERVER LIST", PkActionKind.Go),
            action(requiresSetup = true)
        )
    }

    @Test
    fun anUnnamedExitStillProducesAButtonThatReads() {
        assertEquals(PkAction("TAKE A SEAT", PkActionKind.Go), action(exitName = null))
        assertEquals(PkAction("TAKE A SEAT", PkActionKind.Go), action(exitName = "   "))
        assertEquals(
            PkAction("DISCONNECT", PkActionKind.Stop),
            action(isConnected = true, exitName = null)
        )
    }

    @Test
    fun aLongExitNameIsCutAtASeparatorRatherThanMidWord() {
        assertEquals("Netherlands", shortenExitName("Netherlands-Amsterdam-03"))
        assertEquals("Frankfurt am", shortenExitName("Frankfurt am Main Datacenter", max = 14))
    }

    @Test
    fun aNameCutAtASeparatorDoesNotKeepTheSeparator() {
        // " · " is two places to cut at. Cut at the second, the name ended with
        // the dot that introduces the part it had just lost.
        assertEquals("DE · olcRTC", shortenExitName("DE · olcRTC · WB", max = 14))
        assertEquals("United States", shortenExitName("United States · New York", max = 16))
        assertEquals("Paris", shortenExitName("Paris, - Île-de-France", max = 9))
    }

    @Test
    fun aNameThatIsOneLongWordFallsBackToAnEllipsis() {
        assertEquals("Abcdefghijklm…", shortenExitName("Abcdefghijklmnopqrstuvwxyz", max = 14))
    }

    @Test
    fun aNameThatFitsIsLeftAlone() {
        assertEquals("Netherlands", shortenExitName("Netherlands"))
    }

    // ── board head ─────────────────────────────────────────────────────────

    @Test
    fun theHeadingOnlySaysRoomsWhereThereAreSeats() {
        assertEquals("Rooms", boardHeading(hasRooms = true))
        assertEquals("Servers", boardHeading(hasRooms = false))
    }

    @Test
    fun sortCyclesBackToWhereItStarted() {
        var sort = SubscriptionSort.None
        assertEquals("AS SERVED", sortLabel(sort))
        sort = nextSort(sort)
        assertEquals("PING", sortLabel(sort))
        sort = nextSort(sort)
        assertEquals("A–Z", sortLabel(sort))
        assertEquals(SubscriptionSort.None, nextSort(sort))
    }

    // ── the plan bar ───────────────────────────────────────────────────────

    @Test
    fun readsWhatFormatByteSizeWrites() {
        assertEquals(1024L * 1024 * 1024, parseQuotaBytes("1.0 GB"))
        assertEquals(512L, parseQuotaBytes("512 B"))
        assertEquals((9.4 * 1024 * 1024).toLong(), parseQuotaBytes("9.4 MB"))
        assertEquals(1024L * 1024 * 1024 * 1024, parseQuotaBytes("1 TB"))
    }

    @Test
    fun readsTheSpellingsProvidersActuallySend() {
        assertEquals(parseQuotaBytes("300 GB"), parseQuotaBytes("300GB"))
        assertEquals(parseQuotaBytes("300 GB"), parseQuotaBytes("300 gb"))
        // Binary and decimal spellings are treated alike on purpose.
        assertEquals(parseQuotaBytes("1 GB"), parseQuotaBytes("1 GiB"))
        assertEquals(parseQuotaBytes("1.5 GB"), parseQuotaBytes("1,5 GB"))
    }

    @Test
    fun refusesAnythingItDoesNotUnderstand() {
        assertNull(parseQuotaBytes(null))
        assertNull(parseQuotaBytes(""))
        assertNull(parseQuotaBytes("unlimited"))
        assertNull(parseQuotaBytes("GB"))
        assertNull(parseQuotaBytes("300"))
        assertNull(parseQuotaBytes("-5 GB"))
        assertNull(parseQuotaBytes("lots of GB"))
    }

    @Test
    fun theBarFillsFromWhatIsSpent() {
        assertEquals(0.5f, planFraction("150 GB", "300 GB"))
        assertEquals(0f, planFraction("0 B", "300 GB"))
    }

    @Test
    fun spendingMoreThanThePlanStillStopsAtFull() {
        assertEquals(1f, planFraction("400 GB", "300 GB"))
    }

    @Test
    fun aPlanNobodyStatedGetsNoBarAtAll() {
        assertNull(planFraction(null, "300 GB"))
        assertNull(planFraction("150 GB", null))
        assertNull(planFraction("150 GB", "unlimited"))
        // Zero is not an allowance of nothing — it is the absence of one.
        assertNull(planFraction("150 GB", "0 B"))
    }
}
