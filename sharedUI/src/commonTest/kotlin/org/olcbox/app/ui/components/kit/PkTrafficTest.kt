package org.olcbox.app.ui.components.kit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PkTrafficTest {
    /**
     * The clock of a session that is watched: every reading a second after the
     * one before it, which is how the screen takes them.
     */
    private val readAt = mutableMapOf<ThroughputWindow, Long>()

    private fun ThroughputWindow.read(totalReceived: Long, totalSent: Long) {
        val at = readAt[this]?.plus(1_000L) ?: SESSION_BEGAN
        readAt[this] = at
        record(totalReceived, totalSent, at)
    }

    // ── the window ─────────────────────────────────────────────────────────

    @Test
    fun theFirstReadingOfASessionIsWhereItCountsFromNotASecondOfTraffic() {
        // The platform's totals are running ones. Taken as a second's worth, the
        // first of them would open every chart with a bar the size of the session.
        val window = ThroughputWindow()
        window.read(totalReceived = 3_000_000_000L, totalSent = 500_000_000L)
        assertEquals(0, window.recorded)
        assertFalse(window.hasData)
        assertEquals(0L, window.receivedAgo(0))
    }

    @Test
    fun aSecondIsTheDifferenceBetweenTwoReadingsInEachDirection() {
        val window = ThroughputWindow()
        window.read(1_000L, 500L)
        window.read(1_300L, 530L)
        window.read(1_900L, 530L)
        assertTrue(window.hasData)
        assertEquals(2, window.recorded)
        assertEquals(600L, window.receivedAgo(0))
        assertEquals(0L, window.sentAgo(0))
        assertEquals(300L, window.receivedAgo(1))
        assertEquals(30L, window.sentAgo(1))
    }

    @Test
    fun theWindowKeepsItsNewestSecondsAndNoMore() {
        val window = ThroughputWindow(seconds = 5)
        var total = 0L
        window.read(total, 0L)
        for (second in 1..12) {
            total += second
            window.read(total, 0L)
        }
        assertEquals(5, window.recorded)
        assertEquals(12L, window.receivedAgo(0))
        assertEquals(8L, window.receivedAgo(4))
        // Past the window there is nothing, rather than whatever the array held.
        assertEquals(0L, window.receivedAgo(5))
        assertEquals(0L, window.receivedAgo(-1))
    }

    @Test
    fun totalsThatWentBackwardsAreASecondThatMovedNothing() {
        // A counter reset under a session. The second it happens in is unknown,
        // not negative — and the one after it is counted from the new totals.
        val window = ThroughputWindow()
        window.read(9_000L, 9_000L)
        window.read(100L, 100L)
        assertEquals(0L, window.receivedAgo(0))
        assertEquals(0L, window.sentAgo(0))
        window.read(350L, 120L)
        assertEquals(250L, window.receivedAgo(0))
        assertEquals(20L, window.sentAgo(0))
    }

    @Test
    fun aResetForgetsTheSecondsAndTheTotalsTheyWereCountedFrom() {
        val window = ThroughputWindow()
        window.read(0L, 0L)
        window.read(5_000L, 5_000L)
        window.reset()
        assertEquals(0, window.recorded)
        assertFalse(window.hasData)
        // The next session's first reading is a starting point again, however
        // far its totals are from the last session's.
        window.read(40L, 40L)
        assertEquals(0, window.recorded)
        window.read(140L, 60L)
        assertEquals(100L, window.receivedAgo(0))
        assertEquals(20L, window.sentAgo(0))
    }

    // ── the seconds between two readings ───────────────────────────────────

    @Test
    fun aReadingThatComesThreeSecondsAfterTheLastIsThreeSecondsOfTraffic() {
        // What the chart was drawn from on a phone: counters another timer
        // refreshed every three seconds, read every second. One second held all
        // three and the two beside it nothing: a comb, and "0 B/s" two seconds
        // in three.
        val window = ThroughputWindow()
        window.record(10_000L, 500L, atMillis = 0L)
        window.record(13_000L, 800L, atMillis = 3_000L)
        assertEquals(3, window.recorded)
        assertEquals(listOf(1_000L, 1_000L, 1_000L), (0..2).map(window::receivedAgo))
        assertEquals(listOf(100L, 100L, 100L), (0..2).map(window::sentAgo))
    }

    @Test
    fun whatDoesNotDivideAmongThoseSecondsWentByInTheNewest() {
        val window = ThroughputWindow()
        window.record(0L, 0L, atMillis = 0L)
        window.record(1_000L, 2L, atMillis = 3_000L)
        assertEquals(listOf(334L, 333L, 333L), (0..2).map(window::receivedAgo))
        assertEquals(listOf(2L, 0L, 0L), (0..2).map(window::sentAgo))
    }

    @Test
    fun aReadingIsASecondWhenItIsNearerOneThanNoneOrTwo() {
        // The screen's second is a second and a few milliseconds. That is one
        // bar each time, with no second ever owed and paid back as two.
        val window = ThroughputWindow()
        window.record(0L, 0L, atMillis = 0L)
        window.record(100L, 0L, atMillis = 1_020L)
        window.record(300L, 0L, atMillis = 2_460L)
        assertEquals(2, window.recorded)
        assertEquals(200L, window.receivedAgo(0))
        assertEquals(100L, window.receivedAgo(1))
    }

    @Test
    fun aReadingThatComesBeforeItsSecondIsUpWaitsForTheOneThatCompletesIt() {
        // Two readers for a moment, or a screen built again a moment after the
        // last one read. A third of a second is not a bar.
        val window = ThroughputWindow()
        window.record(0L, 0L, atMillis = 0L)
        window.record(400L, 40L, atMillis = 300L)
        assertEquals(0, window.recorded)
        assertFalse(window.hasData)
        window.record(1_000L, 100L, atMillis = 1_000L)
        assertEquals(1, window.recorded)
        assertEquals(1_000L, window.receivedAgo(0))
        assertEquals(100L, window.sentAgo(0))
    }

    @Test
    fun anAbsenceLongerThanTheChartLeavesItsAverageOnEveryBar() {
        // A phone's screen is not there while the app is in the background, and
        // nobody reads. What moved meanwhile moved at some rate; the chart shows
        // the one it can know.
        val window = ThroughputWindow(seconds = 5)
        window.record(0L, 0L, atMillis = 0L)
        window.record(9_000L, 0L, atMillis = 1_000L)
        window.record(9_000L + 20_000L, 40L, atMillis = 21_000L)
        assertEquals(5, window.recorded)
        assertEquals(List(5) { 1_000L }, (0..4).map(window::receivedAgo))
        assertEquals(List(5) { 2L }, (0..4).map(window::sentAgo))
    }

    @Test
    fun aShortAbsenceMovesTheSecondsBeforeItAlongTheChart() {
        val window = ThroughputWindow(seconds = 6)
        window.record(0L, 0L, atMillis = 0L)
        window.record(700L, 0L, atMillis = 1_000L)
        window.record(900L, 0L, atMillis = 4_000L)
        assertEquals(4, window.recorded)
        // Three seconds away, and the second from before them behind those.
        assertEquals(700L, window.receivedAgo(3))
        assertEquals(200L, (0..2).sumOf(window::receivedAgo))
    }

    @Test
    fun aClockThatWasSetBackIsASecondThatPassedAllTheSame() {
        val window = ThroughputWindow()
        window.record(0L, 0L, atMillis = 500_000L)
        window.record(250L, 0L, atMillis = 100_000L)
        assertEquals(1, window.recorded)
        assertEquals(250L, window.receivedAgo(0))
        // And the chart goes on from the clock as it is now.
        window.record(650L, 0L, atMillis = 101_000L)
        assertEquals(400L, window.receivedAgo(0))
    }

    // ── the scale ──────────────────────────────────────────────────────────

    @Test
    fun bothRowsAreScaledToTheBusierDirection() {
        // One scale for the two, so the taller row is the busier one.
        val window = ThroughputWindow()
        window.read(0L, 0L)
        window.read(4_000_000L, 200_000L)
        window.read(4_100_000L, 900_000L)
        assertEquals(4_000_000L, window.peak())
    }

    @Test
    fun anIdleTunnelIsScaledToTheFloorNotToItsOwnKeepalives() {
        val window = ThroughputWindow()
        window.read(0L, 0L)
        window.read(300L, 200L)
        window.read(700L, 400L)
        assertEquals(THROUGHPUT_FLOOR_BYTES, window.peak())
        assertTrue(throughputHeight(400L, window.peak()) < 0.1f, "a keepalive must not fill the height")
    }

    @Test
    fun theScaleIsSetByTheSecondsOnTheChartNotByOneThatHasLeftIt() {
        val window = ThroughputWindow()
        var total = 0L
        window.read(total, 0L)
        total += 50_000_000L
        window.read(total, 0L)
        repeat(10) {
            total += 500_000L
            window.read(total, 0L)
        }
        assertEquals(50_000_000L, window.peak())
        assertEquals(500_000L, window.peak(among = 10))
    }

    @Test
    fun aBarGrowsWithTheRootOfItsTraffic() {
        val peak = 4_000_000L
        assertEquals(1f, throughputHeight(peak, peak))
        assertEquals(0.5f, throughputHeight(peak / 4, peak), 0.001f)
        // The point of the root: a second with a hundredth of the peak's traffic
        // is a tenth of its height, where in proportion it would be a pixel.
        assertEquals(0.1f, throughputHeight(peak / 100, peak), 0.001f)
    }

    @Test
    fun aBarIsNeverTallerThanItsRoomNorDrawnForNothing() {
        assertEquals(1f, throughputHeight(9_000_000L, 4_000_000L))
        assertEquals(0f, throughputHeight(0L, 4_000_000L))
        assertEquals(0f, throughputHeight(-5L, 4_000_000L))
        assertEquals(0f, throughputHeight(100L, 0L))
    }

    // ── the figures ────────────────────────────────────────────────────────

    @Test
    fun aSecondsBytesAreSaidAsARate() {
        assertEquals("0 B/s", throughputRate(0L))
        assertEquals("512 B/s", throughputRate(512L))
        assertEquals("1.5 KB/s", throughputRate(1536L))
        assertEquals("84 KB/s", throughputRate(84L * 1024))
        assertEquals("1.2 MB/s", throughputRate(1_258_292L))
        assertEquals("0 B/s", throughputRate(-1L))
    }

    @Test
    fun aWindowWithNoSecondOnRecordSaysItsRateIsStillToCome() {
        // The chart stands from the moment a session is asked for, a second or
        // more before there is a second to chart. "0 B/s" there would be a
        // measurement nobody made.
        val window = ThroughputWindow()
        assertEquals(THROUGHPUT_PENDING, window.newestRate(sent = false))
        // The first reading is where the counting starts, not a second of it.
        window.read(5_000L, 5_000L)
        assertEquals(THROUGHPUT_PENDING, window.newestRate(sent = false))
        assertEquals(THROUGHPUT_PENDING, window.newestRate(sent = true))
    }

    @Test
    fun aSecondThatMovedNothingIsARateOfZeroAndNotOneToCome() {
        val window = ThroughputWindow()
        window.read(5_000L, 700L)
        window.read(5_000L, 2_236L)
        assertEquals("0 B/s", window.newestRate(sent = false))
        assertEquals("1.5 KB/s", window.newestRate(sent = true))
    }

    @Test
    fun aSessionThatEndedHasNoRateLeftOverForTheNextOne() {
        val window = ThroughputWindow()
        window.read(0L, 0L)
        window.read(84L * 1024, 0L)
        assertEquals("84 KB/s", window.newestRate(sent = false))
        window.reset()
        assertEquals(THROUGHPUT_PENDING, window.newestRate(sent = false))
    }

    private companion object {
        /** Any moment: the window counts from its first reading, not from a clock's zero. */
        const val SESSION_BEGAN = 1_760_000_000_000L
    }
}
