package org.olcbox.app.ui.features.home

import org.olcbox.app.ui.components.kit.THROUGHPUT_PENDING
import org.olcbox.app.ui.components.kit.newestRate
import org.olcbox.app.vpn.TrafficCounters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What a session has carried, as the screen says it: the totals in the status
 * line and the chart under them, from the platform's counters read a second
 * apart.
 */
class SessionTrafficTest {
    private fun SessionTraffic.read(bytesIn: Long, bytesOut: Long, second: Int) =
        take(TrafficCounters(bytesIn = bytesIn, bytesOut = bytesOut), atMillis = BEGAN + second * 1_000L)

    @Test fun beforeAnythingIsReadThereAreNoTotalsAndNoRate() {
        val traffic = SessionTraffic()
        assertNull(traffic.totals)
        assertEquals(THROUGHPUT_PENDING, traffic.window.newestRate(sent = false))
    }

    @Test fun theTotalsAreThePlatformsAndTheChartIsWhatMovedBetweenTwoOfThem() {
        val traffic = SessionTraffic()
        // A tunnel that was up before the app was: its counters do not start at zero.
        traffic.read(2_600_000_000L, 516_000_000L, second = 0)
        assertEquals(TrafficCounters(2_600_000_000L, 516_000_000L), traffic.totals)
        assertEquals(0, traffic.window.recorded)

        traffic.read(2_600_300_000L, 516_004_000L, second = 1)
        assertEquals(TrafficCounters(2_600_300_000L, 516_004_000L), traffic.totals)
        assertEquals(300_000L, traffic.window.receivedAgo(0))
        assertEquals(4_000L, traffic.window.sentAgo(0))
    }

    @Test fun aCounterThatStartsAgainGoesOnFromWhereTheSessionWas() {
        // A session outlives its tunnel: the phone changes network, a core is
        // started again, and the counters of what carries it begin at nothing.
        // The session did not.
        val traffic = SessionTraffic()
        traffic.read(0L, 0L, second = 0)
        traffic.read(5_000_000L, 400_000L, second = 1)
        traffic.read(70_000L, 9_000L, second = 2)

        assertEquals(TrafficCounters(5_070_000L, 409_000L), traffic.totals)
        // And that second is what the new counter holds, not a second of nothing.
        assertEquals(70_000L, traffic.window.receivedAgo(0))
        assertEquals(9_000L, traffic.window.sentAgo(0))

        traffic.read(90_000L, 9_500L, second = 3)
        assertEquals(TrafficCounters(5_090_000L, 409_500L), traffic.totals)
        assertEquals(20_000L, traffic.window.receivedAgo(0))
    }

    @Test fun aCounterThatRunsOutOfRoomInOneDirectionGoesOnInBoth() {
        // An iPhone counts a tunnel's bytes in thirty-two bits: at four
        // gigabytes received the count is back at nothing, with the bytes sent
        // nowhere near. The session's totals are not.
        val traffic = SessionTraffic()
        val nearlyFull = 4_294_000_000L
        traffic.read(nearlyFull, 300_000_000L, second = 0)
        traffic.read(33_000L, 300_100_000L, second = 1)

        assertEquals(TrafficCounters(nearlyFull + 33_000L, 300_100_000L), traffic.totals)
        assertEquals(33_000L, traffic.window.receivedAgo(0))
        assertEquals(100_000L, traffic.window.sentAgo(0))
    }

    @Test fun anotherSessionHasNoneOfTheLastOnesTraffic() {
        val traffic = SessionTraffic()
        traffic.read(0L, 0L, second = 0)
        traffic.read(8_000_000L, 1_000_000L, second = 1)

        traffic.reset()

        assertNull(traffic.totals)
        assertEquals(THROUGHPUT_PENDING, traffic.window.newestRate(sent = false))
        // Its first reading is where it counts from, and smaller than the last
        // session's is not a counter that started again.
        traffic.read(1_000L, 500L, second = 60)
        assertEquals(TrafficCounters(1_000L, 500L), traffic.totals)
        assertEquals(0, traffic.window.recorded)
    }

    private companion object {
        const val BEGAN = 1_760_000_000_000L
    }
}
