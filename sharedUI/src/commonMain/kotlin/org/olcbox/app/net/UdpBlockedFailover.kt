package org.olcbox.app.net

import org.olcbox.app.data.model.LocationEntry

/**
 * What to connect through when a Hysteria2 tunnel comes up and carries
 * nothing.
 *
 * A carrier that throttles or drops QUIC leaves the tunnel looking perfect:
 * the system says connected, the extension is alive, and its UDP sessions sit
 * waiting for packets that never arrive (ghostlane#27). Nothing here detects
 * that — that is the probe's job — but once it is known, the answer is the
 * same exit over a transport that rides TCP.
 */
object UdpBlockedFailover {

    /** Transports that do not depend on UDP surviving the carrier. */
    private val OVER_TCP = setOf(
        TransportKind.Reality,
        TransportKind.Xhttp,
        TransportKind.Grpc,
        TransportKind.Tls
    )

    /**
     * The entry to move to, or null when there is nothing to move to.
     *
     * The candidates are [failed]'s own transport group — the same exit, which
     * is what keeps this from moving the user to a country they did not
     * choose — in the order the app prefers transports, minus anything that
     * rides UDP and anything incomplete.
     */
    fun tcpAlternative(failed: LocationEntry, all: List<LocationEntry>): LocationEntry? {
        val group = TransportGroup.siblings(failed, all)
        return TransportSelector.orderCandidates(group).firstOrNull { candidate ->
            candidate.storageId != failed.storageId &&
                candidate.location.transportKind() in OVER_TCP &&
                candidate.location.isComplete()
        }
    }
}
