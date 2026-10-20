package org.olcbox.app.ui.features.home

import multiplatform_app.sharedui.generated.resources.Res
import multiplatform_app.sharedui.generated.resources.refresh_bulk_failed
import multiplatform_app.sharedui.generated.resources.refresh_bulk_none
import multiplatform_app.sharedui.generated.resources.refresh_bulk_partial
import multiplatform_app.sharedui.generated.resources.refresh_bulk_updated
import multiplatform_app.sharedui.generated.resources.refresh_device_refused
import multiplatform_app.sharedui.generated.resources.refresh_empty
import multiplatform_app.sharedui.generated.resources.refresh_rejected
import multiplatform_app.sharedui.generated.resources.refresh_server_error
import multiplatform_app.sharedui.generated.resources.refresh_unchanged
import multiplatform_app.sharedui.generated.resources.refresh_unreachable
import multiplatform_app.sharedui.generated.resources.refresh_updated
import org.jetbrains.compose.resources.getString
import org.olcbox.app.data.repository.SubscriptionRefreshError
import org.olcbox.app.data.repository.SubscriptionRefreshFailure
import org.olcbox.app.data.repository.SubscriptionRefreshReport
import org.olcbox.app.update.currentUpdatePlatform

/**
 * A refresh's outcome in the user's language. The report keeps a closed set of
 * errors so that the UI phrases them; its own `singleMessage()`, `bulkMessage()`
 * and `message()` are the English phrasing, and these say the same.
 */
suspend fun SubscriptionRefreshReport.localizedSingleMessage(): String = when {
    failures.isNotEmpty() -> failures.first().localizedMessage()
    updatedCount > 0 -> getString(Res.string.refresh_updated)
    else -> getString(Res.string.refresh_unchanged)
}

/** [SubscriptionRefreshReport.bulkMessage] in the user's language. */
suspend fun SubscriptionRefreshReport.localizedBulkMessage(): String = when {
    failures.isNotEmpty() && updatedCount == 0 ->
        if (failures.size == 1) failures.first().localizedMessage()
        else getString(Res.string.refresh_bulk_failed, failures.size, failures.first().localizedMessage())
    failures.isNotEmpty() ->
        getString(Res.string.refresh_bulk_partial, updatedCount, failures.size, failures.first().localizedMessage())
    updatedCount > 0 -> getString(Res.string.refresh_bulk_updated, updatedCount)
    else -> getString(Res.string.refresh_bulk_none)
}

/** [SubscriptionRefreshFailure.message] in the user's language. */
suspend fun SubscriptionRefreshFailure.localizedMessage(): String {
    val status = statusCode?.let { " ($it)" }.orEmpty()
    return when (error) {
        SubscriptionRefreshError.Rejected -> getString(Res.string.refresh_rejected, status)
        SubscriptionRefreshError.ServerError -> getString(Res.string.refresh_server_error, status)
        SubscriptionRefreshError.Unreachable -> getString(Res.string.refresh_unreachable)
        SubscriptionRefreshError.Empty -> getString(Res.string.refresh_empty)
        is SubscriptionRefreshError.DeviceRefused -> error.localizedText()
    }
}

/**
 * Why a list's host refused this device, as the screen says it: the provider's
 * own sentence, in whatever language the provider wrote it, or ours in the
 * user's where it sent none.
 *
 * Never the provider's on the iPhone, for the reason its notes are not shown
 * there (the board's `announce`): a provider's text is as often "renew here",
 * and App Review reads a purchase pointer shown in the app as the app's
 * (3.1.1). Ours says the same thing without one.
 */
suspend fun SubscriptionRefreshError.DeviceRefused.localizedText(): String =
    message.takeIf { it.isNotBlank() && providerTextShown() } ?: getString(Res.string.refresh_device_refused)

/** Whether a provider's own text may stand on this platform's screens: not on the iPhone. */
internal fun providerTextShown(): Boolean = currentUpdatePlatform().os != "ios"
