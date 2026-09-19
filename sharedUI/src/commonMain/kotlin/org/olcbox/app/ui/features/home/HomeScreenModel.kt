package org.olcbox.app.ui.features.home

import org.olcbox.app.net.ImportLink
import org.olcbox.app.net.isPartnerLink
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.olcbox.app.data.exporter.LogExporter
import org.olcbox.app.data.importer.ConfigImporter
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.RoutingSettings
import org.olcbox.app.log.LogScrubber
import org.olcbox.app.data.model.SubscriptionSettings
import org.olcbox.app.util.nowMillis
import org.olcbox.app.data.repository.LocationsRepository
import org.olcbox.app.data.repository.SubscriptionRefreshReport
import org.olcbox.app.ui.features.locations.LocationItem
import org.olcbox.app.vpn.OlcrtcFailure
import org.olcbox.app.vpn.VpnManager
import org.olcbox.app.vpn.VpnStatus

class HomeScreenViewModel(
    private val vpnManager: VpnManager,
    private val locationsRepository: LocationsRepository,
    private val configImporter: ConfigImporter,
    private val logExporter: LogExporter
) : ViewModel() {

    private val _state = MutableStateFlow(
        HomeScreenState(
            isVpnConnected = false,
            isVpnLoading = false,
            selectedLocation = null,
            configData = LocationConfig(),
            shouldShowConfigInvalidReminder = false,
            canStartVpn = false,
            startBlockedReason = "Add a location first"
        )
    )
    val state get() = _state.asStateFlow()
    val logs get() = vpnManager.logs

    /**
     * Passed straight through rather than copied into [HomeScreenState]: it is
     * the platform's answer, and on iOS it can name a moment from before this
     * process existed — a tunnel outlives the app there. Mirroring it into our
     * own state would only give it a chance to disagree.
     */
    val connectedSince get() = vpnManager.connectedSince

    /** Same reasoning as [connectedSince]: the platform's own counter. */
    val traffic get() = vpnManager.traffic

    private var selectionJob: Job? = null

    /** Manual choice/stop wins over a pending rank or failover cooldown. */
    fun cancelAutomaticSelection() {
        selectionJob?.cancel()
        selectionJob = null
        if (vpnManager.status.value is VpnStatus.Disconnected || vpnManager.status.value is VpnStatus.Error) {
            _state.update { it.copy(isVpnLoading = false) }
        }
    }

    private fun startLowest(preferredLocationIds: List<String>? = null) {
        cancelAutomaticSelection()
        _state.update { it.copy(isVpnLoading = true, failure = null) }
        selectionJob = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                LowestConnection(vpnManager, locationsRepository) {
                    loadCurrentConfigNow()
                }.run(preferredLocationIds)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isVpnLoading = false, failure = e.message ?: "Could not select a server") }
            } finally {
                // A cancelled old job must not clear a newer connection attempt.
                if (selectionJob === currentCoroutineContext()[Job]) {
                    selectionJob = null
                    _state.update { it.copy(isVpnLoading = false) }
                }
            }
        }
        selectionJob?.start()
    }

    /** Connect in the order already measured and displayed by the home screen. */
    fun connectLowest(preferredLocationIds: List<String>? = null) = startLowest(preferredLocationIds)

    /**
     * What a background refresh found, when the user asked to be told. A shared
     * flow rather than state: it is an event, and replaying the last one on every
     * recomposition would show the same message twice.
     */
    private val _autoRefreshNotice = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val autoRefreshNotice = _autoRefreshNotice.asSharedFlow()

    private val _channelLatency = MutableStateFlow<org.olcbox.app.net.ChannelMeasurement?>(null)
    val channelLatency = _channelLatency.asStateFlow()
    private var measurementEpoch = 0L
    private var measurementRequest = 0L

    suspend fun measureActiveChannel(): Long? {
        val epoch = measurementEpoch
        val request = ++measurementRequest
        val session = vpnManager.connectedSince.value
        val config = _state.value.configData
        val result = vpnManager.measureCurrentChannel()
        // connectedSince survives a network migration. The separate epoch
        // rejects its old sample even if the session clock did not change.
        if (epoch == measurementEpoch && request == measurementRequest &&
            vpnManager.status.value is VpnStatus.Connected &&
            vpnManager.connectedSince.value == session && _state.value.configData == config) {
            _channelLatency.value = org.olcbox.app.net.ChannelMeasurement(result)
        }
        return result
    }

    private val _subscriptionSettings = MutableStateFlow(SubscriptionSettings())
    val subscriptionSettings = _subscriptionSettings.asStateFlow()

    /**
     * Whether [subscriptionSettings] is what was stored or merely the defaults.
     *
     * The load is asynchronous, so the first value every screen sees is a fresh
     * object. Acting on it — connecting on launch, refreshing on open — would be
     * acting on settings the user never chose, and doing so once is enough to
     * make the real ones look ignored.
     */
    private val _subscriptionSettingsLoaded = MutableStateFlow(false)
    val subscriptionSettingsLoaded = _subscriptionSettingsLoaded.asStateFlow()

    fun updateSubscriptionSettings(settings: SubscriptionSettings, onComplete: () -> Unit = {}) {
        val normalized = settings.normalized()
        viewModelScope.launch {
            locationsRepository.saveSubscriptionSettings(normalized)
            _subscriptionSettings.value = normalized
            onComplete()
        }
    }

    private val _routingSettings = MutableStateFlow(RoutingSettings())
    val routingSettings = _routingSettings.asStateFlow()

    /**
     * Saves and, if a tunnel is up, restarts it: a routing choice that takes
     * effect at some unannounced later connect is the kind of setting people
     * toggle twice and stop trusting. Same gesture as a connection-mode change.
     */
    fun updateRoutingSettings(settings: RoutingSettings) {
        if (_routingSettings.value == settings) return
        _routingSettings.value = settings
        viewModelScope.launch {
            locationsRepository.saveRoutingSettings(settings)
            restartVpnIfRunning()
        }
    }

    /**
     * Whether the VPN disclosure has been accepted. Starts false and is only
     * raised by the stored value, so the worst a slow load can do is ask again —
     * the opposite mistake would connect without ever having asked.
     */
    private val _vpnDisclosureAccepted = MutableStateFlow(false)
    val vpnDisclosureAccepted = _vpnDisclosureAccepted.asStateFlow()

    fun acceptVpnDisclosure() {
        _vpnDisclosureAccepted.value = true
        viewModelScope.launch { locationsRepository.acceptVpnDisclosure(nowMillis()) }
    }

    /**
     * Whether the first-run walkthrough has run. Null until the stored answer
     * arrives — the opposite of the flag above, and for the same kind of reason:
     * defaulting to "not seen" would flash three screens of introduction at
     * somebody on their hundredth launch, every launch, for as long as the read
     * took.
     */
    private val _onboardingSeen = MutableStateFlow<Boolean?>(null)
    val onboardingSeen = _onboardingSeen.asStateFlow()

    fun markOnboardingSeen() {
        _onboardingSeen.value = true
        viewModelScope.launch { locationsRepository.setOnboardingSeen(nowMillis()) }
    }

    /** "Replay first run". Clears the note so the walkthrough is offered again. */
    fun replayOnboarding() {
        _onboardingSeen.value = false
        viewModelScope.launch { locationsRepository.setOnboardingSeen(null) }
    }

    init {
        viewModelScope.launch {
            subscriptionSettings.collect { settings ->
                if (!settings.hasAnyLowest()) cancelAutomaticSelection()
            }
        }
        loadCurrentConfig()
        viewModelScope.launch {
            _subscriptionSettings.value = locationsRepository.getSubscriptionSettings()
            _subscriptionSettingsLoaded.value = true
        }
        viewModelScope.launch {
            _routingSettings.value = locationsRepository.getRoutingSettings()
        }
        viewModelScope.launch {
            _vpnDisclosureAccepted.value = locationsRepository.isVpnDisclosureAccepted()
        }
        viewModelScope.launch {
            _onboardingSeen.value = locationsRepository.isOnboardingSeen()
        }
        startSubscriptionAutoRefresh()

        viewModelScope.launch {
            locationsRepository.changes
                .drop(1)
                .collect {
                    loadCurrentConfigNow()
                }
        }

        viewModelScope.launch {
            vpnManager.status.collect { status ->
                _state.update {
                    val next = it.applying(status)
                    // Between failover attempts the old tunnel is deliberately
                    // down. Keep Cancel visible until the selection job ends.
                    if (selectionJob?.isActive == true && status is VpnStatus.Disconnected) {
                        next.copy(isVpnLoading = true)
                    } else {
                        next
                    }
                }
                if (status !is VpnStatus.Connected) {
                    measurementEpoch++
                    _channelLatency.value = null
                }
            }
        }
    }

    fun loadCurrentConfig(onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            loadCurrentConfigNow()
            onComplete()
        }
    }

    private suspend fun loadCurrentConfigNow() {
        val active = locationsRepository.getActiveLocation()
        if (active == null) {
            _state.update {
                it.copy(
                    selectedLocation = null,
                    configData = LocationConfig(),
                    canStartVpn = false,
                    startBlockedReason = "Add a location first"
                )
            }
            return
        }

        val normalized = active.location
        val locationItem = LocationItem(
            storageId = active.storageId,
            fullName = normalized.displayName(),
            config = normalized,
            subscriptionUrl = active.subscriptionUrl,
            subscriptionOriginLink = active.subscriptionOriginLink,
            metadata = active.metadata
        )

        _state.update {
            it.copy(
                configData = normalized,
                selectedLocation = locationItem,
                canStartVpn = normalized.isComplete(),
                startBlockedReason = if (normalized.isComplete()) null else "Complete active location first"
            )
        }
    }

    suspend fun performPing(): Long? {
        return vpnManager.ping(_state.value.configData)
    }

    suspend fun performPingFor(config: LocationConfig): Long? {
        return vpnManager.ping(config)
    }

    /** See [VpnManager.canPing]: never probe what cannot answer. */
    fun canPing(config: LocationConfig): Boolean = vpnManager.canPing(config)

    suspend fun checkConnectionFor(config: LocationConfig): Long? {
        return vpnManager.checkConnection(config)
    }

    fun startVpnContinuation() {
        _state.update { it.copy(isVpnLoading = true, failure = null) }
    }

    /**
     * Continue the exact location selected before Windows restarted us with
     * administrator rights. Lowest has already measured, displayed and stored
     * its winner; calling [ToggleVpn] here would rank the subscription again in
     * the new process and make the elevation restart look like a long hang.
     */
    fun resumeVpnAfterElevation() {
        cancelAutomaticSelection()
        _state.update { it.copy(isVpnLoading = true, failure = null) }
        viewModelScope.launch { vpnManager.startVpn() }
    }

    /** The user has read the last failure and waved it away. */
    fun dismissFailure() {
        _state.update { it.copy(failure = null) }
    }

    fun ToggleVpn() {
        val status = vpnManager.status.value
        if ((selectionJob?.isActive == true && status !is VpnStatus.Connected) ||
            _state.value.isVpnLoading ||
            status is VpnStatus.Connecting ||
            status is VpnStatus.Reconnecting
        ) {
            cancelAutomaticSelection()
            viewModelScope.launch {
                vpnManager.stopVpn()
                _state.update { it.copy(isVpnConnected = false, isVpnLoading = false) }
            }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isVpnLoading = true, failure = null) }
            try {
                if (_state.value.isVpnConnected || vpnManager.status.value is VpnStatus.Connected) {
                    cancelAutomaticSelection()
                    vpnManager.stopVpn()
                } else {
                    val active = locationsRepository.getActiveLocation()
                    if (active == null || !active.location.isComplete()) {
                        _state.update {
                            it.copy(
                                isVpnLoading = false,
                                canStartVpn = false,
                                startBlockedReason = "Add a valid location first"
                            )
                        }
                        return@launch
                    }
                    // Said before a twenty-second wait for a peer that would
                    // never speak this transport: the server is set up for
                    // the one the link named, and the fix is on the server.
                    TransportMismatch.explanation(active)?.let { why ->
                        _state.update { it.copy(isVpnLoading = false, failure = why) }
                        return@launch
                    }
                    if (locationsRepository.getSubscriptionSettings()
                            .lowestEnabledFor(active.subscriptionUrl)) {
                        startLowest()
                    } else {
                        vpnManager.startVpn()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isVpnLoading = false,
                        failure = e.message ?: "Could not start the connection"
                    )
                }
            }
        }
    }

    fun restartVpnIfRunning() {
        cancelAutomaticSelection()
        when (vpnManager.status.value) {
            VpnStatus.Connected,
            VpnStatus.Connecting,
            VpnStatus.Reconnecting -> viewModelScope.launch {
                _state.update { it.copy(isVpnLoading = true, failure = null) }
                vpnManager.startVpn()
            }

            VpnStatus.Disconnected,
            VpnStatus.Stopping,
            is VpnStatus.Error -> Unit
        }
    }
    private fun updateLocationConfig(block: (LocationConfig) -> LocationConfig) {
        _state.update { it.copy(configData = block(it.configData)) }
    }
    fun suggestedLogsFileName(): String = "ghostlane-logs.txt"

    fun onSaveLogsToFile(
        target: Any,
        onSaved: (String) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val content = buildLogsExport(logs.value)
            logExporter.writeLogs(target, content)
                .onSuccess { savedPath ->
                    onSaved(
                        if (savedPath.isBlank() || savedPath == "Logs saved") {
                            "Logs saved"
                        } else {
                            "Logs saved to $savedPath"
                        }
                    )
                }
                .onFailure { error ->
                    onError(error.message ?: "Failed to save logs")
                }
        }
    }

    fun onShareLogs(
        onShared: (String) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val content = buildLogsExport(logs.value)
            logExporter.shareLogs(content)
                .onSuccess { message -> onShared(message) }
                .onFailure { error -> onError(error.message ?: "Failed to share logs") }
        }
    }

    fun onPasteFromClipboard(
        onComplete: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        configImporter.getFromClipboard()?.let { text ->
            onImportFullConfig(text, onComplete, onError)
        } ?: onError("No clipboard data found")
    }

    fun onFileSelected(
        fileSource: Any,
        onComplete: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val text = configImporter.readTextFromSource(fileSource)
            if (text == null) {
                onError("Could not read config file")
            } else {
                onImportFullConfig(text, onComplete, onError)
            }
        }
    }

    /**
     * A `proofkit://add?url=…` or `https://proofkit.org/add#…` link, handed
     * to the app by the system: the same import a paste goes through, once
     * the payload is out of the envelope. Not an import link at all is an
     * answer, not a crash — a bot or a panel may hand us a link we never
     * taught it.
     */
    fun onImportLink(
        uri: String,
        onComplete: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val payload = ImportLink.payloadOf(uri)
        if (payload == null) {
            onError("Not a Ghostlane import link")
            return
        }
        onImportFullConfig(payload, onComplete, onError)
    }

    fun onImportFullConfig(
        rawText: String,
        onComplete: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (rawText.isBlank()) {
            onError("No config text found")
            return
        }
        viewModelScope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    locationsRepository.importText(
                        text = rawText,
                        subscriptionProxy = vpnManager.subscriptionFetchProxy()
                    )
                }
                if (!imported) {
                    // A partner link that did not resolve is not a malformed config
                    // — the user's next move is their provider's bot, not another paste.
                    onError(
                        if (isPartnerLink(rawText)) {
                            "Link not recognised. Open your provider's bot and copy the server list link again."
                        } else {
                            "No valid Ghostlane config found"
                        }
                    )
                    return@launch
                }
                loadCurrentConfigNow()
                onComplete()
            } catch (e: Exception) {
                val message = e.message ?: "Import failed"
                _state.update {
                    it.copy(
                        canStartVpn = false,
                        startBlockedReason = message
                    )
                }
                onError(message)
            }
        }
    }

    fun refreshSubscriptions(
        onComplete: (report: SubscriptionRefreshReport) -> Unit = {}
    ) {
        viewModelScope.launch {
            val report = locationsRepository.refreshSubscriptions(
                subscriptionProxy = vpnManager.subscriptionFetchProxy()
            )
            loadCurrentConfigNow()
            onComplete(report)
        }
    }

    fun refreshSubscription(
        subscriptionUrl: String,
        onComplete: (report: SubscriptionRefreshReport) -> Unit = {}
    ) {
        viewModelScope.launch {
            val report = locationsRepository.refreshSubscription(
                subscriptionUrl = subscriptionUrl,
                subscriptionProxy = vpnManager.subscriptionFetchProxy()
            )
            loadCurrentConfigNow()
            onComplete(report)
        }
    }

    /**
     * Removes a subscription and every location it brought in. Stops the tunnel
     * first when the active location is one of them, otherwise the VPN would keep
     * running against a config the user just deleted.
     */
    fun deleteSubscription(
        subscriptionUrl: String,
        onComplete: (removedCount: Int) -> Unit = {}
    ) {
        cancelAutomaticSelection()
        viewModelScope.launch {
            val activeBelongsToSubscription = locationsRepository.getActiveLocation()
                ?.subscriptionUrl?.trim() == subscriptionUrl.trim()
            if (activeBelongsToSubscription && _state.value.isVpnConnected) {
                vpnManager.stopVpn()
                _state.update { it.copy(isVpnConnected = false, isVpnLoading = false) }
            }
            val removed = locationsRepository.deleteSubscription(subscriptionUrl)
            loadCurrentConfigNow()
            onComplete(removed)
        }
    }

    /**
     * Polls every few minutes and refreshes whatever is due, rather than sleeping
     * for the whole interval: the interval is a user setting that can change
     * under us, and a coroutine parked for a week would not notice.
     */
    private fun startSubscriptionAutoRefresh() {
        viewModelScope.launch {
            subscriptionSettingsLoaded.first { it }
            subscriptionSettings.map { it.autoUpdate to it.updateIntervalHours }
                .distinctUntilChanged().collectLatest { (autoUpdate, _) ->
                if (autoUpdate) {
                    while (true) {
                        refreshDueSubscriptionsIfNeeded()
                        delay(SUBSCRIPTION_AUTO_REFRESH_POLL_MS)
                    }
                }
            }
        }
    }

    private suspend fun refreshDueSubscriptionsIfNeeded() {
        // Background pass: failures here are not surfaced — the user did not ask
        // for it, and the next manual refresh will report the reason.
        val report = withContext(Dispatchers.IO) {
            locationsRepository.refreshDueSubscriptions(
                subscriptionProxy = vpnManager.subscriptionFetchProxy()
            )
        }
        if (report.updatedCount > 0) {
            loadCurrentConfigNow()
            if (_subscriptionSettings.value.notifyOnUpdate) {
                _autoRefreshNotice.emit(report.bulkMessage())
            }
        }
    }

    private fun buildLogsExport(logs: List<String>): String {
        return buildString {
            appendLine("Ghostlane application logs")
            appendLine("Entries: ${logs.size}")
            appendLine()
            logs.forEachIndexed { index, line ->
                appendLine("${index + 1}. $line")
            }
            // The tunnel component's own files, where there is one. Scrubbed
            // like every other line: a debug build's sing-box log names every
            // destination, and a hashed name still shows which rule it hit.
            val diagnostics = vpnManager.diagnosticsLog()
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
            if (diagnostics.isNotEmpty()) {
                appendLine()
                appendLine("--- tunnel diagnostics ---")
                diagnostics.forEach { appendLine(LogScrubber.default.scrub(it)) }
            }
        }
    }
}

data class HomeScreenState(
    val isVpnConnected: Boolean,
    val isVpnLoading: Boolean = false,
    val selectedLocation: LocationItem?,
    val configData: LocationConfig,
    val shouldShowConfigInvalidReminder: Boolean,
    val canStartVpn: Boolean,
    val startBlockedReason: String?,
    /** Why the last connection attempt failed, or null when nothing has. */
    val failure: String? = null
) {
    /**
     * The one line worth putting under the status pill: what went wrong, or
     * failing that what is stopping the user from starting at all.
     *
     * `startBlockedReason` is deliberately not shown while a location is merely
     * missing — the status pill already says "no location" and the button reads
     * SETUP, so repeating it adds noise rather than information.
     */
    /**
     * [keyGone]: the status probe has marked the selected room's key as revoked. A
     * server without our key stays silent, which the engine cannot tell from an
     * older server, so the protocol text is replaced by the one remedy that works.
     */
    fun notice(keyGone: Boolean = false): String? = failure
        ?.let {
            val revocable = it == OlcrtcFailure.PROTOCOL ||
                it == OlcrtcFailure.KEY ||
                it == OlcrtcFailure.SILENT
            if (keyGone && revocable) OlcrtcFailure.KEY_GONE else it
        }
        ?: startBlockedReason?.takeIf { selectedLocation != null && !canStartVpn }

    /** The state after the platform reports [status]. Pure, so it can be tested. */
    fun applying(status: VpnStatus): HomeScreenState = when (status) {
        VpnStatus.Connected ->
            copy(isVpnConnected = true, isVpnLoading = false, failure = null)

        VpnStatus.Connecting ->
            copy(isVpnConnected = false, isVpnLoading = true, failure = null)

        VpnStatus.Reconnecting ->
            copy(isVpnConnected = true, isVpnLoading = true)

        // A stop is the user acting on the message — or on nothing, but either
        // way past it. These two used to leave `failure` alone, which meant the
        // banner outlived everything short of a relaunch: read it, press stop,
        // and a red box about a room you had given up on stayed for the rest
        // of the session.
        VpnStatus.Stopping ->
            copy(isVpnConnected = false, isVpnLoading = false, failure = null)

        VpnStatus.Disconnected ->
            copy(isVpnConnected = false, isVpnLoading = false, failure = null)

        // The reason used to stop here. The extension goes to real trouble to
        // explain itself — it writes a stage breadcrumb the app reads back
        // precisely because the system will only ever say "disconnected" — and
        // this dropped the message on the floor, leaving a button that spins,
        // returns to START and says nothing. The commonest case of all is a
        // user who declined the VPN permission prompt.
        is VpnStatus.Error ->
            copy(isVpnConnected = false, isVpnLoading = false, failure = OlcrtcFailure.describe(status.message))
    }
}

/**
 * How often the due check runs, not how often a subscription refreshes — that
 * is [SubscriptionSettings.updateIntervalHours], and a shorter poll is what lets
 * a one-hour setting mean one hour.
 */
private const val SUBSCRIPTION_AUTO_REFRESH_POLL_MS = 5L * 60L * 1_000L
