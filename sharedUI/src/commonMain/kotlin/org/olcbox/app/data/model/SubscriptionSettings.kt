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
@Serializable
data class SubscriptionSettings(
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
    val collapsible: Boolean = true
) {
    fun normalized(): SubscriptionSettings = copy(
        updateIntervalHours = updateIntervalHours.coerceIn(MIN_INTERVAL_HOURS, MAX_INTERVAL_HOURS)
    )

    companion object {
        const val DEFAULT_INTERVAL_HOURS = 1
        const val MIN_INTERVAL_HOURS = 1

        /** A week. Past this the setting is indistinguishable from "off". */
        const val MAX_INTERVAL_HOURS = 168

        /** Offered in the picker; anything else is reachable by neither. */
        val INTERVAL_CHOICES = listOf(1, 3, 6, 12, 24, 48, 168)
    }
}
