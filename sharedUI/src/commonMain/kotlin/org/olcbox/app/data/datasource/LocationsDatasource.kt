package org.olcbox.app.data.datasource

import kotlin.coroutines.cancellation.CancellationException
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.CurrentAppInfo
import org.olcbox.app.crypt.CryptCodec
import org.olcbox.app.data.identity.DeviceIdentityProvider
import org.olcbox.app.data.identity.PersistentDeviceIdentityProvider
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.data.model.SubscriptionSettings
import org.olcbox.app.data.model.RoutingSettings
import org.olcbox.app.data.model.LocationMetadata
import org.olcbox.app.data.model.SubscriptionMetadata
import org.olcbox.app.data.repository.LocationsRepository
import org.olcbox.app.net.HttpPartnerLinkResolver
import org.olcbox.app.net.LinkParser
import org.olcbox.app.net.PartnerLinkResolver
import org.olcbox.app.net.PartnerLinkResult
import org.olcbox.app.net.isPartnerLink
import org.olcbox.app.util.formatByteSize
import org.olcbox.app.net.LocationKind
import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import org.olcbox.app.data.repository.SubscriptionRefreshError
import org.olcbox.app.data.repository.SubscriptionRefreshFailure
import org.olcbox.app.data.repository.SubscriptionRefreshReport

/** Reports why a subscription fetch failed, without changing the fetch control flow. */
internal typealias SubscriptionFailureSink = (SubscriptionRefreshError, Int?) -> Unit

interface LocationsDataSource {
    suspend fun loadLocationBundle(): LocationBundleV4?
    suspend fun saveLocationBundle(bundle: LocationBundleV4)
    suspend fun loadLegacyLocations(): List<Pair<String, String>>
    suspend fun loadLegacyActiveLocationId(): String?
    suspend fun loadDeviceIdentity(): String? = null
    suspend fun saveDeviceIdentity(value: String) = Unit
}

internal expect fun createProxyHttpClient(
    subscriptionProxy: SubscriptionFetchProxy? = null,
    connectTimeoutMs: Long = 3_000,
    requestTimeoutMs: Long = 8_000,
    socketTimeoutMs: Long = 8_000,
    followRedirects: Boolean = true
): HttpClient

internal expect suspend fun <T> withProxyAuthentication(
    subscriptionProxy: SubscriptionFetchProxy?,
    block: suspend () -> T
): T

class LocationsRepositoryImpl(
    private val dataSource: LocationsDataSource,
    private val httpClient: HttpClient = createProxyHttpClient(),
    private val deviceIdentityProvider: DeviceIdentityProvider = PersistentDeviceIdentityProvider(dataSource),
    private val nowEpochMs: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    private val cryptCodec: CryptCodec? = CryptCodec.default(),
    private val partnerLinkResolver: PartnerLinkResolver? = HttpPartnerLinkResolver(httpClient)
) : LocationsRepository {
    private data class ImportSource(
        val content: String,
        val subscriptionUrl: String? = null,
        /** The encrypted link the user pasted, when the import began with one. */
        val originLink: String? = null,
        val updateIntervalHours: Int? = null,
        val profile: SubscriptionProfile? = null,
        val requestMode: SubscriptionRequestMode = SubscriptionRequestMode.Identity
    )

    private data class DownloadedSubscription(
        val content: String,
        val updateIntervalHours: Int?,
        val profile: SubscriptionProfile?
    )

    /**
     * What a subscription says about itself in its response headers.
     *
     * Every provider that matters serves these, and until now only the update
     * interval was read — so the list showed the subscription's *hostname*,
     * which is both the least useful thing to call it and the one thing worth
     * not printing on a shared screen.
     */
    private data class SubscriptionProfile(
        val title: String? = null,
        val usedBytes: Long? = null,
        val totalBytes: Long? = null,
        val expiresAtEpochMs: Long? = null,
        val supportUrl: String? = null,
        val webPageUrl: String? = null
    ) {
        fun isEmpty(): Boolean = title.isNullOrBlank() &&
            usedBytes == null && totalBytes == null && expiresAtEpochMs == null &&
            supportUrl.isNullOrBlank() && webPageUrl.isNullOrBlank()
    }

    private data class ParsedImport(
        val bundle: LocationBundleV4,
        val mode: ImportMode
    )

    private data class ResolvedImport(
        val source: ImportSource,
        val parsed: ParsedImport
    )

    private data class ParsedOlcRtcUri(
        val location: LocationConfig,
        val mimo: String? = null,
        /** What the link asked for, when the room will run over something else. */
        val requestedTransport: String? = null
    )

    private enum class ImportMode {
        Additive,
        Restore
    }

    private enum class SubscriptionRequestMode {
        Identity,
        Compatibility
    }

    private val mutationMutex = Mutex()
    private val _changes = MutableStateFlow(0L)
    override val changes: StateFlow<Long> = _changes.asStateFlow()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }

    override suspend fun getBundle(): LocationBundleV4 {
        return mutationMutex.withLock {
            getBundleUnlocked()
        }
    }

    private suspend fun getBundleUnlocked(): LocationBundleV4 {
        val stored = dataSource.loadLocationBundle()?.normalized()
        if (stored != null && stored.locations.isNotEmpty()) return stored

        // No locations yet: look for the pre-bundle storage once and adopt what
        // it has. But adopt only the locations. The stored bundle, even with
        // none, is the device's record of everything else — its settings, the
        // routing choice, the accepted disclosure — and replacing it wholesale
        // reset all of that on every fresh install until the first server list
        // arrived, and again whenever the last one was deleted.
        val legacy = migrateLegacyBundle()
        if (legacy.locations.isNotEmpty()) {
            val adopted = stored?.copy(
                activeLocationId = legacy.activeLocationId,
                locations = legacy.locations
            ) ?: legacy
            dataSource.saveLocationBundle(adopted)
            return adopted
        }
        return stored ?: legacy
    }

    override suspend fun saveBundle(bundle: LocationBundleV4) {
        mutationMutex.withLock {
            saveBundleUnlocked(bundle)
        }
    }

    private suspend fun saveBundleUnlocked(bundle: LocationBundleV4) {
        dataSource.saveLocationBundle(bundle.normalized())
        _changes.value = _changes.value + 1
    }

    override suspend fun exportBundle(): String {
        return json.encodeToString(LocationBundleV4.serializer(), getBundle())
    }

    override suspend fun importText(text: String, subscriptionProxy: SubscriptionFetchProxy?): Boolean {
        val resolved = resolveParsedImport(
            text = text,
            subscriptionProxy = subscriptionProxy
        ) ?: return false

        mutationMutex.withLock {
            val merged = mergeImportedBundle(
                current = getBundleUnlocked(),
                imported = resolved.parsed.bundle.normalized(),
                replaceMatchingStorageIds = resolved.parsed.mode == ImportMode.Restore
            )
            saveBundleUnlocked(merged)
        }
        return true
    }

    override suspend fun refreshSubscriptions(
        subscriptionProxy: SubscriptionFetchProxy?
    ): SubscriptionRefreshReport {
        return mutationMutex.withLock {
            refreshSubscriptionsUnlocked(
                onlyUrls = null,
                subscriptionProxy = subscriptionProxy
            )
        }
    }

    override suspend fun refreshSubscription(
        subscriptionUrl: String,
        subscriptionProxy: SubscriptionFetchProxy?
    ): SubscriptionRefreshReport {
        val normalizedUrl = subscriptionUrl.trim()
        if (normalizedUrl.isBlank()) return SubscriptionRefreshReport.EMPTY
        return mutationMutex.withLock {
            refreshSubscriptionsUnlocked(
                onlyUrls = setOf(normalizedUrl),
                subscriptionProxy = subscriptionProxy
            )
        }
    }

    private suspend fun refreshSubscriptionsUnlocked(
        onlyUrls: Set<String>?,
        subscriptionProxy: SubscriptionFetchProxy?
    ): SubscriptionRefreshReport {
        val bundle = getBundleUnlocked()
        if (bundle.locations.isEmpty()) return SubscriptionRefreshReport.EMPTY

        val groupedByUrl = bundle.locations
            .mapNotNull { entry -> entry.subscriptionUrl?.trim()?.takeIf { it.isNotBlank() }?.let { it to entry } }
            .groupBy({ it.first }, { it.second })
            .filterKeys { url -> onlyUrls == null || url in onlyUrls }
        if (groupedByUrl.isEmpty()) return SubscriptionRefreshReport.EMPTY

        val targetUrls = groupedByUrl.keys
        val refreshedLocations = bundle.locations
            .filter { entry ->
                val url = entry.subscriptionUrl?.trim()?.takeIf { it.isNotBlank() }
                url == null || (onlyUrls != null && url !in targetUrls)
            }
            .toMutableList()
        val usedStorageIds = refreshedLocations.mapTo(mutableSetOf()) { it.storageId }
        val activeBefore = bundle.activeLocationId
        var activeAfter = activeBefore
        var successfulRefreshes = 0
        val failures = mutableListOf<SubscriptionRefreshFailure>()

        fun preservePreviousEntries(entries: List<LocationEntry>) {
            entries.forEach { entry ->
                if (usedStorageIds.add(entry.storageId)) {
                    refreshedLocations += entry
                }
            }
        }

        groupedByUrl.forEach { (url, previousEntries) ->
            val previousInterval = previousEntries.subscriptionUpdateIntervalHours()
            // A refresh re-imports from the stored plaintext URL and never sees the
            // encrypted link the subscription arrived as. Without this, the first
            // auto-refresh quietly declassifies it.
            val previousOriginLink = previousEntries.firstNotNullOfOrNull { it.subscriptionOriginLink }
            // Keep the last reported reason: it belongs to the attempt that
            // ultimately failed (Identity is retried in Compatibility mode).
            var lastFailure: SubscriptionRefreshFailure? = null
            val resolved = resolveParsedImport(
                text = url,
                fallbackSubscriptionInterval = previousInterval,
                subscriptionProxy = subscriptionProxy,
                onFailure = { error, status ->
                    lastFailure = SubscriptionRefreshFailure(url, error, status)
                }
            ) ?: run {
                preservePreviousEntries(previousEntries)
                // Downloaded fine but nothing importable ⇒ Empty.
                failures += lastFailure
                    ?: SubscriptionRefreshFailure(url, SubscriptionRefreshError.Empty)
                return@forEach
            }
            val source = resolved.source
            val updateInterval = source.updateIntervalHours
                ?: previousInterval
                ?: SubscriptionMetadata.DEFAULT_UPDATE_INTERVAL_HOURS
            val refreshTimestamp = nowEpochMs()
            val refreshed = resolved.parsed.bundle.locations
            if (refreshed.isEmpty()) {
                preservePreviousEntries(previousEntries)
                failures += SubscriptionRefreshFailure(url, SubscriptionRefreshError.Empty)
                return@forEach
            }

            val reusedBySignature = previousEntries
                .groupBy { subscriptionSignature(it.location) }
                .mapValues { (_, entries) -> entries.toMutableList() }

            // Which previous entry each refreshed line inherits, decided for all of them
            // first, and every inherited id taken before any new line is named. A new
            // line's id comes from its name, and lines can share a name (a partner's
            // list names one origin's carriers alike): named while an inherited id was
            // still free, the new line got that very id, normalized() kept one entry per
            // id, and the new carrier vanished beside the old one.
            val reusedEntries = refreshed.map { entry ->
                val reusedPool = reusedBySignature[subscriptionSignature(entry.location)]
                if (reusedPool.isNullOrEmpty()) null else reusedPool.removeAt(0)
            }
            reusedEntries.forEach { reused -> reused?.let { usedStorageIds.add(it.storageId) } }

            val reassigned = refreshed.mapIndexed { index, entry ->
                val reusedEntry = reusedEntries[index]
                val storageId = reusedEntry?.storageId ?: uniqueStorageId(
                    base = "imported_${entry.location.storageSlug().ifBlank { "location_${index + 1}" }}",
                    used = usedStorageIds
                )
                if (activeBefore == reusedEntry?.storageId) {
                    activeAfter = storageId
                }
                entry.copy(
                    storageId = storageId,
                    subscriptionUrl = url,
                    subscriptionOriginLink = entry.subscriptionOriginLink ?: previousOriginLink,
                    metadata = entry.metadata.withSubscriptionRefreshState(
                        updateIntervalHours = updateInterval,
                        lastRefreshAtEpochMs = refreshTimestamp,
                        profile = source.profile
                    )
                ).normalized()
            }

            // Move the selection only when the server it points at is gone.
            //
            // This asked the opposite. Reuse hands an entry back its own storageId,
            // so `activeAfter == activeBefore` is true exactly when the selection
            // came through the refresh intact — and the branch then threw it away
            // and took the first entry instead. Every refresh of the list holding
            // your server moved you to that list's first one, and because the
            // screen restarts a running tunnel when the active config changes, a
            // refresh re-dialled you onto a server you had not chosen.
            //
            // Refresh updates a list. It is not a command to change servers.
            if (activeBefore != null &&
                previousEntries.any { it.storageId == activeBefore } &&
                reassigned.none { it.storageId == activeAfter }
            ) {
                activeAfter = reassigned.firstOrNull()?.storageId
            }
            refreshedLocations += reassigned
            successfulRefreshes += 1
        }

        if (successfulRefreshes == 0) {
            return SubscriptionRefreshReport(updatedCount = 0, failures = failures)
        }

        saveBundleUnlocked(
            bundle.copy(
                activeLocationId = activeAfter,
                locations = refreshedLocations
            )
        )
        return SubscriptionRefreshReport(updatedCount = successfulRefreshes, failures = failures)
    }

    override suspend fun refreshDueSubscriptions(
        subscriptionProxy: SubscriptionFetchProxy?
    ): SubscriptionRefreshReport {
        return mutationMutex.withLock {
            val bundle = getBundleUnlocked()
            val settings = bundle.settings.normalized()
            if (!settings.autoUpdate) return@withLock SubscriptionRefreshReport.EMPTY
            val now = nowEpochMs()
            val dueUrls = bundle.locations
                .mapNotNull { entry ->
                    val url = entry.subscriptionUrl?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val metadata = entry.metadata?.subscription
                    val interval = minOf(
                        metadata?.updateIntervalHours ?: SubscriptionMetadata.DEFAULT_UPDATE_INTERVAL_HOURS,
                        settings.updateIntervalHours
                    )
                    val lastRefreshAt = metadata?.lastRefreshAtEpochMs ?: 0L
                    val intervalMs = interval.toLong() * 60L * 60L * 1_000L
                    url.takeIf { lastRefreshAt <= 0L || lastRefreshAt > now || now - lastRefreshAt >= intervalMs }
                }
                .toSet()

            if (dueUrls.isEmpty()) {
                SubscriptionRefreshReport.EMPTY
            } else {
                refreshSubscriptionsUnlocked(
                    onlyUrls = dueUrls,
                    subscriptionProxy = subscriptionProxy
                )
            }
        }
    }

    override suspend fun setSubscriptionUpdateInterval(subscriptionUrl: String, hours: Int) {
        val normalizedUrl = subscriptionUrl.trim()
        if (normalizedUrl.isBlank()) return

        mutationMutex.withLock {
            val interval = hours.coerceIn(
                SubscriptionMetadata.MIN_UPDATE_INTERVAL_HOURS,
                SubscriptionMetadata.MAX_UPDATE_INTERVAL_HOURS
            )
            val bundle = getBundleUnlocked()
            val updated = bundle.locations.map { entry ->
                if (entry.subscriptionUrl?.trim() != normalizedUrl) {
                    entry
                } else {
                    entry.copy(
                        metadata = entry.metadata.withSubscriptionInterval(interval)
                    ).normalized()
                }
            }

            saveBundleUnlocked(bundle.copy(locations = updated))
        }
    }

    override suspend fun saveLocation(storageId: String, location: LocationConfig) {
        mutationMutex.withLock {
            val normalizedId = storageId.ifBlank { location.storageSlug() }
            val bundle = getBundleUnlocked()
            val current = bundle.locations.firstOrNull { it.storageId == normalizedId }
            val entry = LocationEntry.from(
                storageId = normalizedId,
                location = location,
                subscriptionUrl = current?.subscriptionUrl,
                metadata = current?.metadata
            )
            val locations = bundle.locations
                .filterNot { it.storageId == entry.storageId } + entry

            saveBundleUnlocked(
                bundle.copy(
                    activeLocationId = entry.storageId,
                    locations = locations
                )
            )
        }
    }

    override suspend fun loadLocation(storageId: String): LocationConfig? {
        return mutationMutex.withLock {
            getBundleUnlocked().locations.firstOrNull { it.storageId == storageId }?.location
        }
    }

    override suspend fun deleteSubscription(subscriptionUrl: String): Int {
        val normalizedUrl = subscriptionUrl.trim()
        if (normalizedUrl.isBlank()) return 0
        return mutationMutex.withLock {
            val bundle = getBundleUnlocked()
            val remaining = bundle.locations.filterNot { entry ->
                entry.subscriptionUrl?.trim() == normalizedUrl
            }
            val removed = bundle.locations.size - remaining.size
            if (removed > 0) {
                // normalized() re-points activeLocationId at a surviving entry when
                // the active one belonged to the deleted subscription.
                saveBundleUnlocked(
                    bundle.copy(
                        activeLocationId = bundle.activeLocationId
                            ?.takeIf { id -> remaining.any { it.storageId == id } },
                        locations = remaining
                    )
                )
            }
            removed
        }
    }

    override suspend fun deleteLocation(storageId: String) {
        mutationMutex.withLock {
            val bundle = getBundleUnlocked()
            saveBundleUnlocked(
                bundle.copy(
                    activeLocationId = bundle.activeLocationId?.takeUnless { it == storageId },
                    locations = bundle.locations.filterNot { it.storageId == storageId }
                )
            )
        }
    }

    override suspend fun getAllLocations(): List<LocationEntry> {
        return mutationMutex.withLock {
            getBundleUnlocked().locations
        }
    }

    override suspend fun getActiveLocationId(): String? {
        return mutationMutex.withLock {
            getBundleUnlocked().activeLocationId
        }
    }

    override suspend fun setActiveLocationId(storageId: String?) {
        mutationMutex.withLock {
            val bundle = getBundleUnlocked()
            val nextActive = storageId?.takeIf { id -> bundle.locations.any { it.storageId == id } }
            saveBundleUnlocked(bundle.copy(activeLocationId = nextActive))
        }
    }

    override suspend fun getActiveLocation(): LocationEntry? {
        return mutationMutex.withLock {
            val bundle = getBundleUnlocked()
            bundle.locations.firstOrNull { it.storageId == bundle.activeLocationId }
        }
    }

    override suspend fun getDeviceIdentity(): String {
        return deviceIdentityProvider.hwid()
    }

    override suspend fun getSubscriptionSettings(): SubscriptionSettings =
        getBundle().settings.normalized()

    override suspend fun saveSubscriptionSettings(settings: SubscriptionSettings) {
        mutationMutex.withLock {
            val bundle = getBundleUnlocked()
            saveBundleUnlocked(bundle.copy(settings = settings.normalized()))
        }
    }

    override suspend fun getRoutingSettings(): RoutingSettings = getBundle().routing

    override suspend fun saveRoutingSettings(settings: RoutingSettings) {
        mutationMutex.withLock {
            val bundle = getBundleUnlocked()
            saveBundleUnlocked(bundle.copy(routing = settings))
        }
    }

    override suspend fun isVpnDisclosureAccepted(): Boolean =
        getBundle().vpnDisclosureAcceptedAt != null

    override suspend fun acceptVpnDisclosure(atMillis: Long) {
        mutationMutex.withLock {
            val bundle = getBundleUnlocked()
            // Never overwritten once set: the value that matters is when consent
            // was first given, and re-accepting on a later screen would quietly
            // move it forward.
            if (bundle.vpnDisclosureAcceptedAt == null) {
                saveBundleUnlocked(bundle.copy(vpnDisclosureAcceptedAt = atMillis))
            }
        }
    }

    override suspend fun isOnboardingSeen(): Boolean =
        getBundle().onboardingSeenAt != null

    override suspend fun setOnboardingSeen(atMillis: Long?) {
        mutationMutex.withLock {
            val bundle = getBundleUnlocked()
            // Overwritten freely, unlike the disclosure timestamp: this one is a
            // note to the app about what it has shown, not a record of consent.
            saveBundleUnlocked(bundle.copy(onboardingSeenAt = atMillis))
        }
    }

    private suspend fun resolveParsedImport(
        text: String,
        fallbackSubscriptionInterval: Int? = null,
        subscriptionProxy: SubscriptionFetchProxy? = null,
        onFailure: SubscriptionFailureSink? = null
    ): ResolvedImport? {
        val input = text.normalizedImportText()
        if (input.isBlank()) return null

        // What the user actually pasted, when that was an encrypted link. Everything
        // below sees only plaintext — the resolver and the codec have done their work
        // by then — so this is the last place the origin can still be observed, and
        // the list has to be able to say "Encrypted link" instead of printing the URL
        // we decrypted out of it.
        val originLink = input.takeIf { isPartnerLink(it) || CryptCodec.isCryptLink(it) }

        // A partner's Happ link is opaque to us. Whoever minted it can say what it
        // points at and hands back a crypt1 link, which the next step already knows
        // how to open. Marker-only, so nothing else takes this detour.
        val resolvedInput = if (isPartnerLink(input) && partnerLinkResolver != null) {
            when (val result = partnerLinkResolver.resolve(input)) {
                is PartnerLinkResult.Resolved -> result.link
                PartnerLinkResult.NotFound -> {
                    onFailure?.invoke(SubscriptionRefreshError.Rejected, 404)
                    return null
                }
                PartnerLinkResult.Unavailable -> {
                    onFailure?.invoke(SubscriptionRefreshError.Unreachable, null)
                    return null
                }
            }
        } else {
            input
        }

        // crypt1: a pasted `olcrtc://crypt1/<blob>` link decrypts to either an
        // http(s) subscription URL or inline olcrtc:// lines. Marker-only, so a
        // plain link falls straight through unchanged.
        val effective = cryptCodec?.decryptLink(resolvedInput) ?: resolvedInput

        var source = resolveImportSource(
            text = effective,
            requestMode = SubscriptionRequestMode.Identity,
            subscriptionProxy = subscriptionProxy,
            onFailure = onFailure
        ) ?: run {
            if (effective.isHttpUrl()) {
                resolveImportSource(
                    text = effective,
                    requestMode = SubscriptionRequestMode.Compatibility,
                    subscriptionProxy = subscriptionProxy,
                    onFailure = onFailure
                )
            } else {
                null
            }
        } ?: return null

        var parsed = parseImportSource(source, fallbackSubscriptionInterval, originLink)
        if (parsed == null && effective.isHttpUrl() && source.requestMode != SubscriptionRequestMode.Compatibility) {
            val fallbackSource = resolveImportSource(
                text = effective,
                requestMode = SubscriptionRequestMode.Compatibility,
                subscriptionProxy = subscriptionProxy,
                onFailure = onFailure
            )
            if (fallbackSource != null) {
                source = fallbackSource
                parsed = parseImportSource(fallbackSource, fallbackSubscriptionInterval, originLink)
            }
        }

        return parsed?.let { ResolvedImport(source, it) }
    }

    private fun parseImportSource(
        source: ImportSource,
        fallbackSubscriptionInterval: Int? = null,
        originLink: String? = null
    ): ParsedImport? {
        val initialSubscriptionInterval = source.updateIntervalHours
            ?: fallbackSubscriptionInterval
            ?: source.subscriptionUrl?.let { SubscriptionMetadata.DEFAULT_UPDATE_INTERVAL_HOURS }

        val parsed = parseImport(
            source.content.normalizedImportText(),
            source.subscriptionUrl,
            initialSubscriptionInterval
        ) ?: return null

        // Stamped here rather than only on refresh.
        //
        // The profile was applied in the refresh path alone, so a freshly pasted
        // link showed its own hostname and nothing else until the user pressed
        // the arrows — at which point the name, the quota and the expiry all
        // appeared, which looks like the first fetch simply failed to ask.
        // The first fetch *is* a refresh, and this is the one funnel both take.
        //
        // The origin link rides along for the same reason, and only for a real
        // subscription: a crypt1 link that decrypts to inline olcrtc lines makes
        // custom locations, which have no URL to hide.
        val stampProfile = source.profile != null
        val stampOrigin = originLink != null && source.subscriptionUrl != null
        if (!stampProfile && !stampOrigin) return parsed
        val stampedAt = nowEpochMs()
        return parsed.copy(
            bundle = parsed.bundle.copy(
                locations = parsed.bundle.locations.map { entry ->
                    entry.copy(
                        metadata = if (stampProfile) {
                            entry.metadata.withSubscriptionProfile(source.profile, stampedAt)
                        } else {
                            entry.metadata
                        },
                        subscriptionOriginLink = if (stampOrigin) {
                            originLink
                        } else {
                            entry.subscriptionOriginLink
                        }
                    )
                }
            )
        )
    }

    private fun LocationMetadata?.withSubscriptionProfile(
        profile: SubscriptionProfile?,
        refreshedAtEpochMs: Long
    ): LocationMetadata? {
        if (profile == null) return this
        val base = this ?: LocationMetadata()
        val subscription = (base.subscription.withProfile(profile) ?: SubscriptionMetadata())
            .copy(lastRefreshAtEpochMs = refreshedAtEpochMs)
            .normalized()
        return base.copy(subscription = subscription)
    }

    private suspend fun resolveImportSource(
        text: String,
        requestMode: SubscriptionRequestMode,
        subscriptionProxy: SubscriptionFetchProxy?,
        onFailure: SubscriptionFailureSink? = null
    ): ImportSource? {
        if (text.isBlank()) return null

        if (!text.isHttpUrl()) {
            return ImportSource(content = text.normalizedImportText())
        }

        val downloaded = downloadTextFromUrl(
            url = text,
            requestMode = requestMode,
            subscriptionProxy = subscriptionProxy,
            onFailure = onFailure
        ) ?: return null
        // crypt1: decrypt the body ONLY when the request URL carries ?crypt=1 (and
        // a codec is baked). Never heuristic-guess — a plaintext subscription must
        // never be mis-decrypted. On decrypt failure, fall back to the raw content.
        val rawContent = downloaded.content.normalizedImportText()
        val content = if (text.contains("crypt=1") && cryptCodec != null) {
            cryptCodec.decryptBody(rawContent) ?: rawContent
        } else {
            rawContent
        }
        return content
            .takeIf { it.isNotBlank() }
            ?.let {
                ImportSource(
                    content = it,
                    subscriptionUrl = text.trim(),
                    updateIntervalHours = downloaded.updateIntervalHours,
                    profile = downloaded.profile,
                    requestMode = requestMode
                )
            }
    }

    private suspend fun downloadTextFromUrl(
        url: String,
        requestMode: SubscriptionRequestMode,
        subscriptionProxy: SubscriptionFetchProxy?,
        onFailure: SubscriptionFailureSink? = null
    ): DownloadedSubscription? {
        val hwid = if (requestMode == SubscriptionRequestMode.Identity) {
            deviceIdentityProvider.hwid()
        } else {
            null
        }
        val client = if (subscriptionProxy == null) {
            httpClient
        } else {
            createProxyHttpClient(subscriptionProxy)
        }

        return try {
            withProxyAuthentication(subscriptionProxy) {
                val response = runCatching {
                    client.get(url) {
                        headers {
                            append(
                                HttpHeaders.Accept,
                                "text/plain, text/markdown, application/octet-stream, */*"
                            )
                            if (requestMode == SubscriptionRequestMode.Identity) {
                                append(HttpHeaders.UserAgent, CurrentAppInfo.userAgent)
                                append("x-hwid", hwid.orEmpty())
                            } else {
                                append(
                                    HttpHeaders.UserAgent,
                                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                                        "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
                                )
                            }
                        }
                    }
                }.onFailure { if (it is CancellationException) throw it }.getOrNull() ?: run {
                    onFailure?.invoke(SubscriptionRefreshError.Unreachable, null)
                    return@withProxyAuthentication null
                }

                if (response.status.value !in 200..299) {
                    val status = response.status.value
                    onFailure?.invoke(
                        if (status >= 500) SubscriptionRefreshError.ServerError
                        else SubscriptionRefreshError.Rejected,
                        status
                    )
                    return@withProxyAuthentication null
                }

                val content = runCatching {
                    response.bodyAsText()
                }.onFailure { if (it is CancellationException) throw it }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: run {
                        onFailure?.invoke(SubscriptionRefreshError.Empty, null)
                        return@withProxyAuthentication null
                    }

                DownloadedSubscription(
                    content = content,
                    updateIntervalHours = response.profileUpdateIntervalHours(),
                    profile = response.subscriptionProfile()
                )
            }
        } finally {
            if (subscriptionProxy != null) {
                client.close()
            }
        }
    }

    private fun String.isHttpUrl(): Boolean {
        val value = trim().lowercase()
        return value.startsWith("http://") || value.startsWith("https://")
    }

    private fun String.normalizedImportText(): String {
        return trim().removePrefix(UTF8_BOM).trim()
    }

    private suspend fun migrateLegacyBundle(): LocationBundleV4 {
        val legacy = dataSource.loadLegacyLocations().mapNotNull { (storageId, text) ->
            parseSingleLocation(text, storageId)
        }

        val active = dataSource.loadLegacyActiveLocationId()?.takeIf { id ->
            legacy.any { it.storageId == id }
        }

        return LocationBundleV4(
            activeLocationId = active,
            locations = legacy
        ).normalized()
    }

    private fun outboundToLocation(spec: OutboundSpec, rawLine: String): LocationConfig {
        val kind = when (spec) {
            is OutboundSpec.Vless -> LocationKind.Vless
            is OutboundSpec.Hysteria2 -> LocationKind.Hysteria2
        }
        return LocationConfig(
            name = spec.tag,
            id = "${spec.host}:${spec.port}", // stable-ish identity for the storage slug
            key = "",
            kind = kind,
            rawLink = rawLine.trim()
        )
    }

    private fun parseSingBoxText(
        text: String,
        subscriptionUrl: String? = null,
        @Suppress("UNUSED_PARAMETER") updateIntervalHours: Int? = null
    ): LocationBundleV4? {
        val usedStorageIds = mutableSetOf<String>()
        val entries = text.lineSequence()
            .map { it.normalizedImportText() }
            .filter { line ->
                line.startsWith(VLESS_URI_PREFIX) || HY2_URI_PREFIXES.any { line.startsWith(it) }
            }
            .mapNotNull { line ->
                val spec = LinkParser.parse(line) ?: return@mapNotNull null
                val location = outboundToLocation(spec, line).normalized()
                val base = location.storageSlug().ifBlank { location.name }
                val storageId = uniqueStorageId("imported_$base", usedStorageIds)
                LocationEntry.from(
                    storageId = storageId,
                    location = location,
                    subscriptionUrl = subscriptionUrl,
                    metadata = null
                )
            }.toList()
        if (entries.isEmpty()) return null
        return LocationBundleV4(
            activeLocationId = entries.firstOrNull()?.storageId,
            locations = entries
        )
    }

    private fun parseImport(
        text: String,
        subscriptionUrl: String? = null,
        updateIntervalHours: Int? = null
    ): ParsedImport? {
        // Scheme dispatch: a subscription body may mix olcrtc:// lines with
        // xray-side vless://+hysteria2:// lines. Parse each family and merge so a
        // single import yields all locations (olcrtc entries first).
        run {
            val olc = parseOlcRtcText(text, subscriptionUrl, updateIntervalHours)
            val sb = parseSingBoxText(text, subscriptionUrl, updateIntervalHours)
            val merged = when {
                olc != null && sb != null ->
                    olc.copy(locations = olc.locations + sb.locations)
                olc != null -> olc
                sb != null -> sb
                else -> null
            }
            if (merged != null) return ParsedImport(merged, ImportMode.Additive)
        }

        // Standard subscription format (Happ / v2rayNG): the whole body is base64
        // of the scheme-line list. Only reached when direct parsing found nothing,
        // and the codec only accepts decodes that contain scheme links.
        SubscriptionBodyCodec.decodeBase64(text)?.let { decoded ->
            val olc = parseOlcRtcText(decoded, subscriptionUrl, updateIntervalHours)
            val sb = parseSingBoxText(decoded, subscriptionUrl, updateIntervalHours)
            val merged = when {
                olc != null && sb != null ->
                    olc.copy(locations = olc.locations + sb.locations)
                olc != null -> olc
                sb != null -> sb
                else -> null
            }
            if (merged != null) return ParsedImport(merged, ImportMode.Additive)
        }

        if (!text.startsWith("{") || !text.endsWith("}")) return null

        val root = runCatching {
            json.parseToJsonElement(text).jsonObject
        }.getOrNull() ?: return null

        parseBundle(root, subscriptionUrl, updateIntervalHours)?.let {
            return ParsedImport(it, ImportMode.Restore)
        }

        return parseSingleLocation(root, null, subscriptionUrl)?.let {
            ParsedImport(
                LocationBundleV4(
                    activeLocationId = it.storageId,
                    locations = listOf(
                        it.copy(
                            metadata = it.metadata.withSubscriptionInterval(updateIntervalHours)
                        ).normalized()
                    )
                ),
                ImportMode.Additive
            )
        }
    }

    private fun mergeImportedBundle(
        current: LocationBundleV4?,
        imported: LocationBundleV4,
        replaceMatchingStorageIds: Boolean
    ): LocationBundleV4 {
        val currentBundle = current?.normalized()
        if (currentBundle == null || currentBundle.locations.isEmpty()) {
            // The imported bundle carries default settings; the user's are on the
            // one being replaced. Returning `imported` unchanged silently reset
            // every preference the moment someone imported into an empty app.
            return imported.copy(settings = currentBundle?.settings ?: imported.settings)
        }

        // Importing a subscription URL that is already here is nearly always
        // someone pasting a link they already have. Two copies of one provider's
        // servers is a list nobody can read, and refreshing the one that exists
        // is what they meant. Off, the old behaviour returns: both are kept.
        val importedUrls = imported.locations
            .mapNotNull { it.subscriptionUrl?.trim()?.takeIf { url -> url.isNotBlank() } }
            .toSet()
        val keptLocations = if (currentBundle.settings.preventDuplicates && importedUrls.isNotEmpty()) {
            currentBundle.locations.filterNot { it.subscriptionUrl?.trim() in importedUrls }
        } else {
            currentBundle.locations
        }

        val currentStorageIds = keptLocations.mapTo(mutableSetOf()) { it.storageId }
        val existingStorageIds = keptLocations.mapTo(mutableSetOf()) { it.storageId }

        val importedByStorageId = if (replaceMatchingStorageIds) {
            imported.locations.associateBy { it.storageId }
        } else {
            emptyMap()
        }
        val replacedStorageIds = importedByStorageId.keys.intersect(currentStorageIds)

        val mergedLocations = keptLocations
            .map { existing ->
                importedByStorageId[existing.storageId]?.also {
                    existingStorageIds.add(it.storageId)
                } ?: existing
            }
            .toMutableList()

        val importedIdMap = mutableMapOf<String, String>()

        imported.locations.forEach { entry ->
            if (replaceMatchingStorageIds && entry.storageId in replacedStorageIds) return@forEach

            val storageId = uniqueStorageId(entry.storageId, existingStorageIds)
            importedIdMap[entry.storageId] = storageId
            mergedLocations += entry.copy(storageId = storageId).normalized()
        }

        val importedActive = imported.activeLocationId
            ?.let { id -> importedIdMap[id] ?: id }
            ?.takeIf { id -> mergedLocations.any { it.storageId == id } }

        val active = importedActive
            ?: currentBundle.activeLocationId?.takeIf { id -> mergedLocations.any { it.storageId == id } }
            ?: mergedLocations.firstOrNull()?.storageId

        return currentBundle.copy(
            activeLocationId = active,
            locations = mergedLocations
        )
    }

    private fun parseBundle(
        root: JsonObject,
        subscriptionUrl: String? = null,
        updateIntervalHours: Int? = null
    ): LocationBundleV4? {
        val locationsElement = root["locations"] ?: return null

        val locations = runCatching {
            locationsElement.jsonArray
        }.getOrNull()?.mapNotNull { element ->
            val item = element.jsonObjectOrNull() ?: return@mapNotNull null

            decodeLocationEntry(item, subscriptionUrl)?.let {
                return@mapNotNull it.copy(
                    metadata = it.metadata.withSubscriptionInterval(updateIntervalHours)
                ).normalized()
            }

            val storageId = item.string("storage_id")
                ?: item.string("storageId")
                ?: item.string("id")?.let { "imported_${it.storageSlug()}" }

            parseSingleLocation(item, storageId, subscriptionUrl)?.let { entry ->
                entry.copy(
                    metadata = entry.metadata.withSubscriptionInterval(updateIntervalHours)
                ).normalized()
            }
        } ?: return null

        val version = root["version"]?.jsonPrimitive?.intOrNull ?: 3
        if (version < 3 && locations.isEmpty()) return null

        return LocationBundleV4(
            activeLocationId = root.string("active_location_id")
                ?: root.string("activeLocationId"),
            locations = locations
        )
    }

    private fun parseSingleLocation(
        text: String,
        fallbackStorageId: String?,
        subscriptionUrl: String? = null
    ): LocationEntry? {
        val root = runCatching {
            json.parseToJsonElement(text).jsonObject
        }.getOrNull() ?: return null

        parseBundle(root, subscriptionUrl)?.let { bundle ->
            return bundle.normalized().locations.firstOrNull()
        }

        return parseSingleLocation(root, fallbackStorageId, subscriptionUrl)
    }

    private fun parseSingleLocation(
        root: JsonObject,
        fallbackStorageId: String?,
        subscriptionUrl: String? = null
    ): LocationEntry? {
        decodeLocationEntry(root, subscriptionUrl)?.let { return it }

        val source = root["location"]?.jsonObjectOrNull()
            ?: root["hysteria"]?.jsonObjectOrNull()
            ?: root

        val provider = firstNotBlank(
            source.string("auth_provider"),
            source.string("authProvider"),
            source.string("bypass_provider"),
            source.string("bypassProvider"),
            source.string("provider"),
            root["turn"]?.jsonObjectOrNull()?.string("type"),
            root.string("auth_provider"),
            root.string("authProvider"),
            root.string("bypass_provider"),
            root.string("bypassProvider"),
            root.string("provider")
        )

        val transportArgs = firstNotBlank(
            source.string("transport_args"),
            source.string("transportArgs"),
            source.string("args"),
            root.string("transport_args"),
            root.string("transportArgs"),
            root.string("args")
        )

        val vp8Fps = firstInt(
            source.int("vp8_fps"),
            source.int("vp8Fps"),
            root.int("vp8_fps"),
            root.int("vp8Fps"),
            transportArgInt(transportArgs, "-vp8-fps")
        ) ?: LocationConfig.DEFAULT_VP8_FPS

        val vp8Batch = firstInt(
            source.int("vp8_batch"),
            source.int("vp8Batch"),
            root.int("vp8_batch"),
            root.int("vp8Batch"),
            transportArgInt(transportArgs, "-vp8-batch")
        ) ?: LocationConfig.DEFAULT_VP8_BATCH

        val location = LocationConfig(
            name = firstNotBlank(source.string("name"), root.string("name")),
            id = firstNotBlank(
                source.string("id"),
                source.string("room_id"),
                source.string("server"),
                root.string("id")
            ),
            key = firstNotBlank(
                source.string("key"),
                source.string("encryption_key"),
                source.string("password"),
                root.string("key")
            ),
            bypassProvider = provider,
            transport = firstNotBlank(
                source.string("transport"),
                root.string("transport"),
                if (transportArgs.isNotBlank()) LocationConfig.TRANSPORT_VP8CHANNEL else null
            ),
            vp8Fps = vp8Fps,
            vp8Batch = vp8Batch
        ).normalized()

        if (!location.isComplete()) return null

        val storageId = firstNotBlank(
            fallbackStorageId,
            root.string("storage_id"),
            root.string("storageId"),
            source.string("storage_id"),
            source.string("storageId"),
            "imported_${location.storageSlug()}"
        )

        return LocationEntry.from(storageId, location, subscriptionUrl = subscriptionUrl)
    }

    private fun parseOlcRtcText(
        text: String,
        subscriptionUrl: String? = null,
        updateIntervalHours: Int? = null
    ): LocationBundleV4? {
        if (!text.contains(OLCRTC_URI_PREFIX)) return null

        val subscriptionFields = linkedMapOf<String, String>()
        val locations = mutableListOf<Pair<ParsedOlcRtcUri, MutableMap<String, String>>>()
        var localFields: MutableMap<String, String>? = null

        text.lineSequence()
            .map { it.normalizedImportText() }
            .filter { it.isNotBlank() }
            .forEach { line ->
                when {
                    line.startsWith(OLCRTC_URI_PREFIX) -> {
                        parseOlcRtcUri(line)?.let { parsed ->
                            val fields = linkedMapOf<String, String>()
                            locations += parsed to fields
                            localFields = fields
                        }
                    }

                    line.startsWith("##") && locations.isNotEmpty() -> {
                        val (key, value) = parseSubscriptionField(
                            line.removePrefix("##")
                        ) ?: return@forEach

                        localFields?.set(key, value)
                    }

                    line.startsWith("#") -> {
                        val (key, value) = parseSubscriptionField(
                            line.removePrefix("#")
                        ) ?: return@forEach

                        subscriptionFields[key] = value
                    }
                }
            }

        if (locations.isEmpty()) return null

        val subscriptionMetadata = buildSubscriptionMetadata(subscriptionFields)
            .withSubscriptionInterval(updateIntervalHours)
        val usedStorageIds = mutableSetOf<String>()

        val entries = locations.mapIndexed { index, (parsed, fields) ->
            val metadata = buildLocationMetadata(
                fields = fields,
                mimo = parsed.mimo,
                subscription = subscriptionMetadata,
                requestedTransport = parsed.requestedTransport
            )
            val location = parsed.location.copy(
                name = firstNotBlank(
                    metadata?.name,
                    parsed.mimo,
                    parsed.location.name
                ),
                failoverRoomIds = parseFailoverRooms(fields["rooms"])
            ).normalized()
            val base = location.storageSlug().ifBlank { "location_${index + 1}" }
            val storageId = uniqueStorageId("imported_$base", usedStorageIds)
            LocationEntry.from(
                storageId = storageId,
                location = location,
                subscriptionUrl = subscriptionUrl,
                metadata = metadata
            )
        }

        return LocationBundleV4(
            activeLocationId = entries.firstOrNull()?.storageId,
            locations = entries
        )
    }

    private fun parseOlcRtcUri(line: String): ParsedOlcRtcUri? {
        val payload = line.removePrefix(OLCRTC_URI_PREFIX)

        val transportMarker = payload.indexOf('?')
        val roomMarker = payload.indexOf('@', startIndex = transportMarker + 1)
        val keyMarker = payload.indexOf('#', startIndex = roomMarker + 1)

        if (transportMarker <= 0 || roomMarker <= transportMarker || keyMarker <= roomMarker) {
            return null
        }

        val clientMarker = payload
            .indexOf('%', startIndex = keyMarker + 1)
            .takeIf { it >= 0 }

        val mimoMarker = payload
            .indexOf('$', startIndex = keyMarker + 1)
            .takeIf { it >= 0 }

        val keyEnd = listOfNotNull(clientMarker, mimoMarker).minOrNull() ?: payload.length

        val provider = payload.substring(0, transportMarker).trim()
        val transportToken = payload.substring(transportMarker + 1, roomMarker).trim()
        val (transport, transportOptions) = parseTransportToken(transportToken)
        val roomId = payload.substring(roomMarker + 1, keyMarker).trim()
        val key = payload.substring(keyMarker + 1, keyEnd).trim()

        val mimo = mimoMarker
            ?.let { payload.substring(it + 1) }
            .orEmpty()
            .trim()

        val location = LocationConfig(
            name = mimo.ifBlank { roomId },
            id = roomId,
            key = key,
            bypassProvider = provider,
            transport = transport,
            vp8Fps = transportOptions["vp8-fps"]
                ?: transportOptions["fps"]
                ?: LocationConfig.DEFAULT_VP8_FPS,
            vp8Batch = transportOptions["vp8-batch"]
                ?: transportOptions["batch"]
                ?: LocationConfig.DEFAULT_VP8_BATCH
        ).normalized()

        // A provider that cannot carry what the link asked for runs the room
        // over what it can; the request is kept so the app can say so, rather
        // than showing DataChannel for a link that plainly said vp8channel and
        // leaving the server to wait for a peer that never comes (olcbox#15).
        val requestedTransport = LocationConfig.transportOrNull(transport)
            ?.takeIf { it != location.transport }

        return location
            .takeIf { it.isComplete() }
            ?.let { ParsedOlcRtcUri(it, mimo.takeIf { value -> value.isNotBlank() }, requestedTransport) }
    }

    private fun buildSubscriptionMetadata(fields: Map<String, String>): SubscriptionMetadata? {
        return SubscriptionMetadata(
            name = fields["name"],
            update = fields["update"],
            refresh = fields["refresh"],
            color = fields["color"],
            icon = fields["icon"],
            used = fields["used"],
            available = fields["available"]
        ).normalized().takeUnless { it.isEmpty() }
    }

    private fun buildLocationMetadata(
        fields: Map<String, String>,
        mimo: String?,
        subscription: SubscriptionMetadata?,
        requestedTransport: String? = null
    ): LocationMetadata? {
        return LocationMetadata(
            name = fields["name"],
            color = fields["color"],
            icon = fields["icon"],
            used = fields["used"],
            available = fields["available"],
            ip = fields["ip"],
            comment = fields["comment"],
            mimo = mimo,
            subscription = subscription,
            requestedTransport = requestedTransport
        ).normalized().takeUnless { it.isEmpty() }
    }

    private fun parseTransportToken(token: String): Pair<String, Map<String, Int>> {
        val optionsStart = token.indexOf('<')
        val optionsEnd = token.lastIndexOf('>')
        if (optionsStart < 0 || optionsEnd <= optionsStart) {
            return token to emptyMap()
        }

        val transport = token.substring(0, optionsStart).trim()
        val options = token.substring(optionsStart + 1, optionsEnd)
            .split('&')
            .mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val key = part.substring(0, separator).trim().lowercase()
                val value = part.substring(separator + 1).trim().toIntOrNull() ?: return@mapNotNull null
                key to value
            }
            .toMap()

        return transport to options
    }

    /**
     * The `##rooms:` header under a location line: extra room ids, comma or
     * whitespace separated, that share the line's key, carrier and transport.
     * A client that does not know the header ignores it and keeps the primary.
     */
    private fun parseFailoverRooms(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return value.split(',', ' ', '\t')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun parseSubscriptionField(value: String): Pair<String, String>? {
        val separator = value.indexOf(':')
        if (separator <= 0) return null

        val key = value.substring(0, separator).trim().lowercase()
        val fieldValue = value.substring(separator + 1).trim()

        return key to fieldValue
    }

    private fun uniqueStorageId(base: String, used: MutableSet<String>): String {
        val normalizedBase = base.storageSlug()
        var candidate = normalizedBase
        var suffix = 2

        while (!used.add(candidate)) {
            candidate = "${normalizedBase}_$suffix"
            suffix += 1
        }

        return candidate
    }

    private fun decodeLocationEntry(root: JsonObject, subscriptionUrl: String? = null): LocationEntry? {
        return runCatching {
            json.decodeFromJsonElement(LocationEntry.serializer(), root)
                .let { entry ->
                    if (entry.subscriptionUrl.isNullOrBlank() && !subscriptionUrl.isNullOrBlank()) {
                        entry.copy(subscriptionUrl = subscriptionUrl)
                    } else {
                        entry
                    }
                }
                .normalized()
                .takeIf { it.location.isComplete() }
        }.getOrNull()
    }

    private fun subscriptionSignature(location: LocationConfig): String {
        val normalized = location.normalized()
        return listOf(
            normalized.bypassProvider,
            normalized.transport,
            normalized.id,
            normalized.key
        ).joinToString("|")
    }

    private fun LocationConfig.storageSlug(): String {
        return displayName().ifBlank { id }.storageSlug()
    }

    private fun String.storageSlug(): String {
        return lowercase()
            .replace(Regex("[^a-z0-9_-]+"), "_")
            .trim('_')
            .take(32)
            .ifBlank { "location" }
    }

    private fun JsonObject.string(name: String): String? {
        return (this[name] as? JsonPrimitive)?.contentOrNull
    }

    private fun JsonObject.int(name: String): Int? {
        return (this[name] as? JsonPrimitive)?.intOrNull
    }

    private fun JsonElement.jsonObjectOrNull(): JsonObject? {
        return runCatching { jsonObject }.getOrNull()
    }

    private fun firstNotBlank(vararg values: String?): String {
        return values.firstOrNull { !it.isNullOrBlank() } ?: ""
    }

    private fun firstInt(vararg values: Int?): Int? {
        return values.firstOrNull { it != null }
    }

    private fun transportArgInt(args: String, name: String): Int? {
        if (args.isBlank()) return null

        val parts = args.split(Regex("\\s+")).filter { it.isNotBlank() }
        val index = parts.indexOf(name)

        return parts.getOrNull(index + 1)?.toIntOrNull()
    }

    /**
     * `profile-title`, `subscription-userinfo`, `support-url` and
     * `profile-web-page-url`, as every client that shows a subscription reads
     * them. The title is often base64, which the prefix announces.
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun HttpResponse.subscriptionProfile(): SubscriptionProfile? {
        val title = headers["profile-title"]?.trim()?.let { raw ->
            if (raw.startsWith("base64:", ignoreCase = true)) {
                runCatching {
                    Base64.Default.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
                        .decode(raw.substringAfter(':').trim())
                        .decodeToString()
                }.getOrNull()
            } else {
                raw
            }
        }?.takeIf { it.isNotBlank() }

        // `upload=..; download=..; total=..; expire=..` — used is what the two
        // directions add up to, and total 0 means unmetered rather than empty.
        val info = headers["subscription-userinfo"]
            ?.split(';')
            ?.mapNotNull { part ->
                val (k, v) = part.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                k.trim().lowercase() to (v.trim().toLongOrNull() ?: return@mapNotNull null)
            }
            ?.toMap()
            .orEmpty()

        val used = listOfNotNull(info["upload"], info["download"])
            .takeIf { it.isNotEmpty() }
            ?.sum()

        val profile = SubscriptionProfile(
            title = title,
            usedBytes = used,
            totalBytes = info["total"]?.takeIf { it > 0 },
            // The header is in seconds, and 0 is the documented "never".
            expiresAtEpochMs = info["expire"]?.takeIf { it > 0 }?.let { it * 1000 },
            supportUrl = headers["support-url"]?.trim()?.takeIf { it.isNotBlank() },
            webPageUrl = headers["profile-web-page-url"]?.trim()?.takeIf { it.isNotBlank() }
        )
        return profile.takeUnless { it.isEmpty() }
    }

    private fun HttpResponse.profileUpdateIntervalHours(): Int? {
        return headers["profile-update-interval"]
            ?.trim()
            ?.toIntOrNull()
            ?.coerceIn(
                SubscriptionMetadata.MIN_UPDATE_INTERVAL_HOURS,
                SubscriptionMetadata.MAX_UPDATE_INTERVAL_HOURS
            )
    }

    private fun List<LocationEntry>.subscriptionUpdateIntervalHours(): Int? {
        return firstNotNullOfOrNull { entry ->
            entry.metadata?.subscription?.updateIntervalHours
        }
    }

    private fun SubscriptionMetadata?.withSubscriptionInterval(hours: Int?): SubscriptionMetadata? {
        if (hours == null) return this
        return (this ?: SubscriptionMetadata()).copy(
            updateIntervalHours = hours
        ).normalized()
    }

    /**
     * Merges what the subscription said about itself. Absent fields leave what
     * is stored alone: a provider that stops sending a header has not withdrawn
     * the fact, and blanking the name would put the hostname back on screen.
     */
    private fun SubscriptionMetadata?.withProfile(
        profile: SubscriptionProfile?
    ): SubscriptionMetadata? {
        if (profile == null) return this
        val base = this ?: SubscriptionMetadata()
        return base.copy(
            name = profile.title ?: base.name,
            used = profile.usedBytes?.let(::formatByteSize) ?: base.used,
            available = profile.totalBytes?.let(::formatByteSize) ?: base.available,
            expiresAtEpochMs = profile.expiresAtEpochMs ?: base.expiresAtEpochMs,
            supportUrl = profile.supportUrl ?: base.supportUrl,
            webPageUrl = profile.webPageUrl ?: base.webPageUrl
        ).normalized()
    }

    private fun LocationMetadata?.withSubscriptionInterval(hours: Int?): LocationMetadata? {
        if (hours == null) return this
        return withSubscriptionRefreshState(
            updateIntervalHours = hours,
            lastRefreshAtEpochMs = this?.subscription?.lastRefreshAtEpochMs
        )
    }

    private fun LocationMetadata?.withSubscriptionRefreshState(
        updateIntervalHours: Int,
        lastRefreshAtEpochMs: Long?,
        profile: SubscriptionProfile? = null
    ): LocationMetadata {
        val subscription = (this?.subscription).withProfile(profile) ?: SubscriptionMetadata()
        return (this ?: LocationMetadata()).copy(
            subscription = subscription.copy(
                updateIntervalHours = updateIntervalHours,
                lastRefreshAtEpochMs = lastRefreshAtEpochMs
            )
        ).normalized()
    }

    private companion object {
        const val OLCRTC_URI_PREFIX = "olcrtc://"
        const val VLESS_URI_PREFIX = "vless://"
        val HY2_URI_PREFIXES = listOf("hysteria2://", "hy2://")
        const val UTF8_BOM = "\uFEFF"
    }
}
