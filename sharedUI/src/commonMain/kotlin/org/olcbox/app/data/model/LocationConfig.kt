package org.olcbox.app.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.olcbox.app.net.LinkParser
import org.olcbox.app.net.LocationKind
import org.olcbox.app.net.transportKind
import org.olcbox.app.net.OutboundSpec

@Serializable
data class LocationConfig(
    val name: String = "",
    val id: String = "",
    val key: String = "",
    @SerialName("bypass_provider")
    val bypassProvider: String = DEFAULT_BYPASS_PROVIDER,
    val transport: String = DEFAULT_TRANSPORT,
    @SerialName("vp8_fps")
    val vp8Fps: Int = DEFAULT_VP8_FPS,
    @SerialName("vp8_batch")
    val vp8Batch: Int = DEFAULT_VP8_BATCH,
    @SerialName("kind")
    val kind: LocationKind = LocationKind.Olcrtc,
    @SerialName("raw_link")
    val rawLink: String? = null,
    /**
     * The other rooms that, with [id], form one failover group sharing this
     * location's key, carrier and transport: the `##rooms` header of a
     * subscription whose server moves its clients between short-lived rooms.
     * The engine hops to them when the room it is in ends.
     */
    @SerialName("failover_rooms")
    val failoverRoomIds: List<String> = emptyList()
) {
    fun normalized(): LocationConfig {
        val provider = normalizeProvider(bypassProvider)
        val normalizedTransport = normalizeTransport(transport, provider)
        return copy(
            name = name.trim(),
            id = id.trim(),
            key = key.trim(),
            bypassProvider = provider,
            transport = normalizedTransport,
            vp8Fps = sanitizeVp8Fps(vp8Fps),
            vp8Batch = sanitizeVp8Batch(vp8Batch),
            failoverRoomIds = failoverRoomIds
                .map { it.trim() }
                .filter { it.isNotBlank() && it != id.trim() }
                .distinct()
        )
    }

    /** The rooms in the order the engine walks them: the primary, then the extras. */
    fun failoverRooms(): List<String> = (listOf(id) + failoverRoomIds)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

    fun isComplete(): Boolean = when (kind) {
        LocationKind.Olcrtc -> id.isNotBlank() && key.isNotBlank()
        LocationKind.Vless, LocationKind.Hysteria2 ->
            rawLink?.let { LinkParser.parse(it) != null } ?: false
    }

    /**
     * Whether a latency measurement is possible for this location at all.
     *
     * The prober joins an olcRTC room and times an HTTP request through it —
     * that is the only measurement the app has. Handing it a Reality, XHTTP or
     * Hysteria2 location means handing it a room id that is not a room, so it
     * cannot succeed by construction; the null it returns was then stored and
     * drawn as **Offline**, marking working exits dead. Measuring nothing is
     * the honest answer where nothing can be measured.
     */
    fun isPingable(): Boolean = kind == LocationKind.Olcrtc && isComplete()

    fun displayName(): String = name.ifBlank { id }

    fun providerName(): String = providerDisplayName(bypassProvider)

    fun transportName(): String = transportDisplayName(transport)

    /**
     * Protocol labels for the location list subtitle.
     *
     * [bypassProvider]/[transport] describe the olcRTC carrier (whiteboard stream,
     * VP8 channel…) and are meaningless for imported xray-side links — those kept
     * the defaults and rendered as "WB stream · VP8". Only olcRTC locations get the
     * carrier labels; vless/hysteria2 report what their link actually uses.
     */
    fun protocolLabels(): List<String> = when (kind) {
        LocationKind.Olcrtc -> listOf(providerName(), transportName())
        LocationKind.Vless -> {
            // Unparseable links report the protocol alone rather than guessing.
            val label = rawLink?.let { LinkParser.parse(it) }?.let { transportKind().label() }
            listOfNotNull("VLESS", label)
        }
        LocationKind.Hysteria2 -> {
            val spec = rawLink?.let { LinkParser.parse(it) } as? OutboundSpec.Hysteria2
            listOfNotNull(
                "Hysteria2",
                "Salamander".takeIf { !spec?.obfsPassword.isNullOrBlank() }
            )
        }
    }

    companion object {
        const val PROVIDER_TELEMOST = "telemost"
        const val PROVIDER_WB_STREAM = "wbstream"
        const val PROVIDER_JITSI = "jitsi"
        /**
         * The third carrier (Sber SaluteJazz, LiveKit-as-JSON over pion),
         * merged into the engine 2026-09-22. This value is forwarded verbatim
         * as the engine's `auth.provider` (see OlcRtcCommand.desktopProviderArg,
         * IosVpnManager.carrierName, OlcboxVpnService.setProvider), and the
         * engine answers to `salutejazz` alone. `jazz` is the same service
         * under upstream olcrtc's older name, gone from the engine since May
         * 2026 and kept by olcbox's picker: [normalizeProvider] maps it here.
         */
        const val PROVIDER_SALUTEJAZZ = "salutejazz"
        const val DEFAULT_BYPASS_PROVIDER = PROVIDER_WB_STREAM

        const val TRANSPORT_DATACHANNEL = "datachannel"
        const val TRANSPORT_VP8CHANNEL = "vp8channel"
        const val TRANSPORT_SEICHANNEL = "seichannel"
        const val DEFAULT_TRANSPORT = TRANSPORT_VP8CHANNEL

        const val DEFAULT_VP8_FPS = 60
        const val DEFAULT_VP8_BATCH = 64

        val supportedBypassProviders = listOf(
            PROVIDER_TELEMOST,
            PROVIDER_WB_STREAM,
            PROVIDER_JITSI,
            PROVIDER_SALUTEJAZZ
        )

        val supportedTransports = listOf(
            TRANSPORT_DATACHANNEL,
            TRANSPORT_VP8CHANNEL,
            TRANSPORT_SEICHANNEL
        )

        fun supportedTransportsForProvider(provider: String): List<String> {
            return when (normalizeProvider(provider)) {
                PROVIDER_TELEMOST -> listOf(TRANSPORT_VP8CHANNEL, TRANSPORT_SEICHANNEL)
                PROVIDER_JITSI -> listOf(TRANSPORT_DATACHANNEL)
                // SaluteJazz guests get data channels only, no media track.
                PROVIDER_SALUTEJAZZ -> listOf(TRANSPORT_DATACHANNEL)
                else -> supportedTransports
            }
        }

        fun normalizeProvider(value: String): String {
            return when (value.trim().lowercase()) {
                PROVIDER_TELEMOST, "yandex", "yandex_telemost" -> PROVIDER_TELEMOST
                PROVIDER_WB_STREAM, "wbstream", "wb-stream", "wildberries" -> PROVIDER_WB_STREAM
                PROVIDER_JITSI, "jitsi-meet", "jitsi_meet", "meet" -> PROVIDER_JITSI
                PROVIDER_SALUTEJAZZ, "jazz", "sberjazz", "sber_jazz" -> PROVIDER_SALUTEJAZZ
                else -> DEFAULT_BYPASS_PROVIDER
            }
        }

        /** The transport [value] names, or null when it names none. */
        fun transportOrNull(value: String): String? {
            return when (value.trim().lowercase()) {
                TRANSPORT_DATACHANNEL, "data", "dc" -> TRANSPORT_DATACHANNEL
                TRANSPORT_VP8CHANNEL, "vp8", "video_vp8", "video-vp8" -> TRANSPORT_VP8CHANNEL
                TRANSPORT_SEICHANNEL, "sei", "sei_channel", "sei-channel", "h264_sei" -> TRANSPORT_SEICHANNEL
                else -> null
            }
        }

        /**
         * The transport the room will run over: what [value] names when the
         * provider carries it, else the provider's own. A link that asked for
         * the former is remembered by the import as [LocationMetadata.requestedTransport].
         */
        fun normalizeTransport(value: String, provider: String = DEFAULT_BYPASS_PROVIDER): String {
            val normalized = transportOrNull(value) ?: DEFAULT_TRANSPORT
            val supported = supportedTransportsForProvider(provider)
            return normalized.takeIf { it in supported }
                ?: supported.firstOrNull()
                ?: DEFAULT_TRANSPORT
        }

        fun providerDisplayName(provider: String): String {
            return when (normalizeProvider(provider)) {
                PROVIDER_TELEMOST -> "Telemost"
                PROVIDER_WB_STREAM -> "WB Stream"
                PROVIDER_JITSI -> "Jitsi"
                PROVIDER_SALUTEJAZZ -> "SaluteJazz"
                else -> "WB Stream"
            }
        }

        fun transportDisplayName(transport: String): String {
            return when (normalizeTransport(transport)) {
                TRANSPORT_DATACHANNEL -> "DataChannel"
                TRANSPORT_VP8CHANNEL -> "VP8"
                TRANSPORT_SEICHANNEL -> "SEI"
                else -> "VP8"
            }
        }

        fun sanitizeVp8Fps(value: Int): Int = value.coerceIn(1, 120)

        fun sanitizeVp8Batch(value: Int): Int = value.coerceIn(1, 64)
    }
}

@Serializable
data class Vp8TransportConfig(
    val fps: Int = LocationConfig.DEFAULT_VP8_FPS,
    val batch: Int = LocationConfig.DEFAULT_VP8_BATCH
) {
    fun normalized(): Vp8TransportConfig {
        return copy(
            fps = LocationConfig.sanitizeVp8Fps(fps),
            batch = LocationConfig.sanitizeVp8Batch(batch)
        )
    }

    companion object {
        fun from(config: LocationConfig): Vp8TransportConfig {
            return Vp8TransportConfig(config.vp8Fps, config.vp8Batch).normalized()
        }
    }
}

@Serializable(with = LocationTransportConfigSerializer::class)
data class LocationTransportConfig(
    val type: String = LocationConfig.DEFAULT_TRANSPORT,
    val vp8: Vp8TransportConfig? = null
) {
    fun normalized(provider: String): LocationTransportConfig {
        val normalizedType = LocationConfig.normalizeTransport(type, provider)
        return copy(
            type = normalizedType,
            vp8 = if (normalizedType == LocationConfig.TRANSPORT_VP8CHANNEL) {
                (vp8 ?: Vp8TransportConfig()).normalized()
            } else {
                null
            }
        )
    }

    companion object {
        fun from(config: LocationConfig): LocationTransportConfig {
            val normalized = config.normalized()
            return LocationTransportConfig(
                type = normalized.transport,
                vp8 = if (normalized.transport == LocationConfig.TRANSPORT_VP8CHANNEL) {
                    Vp8TransportConfig.from(normalized)
                } else {
                    null
                }
            )
        }
    }
}

@Serializable
private data class LocationTransportConfigSurrogate(
    val type: String = LocationConfig.DEFAULT_TRANSPORT,
    val vp8: Vp8TransportConfig? = null
)

object LocationTransportConfigSerializer : KSerializer<LocationTransportConfig> {
    override val descriptor: SerialDescriptor = LocationTransportConfigSurrogate.serializer().descriptor

    override fun deserialize(decoder: Decoder): LocationTransportConfig {
        val jsonDecoder = decoder as? JsonDecoder ?: return LocationTransportConfig()
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> LocationTransportConfig(type = element.contentOrNull.orEmpty())
            is JsonObject -> {
                val surrogate = jsonDecoder.json.decodeFromJsonElement(
                    LocationTransportConfigSurrogate.serializer(),
                    element
                )
                LocationTransportConfig(
                    type = surrogate.type,
                    vp8 = surrogate.vp8
                )
            }
            else -> LocationTransportConfig()
        }
    }

    override fun serialize(encoder: Encoder, value: LocationTransportConfig) {
        val jsonEncoder = encoder as? JsonEncoder
        val surrogate = LocationTransportConfigSurrogate(
            type = value.type,
            vp8 = value.vp8
        )
        if (jsonEncoder != null) {
            jsonEncoder.encodeJsonElement(
                jsonEncoder.json.encodeToJsonElement(
                    LocationTransportConfigSurrogate.serializer(),
                    surrogate
                )
            )
        } else {
            encoder.encodeSerializableValue(LocationTransportConfigSurrogate.serializer(), surrogate)
        }
    }
}

@Serializable
data class LocationEndpointConfig(
    @SerialName("room_id")
    val roomId: String = "",
    val key: String = "",
    @SerialName("client_id")
    val legacyClientId: String? = null
)

@Serializable
data class SubscriptionMetadata(
    val name: String? = null,
    val update: String? = null,
    val refresh: String? = null,
    val color: String? = null,
    val icon: String? = null,
    val used: String? = null,
    val available: String? = null,
    @SerialName("update_interval_hours")
    val updateIntervalHours: Int? = null,
    @SerialName("last_refresh_at_epoch_ms")
    val lastRefreshAtEpochMs: Long? = null,
    /** From `subscription-userinfo`; 0 or absent means it never expires. */
    @SerialName("expires_at_epoch_ms")
    val expiresAtEpochMs: Long? = null,
    /** `support-url`. A provider's own way of being reached — usually a bot. */
    @SerialName("support_url")
    val supportUrl: String? = null,
    /** `profile-web-page-url`. */
    @SerialName("web_page_url")
    val webPageUrl: String? = null,
    /**
     * `announce`: the provider's note to its users, at most
     * [ANNOUNCE_MAX_CHARS] characters. Kept until the provider sends `0`.
     */
    @SerialName("announce")
    val announce: String? = null,
    /**
     * `fallback-url`: where the list is asked for when its own address does
     * not answer, as a provider whose domain was blocked arranges in advance.
     */
    @SerialName("fallback_url")
    val fallbackUrl: String? = null
) {
    fun normalized(): SubscriptionMetadata {
        return copy(
            name = name.cleanMetadataValue(),
            update = update.cleanMetadataValue(),
            refresh = refresh.cleanMetadataValue(),
            color = color.cleanMetadataValue(),
            icon = icon.cleanMetadataValue(),
            used = used.cleanMetadataValue(),
            available = available.cleanMetadataValue(),
            updateIntervalHours = updateIntervalHours?.coerceIn(MIN_UPDATE_INTERVAL_HOURS, MAX_UPDATE_INTERVAL_HOURS),
            lastRefreshAtEpochMs = lastRefreshAtEpochMs?.takeIf { it > 0 },
            expiresAtEpochMs = expiresAtEpochMs?.takeIf { it > 0 },
            supportUrl = supportUrl.cleanMetadataValue(),
            webPageUrl = webPageUrl.cleanMetadataValue(),
            announce = announce.cleanMetadataValue()?.take(ANNOUNCE_MAX_CHARS),
            fallbackUrl = fallbackUrl.cleanMetadataValue()
        )
    }

    fun isEmpty(): Boolean {
        return name.isNullOrBlank() &&
                update.isNullOrBlank() &&
                refresh.isNullOrBlank() &&
                color.isNullOrBlank() &&
                icon.isNullOrBlank() &&
                used.isNullOrBlank() &&
                available.isNullOrBlank() &&
                updateIntervalHours == null &&
                lastRefreshAtEpochMs == null &&
                expiresAtEpochMs == null &&
                supportUrl.isNullOrBlank() &&
                webPageUrl.isNullOrBlank() &&
                announce.isNullOrBlank() &&
                fallbackUrl.isNullOrBlank()
    }

    companion object {
        const val DEFAULT_UPDATE_INTERVAL_HOURS = 24
        const val MIN_UPDATE_INTERVAL_HOURS = 1
        const val MAX_UPDATE_INTERVAL_HOURS = 720
        /** What Happ displays of an `announce`; providers write to that length. */
        const val ANNOUNCE_MAX_CHARS = 200
    }
}

@Serializable
data class LocationMetadata(
    val name: String? = null,
    val color: String? = null,
    val icon: String? = null,
    val used: String? = null,
    val available: String? = null,
    val ip: String? = null,
    val comment: String? = null,
    val mimo: String? = null,
    val subscription: SubscriptionMetadata? = null,
    /**
     * The transport the link asked for when the provider cannot carry it -
     * vp8channel on a Jitsi room - and the room runs over another. Kept so the
     * board and the connect button can say so instead of silently rewriting.
     */
    val requestedTransport: String? = null
) {
    fun normalized(): LocationMetadata {
        val normalizedSubscription = subscription
            ?.normalized()
            ?.takeUnless { it.isEmpty() }
        return copy(
            name = name.cleanMetadataValue(),
            color = color.cleanMetadataValue(),
            icon = icon.cleanMetadataValue(),
            used = used.cleanMetadataValue(),
            available = available.cleanMetadataValue(),
            ip = ip.cleanMetadataValue(),
            comment = comment.cleanMetadataValue(),
            mimo = mimo.cleanMetadataValue(),
            subscription = normalizedSubscription,
            requestedTransport = requestedTransport.cleanMetadataValue()
        )
    }

    fun isEmpty(): Boolean {
        return name.isNullOrBlank() &&
                color.isNullOrBlank() &&
                icon.isNullOrBlank() &&
                used.isNullOrBlank() &&
                available.isNullOrBlank() &&
                ip.isNullOrBlank() &&
                comment.isNullOrBlank() &&
                mimo.isNullOrBlank() &&
                requestedTransport.isNullOrBlank() &&
                (subscription == null || subscription.isEmpty())
    }
}

@Serializable
data class LocationEntry(
    @SerialName("storage_id")
    val storageId: String,
    val name: String = "",
    @SerialName("subscription_url")
    val subscriptionUrl: String? = null,
    /**
     * The encrypted link this subscription arrived as — `olcrtc://crypt1/…` or a
     * partner's `happ://crypt5/…` — when it did.
     *
     * Its presence is what makes a subscription secret: the link exists so the
     * endpoint is not readable, and [subscriptionUrl] holds exactly the endpoint it
     * was hiding. Also what QR/share hands out, so moving a subscription to a second
     * device does not decrypt it on the way.
     */
    @SerialName("subscription_origin_link")
    val subscriptionOriginLink: String? = null,
    val endpoint: LocationEndpointConfig? = null,
    /**
     * The other rooms of this location's failover group, from the
     * subscription's `##rooms` header. Persisted: a list that lived only in
     * the parse was dropped on the first save, and the one room the client
     * then knew was the one the server was about to retire.
     */
    @SerialName("failover_rooms")
    val failoverRooms: List<String> = emptyList(),
    @SerialName("auth_provider")
    val authProvider: String? = null,
    @SerialName("carrier")
    val legacyCarrier: String? = null,
    val transport: LocationTransportConfig? = null,
    val metadata: LocationMetadata? = null,
    @SerialName("subscriptionUrl")
    val legacySubscriptionUrl: String? = null,
    @SerialName("id")
    val legacyId: String? = null,
    @SerialName("room_id")
    val legacyRoomId: String? = null,
    @SerialName("server")
    val legacyServer: String? = null,
    @SerialName("client_id")
    val legacyClientId: String? = null,
    @SerialName("clientId")
    val legacyClientIdCamel: String? = null,
    @SerialName("key")
    val legacyKey: String? = null,
    @SerialName("password")
    val legacyPassword: String? = null,
    @SerialName("bypass_provider")
    val legacyBypassProvider: String? = null,
    @SerialName("bypassProvider")
    val legacyBypassProviderCamel: String? = null,
    @SerialName("provider")
    val legacyProvider: String? = null,
    @SerialName("vp8_fps")
    val legacyVp8Fps: Int? = null,
    @SerialName("vp8Fps")
    val legacyVp8FpsCamel: Int? = null,
    @SerialName("vp8_batch")
    val legacyVp8Batch: Int? = null,
    @SerialName("vp8Batch")
    val legacyVp8BatchCamel: Int? = null,
    @SerialName("kind")
    val kind: LocationKind = LocationKind.Olcrtc,
    @SerialName("raw_link")
    val rawLink: String? = null
) {
    val location: LocationConfig
        get() {
            val provider = firstNotBlank(
                authProvider,
                legacyCarrier,
                legacyBypassProvider,
                legacyBypassProviderCamel,
                legacyProvider
            )
            val transportConfig = transport ?: LocationTransportConfig()
            val vp8Options = transportConfig.vp8
            return LocationConfig(
                name = name,
                id = firstNotBlank(endpoint?.roomId, legacyId, legacyRoomId, legacyServer),
                key = firstNotBlank(endpoint?.key, legacyKey, legacyPassword),
                bypassProvider = provider,
                transport = transportConfig.type,
                vp8Fps = vp8Options?.fps
                    ?: legacyVp8Fps
                    ?: legacyVp8FpsCamel
                    ?: LocationConfig.DEFAULT_VP8_FPS,
                vp8Batch = vp8Options?.batch
                    ?: legacyVp8Batch
                    ?: legacyVp8BatchCamel
                    ?: LocationConfig.DEFAULT_VP8_BATCH,
                kind = kind,
                rawLink = rawLink,
                failoverRoomIds = failoverRooms
            ).normalized()
        }

    val bypassProvider: String
        get() = location.bypassProvider

    fun normalized(): LocationEntry {
        val config = location
        return LocationEntry(
            storageId = storageId.trim(),
            name = config.name,
            subscriptionUrl = firstNotBlank(subscriptionUrl, legacySubscriptionUrl).ifBlank { null },
            subscriptionOriginLink = subscriptionOriginLink?.trim()?.ifBlank { null },
            endpoint = LocationEndpointConfig(
                roomId = config.id,
                key = config.key
            ),
            failoverRooms = config.failoverRoomIds,
            authProvider = config.bypassProvider,
            transport = LocationTransportConfig.from(config),
            metadata = metadata
                ?.normalized()
                ?.takeUnless { it.isEmpty() },
            kind = config.kind,
            rawLink = config.rawLink
        )
    }

    companion object {
        fun from(
            storageId: String,
            location: LocationConfig,
            subscriptionUrl: String? = null,
            subscriptionOriginLink: String? = null,
            metadata: LocationMetadata? = null
        ): LocationEntry {
            val config = location.normalized()
            return LocationEntry(
                storageId = storageId,
                name = config.name,
                subscriptionUrl = subscriptionUrl,
                subscriptionOriginLink = subscriptionOriginLink,
                endpoint = LocationEndpointConfig(
                    roomId = config.id,
                    key = config.key
                ),
                failoverRooms = config.failoverRoomIds,
                authProvider = config.bypassProvider,
                transport = LocationTransportConfig.from(config),
                metadata = metadata,
                kind = config.kind,
                rawLink = config.rawLink
            ).normalized()
        }

        private fun firstNotBlank(vararg values: String?): String {
            return values.firstOrNull { !it.isNullOrBlank() } ?: ""
        }
    }
}

private fun String?.cleanMetadataValue(): String? {
    return this?.trim()?.takeIf { it.isNotEmpty() }
}

@Serializable
data class LocationBundleV4(
    val version: Int = 5,
    @SerialName("active_location_id")
    val activeLocationId: String? = null,
    val locations: List<LocationEntry> = emptyList(),
    /**
     * How subscriptions behave. Kept here because this bundle is the one thing
     * already persisted identically on every platform — a settings store of its
     * own would be three implementations and three chances to disagree.
     */
    val settings: SubscriptionSettings = SubscriptionSettings(),
    /** What leaves through the tunnel. See [RoutingSettings]; kept here for the reason stated on [settings]. */
    @SerialName("routing")
    val routing: RoutingSettings = RoutingSettings(),
    /**
     * When the user accepted the VPN disclosure, in epoch millis; null until they
     * have. A timestamp rather than a flag because the question Play asks is when
     * consent was given, and a boolean cannot answer it afterwards.
     *
     * Kept beside [settings] for the reason stated there: this bundle is the one
     * thing already persisted identically on every platform.
     */
    @SerialName("vpn_disclosure_accepted_at")
    val vpnDisclosureAcceptedAt: Long? = null,
    /**
     * When the first-run walkthrough was last completed or skipped, in epoch
     * millis; null until it has been, and null again after "replay first run".
     *
     * Nullable rather than a boolean because unlike the disclosure above this one
     * is meant to be resettable — consent is a fact about the past, an
     * introduction is a thing a user may want again.
     *
     * Kept beside [settings] for the reason stated there: this bundle is the one
     * thing already persisted identically on every platform.
     */
    @SerialName("onboarding_seen_at")
    val onboardingSeenAt: Long? = null
) {
    fun normalized(): LocationBundleV4 {
        val normalizedLocations = locations
            .map { it.normalized() }
            .filter { it.storageId.isNotBlank() && it.location.isComplete() }
            .distinctBy { it.storageId }

        val active = activeLocationId
            ?.takeIf { id -> normalizedLocations.any { it.storageId == id } }
            ?: normalizedLocations.firstOrNull()?.storageId

        return copy(
            version = CURRENT_VERSION,
            activeLocationId = active,
            locations = normalizedLocations,
            settings = settings.normalized()
        )
    }

    companion object {
        const val CURRENT_VERSION = 5
    }
}
