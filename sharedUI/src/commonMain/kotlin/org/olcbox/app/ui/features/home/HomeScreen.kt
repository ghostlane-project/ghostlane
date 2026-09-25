package org.olcbox.app.ui.features.home

import org.olcbox.app.ui.components.kit.boardWords
import multiplatform_app.sharedui.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import multiplatform_app.sharedui.generated.resources.action_cancel
import multiplatform_app.sharedui.generated.resources.action_remove
import multiplatform_app.sharedui.generated.resources.imported_from_clipboard
import multiplatform_app.sharedui.generated.resources.latency_nothing_measurable
import multiplatform_app.sharedui.generated.resources.latency_on_connected
import multiplatform_app.sharedui.generated.resources.lowest_selected
import multiplatform_app.sharedui.generated.resources.reconnecting_lowest
import multiplatform_app.sharedui.generated.resources.reconnecting_new_location
import multiplatform_app.sharedui.generated.resources.reconnecting_through
import multiplatform_app.sharedui.generated.resources.remove_location_body
import multiplatform_app.sharedui.generated.resources.remove_location_question
import multiplatform_app.sharedui.generated.resources.remove_server_list_body
import multiplatform_app.sharedui.generated.resources.remove_server_list_question
import multiplatform_app.sharedui.generated.resources.removed_locations
import multiplatform_app.sharedui.generated.resources.status_add_list_to_start
import multiplatform_app.sharedui.generated.resources.status_connected
import multiplatform_app.sharedui.generated.resources.status_connecting
import multiplatform_app.sharedui.generated.resources.status_in_room
import multiplatform_app.sharedui.generated.resources.status_joining_room
import multiplatform_app.sharedui.generated.resources.status_no_server_list
import multiplatform_app.sharedui.generated.resources.status_not_connected
import multiplatform_app.sharedui.generated.resources.status_room_full
import multiplatform_app.sharedui.generated.resources.this_server_list
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.pluralStringResource
import org.olcbox.app.ui.features.home.components.listWords
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.ScrollState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import org.olcbox.app.ui.features.home.components.subscriptionTitle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.olcbox.app.admin.AdminState
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.net.TransportKind
import org.olcbox.app.net.transportKind
import org.olcbox.app.ui.components.AdminPasswordDialog
import org.olcbox.app.ui.components.CameraRationaleSheet
import org.olcbox.app.ui.components.VpnDisclosureScreen
import org.olcbox.app.ui.components.kit.appendThroughput
import org.olcbox.app.ui.components.kit.boardAction
import org.olcbox.app.ui.components.kit.boardHeading
import org.olcbox.app.ui.components.kit.nextSort
import org.olcbox.app.ui.components.kit.roomIsBlocked
import org.olcbox.app.ui.components.kit.shortenExitName
import org.olcbox.app.ui.components.kit.sortLabel
import org.olcbox.app.ui.components.kit.throughputTrace
import org.olcbox.app.ui.features.home.components.AddConfigurationSheet
import org.olcbox.app.ui.features.home.components.LogsSheet
import org.olcbox.app.ui.features.home.components.buildBoardModel
import org.olcbox.app.ui.features.home.components.formatSessionDuration
import org.olcbox.app.ui.features.home.components.locationDisplayParts
import org.olcbox.app.ui.features.home.components.pingFor
import org.olcbox.app.ui.features.home.components.rememberBoardModel
import org.olcbox.app.ui.features.locations.LocationViewModel
import org.olcbox.app.ui.features.onboarding.OnboardingScreen
import org.olcbox.app.util.formatByteSize
import org.olcbox.app.util.nowMillis

/**
 * The board, and the two pinned things that frame it.
 *
 * What changed from the layout this replaces, and why: the 200dp circular power
 * dial is gone. It was the most recognisable piece of a silhouette shared with
 * every other sing-box front end, it ate the top third of the screen to say one
 * word, and it could not name what it would connect to. The bar at the bottom
 * always does.
 *
 * Everything here is state and effects; the layout itself is `HomeScreenContent`.
 */
@Composable
fun HomeScreen(
    viewModel: HomeScreenViewModel,
    locationViewModel: LocationViewModel,
    scrollState: ScrollState,
    onToggleClick: () -> Unit = { viewModel.ToggleVpn() },
    onImportFileRequested: () -> Unit = {},
    onImportFromClipboardRequested: (onImported: () -> Unit, onError: (String) -> Unit) -> Unit = { _, _ -> },
    onScanQrRequested: () -> Unit = {},
    onImportFromPhoneRequested: (() -> Unit)? = null,
    onSaveLogsRequested: (onSaved: (String) -> Unit, onError: (String) -> Unit) -> Unit = { _, _ -> },
    showAppSettingsButton: Boolean = false,
    canScanQr: Boolean = false,
    onAppSettingsClick: () -> Unit = {},
    showSplitTunnelingButton: Boolean = false,
    onSplitTunnelingClick: () -> Unit = {},
    onOpenLocationSettings: (String?) -> Unit,
    onAddLocation: () -> Unit,
    onGetSubscriptionClick: () -> Unit = {},
    showGetSubscription: Boolean = true,
    showCustomLocation: Boolean = true,
    /** Opens a provider's support or web link. Platform-supplied. */
    onOpenExternalUrl: (String) -> Unit = {}
) {
    var isLogsSheetOpen by remember { mutableStateOf(false) }
    var isAddSheetOpen by remember { mutableStateOf(false) }
    var isRefreshingSubscriptions by remember { mutableStateOf(false) }
    var refreshingSubscriptionUrl by remember { mutableStateOf<String?>(null) }
    var showAdminDialog by remember { mutableStateOf(false) }
    var lowestMeasureRequest by remember { mutableStateOf(0L) }
    // Asked once per launch, before the system's own prompt. On iOS that prompt
    // cannot be shown twice, so arriving at it with no explanation attached is a
    // permission spent.
    var showCameraRationale by remember { mutableStateOf(false) }
    // 24 exits x 3 transports is 72 rows out of one server list; without a filter
    // the list is unusable. Chips only appear for transports actually present.
    var transportFilter by rememberSaveable { mutableStateOf<String?>(null) }

    val state by viewModel.state.collectAsState()
    val connectedSince by viewModel.connectedSince.collectAsState()
    val channelLatency by viewModel.channelLatency.collectAsState()
    val subscriptionSettings by viewModel.subscriptionSettings.collectAsState()
    val routingSettings by viewModel.routingSettings.collectAsState()
    val subscriptionSettingsLoaded by viewModel.subscriptionSettingsLoaded.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val pingsState = locationViewModel.pingsState
    val locations = locationViewModel.locations.toList()
    val hasSubscriptions = locations.any { !it.subscriptionUrl.isNullOrBlank() }

    // The per-location editor and "create custom location" are plumbing, and this
    // predicate fails closed: see AdminState.plumbingVisible. Everything a user
    // legitimately needs — settings, server lists, split tunneling, logs — is
    // visible regardless.
    val admin = AdminState.plumbingVisible

    val requiresSetup = !state.canStartVpn && !state.isVpnConnected && !state.isVpnLoading

    /**
     * Building a location by hand is plumbing, so it answers to the same gate the
     * per-location editor does — which is what `AdminState.plumbingVisible` has
     * documented all along ("the per-location configurator *and* create custom
     * location"). The button was never wired to it.
     *
     * The comment that used to sit here argued the opposite: that adding a location
     * by hand is the same act as importing a link, and neither should be gated. It
     * was wrong on its own terms, because the button already went nowhere —
     * `OlcboxAppContent` bounces `LocationSettings` straight back home unless the
     * gate is open. On desktop, Windows and Linux this was a control that did
     * nothing at all, offering to hand-build a location out of a room, a key, a
     * provider and a transport to somebody who only wants to pick an exit.
     *
     * The platform flag still has the last word: iOS says no to it regardless.
     */
    val canCreateCustomLocation = showCustomLocation && admin

    // Elapsed time is not derived from anything Compose can observe, so it is
    // resampled on a timer rather than recomputed on recomposition. Only while a
    // session is up: a loop ticking over an idle screen is a wakeup a second for
    // a number nobody is reading.
    //
    // The same tick samples throughput. The platform counters are cumulative, so
    // what the trace wants is the difference between two of them — which means
    // remembering the previous reading, and forgetting it when the session ends
    // so the next one does not open with a spike the size of the last one's total.
    //
    // Held in state objects that nothing in this function reads. The strip reads
    // them, through the lambdas below, so a second passing recomposes a strip
    // rather than the whole board — which is what it did, once a second, for
    // every card, every seat pip with its colour animation and every canvas on
    // the screen. The traffic counters are read off the StateFlow inside the loop
    // for the same reason: collectAsState here would subscribe this function to
    // something that changes every second.
    val nowTick = remember { mutableStateOf(nowMillis()) }
    val trafficSamples = remember { mutableStateOf(emptyList<Long>()) }
    val bytesLine = remember { mutableStateOf("") }
    LaunchedEffect(state.isVpnConnected, connectedSince) {
        if (!state.isVpnConnected) {
            trafficSamples.value = emptyList()
            bytesLine.value = ""
            return@LaunchedEffect
        }
        var previousTotal: Long? = null
        while (state.isVpnConnected) {
            delay(1_000)
            nowTick.value = nowMillis()
            val counters = viewModel.traffic.value
            if (counters != null) {
                bytesLine.value =
                    "↓ ${formatByteSize(counters.bytesIn)}   ↑ ${formatByteSize(counters.bytesOut)}"
                val total = counters.bytesIn + counters.bytesOut
                previousTotal?.let {
                    trafficSamples.value = appendThroughput(trafficSamples.value, total - it)
                }
                previousTotal = total
            }
        }
    }

    // Null while the stored answer is still arriving, so a returning user does
    // not get three screens of introduction flashed at them on every launch.
    val onboardingSeen by viewModel.onboardingSeen.collectAsState()
    if (onboardingSeen == false) {
        OnboardingScreen(
            onFinished = { viewModel.markOnboardingSeen() },
            onAddServerList = { isAddSheetOpen = true }
        )
        return
    }

    val vpnDisclosureAccepted by viewModel.vpnDisclosureAccepted.collectAsState()
    var showVpnDisclosure by remember { mutableStateOf(false) }

    // Removing something is one tap away on the board now, so it asks first. Both
    // are irreversible and one of them takes a dozen rows with it.
    var confirmRemove by remember { mutableStateOf<PendingRemoval?>(null) }
    confirmRemove?.let { pending ->
        val isList = pending is PendingRemoval.ServerList
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(stringResource(if (isList) Res.string.remove_server_list_question else Res.string.remove_location_question)) },
            text = {
                Text(
                    when (pending) {
                        is PendingRemoval.ServerList ->
                            pluralStringResource(Res.plurals.remove_server_list_body, pending.count, pending.title, pending.count)
                        is PendingRemoval.Location ->
                            stringResource(Res.string.remove_location_body, pending.title)
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    when (pending) {
                        is PendingRemoval.ServerList -> viewModel.deleteSubscription(pending.url) { removed ->
                            scope.launch {
                                snackbarHostState.showSnackbar(getPluralString(Res.plurals.removed_locations, removed, removed))
                            }
                            locationViewModel.loadLocations()
                        }
                        is PendingRemoval.Location -> locationViewModel.deleteLocation(pending.id) {
                            viewModel.loadCurrentConfig()
                        }
                    }
                    confirmRemove = null
                }) {
                    Text(stringResource(Res.string.action_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text(stringResource(Res.string.action_cancel)) } }
        )
    }

    /**
     * The config the tunnel is currently built from, or null when nothing is running.
     *
     * Captured before a refresh and compared after, because a refresh that changed
     * nothing about the active location has no reason to tear its tunnel down. Refresh
     * used to restart unconditionally, so pressing the arrow on one server list
     * dropped a connection running on another — the user pressed "update the list" and
     * got their traffic cut.
     */
    fun activeLocationConfig(): LocationConfig? =
        locations.firstOrNull { it.storageId == locationViewModel.selectedLocationId }?.config

    /** Restarts only if the running tunnel's own config actually moved. */
    fun restartIfActiveChanged(before: LocationConfig?) {
        val after = activeLocationConfig()
        // Vanished counts too: the location the tunnel runs on being gone is exactly
        // when a restart is right, and comparing to null covers it.
        if (after != before) viewModel.restartVpnIfRunning()
    }

    fun refreshSubscriptions() {
        isRefreshingSubscriptions = true
        val activeBefore = activeLocationConfig()
        viewModel.refreshSubscriptions { report ->
            locationViewModel.loadLocations {
                isRefreshingSubscriptions = false
                restartIfActiveChanged(activeBefore)
                scope.launch { snackbarHostState.showSnackbar(report.localizedBulkMessage()) }
            }
        }
    }

    fun refreshSubscription(url: String) {
        refreshingSubscriptionUrl = url
        val activeBefore = activeLocationConfig()
        viewModel.refreshSubscription(url) { report ->
            locationViewModel.loadLocations {
                refreshingSubscriptionUrl = null
                restartIfActiveChanged(activeBefore)
                scope.launch { snackbarHostState.showSnackbar(report.localizedSingleMessage()) }
            }
        }
    }

    fun refreshHttpPings(
        targetLocationIds: List<String>? = null,
        overallDeadlineMs: Long? = null,
        onComplete: (onlineCount: Int, totalCount: Int) -> Unit = { _, _ -> }
    ) {
        // The control is always there; the measurement is not always possible.
        // Latency is timed through a connection, so with nothing connected only an
        // olcRTC room can be probed — say that instead of appearing to do nothing,
        // which is what the user is left with otherwise.
        val measurable = locations.any { item ->
            (targetLocationIds == null || item.storageId in targetLocationIds) &&
                item.config?.let { viewModel.canPing(it) } == true
        }
        if (!measurable) {
            scope.launch {
                snackbarHostState.showSnackbar(
                    getString(
                        if (state.isVpnConnected) Res.string.latency_on_connected
                        else Res.string.latency_nothing_measurable
                    )
                )
            }
            onComplete(0, 0)
            return
        }

        locationViewModel.refreshPings(
            targetLocationIds = targetLocationIds,
            performPing = { config -> viewModel.performPingFor(config) },
            canPing = { config -> viewModel.canPing(config) },
            overallDeadlineMs = overallDeadlineMs,
            onComplete = onComplete,
        )
    }

    // A small request over the existing tunnel, including Telemost. No room is
    // joined merely to draw this value; leaving the screen cancels the sampler.
    // Loading toggles retain the column and in-flight startup probes. Mark those
    // results historical instead of presenting an old network as a live sample.
    LaunchedEffect(state.isVpnConnected, state.isVpnLoading, connectedSince) {
        locationViewModel.markPingsStale()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state.isVpnConnected, state.isVpnLoading, connectedSince, lifecycle) {
        if (state.isVpnConnected && !state.isVpnLoading) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    viewModel.measureActiveChannel()
                    delay(30_000)
                }
            }
        }
    }

    // Occupancy goes stale on its own, so it has to be re-asked.
    //
    // It was fetched once, when the location list loaded, and then never again — so a
    // node that filled up, or the slot the user just freed by disconnecting, kept
    // showing whatever was true minutes ago. A number that only moves when the list
    // reloads is worse than no number: it looks live and is not.
    //
    // Re-asked on every change of connection state, because that is the moment the
    // count moves and the moment the user is looking at it, and on a slow tick besides
    // for everyone else's comings and goings. The tick is well inside the server's
    // five-minute presence window, so a freed slot shows up long before it would
    // matter, and each pass is one small request per olcRTC location. It is also what
    // feeds the sparkline on each card.
    LaunchedEffect(state.isVpnConnected) {
        while (true) {
            locationViewModel.refreshOlcrtcSlots()
            delay(OCCUPANCY_REFRESH_MS)
        }
    }

    // What the app does on its own when it opens. Each is off unless asked for:
    // connecting without being told to is not a default anyone should inherit.
    var launchActionsDone by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(subscriptionSettingsLoaded, locations.isEmpty(), launchActionsDone) {
        // Only once the stored settings have actually arrived: the first value is
        // a default object, and acting on it would ignore what the user chose.
        //
        // And only once there are locations to act on. Settings load faster than
        // the location list, so "ping on launch" fired against an empty list,
        // found nothing measurable and announced that instead — which is exactly
        // what "the checkbox does nothing" looks like from outside.
        if (launchActionsDone || !subscriptionSettingsLoaded || locations.isEmpty()) {
            return@LaunchedEffect
        }
        val settings = subscriptionSettings
        launchActionsDone = true
        if (settings.refreshOnOpen && hasSubscriptions) refreshSubscriptions()
        if (settings.pingOnLaunch) refreshHttpPings()
        if (settings.connectOnLaunch && state.canStartVpn && !state.isVpnConnected) {
            onToggleClick()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.autoRefreshNotice.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    // ── what the board is, computed once for the head and the list ──────────

    val selectedId = locationViewModel.selectedLocationId
    val selectedItem = locations.firstOrNull { it.storageId == selectedId }
    val selectedConfig = selectedItem?.config
    val knownSubscriptionUrls = remember(locations) {
        locations.mapNotNull {
            it.subscriptionUrl?.trim()?.takeIf(String::isNotEmpty)
        }.toSet()
    }
    val lowestSubscriptionUrls = remember(knownSubscriptionUrls, subscriptionSettings) {
        knownSubscriptionUrls.filterTo(mutableSetOf()) {
            subscriptionSettings.lowestEnabledFor(it)
        }
    }
    val model = rememberBoardModel(
        locations = locations,
        activeFilterKey = transportFilter,
        sort = subscriptionSettings.sort,
        lowestSubscriptionUrls = lowestSubscriptionUrls,
        pingsState = pingsState
    )
    val lowestActive = selectedItem?.subscriptionUrl?.trim()?.let { it in lowestSubscriptionUrls } == true
    val actualSelectedName = selectedItem?.let { locationDisplayParts(it).second }
    val selectedName = if (lowestActive) {
        stringResource(Res.string.lowest_selected, actualSelectedName.orEmpty())
    } else {
        actualSelectedName
    }
    val selectedSlots = if (lowestActive) null else selectedId?.let { locationViewModel.olcrtcSlots[it] }

    fun startLowestConnection(subscriptionUrl: String) {
        val groupIds = locations.filter {
            it.subscriptionUrl?.trim() == subscriptionUrl.trim() &&
                it.config?.let(viewModel::canPing) == true
        }.map { it.storageId }

        // The busy action is Cancel. Invalidate the completion callback before
        // cancelling its probes so a late result cannot start a tunnel anyway.
        if (state.isVpnLoading) {
            lowestMeasureRequest++
            locationViewModel.cancelPings(groupIds)
            onToggleClick()
            return
        }
        val request = ++lowestMeasureRequest
        // A manual Measure may still be running. Start one complete, coherent
        // snapshot for Connect instead of waiting for only the rows it missed.
        locationViewModel.cancelPings(groupIds)
        viewModel.startVpnContinuation()
        refreshHttpPings(groupIds, overallDeadlineMs = LOWEST_CONNECT_BUDGET_MS) { _, _ ->
            if (request != lowestMeasureRequest) return@refreshHttpPings
            val pings = locationViewModel.pingSnapshot(groupIds)
            val providerOrder = groupIds.withIndex().associate { it.value to it.index }
            val ranked = groupIds.sortedWith(
                compareBy<String> { pings[it] ?: Int.MAX_VALUE }
                    .thenBy { providerOrder[it] ?: Int.MAX_VALUE }
            )
            if (request == lowestMeasureRequest) {
                // The platform callback owns VPN permission. Keeping the ranked
                // candidates in the ViewModel also survives this composable
                // leaving while Android's system dialog is visible.
                viewModel.queueLowestAfterPermission(ranked)
                onToggleClick()
            }
        }
    }

    fun startSelectedConnection() {
        val selectedSubscription = selectedItem?.subscriptionUrl?.trim()
        if (!lowestActive || selectedSubscription.isNullOrEmpty()) {
            onToggleClick()
            return
        }
        startLowestConnection(selectedSubscription)
    }

    if (showVpnDisclosure) {
        VpnDisclosureScreen(
            onAccept = {
                showVpnDisclosure = false
                viewModel.acceptVpnDisclosure()
                startSelectedConnection()
            },
            onDecline = { showVpnDisclosure = false }
        )
    }

    val words = listWords()
    val statusWords = statusWords()
    val boardWords = boardWords()
    val thisServerList = stringResource(Res.string.this_server_list)
    HomeScreenContent(
        chrome = HomeChrome(
            tag = HEADER_TAG,
            statusLabel = statusLabel(
                isConnected = state.isVpnConnected,
                isConnecting = state.isVpnLoading,
                requiresSetup = requiresSetup,
                hasSeats = selectedSlots != null,
                transportLabel = selectedConfig?.transportKind()?.label(),
                words = statusWords
            ),
            statusMeta = {
                statusMeta(
                    isConnected = state.isVpnConnected,
                    bytesLine = bytesLine.value,
                    requiresSetup = requiresSetup,
                    isFull = roomIsBlocked(selectedSlots, mine = state.isVpnConnected),
                    protocolLine = selectedConfig?.protocolLabels()?.joinToString(" · "),
                    words = statusWords
                ) + if (state.isVpnConnected) {
                    " · " + (channelLatency?.label() ?: "HTTP …")
                } else ""
            },
            statusValue = {
                statusValue(
                    isConnected = state.isVpnConnected,
                    connectedSince = connectedSince,
                    nowEpochMs = nowTick.value,
                    exitName = selectedName
                )
            },
            isActive = state.isVpnConnected,
            isBusy = state.isVpnLoading,
            trafficTrace = { throughputTrace(trafficSamples.value) },
            notice = state.notice(keyGone = selectedId != null && selectedId in locationViewModel.olcrtcRevoked)
                ?.let { localizedNotice(it) },
            noticeDismissible = state.failure != null,
            heading = boardHeading(model.hasRooms, boardWords),
            sortLabel = sortLabel(subscriptionSettings.sort, boardWords),
            action = boardAction(
                requiresSetup = requiresSetup,
                isConnected = state.isVpnConnected,
                isConnecting = state.isVpnLoading,
                selectedIsRoom = !lowestActive && selectedConfig?.transportKind() == TransportKind.Olcrtc,
                selectedIsFull = roomIsBlocked(selectedSlots, mine = state.isVpnConnected),
                exitName = selectedName,
                words = boardWords
            ),
            showAppSettingsButton = showAppSettingsButton,
            showSplitTunnelingButton = showSplitTunnelingButton,
            showLock = AdminState.showLock
        ),
        board = HomeBoard(
            model = model,
            selectedLocationId = selectedId,
            isConnected = state.isVpnConnected,
            pingsState = pingsState,
            pingsStale = locationViewModel.stalePingIds.any { id -> locations.any { it.storageId == id } },
            olcrtcSlots = locationViewModel.olcrtcSlots,
            occupancyHistory = locationViewModel.olcrtcHistory,
            revokedKeys = locationViewModel.olcrtcRevoked,
            transportFilter = transportFilter,
            isRefreshingSubscriptions = isRefreshingSubscriptions,
            refreshingSubscriptionUrl = refreshingSubscriptionUrl,
            lowestSubscriptionUrls = lowestSubscriptionUrls,
            collapsible = subscriptionSettings.collapsible,
            showSettings = admin,
            showCustomLocation = canCreateCustomLocation,
            showGetSubscription = showGetSubscription
        ),
        callbacks = HomeCallbacks(
            // Hidden admin gesture: 7 taps on the brand within ~3s.
            onBrandTap = { if (AdminState.registerTitleTap(nowMillis())) showAdminDialog = true },
            onDiagnosticsClick = { isLogsSheetOpen = true },
            onLockClick = { AdminState.lock() },
            onSplitTunnelingClick = onSplitTunnelingClick,
            onAddClick = { isAddSheetOpen = true },
            onSettingsClick = onAppSettingsClick,
            onSortClick = {
                viewModel.updateSubscriptionSettings(
                    subscriptionSettings.copy(sort = nextSort(subscriptionSettings.sort))
                )
            },
            onFilterSelected = { transportFilter = it },
            onActionClick = {
                when {
                    requiresSetup -> isAddSheetOpen = true
                    // Only on the way up, and before the system's own VPN dialog:
                    // the disclosure has to be what explains that prompt, not
                    // something the user meets after granting it. Stopping never
                    // asks.
                    !vpnDisclosureAccepted && !state.isVpnConnected -> showVpnDisclosure = true
                    else -> startSelectedConnection()
                }
            },
            onDismissNotice = { viewModel.dismissFailure() },
            onPullToRefresh = { refreshSubscriptions() },
            onLocationSelected = { id ->
                viewModel.cancelAutomaticSelection()
                // Read before the switch: picking a card while connected tears the
                // tunnel down and builds a new one, which took seconds and
                // announced itself only as a spinner.
                val wasConnected = state.isVpnConnected
                val name = locations.firstOrNull { it.storageId == id }
                    ?.let { locationDisplayParts(it).second }
                val target = locations.firstOrNull { it.storageId == id }
                val settings = subscriptionSettings.withLowestEnabled(
                    target?.subscriptionUrl,
                    enabled = false,
                    knownSubscriptionUrls = knownSubscriptionUrls
                )
                viewModel.updateSubscriptionSettings(settings) {
                    locationViewModel.selectLocation(id) {
                        viewModel.loadCurrentConfig()
                        viewModel.restartVpnIfRunning()
                        if (wasConnected) {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    name?.takeIf { it.isNotBlank() }
                                        ?.let { getString(Res.string.reconnecting_through, it) }
                                        ?: getString(Res.string.reconnecting_new_location)
                                )
                            }
                        }
                    }
                }
            },
            onLowestSelected = { subscriptionUrl, fallbackId ->
                viewModel.cancelAutomaticSelection()
                val wasConnected = state.isVpnConnected
                val settings = subscriptionSettings.withLowestEnabled(
                    subscriptionUrl,
                    enabled = true,
                    knownSubscriptionUrls = knownSubscriptionUrls
                )
                viewModel.updateSubscriptionSettings(settings) {
                    val alreadyInList = locations.firstOrNull { it.storageId == selectedId }
                        ?.subscriptionUrl?.trim() == subscriptionUrl.trim()
                    val selectId = if (alreadyInList) selectedId else fallbackId
                    locationViewModel.selectLocation(selectId ?: fallbackId) {
                        viewModel.loadCurrentConfig()
                        if (wasConnected) {
                            scope.launch {
                                snackbarHostState.showSnackbar(getString(Res.string.reconnecting_lowest))
                            }
                        }
                        startLowestConnection(subscriptionUrl)
                    }
                }
            },
            onLocationSettingsClick = { id -> onOpenLocationSettings(id) },
            onMeasure = { ids -> refreshHttpPings(ids) },
            onRefreshSubscriptionClick = { url -> refreshSubscription(url) },
            onDeleteLocationClick = { id ->
                locations.firstOrNull { it.storageId == id }?.let { item ->
                    confirmRemove = PendingRemoval.Location(id = id, title = item.fullName)
                }
            },
            onDeleteSubscriptionClick = { url ->
                val members = locations.filter { it.subscriptionUrl?.trim() == url.trim() }
                confirmRemove = PendingRemoval.ServerList(
                    url = url,
                    title = members.firstOrNull()?.subscriptionTitle(words)?.takeIf { it.isNotBlank() }
                        ?: thisServerList,
                    count = members.size
                )
            },
            onOpenUrl = onOpenExternalUrl,
            onAddLocationClick = onAddLocation,
            onGetSubscriptionClick = onGetSubscriptionClick,
            canPing = { config -> viewModel.canPing(config) }
        ),
        scrollState = scrollState,
        snackbarHostState = snackbarHostState
    )

    if (isLogsSheetOpen) {
        val logs by viewModel.logs.collectAsState()
        LogsSheet(
            logs = logs,
            verboseDebugLogs = routingSettings.verboseDebugLogs,
            onVerboseDebugLogsChanged = { enabled ->
                viewModel.updateRoutingSettings(routingSettings.copy(verboseDebugLogs = enabled))
            },
            onSaveClick = {
                onSaveLogsRequested(
                    { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
                    { message -> scope.launch { snackbarHostState.showSnackbar(message) } }
                )
            },
            onShareClick = {
                viewModel.onShareLogs(
                    onShared = { message ->
                        scope.launch { snackbarHostState.showSnackbar(message) }
                    },
                    onError = { message ->
                        scope.launch { snackbarHostState.showSnackbar(message) }
                    }
                )
            },
            onDismiss = { isLogsSheetOpen = false }
        )
    }

    if (isAddSheetOpen) {
        AddConfigurationSheet(
            canScanQr = canScanQr,
            hasSubscriptions = hasSubscriptions,
            onDismiss = { isAddSheetOpen = false },
            onScanQrClick = {
                isAddSheetOpen = false
                showCameraRationale = true
            },
            onPasteLinkClick = {
                isAddSheetOpen = false
                onImportFromClipboardRequested(
                    { scope.launch { snackbarHostState.showSnackbar(getString(Res.string.imported_from_clipboard)) } },
                    { message -> scope.launch { snackbarHostState.showSnackbar(message) } }
                )
            },
            onImportFileClick = {
                isAddSheetOpen = false
                onImportFileRequested()
            },
            onImportFromPhoneClick = onImportFromPhoneRequested?.let { open ->
                {
                    isAddSheetOpen = false
                    open()
                }
            },
            onUpdateSubscriptionsClick = {
                isAddSheetOpen = false
                refreshSubscriptions()
            },
            onAddCustomLocationClick = {
                isAddSheetOpen = false
                onAddLocation()
            },
            onGetSubscriptionClick = {
                isAddSheetOpen = false
                onGetSubscriptionClick()
            },
            showGetSubscription = showGetSubscription,
            // The same answer as the board above: these two disagreed once, so the
            // button appeared or vanished depending on which way in you took.
            showCustomLocation = canCreateCustomLocation
        )
    }

    if (showCameraRationale) {
        // The only way out is forward, into the system prompt: App Review reads a
        // message that can be dismissed as a permission request being delayed.
        CameraRationaleSheet(
            onContinue = {
                showCameraRationale = false
                onScanQrRequested()
            }
        )
    }

    if (showAdminDialog) {
        AdminPasswordDialog(
            onDismiss = { showAdminDialog = false },
            onSubmit = { AdminState.tryUnlock(it) },
        )
    }
}

/**
 * The mono tag beside the brand.
 *
 * `OLCRTC CORE`, not the fork's name: olcRTC is the one thing in this app no
 * other App Store client implements, and the header is the first place a
 * reviewer's eye lands.
 */
private const val HEADER_TAG = "OLCRTC CORE"
private const val LOWEST_CONNECT_BUDGET_MS = 6_000L

/**
 * The words of the status strip. English by default, so the helpers below stay
 * pure; the screen passes [statusWords], read from string resources.
 */
internal data class StatusWords(
    val inRoom: String = "in a room",
    val connected: String = "connected",
    val joiningRoom: String = "joining room",
    val connecting: String = "connecting",
    val noServerList: String = "no server list",
    val notConnected: String = "not connected",
    val addServerListToStart: String = "Add a server list to start",
    val roomFull: String = "this room is full"
)

/** [StatusWords] in the user's language. */
@Composable
internal fun statusWords(): StatusWords = StatusWords(
    inRoom = stringResource(Res.string.status_in_room),
    connected = stringResource(Res.string.status_connected),
    joiningRoom = stringResource(Res.string.status_joining_room),
    connecting = stringResource(Res.string.status_connecting),
    noServerList = stringResource(Res.string.status_no_server_list),
    notConnected = stringResource(Res.string.status_not_connected),
    addServerListToStart = stringResource(Res.string.status_add_list_to_start),
    roomFull = stringResource(Res.string.status_room_full)
)

/** What the status strip's first line says. */
internal fun statusLabel(
    isConnected: Boolean,
    isConnecting: Boolean,
    requiresSetup: Boolean,
    hasSeats: Boolean,
    transportLabel: String?,
    words: StatusWords = StatusWords()
): String = when {
    isConnected -> listOfNotNull(
        if (hasSeats) words.inRoom else words.connected,
        transportLabel
    ).joinToString(" · ")
    isConnecting -> if (hasSeats) words.joiningRoom else words.connecting
    requiresSetup -> words.noServerList
    else -> words.notConnected
}

/** The second line: traffic while connected, and what would be joined while not. */
internal fun statusMeta(
    isConnected: Boolean,
    bytesLine: String,
    requiresSetup: Boolean,
    isFull: Boolean,
    protocolLine: String?,
    words: StatusWords = StatusWords()
): String = when {
    isConnected && bytesLine.isNotBlank() -> bytesLine
    isConnected -> protocolLine.orEmpty()
    requiresSetup -> words.addServerListToStart
    isFull -> words.roomFull
    else -> protocolLine.orEmpty()
}

/**
 * The one number worth the weight: the session timer, or the exit's name before
 * there is a session to time.
 */
internal fun statusValue(
    isConnected: Boolean,
    connectedSince: Long?,
    nowEpochMs: Long,
    exitName: String?
): String = when {
    isConnected && connectedSince != null -> formatSessionDuration(nowEpochMs - connectedSince)
    isConnected -> ""
    // Cut at a separator, not at character twelve: "United State" is a
    // typo where "United States" is a country.
    else -> exitName?.let { shortenExitName(it, max = 14) }.orEmpty()
}

/**
 * How often the server is re-asked how full each olcRTC node is.
 *
 * Comfortably inside the five-minute window the server uses to decide somebody has
 * left, so a slot that frees is visible long before anyone would act on it, and slow
 * enough that a list of rooms costs a handful of requests a minute.
 */
private const val OCCUPANCY_REFRESH_MS = 45_000L
