package org.olcbox.app.net

import org.olcbox.app.data.model.LocationEntry

/**
 * Groups the locations that are the same exit reached over different transports.
 *
 * The coordinator publishes one exit three times and distinguishes them by a name
 * suffix: `US via RU | 0.13TON/GB`, `… · Hysteria2`, `… · XHTTP`. The edge host is
 * NOT usable as the key — one RU edge fronts US, JP, IT and IN, so host grouping
 * would mix countries.
 *
 * If that naming convention ever changes, every group collapses to a single member:
 * fallback quietly stops happening, which is the safe direction. A wrong grouping
 * would connect someone to another country; a missing one only costs the feature.
 */
object TransportGroup {

    /** Suffixes the coordinator appends, longest first so stripping is unambiguous. */
    private val TRANSPORT_SUFFIXES = listOf(" · Hysteria2", " · XHTTP", " · olcRTC")

    data class Key(
        val subscriptionUrl: String?,
        val baseName: String
    )

    /** Display name with the transport suffix removed. */
    fun baseName(name: String): String {
        val trimmed = name.trim()
        val suffix = TRANSPORT_SUFFIXES.firstOrNull { trimmed.endsWith(it) } ?: return trimmed
        return trimmed.removeSuffix(suffix).trim()
    }

    fun keyOf(entry: LocationEntry): Key = Key(
        subscriptionUrl = entry.subscriptionUrl?.trim()?.takeIf { it.isNotBlank() },
        baseName = baseName(entry.name.ifBlank { entry.location.displayName() })
    )

    /**
     * Siblings of [entry] — the same exit over other transports.
     *
     * olcRTC never joins a group: there is a single olcRTC entry and it is a
     * different exit country and price line, so falling into it automatically
     * would move the user somewhere they did not choose.
     */
    fun siblings(entry: LocationEntry, all: List<LocationEntry>): List<LocationEntry> {
        if (entry.location.kind == LocationKind.Olcrtc) return listOf(entry)
        val key = keyOf(entry)
        return all.filter { candidate ->
            candidate.location.kind != LocationKind.Olcrtc && keyOf(candidate) == key
        }
    }

    fun groupByExit(all: List<LocationEntry>): Map<Key, List<LocationEntry>> =
        all.filter { it.location.kind != LocationKind.Olcrtc }.groupBy { keyOf(it) }

    /**
     * The country a line serves, from its name: a core line's base name leads with
     * it (`US via RU | 0.13TON/GB`, `DE Direct | …`) and an olcRTC line is named by
     * it (`DE · olcRTC`, `DE · olcRTC · WB`). Two capital letters and nothing else,
     * or null: a name that does not say its country gets no olcRTC step.
     */
    fun countryOf(name: String): String? {
        val first = name.trim().substringBefore(' ').substringBefore('|').trim()
        return first.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } }
    }

    /**
     * The olcRTC lines of [entry]'s subscription for [entry]'s own country, in the
     * order the subscription lists them (Telemost, WB, SaluteJazz). The smart
     * connect's last resort: the same country, never another one, so no line for
     * this country means none at all.
     */
    fun olcrtcFallbacks(entry: LocationEntry, all: List<LocationEntry>): List<LocationEntry> {
        if (entry.location.kind == LocationKind.Olcrtc) return emptyList()
        val url = entry.subscriptionUrl?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()
        val country = countryOf(keyOf(entry).baseName) ?: return emptyList()
        return all.filter { candidate ->
            candidate.location.kind == LocationKind.Olcrtc &&
                candidate.subscriptionUrl?.trim() == url &&
                countryOf(candidate.name.ifBlank { candidate.location.displayName() }) == country
        }
    }
}
