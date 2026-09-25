package org.olcbox.app.net

import org.olcbox.app.data.model.LocationEntry

/**
 * What smart connect tries, in order, when the user connects an exit that a
 * subscription publishes over several transports (see
 * docs/superpowers/specs/2026-09-25-smart-connect-design.md). Pure: the probing
 * and the connecting are the caller's.
 */
object SmartConnect {

    sealed interface Step {
        val entry: LocationEntry

        /** A core transport: probed through its own core before anything is connected. */
        data class Probe(override val entry: LocationEntry) : Step

        /** An olcRTC room: joining takes seconds and is itself the test, so it is connected. */
        data class Connect(override val entry: LocationEntry) : Step
    }

    /** Where the group's last winner is remembered: one key per exit of a subscription. */
    fun groupKey(active: LocationEntry): String {
        val key = TransportGroup.keyOf(active)
        return "${key.subscriptionUrl.orEmpty()}\n${key.baseName}"
    }

    /**
     * The steps for connecting [active], best first; empty when there is nothing to
     * choose between (a lone transport, or an olcRTC room the user picked).
     *
     * Normal order: the group's last winner, then the row the user picked, then the
     * other transports Reality → Hysteria2 → gRPC → XHTTP → TLS, then olcRTC of the
     * same country as the subscription lists it. In [whitelist] mode, when only
     * domestic addresses answer, no core can get out: olcRTC goes first (a last
     * winner that is olcRTC first of all) and the cores follow, in case the check
     * was wrong.
     */
    fun plan(
        active: LocationEntry,
        all: List<LocationEntry>,
        lastKnownGood: String? = null,
        whitelist: Boolean = false
    ): List<Step> {
        if (active.location.kind == LocationKind.Olcrtc) return emptyList()
        val cores = TransportSelector.orderCandidates(TransportGroup.siblings(active, all))
        val rooms = TransportGroup.olcrtcFallbacks(active, all)
        if (cores.size + rooms.size <= 1) return emptyList()

        val winner = (cores + rooms).firstOrNull { it.storageId == lastKnownGood }
        val coreSteps = buildList {
            if (!whitelist && winner != null && winner in cores) add(winner)
            if (active in cores) add(active)
            addAll(cores)
        }.distinctBy { it.storageId }.map { Step.Probe(it) }
        val roomSteps = buildList {
            if (winner != null && winner in rooms) add(winner)
            addAll(rooms)
        }.distinctBy { it.storageId }.map { Step.Connect(it) }
        return if (whitelist) roomSteps + coreSteps else coreSteps + roomSteps
    }
}
