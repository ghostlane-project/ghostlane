package org.olcbox.app.net

import org.olcbox.app.data.model.LocationEntry

/**
 * Where a session goes when the line it is on stops answering, and when it is
 * time to look (docs/superpowers/specs/2026-10-04-kill-switch-failover-design.md).
 * Pure: the probing and the connecting are the caller's, as in [SmartConnect].
 *
 * The country never changes and neither does the server list. Inside those two,
 * a session is wider than a connect: smart connect stays on the exit the user
 * tapped, because a tap is a choice; a session whose exit has died has nothing
 * left of that choice but the country.
 */
object SessionFailover {
    /** A line is given up on after this many failed reconnects... */
    const val MIN_FAILED_ATTEMPTS = 2
    /** ...and this long without it, both counted only while there is a network. */
    const val MIN_OUTAGE_MS = 30_000L
    /** When nothing else answered either, the look is repeated this often. */
    const val REPEAT_MS = 5 * 60_000L

    /** One outage of the active line: since when, how many reconnects failed, when the others were last tried. */
    data class Outage(val startedAtMs: Long, val failedAttempts: Int, val lastPassAtMs: Long? = null) {
        fun another(): Outage = copy(failedAttempts = failedAttempts + 1)
    }

    /**
     * Whether to look for another line now. Not without a network: a phone in a
     * lift is not a dead server, and every candidate would fail the same way.
     */
    fun due(outage: Outage?, nowMs: Long, networkPresent: Boolean, enabled: Boolean): Boolean {
        if (!enabled || !networkPresent || outage == null) return false
        if (outage.failedAttempts < MIN_FAILED_ATTEMPTS) return false
        if (nowMs - outage.startedAtMs < MIN_OUTAGE_MS) return false
        return outage.lastPassAtMs == null || nowMs - outage.lastPassAtMs >= REPEAT_MS
    }

    /**
     * The lines to try instead of [active], best first; [active] itself is not
     * among them, its own retry goes on beside this.
     *
     * From a core line: the other transports of the same exit (the last winner
     * first), then the other exits of the country, each exit's transports in
     * smart connect's order and the exits in the list's, then olcRTC of the
     * country. In [whitelist] mode olcRTC goes first, as at connect. From an
     * olcRTC room: the country's other rooms, then its core lines.
     */
    fun candidates(
        active: LocationEntry,
        all: List<LocationEntry>,
        lastKnownGood: String? = null,
        whitelist: Boolean = false
    ): List<SmartConnect.Step> {
        val otherExits = TransportGroup.sameCountryExits(active, all)
            .groupBy { TransportGroup.keyOf(it) }
            .values
            .flatMap { TransportSelector.orderCandidates(it) }
        if (active.location.kind == LocationKind.Olcrtc) {
            return TransportGroup.otherRooms(active, all).map { SmartConnect.Step.Connect(it) } +
                otherExits.map { SmartConnect.Step.Probe(it) }
        }
        val sameExit = TransportSelector.orderCandidates(TransportGroup.siblings(active, all), lastKnownGood)
            .filterNot { it.storageId == active.storageId }
        val cores = (sameExit + otherExits).map { SmartConnect.Step.Probe(it) }
        val rooms = TransportGroup.olcrtcFallbacks(active, all).map { SmartConnect.Step.Connect(it) }
        return if (whitelist) rooms + cores else cores + rooms
    }
}
