package org.olcbox.app.ui.components.kit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PkTrafficTest {

    // ── the window ─────────────────────────────────────────────────────────

    @Test
    fun theFirstReadingOfASessionIsWhereItCountsFromNotASecondOfTraffic() {
        // The platform's totals are running ones. Taken as a second's worth, the
        // first of them would open every chart with a bar the size of the session.
        val window = ThroughputWindow()
        window.record(totalReceived = 3_000_000_000L, totalSent = 500_000_000L)
        assertEquals(0, window.recorded)
        assertFalse(window.hasData)
        assertEquals(0L, window.receivedAgo(0))
    }

    @Test
    fun aSecondIsTheDifferenceBetweenTwoReadingsInEachDirection() {
        val window = ThroughputWindow()
        window.record(1_000L, 500L)
        window.record(1_300L, 530L)
        window.record(1_900L, 530L)
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
        window.record(total, 0L)
        for (second in 1..12) {
            total += second
            window.record(total, 0L)
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
        window.record(9_000L, 9_000L)
        window.record(100L, 100L)
        assertEquals(0L, window.receivedAgo(0))
        assertEquals(0L, window.sentAgo(0))
        window.record(350L, 120L)
        assertEquals(250L, window.receivedAgo(0))
        assertEquals(20L, window.sentAgo(0))
    }

    @Test
    fun aResetForgetsTheSecondsAndTheTotalsTheyWereCountedFrom() {
        val window = ThroughputWindow()
        window.record(0L, 0L)
        window.record(5_000L, 5_000L)
        window.reset()
        assertEquals(0, window.recorded)
        assertFalse(window.hasData)
        // The next session's first reading is a starting point again, however
        // far its totals are from the last session's.
        window.record(40L, 40L)
        assertEquals(0, window.recorded)
        window.record(140L, 60L)
        assertEquals(100L, window.receivedAgo(0))
        assertEquals(20L, window.sentAgo(0))
    }

    // ── the scale ──────────────────────────────────────────────────────────

    @Test
    fun bothRowsAreScaledToTheBusierDirection() {
        // One scale for the two, so the taller row is the busier one.
        val window = ThroughputWindow()
        window.record(0L, 0L)
        window.record(4_000_000L, 200_000L)
        window.record(4_100_000L, 900_000L)
        assertEquals(4_000_000L, window.peak())
    }

    @Test
    fun anIdleTunnelIsScaledToTheFloorNotToItsOwnKeepalives() {
        val window = ThroughputWindow()
        window.record(0L, 0L)
        window.record(300L, 200L)
        window.record(700L, 400L)
        assertEquals(THROUGHPUT_FLOOR_BYTES, window.peak())
        assertTrue(throughputHeight(400L, window.peak()) < 0.1f, "a keepalive must not fill the height")
    }

    @Test
    fun theScaleIsSetByTheSecondsOnTheChartNotByOneThatHasLeftIt() {
        val window = ThroughputWindow()
        var total = 0L
        window.record(total, 0L)
        total += 50_000_000L
        window.record(total, 0L)
        repeat(10) {
            total += 500_000L
            window.record(total, 0L)
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
}
