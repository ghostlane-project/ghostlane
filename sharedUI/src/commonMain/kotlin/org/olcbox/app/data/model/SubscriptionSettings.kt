package org.olcbox.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How the server list is ordered. */
@Serializable
enum class SubscriptionSort {
    /** Whatever order the provider serves, which is often deliberate. */
    @SerialName("none")
    None,

    /** Fastest measured first; anything unmeasured sinks to the bottom. */
    @SerialName("ping")
    Ping,

    @SerialName("name")
    Alphabetical;

    fun label(): String = when (this) {
        None -> "No sorting"
        Ping -> "Sort by ping"
        Alphabetical -> "Sort alphabetically"
    }
}

/**
 * Everything about how subscriptions behave, as opposed to what is in them.
 *
 * Carried inside [LocationBundleV4] rather than in a store of its own. That
 * bundle is already persisted on every platform through one datasource, so this
 * arrives on iOS, Android and desktop at once and cannot drift between them —
 * which is the whole point of asking for it. A new field with a default reads
 * back cleanly from a bundle written before it existed.
 */
/**
 * A room pinned to the top of its list, as the board knows it again.
 *
 * By what a person would call the same room: the list it is in, how it is
 * reached, and its name. Not by its storage id, and not by its link. A fetch of
 * the list gives an entry a new id whenever its link changed, and a room's link
 * changes with the meeting it is held in — which a service ends and a server
 * replaces as a matter of course. A pin that followed the link would be gone
 * after the first of those, from a list that still shows the room under the
 * same name.
 */
@Serializable
data class PinnedLocation(
    /** The list it is in, by address. Empty for a location added by hand. */
    @SerialName("list")
    val subscriptionUrl: String = "",

    /**
     * How it is reached: the protocol, and for a room the meeting service and
     * the transport inside the call. A list names one exit's rooms alike, one
     * to a service, and this is what tells them apart.
     */
    val wire: String = "",

    /** Its name, as the list gives it. */
    val name: String = ""
) {
    fun normalized(): PinnedLocation = copy(subscriptionUrl = subscriptionUrl.trim(), wire = wire.trim(), name = name.trim())
}

@Serializable
data class SubscriptionSettings(
    /** Choose once before connecting; keep the native core for each transport. */
    @SerialName("auto_select_lowest")
    val autoSelectLowest: Boolean = false,

    /** Subscriptions that choose their fastest measured member at connect. */
    @SerialName("lowest_subscription_urls")
    val lowestSubscriptionUrls: Set<String> = emptySet(),

    /** Refresh subscriptions on a timer while the app is running. */
    @SerialName("auto_update")
    val autoUpdate: Boolean = true,

    @SerialName("update_interval_hours")
    val updateIntervalHours: Int = DEFAULT_INTERVAL_HOURS,

    /** Say so when a refresh actually changed something. */
    @SerialName("notify_on_update")
    val notifyOnUpdate: Boolean = true,

    @SerialName("refresh_on_open")
    val refreshOnOpen: Boolean = false,

    @SerialName("ping_on_launch")
    val pingOnLaunch: Boolean = false,

    @SerialName("connect_on_launch")
    val connectOnLaunch: Boolean = false,

    @SerialName("sort")
    val sort: SubscriptionSort = SubscriptionSort.None,

    /**
     * On by default, and deliberately so: importing the same subscription URL
     * twice is nearly always a person pasting a link they already have, and two
     * copies of one provider's servers is a list nobody can read. Refreshing the
     * one that exists is what they meant.
     */
    @SerialName("prevent_duplicates")
    val preventDuplicates: Boolean = true,

    /** Allow folding a subscription's rows away. Off means always expanded. */
    @SerialName("collapsible")
    val collapsible: Boolean = true,

    /**
     * Smart connect: when the exit the user connects is published over several
     * transports, try them in turn and end on olcRTC of the same country
     * (SmartConnect). On by default; off is exactly the old behaviour.
     */
    @SerialName("smart_connect")
    val smartConnect: Boolean = true,

    /** The transport that last got through, per exit (SmartConnect.groupKey → storageId). */
    @SerialName("last_known_good_transport")
    val lastKnownGoodTransport: Map<String, String> = emptyMap(),

    /**
     * The server lists whose rows are folded away, by address.
     *
     * Here and not in the screen's memory. There it lasted as long as the
     * process: fold a list of a hundred rooms, leave the app, and it was
     * unfolded on the way back in. A list with no rows on the screen is a
     * choice about the list, like its order, and is kept where that is.
     */
    @SerialName("folded_subscription_urls")
    val foldedSubscriptionUrls: Set<String> = emptySet(),

    /**
     * The rooms pinned to the top of their lists, in the order they were
     * pinned: the first is the one somebody reaches for without looking, and
     * a later pin goes under it.
     */
    @SerialName("pinned_locations")
    val pinnedLocations: List<PinnedLocation> = emptyList(),

    /**
     * The transport the board is narrowed to, by the key of its chip; null for
     * every transport.
     *
     * Here for the reason the folded lists are. A phone's screen is built again
     * every time the app is returned to, so a choice the screen only remembered
     * was gone on the way back in; and somebody on a network that lets one
     * transport through chooses it once, not at every launch. A key no list
     * serves any more reads as every transport and is left as it is: the board
     * resolves it, see `buildBoardModel`.
     */
    @SerialName("transport_filter")
    val transportFilter: String? = null
) {
    fun normalized(): SubscriptionSettings = copy(
        updateIntervalHours = updateIntervalHours.coerceIn(MIN_INTERVAL_HOURS, MAX_INTERVAL_HOURS),
        lowestSubscriptionUrls = lowestSubscriptionUrls.addresses(),
        foldedSubscriptionUrls = foldedSubscriptionUrls.addresses(),
        transportFilter = transportFilter?.trim()?.takeIf(String::isNotEmpty),
        pinnedLocations = pinnedLocations
            .map { it.normalized() }
            .filter { it.name.isNotEmpty() }
            .distinct()
            // The newest stay. A room can leave its list for good, and its pin
            // stays written where nobody sees it: this is what keeps that from
            // growing for as long as the app is installed.
            .takeLast(MAX_PINNED)
    )

    /** The old global flag remains a read-only migration fallback. */
    fun lowestEnabledFor(subscriptionUrl: String?): Boolean {
        val url = subscriptionUrl?.trim().orEmpty()
        return url.isNotEmpty() && (url in lowestSubscriptionUrls ||
            (autoSelectLowest && lowestSubscriptionUrls.isEmpty()))
    }

    /**
     * Selects the virtual Lowest row for one server list. [knownSubscriptionUrls]
     * materialises the old global flag before changing one list, so upgrading an
     * existing installation does not silently switch Lowest off everywhere else.
     */
    fun withLowestEnabled(
        subscriptionUrl: String?,
        enabled: Boolean,
        knownSubscriptionUrls: Set<String>
    ): SubscriptionSettings {
        val url = subscriptionUrl?.trim().orEmpty()
        if (url.isEmpty()) return this
        val known = knownSubscriptionUrls.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()
        val current = if (autoSelectLowest && lowestSubscriptionUrls.isEmpty()) known else lowestSubscriptionUrls
        return copy(
            autoSelectLowest = false,
            lowestSubscriptionUrls = if (enabled) current + url else current - url
        ).normalized()
    }

    fun hasAnyLowest(): Boolean = autoSelectLowest || lowestSubscriptionUrls.isNotEmpty()

    /** Whether the list at [subscriptionUrl] is folded away. A row with no list is never. */
    fun isFolded(subscriptionUrl: String?): Boolean {
        val url = subscriptionUrl?.trim().orEmpty()
        return url.isNotEmpty() && url in foldedSubscriptionUrls
    }

    fun withFolded(subscriptionUrl: String?, folded: Boolean): SubscriptionSettings {
        val url = subscriptionUrl?.trim().orEmpty()
        if (url.isEmpty()) return this
        return copy(
            foldedSubscriptionUrls = if (folded) foldedSubscriptionUrls + url else foldedSubscriptionUrls - url
        ).normalized()
    }

    /**
     * These settings with nothing left in them about the list at [subscriptionUrl],
     * for when that list is removed.
     *
     * The settings know a list by its address, and for an encrypted list the
     * address is what its link was hiding: removed from the board, it would
     * otherwise stay written here for as long as the app is installed.
     */
    fun withoutSubscription(subscriptionUrl: String): SubscriptionSettings {
        val url = subscriptionUrl.trim()
        if (url.isEmpty()) return this
        return copy(
            lowestSubscriptionUrls = lowestSubscriptionUrls - url,
            foldedSubscriptionUrls = foldedSubscriptionUrls - url,
            pinnedLocations = pinnedLocations.filterNot { it.subscriptionUrl.trim() == url }
        ).normalized()
    }

    fun isPinned(pin: PinnedLocation): Boolean = pin.normalized() in pinnedLocations

    /** Pins a room under the ones already pinned, or takes its pin out and leaves the rest in their order. */
    fun withPinned(pin: PinnedLocation, pinned: Boolean): SubscriptionSettings {
        val room = pin.normalized()
        if (room.name.isEmpty()) return this
        return copy(
            pinnedLocations = if (pinned) pinnedLocations + room else pinnedLocations.filterNot { it.normalized() == room }
        ).normalized()
    }

    private fun Set<String>.addresses(): Set<String> = mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()

    companion object {
        const val DEFAULT_INTERVAL_HOURS = 1
        const val MIN_INTERVAL_HOURS = 1

        /** A week. Past this the setting is indistinguishable from "off". */
        const val MAX_INTERVAL_HOURS = 168

        /**
         * The most rooms that stay pinned. Far more than anybody pins — a pin's
         * whole point is that there are few — and a bound on what is written.
         */
        const val MAX_PINNED = 48

        /** Offered in the picker; anything else is reachable by neither. */
        val INTERVAL_CHOICES = listOf(1, 3, 6, 12, 24, 48, 168)
    }
}
