package org.olcbox.app.ui.features.home

import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.data.model.LocationMetadata
import org.olcbox.app.ui.components.kit.fill

/**
 * A link can ask for a transport its provider cannot carry — vp8channel on a
 * Jitsi room, whose engine speaks DataChannel only — and the app runs the room
 * over the one it can. The import keeps the request on the entry; this is what
 * the board and the connect button say about it. A server set up for the
 * other transport waits for a peer that never speaks it, and silence here made
 * that look like a broken app (olcbox#15).
 */
object TransportMismatch {
    /**
     * [caption]'s words (what the link asked for, the provider, what it runs), and
     * [explanation]'s (the same and the transport to set). English; a screen passes
     * its string resource, which says the same in the user's language.
     */
    const val CAPTION = "LINK ASKS FOR %1\$s · %2\$s RUNS %3\$s"
    const val EXPLANATION = "This link asks for %1\$s, and Ghostlane runs %2\$s rooms over %3\$s. " +
        "Set the server's transport to %4\$s, then connect."

    /** One line for the room card, in the board's own voice. */
    fun caption(entry: LocationEntry, pattern: String = CAPTION): String? =
        caption(entry.location, entry.metadata, pattern)

    /** The same, for a board row that carries the config and metadata apart. */
    fun caption(location: LocationConfig?, metadata: LocationMetadata?, pattern: String = CAPTION): String? =
        parts(location, metadata)?.let { (asked, provider, runs) ->
            pattern.fill(asked.uppercase(), provider.uppercase(), runs.uppercase())
        }

    /** The sentence the connect button answers with, and what to do about it. */
    fun explanation(entry: LocationEntry, pattern: String = EXPLANATION): String? =
        parts(entry.location, entry.metadata)?.let { (asked, provider, runs) ->
            pattern.fill(asked, provider, runs, entry.location.transport)
        }

    private fun parts(location: LocationConfig?, metadata: LocationMetadata?): Triple<String, String, String>? {
        if (location == null) return null
        val requested = metadata?.requestedTransport?.takeIf { it.isNotBlank() } ?: return null
        if (LocationConfig.normalizeTransport(requested) == location.transport) return null
        return Triple(
            LocationConfig.transportDisplayName(requested),
            LocationConfig.providerDisplayName(location.bypassProvider),
            LocationConfig.transportDisplayName(location.transport)
        )
    }
}
