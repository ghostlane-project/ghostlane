package org.olcbox.app.ui.features.home

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.olcbox.app.ui.components.kit.THROUGHPUT_SECONDS
import org.olcbox.app.ui.components.kit.ThroughputWindow
import org.olcbox.app.vpn.TrafficCounters

/**
 * What a session has carried, as the screen says it: the totals in the status
 * line, and the chart of its last seconds under them.
 *
 * Made from the platform's counters, read a second apart. Those are the
 * counters of whatever carries the session at the time — a tunnel interface, a
 * core — and a session outlives them: a phone changes network and its tunnel is
 * built again, a core that died is started again behind the tunnel, and what
 * counts begins at nothing while the session goes on. On an iPhone it also runs
 * out of room: a tunnel's bytes are counted there in thirty-two bits, four
 * gigabytes to a lap. So a count that reads less than it did has started again,
 * and the session's totals go on from where they were.
 *
 * It follows that a reading must never be less than the last by accident. A
 * platform that could not read says so ([org.olcbox.app.vpn.VpnManager.readTraffic]
 * answers null); zeroes handed over in place of an answer would be taken for a
 * counter starting again, and the totals would count the session twice.
 *
 * Kept by the screen's model rather than by the screen, which on a phone is
 * built again on every return to the app.
 */
@Stable
class SessionTraffic(seconds: Int = THROUGHPUT_SECONDS) {
    /** The session's last seconds, for the chart. */
    val window = ThroughputWindow(seconds)

    /**
     * What the session has carried so far, or null while nothing has been read.
     * A state, read where it is drawn: a second that passes recomposes the line
     * that prints it and nothing above.
     */
    var totals: TrafficCounters? by mutableStateOf(null)
        private set

    /** What the counters that have since started again had reached. */
    private var carriedIn = 0L
    private var carriedOut = 0L
    private var lastIn = 0L
    private var lastOut = 0L

    /** Takes the platform's counters as they stood at [atMillis]. */
    fun take(reading: TrafficCounters, atMillis: Long) {
        if (totals != null) {
            // Each direction by itself: they are two counters, and one of them
            // running out of room says nothing about the other.
            if (reading.bytesIn < lastIn) carriedIn += lastIn
            if (reading.bytesOut < lastOut) carriedOut += lastOut
        }
        lastIn = reading.bytesIn
        lastOut = reading.bytesOut
        val soFar = TrafficCounters(bytesIn = carriedIn + lastIn, bytesOut = carriedOut + lastOut)
        totals = soFar
        window.record(soFar.bytesIn, soFar.bytesOut, atMillis)
    }

    /** Forgets the session: the next one's first reading is where it counts from. */
    fun reset() {
        carriedIn = 0L
        carriedOut = 0L
        lastIn = 0L
        lastOut = 0L
        totals = null
        window.reset()
    }
}
