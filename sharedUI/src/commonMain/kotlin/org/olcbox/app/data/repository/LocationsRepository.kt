package org.olcbox.app.data.repository

import kotlinx.coroutines.flow.StateFlow
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.data.model.SubscriptionSettings
import org.olcbox.app.data.model.RoutingSettings

interface LocationsRepository {
    val changes: StateFlow<Long>
    suspend fun getBundle(): LocationBundleV4
    suspend fun saveBundle(bundle: LocationBundleV4)
    /**
     * The whole stored bundle as JSON.
     *
     * **There is deliberately no way to reach this from the UI, and adding one
     * needs a better reason than "backup".** It serialises every location's room
     * id and key, every raw link, and every server-list URL — and a server-list
     * URL is a bearer credential, which is why the screens go to the trouble of
     * masking them (`pkSubscriptionSourceLine`). A "Copy Full Config" row used to
     * put all of that on the clipboard in one tap, which undid the masking and
     * everything behind it.
     *
     * Kept as the counterpart of [importText], and covered by its round-trip test.
     */
    suspend fun exportBundle(): String
    suspend fun importText(text: String, subscriptionProxy: SubscriptionFetchProxy? = null): Boolean

    /**
     * [importText], answering what came of it: whether locations were stored,
     * and when the list's host refused this device, its reason. A refused list
     * is stored all the same, with no servers and what the host said about it,
     * so the board can say whose list it is and why it is empty, and so it can
     * be asked again once the provider has made room.
     */
    suspend fun importReport(text: String, subscriptionProxy: SubscriptionFetchProxy? = null): ImportReport
    suspend fun refreshSubscriptions(
        subscriptionProxy: SubscriptionFetchProxy? = null
    ): SubscriptionRefreshReport
    suspend fun refreshSubscription(
        subscriptionUrl: String,
        subscriptionProxy: SubscriptionFetchProxy? = null
    ): SubscriptionRefreshReport
    suspend fun refreshDueSubscriptions(
        subscriptionProxy: SubscriptionFetchProxy? = null
    ): SubscriptionRefreshReport
    suspend fun setSubscriptionUpdateInterval(subscriptionUrl: String, hours: Int)

    /**
     * Removes every location that came from [subscriptionUrl]. Returns how many
     * were removed. Manually added locations are matched by the same trimmed-URL
     * rule the refresh path uses, so they are never touched. A list its host
     * refused is removed too, and has no locations to count.
     */
    suspend fun deleteSubscription(subscriptionUrl: String): Int
    suspend fun saveLocation(storageId: String, location: LocationConfig)
    suspend fun loadLocation(storageId: String): LocationConfig?
    suspend fun deleteLocation(storageId: String)
    suspend fun getAllLocations(): List<LocationEntry>

    /**
     * The selected location's id: none when no location is stored, or when the
     * list the selected server came from refused this device and nothing has
     * been chosen since (`LocationBundleV4.selectionWithdrawn`). Then the
     * store chooses nothing by itself, and neither may a caller: a start finds
     * no location and says so, until the person picks one.
     */
    suspend fun getActiveLocationId(): String?
    suspend fun setActiveLocationId(storageId: String?)

    /**
     * Makes [toStorageId] the active location in place of [fromStorageId], and
     * the remembered line of [group] (see `SubscriptionSettings.lastKnownGoodTransport`),
     * as one change. For a session that moved by itself: false, and nothing
     * stored, when the active location is no longer [fromStorageId], which is
     * the user having chosen another meanwhile, or when [toStorageId] has left
     * the store.
     */
    suspend fun moveActiveLocation(fromStorageId: String, toStorageId: String, group: String): Boolean

    /** The selected location, when there is one: see [getActiveLocationId]. */
    suspend fun getActiveLocation(): LocationEntry?

    /**
     * This install's id, as a list fetch sends it, for the olcRTC engine; empty
     * when the stored id could not be read this time, which the engine takes
     * as none.
     */
    suspend fun getDeviceIdentity(): String

    /** How subscriptions behave. Persisted with the bundle, so one copy per device. */
    suspend fun getSubscriptionSettings(): SubscriptionSettings
    suspend fun saveSubscriptionSettings(settings: SubscriptionSettings)

    /**
     * Makes [change] to the settings as they are stored now, as one write, and
     * answers what was stored. For a choice made by a press on the board: a list
     * folded away.
     *
     * Not [saveSubscriptionSettings] with a copy changed by the caller, for two
     * reasons. That replaces every setting with what the caller read a while
     * ago, and the store learns things meanwhile that no screen was told — the
     * line a session moved to by itself. And it is announced on [changes], which
     * is how the app hears that its locations moved: everything that listens
     * reads the whole list again, hands it to a running tunnel, and asks every
     * room how full it is. None of that is owed to a list folded.
     *
     * [change] is applied to whatever is stored, so it says what to change and
     * carries no copy of the rest; it may be run more than once.
     */
    suspend fun changeSubscriptionSettings(change: (SubscriptionSettings) -> SubscriptionSettings): SubscriptionSettings

    /** What leaves through the tunnel. Persisted with the bundle, so one copy per device. */
    suspend fun getRoutingSettings(): RoutingSettings
    suspend fun saveRoutingSettings(settings: RoutingSettings)

    /**
     * Whether the VPN disclosure has been accepted on this device.
     *
     * Google Play requires an in-app screen, shown in the ordinary course of
     * using the app, that says what the VpnService API does here and is accepted
     * by a deliberate action — not a privacy policy, not a line in the store
     * listing, and not bundled with any other consent.
     */
    suspend fun isVpnDisclosureAccepted(): Boolean
    suspend fun acceptVpnDisclosure(atMillis: Long)

    /**
     * Whether the first-run walkthrough has already been shown on this device.
     *
     * Nothing to do with the disclosure above, and deliberately separate: Play
     * requires that consent not be combined with any other screen, so these two
     * may never become one flag however alike they look.
     */
    suspend fun isOnboardingSeen(): Boolean

    /** Null replays it — see "replay first run" in settings. */
    suspend fun setOnboardingSeen(atMillis: Long?)
}

data class SubscriptionFetchProxy(
    val host: String,
    val port: Int,
    val username: String = "",
    val password: String = ""
)
