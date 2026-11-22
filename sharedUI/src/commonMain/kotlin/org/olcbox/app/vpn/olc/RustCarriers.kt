package org.olcbox.app.vpn.olc

import org.olcbox.app.data.model.LocationConfig

/**
 * Which olcRTC carriers and lanes the Rust core can carry, asked before any
 * tunnel is made.
 *
 * Telemost and WB Stream have no path in the Rust client, and neither has the
 * SEI lane. A room on one of them used to fail late, when the engine was told
 * its provider. Here it is refused up front, with a sentence the caller can
 * show. Jitsi is not refused here.
 */
object RustCarriers {
    /** Carriers (canonical names) the Rust core does not carry. */
    val retiredProviders: Set<String> = setOf(
        LocationConfig.PROVIDER_TELEMOST,
        LocationConfig.PROVIDER_WB_STREAM
    )

    /** Lanes the Rust core does not carry, whatever the carrier. */
    val retiredTransports: Set<String> = setOf(LocationConfig.TRANSPORT_SEICHANNEL)

    /**
     * Why a room on [provider] over [transport] cannot be carried, or null.
     * Both are read the way the app reads a link: aliases and case count.
     * An unknown carrier name is the default carrier, which is live.
     */
    fun refusal(provider: String, transport: String): String? {
        val carrier = LocationConfig.normalizeProvider(provider)
        if (carrier in retiredProviders) {
            val name = LocationConfig.providerDisplayName(carrier)
            return "$name rooms are no longer carried. Use a room on SaluteJazz, VK Calls or MTS Link."
        }
        if (LocationConfig.transportOrNull(transport) in retiredTransports) {
            return "The SEI lane is no longer carried. Use a room on another lane."
        }
        return null
    }

    /** The same question for a location. */
    fun refusal(config: LocationConfig): String? = refusal(config.bypassProvider, config.transport)
}
