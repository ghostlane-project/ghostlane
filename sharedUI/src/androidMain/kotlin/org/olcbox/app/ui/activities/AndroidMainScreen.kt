package org.olcbox.app.ui.activities

import org.olcbox.app.ui.tv.isTelevision
import org.olcbox.app.ui.tv.hasCamera
import org.olcbox.app.ui.tv.PhoneImportSheet
import org.olcbox.app.ui.features.home.localizedSingleMessage
import multiplatform_app.sharedui.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import multiplatform_app.sharedui.generated.resources.location_qr
import multiplatform_app.sharedui.generated.resources.no_browser
import multiplatform_app.sharedui.generated.resources.qr_imported
import multiplatform_app.sharedui.generated.resources.server_list_added
import multiplatform_app.sharedui.generated.resources.server_list_not_found
import multiplatform_app.sharedui.generated.resources.server_list_qr
import multiplatform_app.sharedui.generated.resources.server_list_removed
import multiplatform_app.sharedui.generated.resources.unknown_error
import multiplatform_app.sharedui.generated.resources.update_allow_install
import multiplatform_app.sharedui.generated.resources.update_already_downloaded
import multiplatform_app.sharedui.generated.resources.update_available_version
import multiplatform_app.sharedui.generated.resources.update_check_failed
import multiplatform_app.sharedui.generated.resources.update_checking
import multiplatform_app.sharedui.generated.resources.update_download_failed
import multiplatform_app.sharedui.generated.resources.update_downloading
import multiplatform_app.sharedui.generated.resources.update_installing
import multiplatform_app.sharedui.generated.resources.update_service_unavailable
import multiplatform_app.sharedui.generated.resources.update_up_to_date
import org.jetbrains.compose.resources.getString
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import org.olcbox.app.admin.AdminState
import org.olcbox.app.data.share.ConfigShareService
import org.olcbox.app.ui.components.kit.PkBrand
import org.olcbox.app.update.AndroidUpdateSettingsStore
import org.olcbox.app.update.AppUpdateInfo
import org.olcbox.app.update.AppUpdateSettings
import org.olcbox.app.update.AppUpdateService
import org.olcbox.app.update.AndroidUpdateInstaller
import org.olcbox.app.update.identity
import org.olcbox.app.update.isDownloaded
import org.olcbox.app.update.isUpdateCheckDue
import org.olcbox.app.update.shouldShowOffer
import org.olcbox.app.ui.OlcboxAppContent
import org.olcbox.app.ui.components.ApplicationUpdateOfferSheet
import org.olcbox.app.ui.features.home.HomeScreenViewModel
import org.olcbox.app.ui.features.locations.LocationViewModel
import org.olcbox.app.ui.features.locations.subscriptionShareItems
import org.olcbox.app.ui.navigation.AppScreen
import org.olcbox.app.vpn.AndroidConnectionMode
import org.olcbox.app.vpn.AndroidSplitTunnelList
import org.olcbox.app.vpn.AndroidSplitTunnelMode
import org.olcbox.app.vpn.AndroidVpnManager

@Composable
fun AndroidMainScreen(
    viewModel: HomeScreenViewModel,
    locationViewModel: LocationViewModel,
    vpnManager: AndroidVpnManager,
    appUpdateService: AppUpdateService? = null,
    /** An import link the activity received; taken once, then cleared. */
    pendingImportLink: MutableStateFlow<String?>? = null
) {

    var currentScreenRoute by rememberSaveable { mutableStateOf("home") }
    var currentLocationId by rememberSaveable { mutableStateOf<String?>(null) }

    val currentScreen: AppScreen =
        when (currentScreenRoute) {
            "location_settings" -> AppScreen.LocationSettings(currentLocationId)
            else -> AppScreen.Home
        }

    val navigate: (AppScreen) -> Unit = { screen ->
        when (screen) {
            AppScreen.Home -> {
                currentScreenRoute = "home"
                currentLocationId = null
            }
            is AppScreen.LocationSettings -> {
                currentScreenRoute = "location_settings"
                currentLocationId = screen.locationId
            }
        }
    }

    val context = LocalContext.current
    // A TV has no camera to scan with and no clipboard worth the name: it takes a
    // server list from a phone on the same network instead.
    val television = remember(context) { context.isTelevision() }
    val camera = remember(context) { context.hasCamera() }
    var phoneImportOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val connectionMode by vpnManager.connectionMode.collectAsState()
    val proxySettings by vpnManager.proxySettings.collectAsState()
    val splitTunnelSettings by vpnManager.splitTunnelSettings.collectAsState()
    val installedApps by vpnManager.installedApps.collectAsState()
    val homeState by viewModel.state.collectAsState()
    val subscriptionSettings by viewModel.subscriptionSettings.collectAsState()
    val routingSettings by viewModel.routingSettings.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val pendingLogSaveCallbacks = remember {
        mutableStateOf<Pair<(String) -> Unit, (String) -> Unit>?>(null)
    }
    val pendingVpnAction = remember {
        mutableStateOf<PendingVpnPermissionAction?>(null)
    }
    var isAppSettingsOpen by remember { mutableStateOf(false) }
    var appSettingsInitialRoute by remember { mutableStateOf(AppSettingsInitialRoute.Hub) }
    var shareSheetPayload by remember { mutableStateOf<Pair<String, String>?>(null) }
    var splitTunnelRestartPending by remember { mutableStateOf(false) }
    val updateSettingsStore = remember(context) {
        AndroidUpdateSettingsStore(context)
    }
    val updateInstaller = remember(context, vpnManager) {
        AndroidUpdateInstaller(context) {
            vpnManager.subscriptionFetchProxy()
        }
    }
    var updateSettings by remember { mutableStateOf(AppUpdateSettings()) }
    var updateStatusText by remember { mutableStateOf<String?>(null) }
    var updateDownloadProgress by remember { mutableStateOf<Float?>(null) }
    var updateOffer by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var relaunchAfterInstall by remember { mutableStateOf(false) }
    val subscriptionRows = subscriptionShareItems(locationViewModel.locations.toList())

    val updateInstallLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        if (relaunchAfterInstall && result.resultCode == Activity.RESULT_OK) {
            relaunchAfterInstall = false
            updateInstaller.relaunchIntent()?.let { intent ->
                runCatching { context.startActivity(intent) }
            }
        } else {
            relaunchAfterInstall = false
        }
    }

    fun markSplitTunnelChanged() {
        if (homeState.isVpnConnected && connectionMode == AndroidConnectionMode.Tun) {
            splitTunnelRestartPending = true
        }
    }

    fun applyPendingSplitTunnelRestart() {
        if (splitTunnelRestartPending && homeState.isVpnConnected && connectionMode == AndroidConnectionMode.Tun) {
            viewModel.restartVpnIfRunning()
        }
        splitTunnelRestartPending = false
    }

    suspend fun saveUpdateSettings(settings: AppUpdateSettings) {
        val normalized = settings.normalized()
        updateSettings = normalized
        updateSettingsStore.save(normalized)
    }

    // Said from callbacks, which cannot read a resource themselves.
    val serverListAddedText = stringResource(Res.string.server_list_added)
    val qrImportedText = stringResource(Res.string.qr_imported)
    val noBrowserText = stringResource(Res.string.no_browser)
    val serverListRemovedText = stringResource(Res.string.server_list_removed)
    val serverListNotFoundText = stringResource(Res.string.server_list_not_found)
    val locationQrTitle = stringResource(Res.string.location_qr)
    val serverListQrTitle = stringResource(Res.string.server_list_qr)

    suspend fun showUpdateResult(info: AppUpdateInfo) {
        if (info.isDownloaded(updateSettings)) {
            updateOffer = null
            updateStatusText = getString(Res.string.update_already_downloaded, info.channel.name.lowercase())
        } else if (info.isUpdateAvailable) {
            updateOffer = info
            updateStatusText = getString(Res.string.update_available_version, info.channel.name, info.version)
        } else {
            updateOffer = null
            updateStatusText = getString(Res.string.update_up_to_date)
        }
    }

    fun checkUpdate(manual: Boolean) {
        val service = appUpdateService
        if (service == null) {
            scope.launch { updateStatusText = getString(Res.string.update_service_unavailable) }
            return
        }
        scope.launch {
            val previousSettings = updateSettings
            val checkStartedAt = kotlin.time.Clock.System.now().toEpochMilliseconds()
            if (!manual && !previousSettings.isUpdateCheckDue(checkStartedAt)) return@launch

            updateStatusText = getString(Res.string.update_checking, previousSettings.channel.name.lowercase())
            val result = service.check(
                previousSettings.channel,
                vpnManager.subscriptionFetchProxy()
            )
            val checkedAt = kotlin.time.Clock.System.now().toEpochMilliseconds()
            val checkedSettings = previousSettings.copy(lastCheckAtEpochMs = checkedAt).normalized()
            saveUpdateSettings(checkedSettings)
            result.fold(
                onSuccess = { info ->
                    if (manual || info.shouldShowOffer(previousSettings, checkedAt)) {
                        showUpdateResult(info)
                    } else {
                        updateOffer = null
                        updateStatusText = null
                    }
                },
                onFailure = { error ->
                    updateStatusText = error.message ?: getString(Res.string.update_check_failed)
                }
            )
        }
    }

    fun downloadUpdate(info: AppUpdateInfo) {
        scope.launch {
            if (!updateInstaller.canRequestPackageInstalls()) {
                updateInstaller.openUnknownSourcesSettings()
                updateStatusText = getString(Res.string.update_allow_install)
                Toast.makeText(context, updateStatusText, Toast.LENGTH_LONG).show()
                return@launch
            }

            updateDownloadProgress = 0f
            updateStatusText = getString(Res.string.update_downloading, info.asset.name)
            val result = updateInstaller.download(info.asset) { progress ->
                updateDownloadProgress = progress
            }
            val file = result.getOrElse { error ->
                updateStatusText = getString(Res.string.update_download_failed, error.message ?: getString(Res.string.unknown_error))
                updateDownloadProgress = null
                Toast.makeText(context, updateStatusText, Toast.LENGTH_LONG).show()
                return@launch
            }
            updateStatusText = getString(Res.string.update_installing, info.asset.name)
            saveUpdateSettings(
                updateSettings.copy(
                    lastSeenUpdateVersion = info.identity(),
                    lastDownloadedUpdateVersion = info.identity()
                )
            )
            updateOffer = null
            updateDownloadProgress = null
            relaunchAfterInstall = true
            updateInstallLauncher.launch(updateInstaller.installIntent(file))
        }
    }

    fun postponeUpdate(info: AppUpdateInfo) {
        scope.launch {
            saveUpdateSettings(updateSettings.copy(lastSeenUpdateVersion = info.identity()))
            updateOffer = null
        }
    }

    LaunchedEffect(appUpdateService) {
        val loaded = updateSettingsStore.load()
        updateSettings = loaded
        if (appUpdateService != null) {
            checkUpdate(manual = false)
        }
    }

    fun reloadLocationsAfterImport(onComplete: () -> Unit = {}) {
        locationViewModel.loadLocations {
            viewModel.loadCurrentConfig(onComplete)
        }
    }

    // A link from a bot or a panel goes through the same import as a paste;
    // the person sees the list appear, or a toast saying why not.
    if (pendingImportLink != null) {
        LaunchedEffect(pendingImportLink) {
            pendingImportLink.collect { link ->
                if (link == null) return@collect
                pendingImportLink.value = null
                viewModel.onImportLink(
                    uri = link,
                    onComplete = {
                        reloadLocationsAfterImport {
                            Toast.makeText(context, serverListAddedText, Toast.LENGTH_SHORT).show()
                        }
                    },
                    onError = { message -> Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
                )
            }
        }
    }

    val vpnRequestLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        if (result.resultCode == Activity.RESULT_OK) {
            when (val action = pendingVpnAction.value) {
                PendingVpnPermissionAction.Toggle -> viewModel.ToggleVpn()
                is PendingVpnPermissionAction.RestartWithMode -> {
                    vpnManager.selectConnectionMode(action.mode)
                    viewModel.restartVpnIfRunning()
                }
                null -> Unit
            }
        } else if (pendingVpnAction.value is PendingVpnPermissionAction.Toggle) {
            viewModel.cancelPendingLowest()
        }
        pendingVpnAction.value = null
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.onFileSelected(it) {
                reloadLocationsAfterImport()
            }
        }
    }

    val qrScannerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult

        val rawText = result.data?.getStringExtra(QrScannerActivity.EXTRA_QR_TEXT)
            ?.trim()
            .orEmpty()

        if (rawText.isBlank()) return@rememberLauncherForActivityResult

        viewModel.onImportFullConfig(rawText) {
            reloadLocationsAfterImport {
                Toast.makeText(context, qrImportedText, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val logSaveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        val callbacks = pendingLogSaveCallbacks.value
        pendingLogSaveCallbacks.value = null
        if (uri == null || callbacks == null) return@rememberLauncherForActivityResult

        viewModel.onSaveLogsToFile(
            target = uri,
            onSaved = callbacks.first,
            onError = callbacks.second
        )
    }

    fun navigateHomeFromLocationSettings() {
        viewModel.loadCurrentConfig()
        navigate(AppScreen.Home)
    }

    BackHandler(enabled = currentScreen is AppScreen.LocationSettings) {
        navigateHomeFromLocationSettings()
    }

    OlcboxAppContent(
        homeViewModel = viewModel,
        locationViewModel = locationViewModel,
        currentScreen = currentScreen,
        onNavigate = navigate,
        onToggleClick = {
            val prepIntent = if (connectionMode == AndroidConnectionMode.Tun) {
                VpnService.prepare(context)
            } else {
                null
            }
            if (prepIntent != null) {
                pendingVpnAction.value = PendingVpnPermissionAction.Toggle
                vpnRequestLauncher.launch(prepIntent)
            } else {
                viewModel.ToggleVpn()
            }
        },
        onImportFileRequested = {
            filePickerLauncher.launch("*/*")
        },
        onImportFromClipboardRequested = { onImported, onError ->
            viewModel.onPasteFromClipboard(
                onComplete = {
                    reloadLocationsAfterImport(onImported)
                },
                onError = onError
            )
        },
        onScanQrRequested = {
            qrScannerLauncher.launch(Intent(context, QrScannerActivity::class.java))
        },
        onShareLocationRequested = { config ->
            shareSheetPayload = locationQrTitle to ConfigShareService.olcRtcUri(config)
        },
        onSaveLogsRequested = { onSaved, onError ->
            pendingLogSaveCallbacks.value = onSaved to onError
            logSaveLauncher.launch(viewModel.suggestedLogsFileName())
        },
        showAppSettingsButton = true,
        onGetSubscriptionClick = {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(PkBrand.siteUrl))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure {
                Toast.makeText(context, noBrowserText, Toast.LENGTH_SHORT).show()
            }
        },
        // The call to action to go and buy a subscription is gone from every
        // platform, not only the one that was made to remove it. It went first on
        // iOS because App Review 3.1.1 required it there; leaving it standing on
        // Android and desktop meant the same app asked for money in two places
        // and not in a third, which is a difference nobody chose.
        showGetSubscription = false,
        showSplitTunnelingButton = false,
        canScanQr = camera,
        onImportFromPhoneRequested = if (television || !camera) ({ phoneImportOpen = true }) else null,
        onAppSettingsClick = {
            appSettingsInitialRoute = AppSettingsInitialRoute.Hub
            vpnManager.refreshInstalledApps()
            isAppSettingsOpen = true
        },
        onSplitTunnelingClick = {
            appSettingsInitialRoute = AppSettingsInitialRoute.SplitTunneling
            vpnManager.refreshInstalledApps()
            isAppSettingsOpen = true
        }
    )

    shareSheetPayload?.let { (title, payload) ->
        AndroidConfigShareSheet(
            title = title,
            payload = payload,
            onDismiss = { shareSheetPayload = null }
        )
    }

    if (phoneImportOpen) {
        PhoneImportSheet(
            onLink = { link ->
                phoneImportOpen = false
                viewModel.onImportFullConfig(
                    rawText = link,
                    onComplete = {
                        reloadLocationsAfterImport {
                            Toast.makeText(context, serverListAddedText, Toast.LENGTH_SHORT).show()
                        }
                    },
                    onError = { message -> Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
                )
            },
            onDismiss = { phoneImportOpen = false }
        )
    }

    updateOffer?.let { info ->
        ApplicationUpdateOfferSheet(
            info = info,
            downloadProgress = updateDownloadProgress,
            onLater = { postponeUpdate(info) },
            onDownload = { downloadUpdate(info) }
        )
    }

    if (isAppSettingsOpen) {
        AppSettingsSheet(
            initialRoute = appSettingsInitialRoute,
            selectedMode = connectionMode,
            proxySettings = proxySettings,
            splitTunnelSettings = splitTunnelSettings,
            installedApps = installedApps,
            logs = logs,
            showUpdates = appUpdateService != null,
            updateSettings = updateSettings,
            updateStatusText = updateStatusText,
            updateDownloadProgress = updateDownloadProgress,
            subscriptions = subscriptionRows,
            subscriptionSettings = subscriptionSettings,
            onSubscriptionSettingsChanged = viewModel::updateSubscriptionSettings,
            routingSettings = routingSettings,
            onRoutingSettingsChanged = viewModel::updateRoutingSettings,
            enabled = !homeState.isVpnLoading,
            isConnectionActive = homeState.isVpnConnected,
            onDismiss = {
                isAppSettingsOpen = false
                applyPendingSplitTunnelRestart()
            },
            onSaveLogsClick = {
                val showToast: (String) -> Unit = { message ->
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
                pendingLogSaveCallbacks.value = showToast to showToast
                logSaveLauncher.launch(viewModel.suggestedLogsFileName())
            },
            onShareLogsClick = {
                val showToast: (String) -> Unit = { message ->
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
                viewModel.onShareLogs(showToast, showToast)
            },
            onUpdateIntervalSelected = { hours ->
                scope.launch {
                    saveUpdateSettings(updateSettings.copy(intervalHours = hours))
                }
            },
            onCheckUpdatesClick = {
                checkUpdate(manual = true)
            },
            onSubscriptionShareClick = { url ->
                shareSheetPayload = serverListQrTitle to ConfigShareService.subscriptionQrText(url)
            },
            onSubscriptionRefreshClick = { url ->
                viewModel.refreshSubscription(url) { report ->
                    reloadLocationsAfterImport {
                        viewModel.restartVpnIfRunning()
                        scope.launch {
                            Toast.makeText(
                                context,
                                report.localizedSingleMessage(),
                                if (report.hasFailures) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            },
            onSubscriptionDeleteClick = { url ->
                viewModel.deleteSubscription(url) { removed ->
                    reloadLocationsAfterImport {
                        Toast.makeText(
                            context,
                            if (removed > 0) serverListRemovedText else serverListNotFoundText,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            },
            onModeSelected = { mode ->
                if (mode != connectionMode && homeState.isVpnConnected) {
                    val prepIntent = if (mode == AndroidConnectionMode.Tun) {
                        VpnService.prepare(context)
                    } else {
                        null
                    }
                    if (prepIntent != null) {
                        pendingVpnAction.value = PendingVpnPermissionAction.RestartWithMode(mode)
                        vpnRequestLauncher.launch(prepIntent)
                    } else {
                        vpnManager.selectConnectionMode(mode)
                        viewModel.restartVpnIfRunning()
                    }
                } else if (mode != connectionMode) {
                    vpnManager.selectConnectionMode(mode)
                }
            },
            onProxySettingsSaved = { host, username, password, port ->
                vpnManager.updateProxySettings(host, username, password, port)
                if (homeState.isVpnConnected) {
                    viewModel.restartVpnIfRunning()
                }
            },
            onProxyPasswordRegenerated = {
                vpnManager.regenerateProxyPassword()
                if (homeState.isVpnConnected) {
                    viewModel.restartVpnIfRunning()
                }
            },
            onSplitTunnelModeSelected = { mode: AndroidSplitTunnelMode ->
                vpnManager.selectSplitTunnelMode(mode)
                markSplitTunnelChanged()
            },
            onSplitTunnelAppToggled = { list: AndroidSplitTunnelList, packageName: String ->
                vpnManager.toggleSplitTunnelApp(list, packageName)
                markSplitTunnelChanged()
            },
            onSplitTunnelAppsSelected = { list: AndroidSplitTunnelList, packages: Set<String> ->
                vpnManager.setSplitTunnelApps(list, packages)
                markSplitTunnelChanged()
            }
        )
    }
}

private sealed class PendingVpnPermissionAction {
    object Toggle : PendingVpnPermissionAction()
    data class RestartWithMode(val mode: AndroidConnectionMode) : PendingVpnPermissionAction()
}
