package org.olcbox.app.ui.features.locations

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.olcbox.app.net.LocationKind
import org.olcbox.app.net.OlcrtcNodeStatus
import org.olcbox.app.net.OlcrtcSlots
import org.olcbox.app.ui.components.kit.appendOccupancy
import org.olcbox.app.net.OlcrtcStatusClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationMetadata
import org.olcbox.app.data.model.SubscriptionMetadata
import org.olcbox.app.data.repository.LocationsRepository

data class LocationItem(
    val storageId: String,
    val fullName: String,
    val config: LocationConfig? = null,
    val subscriptionUrl: String? = null,
    /** See LocationEntry.subscriptionOriginLink — an encrypted subscription never prints its URL. */
    val subscriptionOriginLink: String? = null,
    val metadata: LocationMetadata? = null
)

sealed class PingsState {
    object Idle : PingsState()

    data class Loading(
        val lastPings: Map<String, Int?>? = null,
        val currentPings: Map<String, Int?> = emptyMap(),
        val pendingLocationIds: Set<String> = emptySet(),
        val completed: Int = 0,
        val total: Int = 0
    ) : PingsState()

    data class Success(
        val pings: Map<String, Int?>
    ) : PingsState()

    data class Error(
        val message: String,
        val lastPings: Map<String, Int?>? = null
    ) : PingsState()
}

class LocationViewModel(
    private val locationsRepository: LocationsRepository,
    private val olcrtcStatus: OlcrtcStatusClient =
        OlcrtcStatusClient(org.olcbox.app.data.datasource.createProxyHttpClient()),
) : ViewModel() {

    var locations = mutableStateListOf<LocationItem>()
        private set

    var selectedLocationId by mutableStateOf<String?>(null)
        private set

    var pingsState by mutableStateOf<PingsState>(PingsState.Idle)
        private set

    /**
     * olcRTC occupancy per location storage id.
     *
     * Absent means "not known", never "empty": a node that did not answer must render
     * as it did before occupancy existed rather than as one with no users, which would
     * invite people onto a node that may well be full.
     */
    var olcrtcSlots by mutableStateOf<Map<String, OlcrtcSlots>>(emptyMap())
        private set

    /**
     * How full each olcRTC node has been over the last few polls, by storage id.
     *
     * Not persisted, and deliberately: the sparkline it draws answers "is this
     * filling up or emptying out", which is a question about now. A line restored
     * from yesterday's samples would answer a question nobody asked, and would do
     * it confidently.
     *
     * Fed by [refreshOlcrtcSlots], which already runs on the screen's own tick.
     */
    var olcrtcHistory by mutableStateOf<Map<String, List<Float>>>(emptyMap())
        private set

    /**
     * Locations whose room key the coordinator no longer recognises.
     *
     * Held apart from [olcrtcSlots] because it is a different kind of fact: absent
     * occupancy means "not known", this means "known, and the answer is that this
     * will never connect again". Only a `404` puts an id in here — a coordinator we
     * cannot reach leaves the set exactly as it was.
     */
    var olcrtcRevoked by mutableStateOf<Set<String>>(emptySet())
        private set

    private var olcrtcSlotsJob: Job? = null

    private val activePingJobs = mutableMapOf<String, Job>()
    private val pingSemaphore = Semaphore(LOCATION_PING_PARALLELISM)
    private var loadLocationsJob: Job? = null
    private var loadLocationsRequest = 0
    private val providerDrafts = mutableMapOf<String, ProviderDraft>()

    var editingConfig by mutableStateOf(LocationConfig())
    var editingName by mutableStateOf("")
    var editingId by mutableStateOf<String?>(null)
    var editingSubscriptionUrl by mutableStateOf<String?>(null)
        private set
    var editingSubscriptionIntervalHours by mutableStateOf(SubscriptionMetadata.DEFAULT_UPDATE_INTERVAL_HOURS.toString())
        private set
    var editingServiceProvider by mutableStateOf(LocationConfig.DEFAULT_BYPASS_PROVIDER)
        private set

    var isSaving by mutableStateOf(false)
        private set

    var nameError by mutableStateOf<String?>(null)
        private set

    var serverError by mutableStateOf<String?>(null)
        private set

    var keyError by mutableStateOf<String?>(null)
        private set

    val isFormValid: Boolean
        get() = nameError == null &&
                serverError == null &&
                keyError == null &&
                editingName.isNotBlank() &&
                editingConfig.id.isNotBlank() &&
                editingConfig.key.isNotBlank()

    init {
        loadLocations()
        viewModelScope.launch {
            locationsRepository.changes
                .drop(1)
                .collect {
                    loadLocations()
                }
        }
    }

    fun loadLocations(onComplete: () -> Unit = {}) {
        val requestId = ++loadLocationsRequest
        loadLocationsJob?.cancel()
        loadLocationsJob = viewModelScope.launch {
            val bundle = locationsRepository.getBundle()
            val savedConfigs = bundle.locations
            val currentSelectedId = bundle.activeLocationId

            val nextLocations = savedConfigs.map { entry ->
                val normalized = entry.location
                LocationItem(
                    storageId = entry.storageId,
                    fullName = normalized.displayName(),
                    config = normalized,
                    subscriptionUrl = entry.subscriptionUrl,
                    subscriptionOriginLink = entry.subscriptionOriginLink,
                    metadata = entry.metadata
                )
            }

            if (requestId != loadLocationsRequest) return@launch

            locations.clear()
            locations.addAll(nextLocations)
            refreshOlcrtcSlots()

            val nextSelectedId = if (
                nextLocations.isNotEmpty() &&
                (
                        currentSelectedId.isNullOrBlank() ||
                                nextLocations.none { it.storageId == currentSelectedId }
                        )
            ) {
                nextLocations.firstOrNull()?.storageId
            } else {
                currentSelectedId
            }
            if (
                nextSelectedId != currentSelectedId &&
                nextLocations.any { it.storageId == nextSelectedId }
            ) {
                locationsRepository.setActiveLocationId(nextSelectedId)
            }

            if (requestId != loadLocationsRequest) return@launch

            selectedLocationId = nextSelectedId
            onComplete()
        }
    }

    fun selectLocation(id: String, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            locationsRepository.setActiveLocationId(id)
            selectedLocationId = id
            onComplete()
        }
    }

    fun refreshPings(
        targetLocationIds: List<String>? = null,
        performPing: suspend (LocationConfig) -> Long?,
        /**
         * Asked before probing. Static capability is not enough on its own: on
         * iOS the only location that can be measured while the tunnel is up is
         * the one carrying it, and that is a question about state, not kind.
         */
        canPing: (LocationConfig) -> Boolean = { it.isPingable() },
        onComplete: (onlineCount: Int, totalCount: Int) -> Unit = { _, _ -> },
        onError: (String) -> Unit = {}
    ) {
        val previousPings = currentPingsSnapshot()
        val locationsSnapshot = locations.toList()

        val pingableLocations = locationsSnapshot
            .filter { location ->
                location.config?.let(canPing) == true &&
                        (targetLocationIds == null || targetLocationIds.contains(location.storageId))
            }
            .filterNot { location ->
                activePingJobs.containsKey(location.storageId)
            }

        if (locationsSnapshot.isEmpty()) {
            if (activePingJobs.isEmpty()) {
                pingsState = PingsState.Success(emptyMap())
            }
            onComplete(0, 0)
            return
        }

        if (pingableLocations.isEmpty()) {
            emitPingState(previousPings)
            onComplete(0, 0)
            return
        }

        var completedForThisRequest = 0
        var onlineForThisRequest = 0
        val totalForThisRequest = pingableLocations.size
        val jobsToStart = mutableListOf<Job>()

        pingableLocations.forEach { location ->
            val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
                try {
                    val ping = try {
                        pingSemaphore.withPermit {
                            checkLocationPing(location, performPing, canPing)?.toInt()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }

                    val updatedPings = currentPingsSnapshot().toMutableMap()
                    updatedPings[location.storageId] = ping

                    activePingJobs.remove(location.storageId)

                    if (ping != null) {
                        onlineForThisRequest++
                    }

                    completedForThisRequest++

                    emitPingState(updatedPings.toMap())

                    if (completedForThisRequest == totalForThisRequest) {
                        onComplete(onlineForThisRequest, totalForThisRequest)
                    }
                } catch (e: CancellationException) {
                    activePingJobs.remove(location.storageId)
                    emitPingState()
                    throw e
                } catch (e: Exception) {
                    activePingJobs.remove(location.storageId)

                    val message = e.message ?: "HTTP ping failed"
                    onError(message)

                    emitPingState()
                }
            }

            activePingJobs[location.storageId] = job
            jobsToStart.add(job)
        }

        emitPingState(previousPings)
        jobsToStart.forEach { it.start() }
    }

    private fun currentPingsSnapshot(): Map<String, Int?> {
        return when (val state = pingsState) {
            PingsState.Idle -> emptyMap()

            is PingsState.Loading -> {
                state.currentPings.ifEmpty {
                    state.lastPings.orEmpty()
                }
            }

            is PingsState.Success -> {
                state.pings
            }

            is PingsState.Error -> {
                state.lastPings.orEmpty()
            }
        }
    }

    private fun emitPingState(
        pings: Map<String, Int?> = currentPingsSnapshot()
    ) {
        val pendingIds = activePingJobs.keys.toSet()

        pingsState = if (pendingIds.isEmpty()) {
            PingsState.Success(pings)
        } else {
            PingsState.Loading(
                lastPings = pings,
                currentPings = pings,
                pendingLocationIds = pendingIds,
                completed = 0,
                total = pendingIds.size
            )
        }
    }

    private suspend fun checkLocationPing(
        location: LocationItem,
        performPing: suspend (LocationConfig) -> Long?,
        canPing: (LocationConfig) -> Boolean
    ): Long? {
        val config = location.config?.takeIf(canPing) ?: return null

        return withTimeoutOrNull(LOCATION_PING_TIMEOUT_MS) {
            repeat(LOCATION_PING_ATTEMPTS) { attempt ->
                val result = try {
                    performPing(config)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }

                if (result != null) {
                    return@withTimeoutOrNull result
                }

                if (attempt < LOCATION_PING_ATTEMPTS - 1) {
                    delay(LOCATION_PING_RETRY_DELAY_MS)
                }
            }

            null
        }
    }

    fun startEditing(id: String?) {
        nameError = null
        serverError = null
        keyError = null
        isSaving = false
        providerDrafts.clear()

        if (id == null) {
            editingId = null
            editingConfig = LocationConfig()
            editingName = ""
            editingSubscriptionUrl = null
            editingSubscriptionIntervalHours = SubscriptionMetadata.DEFAULT_UPDATE_INTERVAL_HOURS.toString()
        } else {
            val location = locations.find { it.storageId == id }
            editingId = id
            editingConfig = location?.config?.normalized() ?: LocationConfig()
            editingName = editingConfig.displayName()
            editingSubscriptionUrl = location?.subscriptionUrl
            editingSubscriptionIntervalHours = (
                location?.metadata?.subscription?.updateIntervalHours
                    ?: SubscriptionMetadata.DEFAULT_UPDATE_INTERVAL_HOURS
                ).toString()
        }
        val provider = LocationConfig.normalizeProvider(editingConfig.bypassProvider)
        editingServiceProvider = if (provider == LocationConfig.PROVIDER_JITSI) {
            LocationConfig.DEFAULT_BYPASS_PROVIDER
        } else {
            provider
        }
        providerDrafts[provider] = ProviderDraft(
            room = editingConfig.id,
            key = editingConfig.key
        )
    }

    fun onNameChanged(value: String) {
        editingName = value
        validateName(value)
    }

    fun onServerChanged(value: String) {
        editingConfig = editingConfig.copy(id = value)
        validateServer(value)
    }

    fun onSniChanged(value: String) = Unit

    fun onPasswordChanged(value: String) {
        editingConfig = editingConfig.copy(key = value)
        validateKey(value)
    }

    fun onBypassProviderChanged(value: String) {
        val provider = LocationConfig.normalizeProvider(value)
        val currentProvider = LocationConfig.normalizeProvider(editingConfig.bypassProvider)
        if (provider == currentProvider) return

        providerDrafts[currentProvider] = ProviderDraft(
            room = editingConfig.id,
            key = editingConfig.key
        )

        if (provider != LocationConfig.PROVIDER_JITSI) {
            editingServiceProvider = provider
        }

        val restored = providerDrafts[provider] ?: ProviderDraft()

        editingConfig = editingConfig.copy(
            bypassProvider = provider,
            transport = LocationConfig.normalizeTransport(editingConfig.transport, provider),
            id = restored.room,
            key = restored.key
        )
        serverError = null
        keyError = null
    }

    fun onTransportChanged(value: String) {
        editingConfig = editingConfig.copy(
            transport = LocationConfig.normalizeTransport(value, editingConfig.bypassProvider)
        )
    }

    fun onVp8FpsChanged(value: String) {
        editingConfig = editingConfig.copy(
            vp8Fps = value.filter { it.isDigit() }.toIntOrNull() ?: 0
        )
    }

    fun onVp8BatchChanged(value: String) {
        editingConfig = editingConfig.copy(
            vp8Batch = value.filter { it.isDigit() }.toIntOrNull() ?: 0
        )
    }

    fun onSubscriptionIntervalChanged(value: String) {
        editingSubscriptionIntervalHours = value.filter { it.isDigit() }.take(3)
    }

    private fun validateName(name: String) {
        nameError = when {
            name.isBlank() -> "Name cannot be empty"
            name.length > 30 -> "Name is too long (max 30 chars)"
            else -> null
        }
    }

    private fun validateServer(server: String) {
        val roomLabel = if (editingConfig.bypassProvider == LocationConfig.PROVIDER_JITSI) {
            "Room URL"
        } else {
            "Room ID"
        }
        serverError = when {
            server.isBlank() -> "$roomLabel cannot be empty"
            server.length > 256 -> "$roomLabel is too long"
            else -> null
        }
    }

    private fun validateKey(key: String) {
        keyError = when {
            key.isBlank() -> "Key cannot be empty"
            !key.matches(Regex("^[a-fA-F0-9]{64}$")) -> "Key must be 64 hex characters"
            else -> null
        }
    }

    fun saveEditing(onComplete: () -> Unit) {
        validateName(editingName)
        validateServer(editingConfig.id)
        validateKey(editingConfig.key)

        if (!isFormValid || isSaving) return

        viewModelScope.launch {
            isSaving = true

            val id = editingId ?: "custom_${(100..999).random()}"
            val finalConfig = editingConfig.copy(name = editingName).normalized()

            locationsRepository.saveLocation(id, finalConfig)
            editingSubscriptionUrl?.let { url ->
                val interval = editingSubscriptionIntervalHours.toIntOrNull()
                    ?: SubscriptionMetadata.DEFAULT_UPDATE_INTERVAL_HOURS
                locationsRepository.setSubscriptionUpdateInterval(url, interval)
            }
            locationsRepository.setActiveLocationId(id)

            loadLocations()

            delay(600)

            onComplete()

            isSaving = false
        }
    }

    fun deleteLocation(id: String, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            locationsRepository.deleteLocation(id)
            loadLocations(onComplete)
        }
    }

    private companion object {
        const val LOCATION_PING_ATTEMPTS = 1
        const val LOCATION_PING_TIMEOUT_MS = 12_000L
        const val LOCATION_PING_RETRY_DELAY_MS = 0L
        const val LOCATION_PING_PARALLELISM = 4
    }

    private data class ProviderDraft(
        val room: String = "",
        val key: String = ""
    )

    /**
     * Refreshes occupancy for every olcRTC location that carries a key.
     *
     * Best-effort and non-blocking: failures leave the previous answer in place rather
     * than clearing it, because a momentary network blip should not make every node
     * look unknown while the user is reading the list. Cancels any in-flight pass, so
     * a fast reload cannot leave two writers racing over one map.
     */
    fun refreshOlcrtcSlots() {
        olcrtcSlotsJob?.cancel()
        olcrtcSlotsJob = viewModelScope.launch {
            val targets = OlcrtcProbePlan.targets(locations)
            // Everyone with a list is asked for occupancy; only these may have a
            // 404 read as revocation. See OlcrtcProbePlan.revocable.
            val revocable = OlcrtcProbePlan.revocable(locations, OlcrtcStatusClient.DEFAULT_BASE_URL)
            if (targets.isEmpty()) return@launch

            val fetched = mutableMapOf<String, OlcrtcSlots>()
            val gone = mutableSetOf<String>()
            val alive = mutableSetOf<String>()
            for ((storageId, key) in targets) {
                when (val status = olcrtcStatus.statusFor(key)) {
                    is OlcrtcNodeStatus.Occupancy -> {
                        fetched[storageId] = status.slots
                        alive += storageId
                    }
                    OlcrtcNodeStatus.KeyGone -> if (storageId in revocable) gone += storageId
                    // No answer changes nothing either way.
                    OlcrtcNodeStatus.Unavailable -> Unit
                }
            }
            olcrtcRevoked = OlcrtcProbePlan.nextRevoked(
                previous = olcrtcRevoked,
                // Only the rooms whose 404 carries meaning. A room outside this
                // set is not being judged, so a mark left on it by an earlier
                // pass is dropped rather than renewed.
                probed = targets.map { it.first }.toSet() intersect revocable,
                alive = alive,
                gone = gone
            )
            // Merge rather than replace: a node that failed this pass keeps the number
            // it had, which is older but truer than nothing.
            olcrtcSlots = olcrtcSlots + fetched
            // Only nodes that answered this pass get a sample. Repeating the last
            // reading for one that did not would draw a flat line through an
            // outage, which is the one shape that means "nothing is changing".
            olcrtcHistory = olcrtcHistory + fetched.mapValues { (storageId, slots) ->
                appendOccupancy(olcrtcHistory[storageId], slots)
            }
        }
    }
}
