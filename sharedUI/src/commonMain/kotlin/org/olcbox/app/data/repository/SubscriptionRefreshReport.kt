package org.olcbox.app.data.repository

/**
 * Why a subscription could not be refreshed. Kept as a closed set so the UI can
 * phrase it without the datasource owning user-facing copy.
 */
enum class SubscriptionRefreshError {
    /** The server answered, but said the subscription is gone or not allowed. */
    Rejected,

    /** The server answered with a server-side error. */
    ServerError,

    /** The request never got an answer (offline, DNS, timeout, blocked). */
    Unreachable,

    /** The body came through but held no locations we could import. */
    Empty
}

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
    val failures: List<SubscriptionRefreshFailure> = emptyList()
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
