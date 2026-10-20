package org.olcbox.app.vpn

import org.olcbox.app.data.model.LocationBundleV4

/**
 * Why a tunnel that is up carries nothing while it waits, as a platform says
 * it ([VpnManager.hold]). Android's service holds its interface, so that no
 * traffic leaves outside the tunnel, until there is a server to connect; the
 * screen and the notification say what it waits for.
 */
enum class TunnelHold {
    /** No location to connect: none at all, or the one selected cannot be built. */
    NoLocation,

    /**
     * Servers are there and none is selected: a refusal took the selected one
     * with its list (LocationBundleV4.selectionWithdrawn). The screen is the
     * pick, and Disconnect ends the hold.
     */
    NonePicked;

    /** What the notification says while the tunnel holds, in English: the key of its translation. */
    val notificationText: String
        get() = when (this) {
            NoLocation -> "Add a location first"
            NonePicked -> "Pick a server"
        }

    companion object {
        /** Why [stored] gives no server to connect. */
        fun of(stored: LocationBundleV4): TunnelHold =
            if (stored.locations.isNotEmpty() && stored.activeLocationId == null) NonePicked else NoLocation
    }
}
