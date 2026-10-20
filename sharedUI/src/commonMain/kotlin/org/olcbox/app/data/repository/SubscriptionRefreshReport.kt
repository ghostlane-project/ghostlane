package org.olcbox.app.data.repository

import org.olcbox.app.data.model.DeviceRefusal

/**
 * Why a subscription could not be refreshed. Kept as a closed set so the UI can
 * phrase it without the datasource owning user-facing copy.
 */
sealed interface SubscriptionRefreshError {
    /** The server answered, but said the subscription is gone or not allowed. */
    data object Rejected : SubscriptionRefreshError

    /** The server answered with a server-side error. */
    data object ServerError : SubscriptionRefreshError

    /** The request never got an answer (offline, DNS, timeout, blocked). */
    data object Unreachable : SubscriptionRefreshError

    /** The body came through but held no locations we could import. */
    data object Empty : SubscriptionRefreshError

    /**
     * The host answered this device, and the answer was no: the list is in use
     * on as many devices as it may be, this one was removed from it, or its
     * provider lets in only an app that names its device ([refusal]). Final:
     * the list keeps no server, and nothing is asked again without the
     * device's id.
     *
     * [message] is the provider's own sentence (`announce`), at most 200
     * characters, and empty when it sent none: the phrasing then falls back to
     * ours for the [refusal] ([DEVICE_LIMIT_REACHED_MESSAGE] or
     * [DEVICE_NOT_SUPPORTED_MESSAGE] in English).
     */
    data class DeviceRefused(
        val message: String,
        val refusal: DeviceRefusal = DeviceRefusal.LimitReached
    ) : SubscriptionRefreshError
}

/**
 * What a refusal at the list's device limit says when the provider said
 * nothing, and on iOS, where the provider's words are never shown, always. The
 * host sends the same flag for a device the provider removed from the list, so
 * the words fit both: no place, whichever the reason.
 */
const val DEVICE_LIMIT_REACHED_MESSAGE = "This list has no place for this device. Ask your provider."

/** What a refusal of a request whose device the provider could not read says when it said nothing. */
const val DEVICE_NOT_SUPPORTED_MESSAGE =
    "The provider could not read this device's id. Refresh the list; if it stays so, ask your provider."

data class SubscriptionRefreshFailure(
    val url: String,
    val error: SubscriptionRefreshError,
    /** HTTP status when there was one — sharpens "Rejected"/"ServerError". */
    val statusCode: Int? = null
) {
    /** One line for a toast/snackbar. */
    fun message(): String = when (error) {
        SubscriptionRefreshError.Rejected ->
            "Server list rejected${statusCode?.let { " ($it)" }.orEmpty()} — it may have been revoked"
        SubscriptionRefreshError.ServerError ->
            "Server list host error${statusCode?.let { " ($it)" }.orEmpty()} — try again later"
        SubscriptionRefreshError.Unreachable ->
            "Could not reach the server list host — check your connection"
        SubscriptionRefreshError.Empty ->
            "Server list returned no locations"
        is SubscriptionRefreshError.DeviceRefused -> error.message.ifBlank {
            when (error.refusal) {
                DeviceRefusal.LimitReached -> DEVICE_LIMIT_REACHED_MESSAGE
                DeviceRefusal.NotSupported -> DEVICE_NOT_SUPPORTED_MESSAGE
            }
        }
    }
}

/**
 * Outcome of a refresh run.
 *
 * The old API returned a bare count, so "nothing changed" and "the token was
 * revoked" produced the same message and users read both as the app being broken.
 */
data class SubscriptionRefreshReport(
    val updatedCount: Int = 0,
    val failures: List<SubscriptionRefreshFailure> = emptyList(),
    /**
     * Whether the selected server was one of a list whose host refused this
     * device in this run. The refusal took it away with the rest of its list,
     * and nothing is selected in its place until the person chooses (the
     * bundle's `selectionWithdrawn`). A tunnel running on that server is not
     * stopped or started again for it, and keeps the server only until the
     * platform next starts it, on a network change, a reconnect or a new line:
     * that start then finds no location and says so, where it would otherwise
     * have carried the session onto a server nobody chose. See
     * [restartsRunningTunnel].
     */
    val refusedSelection: Boolean = false
) {
    val hasFailures: Boolean get() = failures.isNotEmpty()

    /**
     * Whether this refresh is a reason to start a running tunnel again: the one
     * rule every screen that refreshes the lists asks, the home screen and the
     * settings screens on Android, iOS and the desktop.
     *
     * Never while nothing is selected ([hasSelection], the selection as the
     * refresh left it): a start would find no location and take down a
     * session the host still carries, for nothing — on Android the transports
     * stop and the tunnel holds, on iOS and the desktop the session ends. That
     * is so after the refusal that withdrew the selection ([refusedSelection])
     * and after every refresh until the person chooses again, a refresh of
     * another list included. Otherwise when the selected location's own config
     * moved in the refresh ([selectionMoved]), as the home screen knows; a
     * screen that does not know passes null and starts the tunnel again after
     * every refresh, as the settings screens always have.
     */
    fun restartsRunningTunnel(hasSelection: Boolean, selectionMoved: Boolean? = null): Boolean =
        hasSelection && !refusedSelection && (selectionMoved ?: true)

    /** Message for refreshing one server list. */
    fun singleMessage(): String = when {
        failures.isNotEmpty() -> failures.first().message()
        updatedCount > 0 -> "Server list updated"
        else -> "Server list unchanged"
    }

    /** Message for refreshing everything at once. */
    fun bulkMessage(): String = when {
        failures.isNotEmpty() && updatedCount == 0 ->
            if (failures.size == 1) failures.first().message()
            else "${failures.size} server lists failed: ${failures.first().message()}"
        failures.isNotEmpty() ->
            "Updated $updatedCount, ${failures.size} failed: ${failures.first().message()}"
        updatedCount > 0 -> "Server lists updated: $updatedCount"
        else -> "No server lists to update"
    }

    companion object {
        val EMPTY = SubscriptionRefreshReport()
    }
}

/**
 * What an import came to.
 *
 * [imported] is what [LocationsRepository.importText] answers: whether locations
 * were stored. [refused] is set when the list's host refused this device; the
 * list is then stored with no servers (see `RefusedList`) and [imported] is
 * false, for nothing that can be connected to was added.
 */
data class ImportReport(
    val imported: Boolean,
    val refused: SubscriptionRefreshError.DeviceRefused? = null
)
