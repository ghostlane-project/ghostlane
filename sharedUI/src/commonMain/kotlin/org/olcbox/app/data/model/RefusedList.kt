package org.olcbox.app.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

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
    val subscription: SubscriptionMetadata? = null,
    /** Which refusal it was: what the app says in its place when the host gave no sentence. */
    @SerialName("refusal")
    val refusal: DeviceRefusal = DeviceRefusal.LimitReached
) {
    fun normalized(): RefusedList = copy(
        subscriptionUrl = subscriptionUrl.trim(),
        subscriptionOriginLink = subscriptionOriginLink?.trim()?.ifBlank { null },
        subscription = subscription?.normalized()?.takeUnless { it.isEmpty() }
    )
}

/**
 * Why a list's host turned this device away, by the flags of its answer as
 * Happ reads them. Both come as an HTTP 200 with the reason in `announce`.
 */
@Serializable(with = DeviceRefusalSerializer::class)
enum class DeviceRefusal(val wireName: String) {
    /**
     * `x-hwid-max-devices-reached: true`: the list is in use on as many devices
     * as it may be, or this one was removed from it.
     */
    LimitReached("limit_reached"),

    /**
     * `x-hwid-not-supported: true`: the provider lets in only an app that names
     * its device, and read no device in the request.
     */
    NotSupported("not_supported")
}

/**
 * [DeviceRefusal] by its wire name, read leniently: a kind a later version wrote
 * and this one does not know reads as [DeviceRefusal.LimitReached]. The bundle is
 * read whole or not at all, and one that failed to read would be every list lost.
 */
object DeviceRefusalSerializer : KSerializer<DeviceRefusal> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("org.olcbox.app.data.model.DeviceRefusal", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: DeviceRefusal) = encoder.encodeString(value.wireName)

    override fun deserialize(decoder: Decoder): DeviceRefusal {
        val name = decoder.decodeString()
        return DeviceRefusal.entries.firstOrNull { it.wireName == name } ?: DeviceRefusal.LimitReached
    }
}
