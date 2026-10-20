package org.olcbox.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A server list whose host refused this device, kept with no servers.
 *
 * A host that holds its list to a number of devices answers a device past it,
 * or one it removed from the list, with `x-hwid-max-devices-reached`; one whose
 * provider lets in only an app that names its device answers an app that names
 * none with `x-hwid-not-supported`. Either answer is final, and carries no
 * server: the one line under it is the reason, written as a server for the apps
 * that read no header.
 *
 * The list stays on the board all the same, under its own name, with the
 * provider's sentence where its servers were and its support link beside it:
 * gone from the board, it would leave nobody any wiser and nothing to ask
 * again once the provider has made room. A list is in
 * [LocationBundleV4.locations] or here, never both; the first answer with
 * servers in it takes it out of here.
 */
@Serializable
data class RefusedList(
    /** The list's address, the one its servers were filed under. */
    @SerialName("subscription_url")
    val subscriptionUrl: String,
    /**
     * The encrypted link the list arrived as, when it did: see
     * [LocationEntry.subscriptionOriginLink]. A list kept with no servers is as
     * secret as one with them.
     */
    @SerialName("subscription_origin_link")
    val subscriptionOriginLink: String? = null,
    /**
     * What the host said about the list in its refusal: its name, its traffic,
     * its support link and how often to ask again, as for any list. Its
     * [SubscriptionMetadata.announce] is the refusal's own sentence, never a note
     * of the provider's from before it; none means the host gave none.
     */
    val subscription: SubscriptionMetadata? = null
) {
    fun normalized(): RefusedList = copy(
        subscriptionUrl = subscriptionUrl.trim(),
        subscriptionOriginLink = subscriptionOriginLink?.trim()?.ifBlank { null },
        subscription = subscription?.normalized()?.takeUnless { it.isEmpty() }
    )
}
