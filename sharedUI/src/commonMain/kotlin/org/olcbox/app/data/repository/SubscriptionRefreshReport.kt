package org.olcbox.app.data.repository

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
     * provider lets in only an app that names its device. Final: the list keeps
     * no server, and nothing is asked again without the device's id.
     *
     * [message] is the provider's own sentence (`announce`), at most 200
     * characters, and empty when it sent none: the phrasing then falls back to
     * ours ([DEVICE_REFUSED_MESSAGE] in English).
     */
    data class DeviceRefused(val message: String) : SubscriptionRefreshError
}

/** What a refusal says when the provider said nothing: see [SubscriptionRefreshError.DeviceRefused]. */
const val DEVICE_REFUSED_MESSAGE = "This list is at its device limit. Ask your provider."

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
        is SubscriptionRefreshError.DeviceRefused ->
            error.message.ifBlank { DEVICE_REFUSED_MESSAGE }
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
     * and the selection went where a removed list sends it, but a tunnel
     * running on it is left to run: the host still carries the session, and
     * a refusal is not a reason to cut it, still less to carry it on to
     * another provider's server. So a screen that restarts the tunnel when
     * the selection moved does not, after this.
     */
    val refusedSelection: Boolean = false
) {
    val hasFailures: Boolean get() = failures.isNotEmpty()

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
