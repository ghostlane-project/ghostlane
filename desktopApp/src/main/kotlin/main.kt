import org.olcbox.app.ui.components.RouterExportSheet
import org.olcbox.app.ui.components.routerEntries
import androidx.compose.animation.AnimatedVisibility
import org.olcbox.app.ui.features.home.localizedSingleMessage
import org.olcbox.app.desktop.localizedDesktopText
import org.olcbox.app.desktop.text
import org.olcbox.app.desktop.load
import org.olcbox.app.desktop.DesktopText
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.awt.SwingWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.flow.MutableStateFlow
import org.olcbox.app.net.ImportLink
import java.net.URI
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.security.SecureRandom
import kotlin.math.min
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import org.olcbox.app.CurrentAppInfo
import org.olcbox.app.data.datasource.JvmLocationsDataSourceImpl
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.exporter.JvmLogExporter
import org.olcbox.app.data.identity.PersistentDeviceIdentityProvider
import org.olcbox.app.data.importer.JvmConfigImporter
import org.olcbox.app.data.share.ConfigShareService
import org.olcbox.app.desktop.DesktopWindowChrome
import org.olcbox.app.desktop.rememberDesktopTrayIcon
import org.olcbox.app.ui.components.kit.LocalWindowTitleBarInset
import org.olcbox.app.ui.components.kit.PkBrand
import org.olcbox.app.ui.OlcboxAppContent
import org.olcbox.app.ui.components.ApplicationConnectionModeOption
import org.olcbox.app.ui.components.ApplicationSocksProxySettings
import org.olcbox.app.ui.components.ApplicationSettingsSheet
import org.olcbox.app.ui.components.ApplicationUpdateOfferSheet
import org.olcbox.app.ui.features.home.HomeScreenViewModel
import org.olcbox.app.ui.features.locations.LocationViewModel
import org.olcbox.app.ui.features.locations.subscriptionShareItems
import org.olcbox.app.ui.navigation.AppScreen
import org.olcbox.app.ui.theme.AppTheme
import org.olcbox.app.update.AppUpdateInfo
import org.olcbox.app.update.AppUpdateSettings
import org.olcbox.app.update.AppUpdateService
import org.olcbox.app.update.JvmUpdateInstaller
import org.olcbox.app.update.JvmUpdateSettingsStore
import org.olcbox.app.update.identity
import org.olcbox.app.update.isDownloaded
import org.olcbox.app.update.isUpdateCheckDue
import org.olcbox.app.update.shouldShowOffer
import org.olcbox.app.vpn.DesktopSocksProxySettings
import org.olcbox.app.vpn.DesktopVpnManager
import org.olcbox.app.vpn.DesktopConnectionMode
import org.olcbox.app.vpn.DesktopConnectionModePreference
import org.olcbox.app.vpn.desktopRoutingModes
import org.olcbox.app.vpn.desktopRoutingNote
import org.olcbox.app.vpn.desktopRoutingUnavailableReason
import org.olcbox.app.vpn.JvmDesktopSocksProxySettingsStore
import org.olcbox.app.vpn.desktop.MacOsTunnelDaemon

private class DesktopAppDependencies {
    private val locationsDataSource = JvmLocationsDataSourceImpl()
    val configImporter = JvmConfigImporter()

    val locationsRepository = LocationsRepositoryImpl(locationsDataSource)
    val updateService = AppUpdateService(
        deviceIdentityProvider = PersistentDeviceIdentityProvider(locationsDataSource)
    )
    val updateSettingsStore = JvmUpdateSettingsStore()
    val updateInstaller = JvmUpdateInstaller()
    val socksProxySettingsStore = JvmDesktopSocksProxySettingsStore()

    val vpnManager = DesktopVpnManager(locationsRepository)

    val homeViewModel = HomeScreenViewModel(
        vpnManager = vpnManager,
        locationsRepository = locationsRepository,
        configImporter = configImporter,
        logExporter = JvmLogExporter()
    )

    val locationViewModel = LocationViewModel(locationsRepository)

    fun close() {
        vpnManager.close()
    }
}

private const val WINDOWS_ELEVATED_START_ARGUMENT = "--olcbox-start-vpn-after-elevation"

/**
 * The one-tap import link, `ghostlane://add?url=…` or `proofkit://…`, until the screen takes it.
 * Linux hands it over as an argument (the desktop entry's `%u`), macOS as an
 * AWT open-URI event; Windows has no handler registered in this release.
 */
private val pendingImportLink = MutableStateFlow<String?>(null)

private fun watchForImportLinks(args: Array<String>) {
    args.firstOrNull { ImportLink.payloadOf(it) != null }?.let { pendingImportLink.value = it }
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_OPEN_URI)) {
        Desktop.getDesktop().setOpenURIHandler { event -> pendingImportLink.value = event.uri.toString() }
    }
}

fun main(args: Array<String>) {
    // AWT reads these once, as it starts, and application {} is what starts it.
    DesktopWindowChrome.installProcessProperties()
    runDesktopApplication(args)
}

@OptIn(ExperimentalComposeUiApi::class)
private fun runDesktopApplication(args: Array<String>) = application {
    remember { watchForImportLinks(args) }
    // Configure JNA to find native libraries in resources
    System.setProperty(
        "jna.library.path",
        System.getProperty("jna.library.path", "") +
                File.pathSeparator +
                File(System.getProperty("user.dir"), "native").absolutePath
    )

    val dependencies = remember { DesktopAppDependencies() }

    // Cmd+Q reaches none of the handlers below.
    //
    // Closing the window only hides it while a tray exists, the tray's own Quit
    // item calls close(), and onDispose covers an orderly teardown — but the
    // standard macOS quit terminates the JVM without visiting any of them. The
    // cores die with the process and the tunnel daemon, by design, does not, so
    // what is left is a tun with nothing behind it and a Mac with no internet.
    //
    // A shutdown hook is the one place every exit passes through, SIGTERM and
    // logout included. It cannot help against SIGKILL — that is what the
    // daemon's own watchdog is for.
    DisposableEffect(dependencies) {
        val hook = Thread({ runCatching { dependencies.close() } }, "olcbox-shutdown")
        Runtime.getRuntime().addShutdownHook(hook)
        onDispose { runCatching { Runtime.getRuntime().removeShutdownHook(hook) } }
    }
    var currentScreen by remember { mutableStateOf<AppScreen>(AppScreen.Home) }
    var showDesktopSettings by remember { mutableStateOf(false) }
    var isWindowVisible by remember { mutableStateOf(true) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var updateSettings by remember { mutableStateOf(AppUpdateSettings()) }
    var updateProgress by remember { mutableStateOf<Float?>(null) }
    var updateOffer by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var sharePayload by remember { mutableStateOf<Pair<String, String>?>(null) }
    var routerExportUrl by remember { mutableStateOf<String?>(null) }
    var desktopNotice by remember { mutableStateOf<String?>(null) }
    // Null on every platform but macOS, and the settings row is then absent.
    var tunnelDaemonSummary by remember { mutableStateOf(MacOsTunnelDaemon.settingsSummary()) }
    // Recomputed alongside it: approving the daemon changes which mode the next
    // connection uses, and a Connection Mode card still reading "Local SOCKS5
    // proxy" next to a tunnel row reading "Installed" is the app contradicting
    // itself in two places at once.
    var connectionModeOptions by remember { mutableStateOf(DesktopConnectionModePreference.available()) }
    var selectedConnectionMode by remember { mutableStateOf(DesktopConnectionModePreference.selected()) }
    // Derived from the two states above, not read back from disk: a pick has to
    // redraw the chooser, and a value nothing in the composition depends on
    // redraws only when something unrelated happens to.
    val effectiveConnectionMode =
        DesktopConnectionModePreference.effective(connectionModeOptions, selectedConnectionMode)
    val scope = rememberCoroutineScope()
    val trayState = rememberTrayState()
    val trayHomeState by dependencies.homeViewModel.state.collectAsState()

    suspend fun saveUpdateSettings(settings: AppUpdateSettings) {
        val normalized = settings.normalized()
        updateSettings = normalized
        dependencies.updateSettingsStore.save(normalized)
    }

    fun checkUpdate(manual: Boolean) {
        scope.launch {
            val previousSettings = updateSettings
            val checkStartedAt = kotlin.time.Clock.System.now().toEpochMilliseconds()
            if (!manual && !previousSettings.isUpdateCheckDue(checkStartedAt)) return@launch

            updateMessage = DesktopText.UpdateChecking.load(previousSettings.channel.name.lowercase())
            val result = dependencies.updateService.check(previousSettings.channel)
            val checkedAt = kotlin.time.Clock.System.now().toEpochMilliseconds()
            val checkedSettings = previousSettings.copy(lastCheckAtEpochMs = checkedAt).normalized()
            saveUpdateSettings(checkedSettings)
            result.fold(
                onSuccess = { info ->
                    if (manual || info.shouldShowOffer(previousSettings, checkedAt)) {
                        if (info.isDownloaded(checkedSettings)) {
                            updateOffer = null
                            updateMessage = DesktopText.UpdateAlreadyDownloaded.load(info.channel.name.lowercase())
                        } else if (info.isUpdateAvailable) {
                            updateOffer = info
                            updateMessage = DesktopText.UpdateFound.load(info.channel.name, info.version)
                        } else {
                            updateOffer = null
                            updateMessage = DesktopText.UpToDate.load()
                        }
                    } else {
                        updateOffer = null
                        updateMessage = null
                    }
                },
                onFailure = { error ->
                    updateMessage = error.message ?: DesktopText.UpdateCheckFailed.load()
                }
            )
        }
    }

    fun downloadUpdate(info: AppUpdateInfo) {
        scope.launch {
            updateProgress = 0f
            updateMessage = DesktopText.Downloading.load(info.asset.name)
            val result = dependencies.updateInstaller.downloadAndOpen(info.asset) { progress ->
                updateProgress = progress
            }
            updateMessage = result.getOrElse { error ->
                DesktopText.DownloadFailed.load(error.message ?: DesktopText.UnknownError.load())
            }
            if (result.isSuccess) {
                saveUpdateSettings(
                    updateSettings.copy(
                        lastSeenUpdateVersion = info.identity(),
                        lastDownloadedUpdateVersion = info.identity()
                    )
                )
                updateOffer = null
            }
            updateProgress = null
        }
    }

    fun postponeUpdate(info: AppUpdateInfo) {
        scope.launch {
            saveUpdateSettings(updateSettings.copy(lastSeenUpdateVersion = info.identity()))
            updateOffer = null
        }
    }

    LaunchedEffect(Unit) {
        val loaded = dependencies.updateSettingsStore.load()
        updateSettings = loaded
        dependencies.vpnManager.refreshLanAddresses()
        val savedProxySettings = dependencies.socksProxySettingsStore.load()
        val availableLanAddresses = dependencies.vpnManager.lanAddresses.value
        var safeProxySettings = savedProxySettings
        if (safeProxySettings.shareOnLan &&
            (safeProxySettings.lanUsername.isBlank() || safeProxySettings.lanPassword.isBlank())
        ) {
            safeProxySettings = safeProxySettings.withGeneratedLanCredentials()
        }
        if (safeProxySettings.shareOnLan && (
                safeProxySettings.lanAddress !in availableLanAddresses ||
                    !dependencies.vpnManager.isTrustedLanNetwork(safeProxySettings)
            )) {
            safeProxySettings = safeProxySettings.copy(
                lanAddress = "",
                shareOnLan = false
            )
        }
        safeProxySettings = safeProxySettings.normalized()
        dependencies.vpnManager.updateSocksProxySettings(safeProxySettings)
        if (safeProxySettings != savedProxySettings) {
            dependencies.socksProxySettingsStore.save(safeProxySettings)
        }
        checkUpdate(manual = false)
        if (WINDOWS_ELEVATED_START_ARGUMENT in args) {
            dependencies.homeViewModel.loadCurrentConfig {
                dependencies.homeViewModel.ToggleVpn()
            }
        }
    }

    // The daemon's state changes without us: the user approves it in System
    // Settings, in another application, and launchd finishes the job afterwards.
    // Polling while the sheet is open is what stops the row from still reading
    // "Approve in System Settings" once they have.
    LaunchedEffect(showDesktopSettings) {
        while (showDesktopSettings) {
            tunnelDaemonSummary = MacOsTunnelDaemon.settingsSummary()
            // Approving the daemon makes the tunnel option pickable, and that
            // happens in System Settings, in another application.
            connectionModeOptions = DesktopConnectionModePreference.available()
            delay(1_000)
        }
    }

    LaunchedEffect(desktopNotice) {
        if (desktopNotice != null) {
            delay(1_800)
            desktopNotice = null
        }
    }

    Tray(
        state = trayState,
        // Monochrome where the platform's other tray icons are (a template image in
        // the macOS menu bar); the coloured tile where nothing says what is behind it.
        icon = rememberDesktopTrayIcon(appIcon = painterResource("LinuxIcon.png")),
        tooltip = "Ghostlane",
        menu = {
            Item(DesktopText.Open.text(), onClick = { isWindowVisible = true })
            Item(
                (if (trayHomeState.isVpnConnected || trayHomeState.isVpnLoading) DesktopText.Stop else DesktopText.Start).text(),
                enabled = trayHomeState.isVpnConnected || trayHomeState.isVpnLoading || trayHomeState.canStartVpn,
                onClick = {
                    dependencies.homeViewModel.ToggleVpn()
                }
            )
            Item(DesktopText.Settings.text(), onClick = {
                isWindowVisible = true
                showDesktopSettings = true
            })
            Separator()
            Item(DesktopText.Quit.text(), onClick = {
                dependencies.close()
                exitApplication()
            })
        }
    )

    val windowState = rememberWindowState(width = 430.dp, height = 780.dp)
    SwingWindow(
        title = "Ghostlane",
        visible = isWindowVisible,
        state = windowState,
        onCloseRequest = {
            if (java.awt.SystemTray.isSupported()) {
                isWindowVisible = false
            } else {
                dependencies.close()
                exitApplication()
            }
        },
        // Runs before the window has a native peer: AppKit takes the title bar's
        // style from these client properties when AWT creates the NSWindow.
        init = { window -> DesktopWindowChrome.prepare(window) },
    ) {
        window.minimumSize = Dimension(350, 600)

        DisposableEffect(Unit) {
            onDispose {
                dependencies.close()
            }
        }

        DesktopAppTheme(windowState) {
            val logs by dependencies.homeViewModel.logs.collectAsState()
            val homeState by dependencies.homeViewModel.state.collectAsState()
            val subscriptionSettings by dependencies.homeViewModel.subscriptionSettings.collectAsState()
            val routingSettings by dependencies.homeViewModel.routingSettings.collectAsState()
            val socksProxySettings by dependencies.vpnManager.socksProxySettings.collectAsState()
            val lanProxyEndpoint by dependencies.vpnManager.lanProxyEndpoint.collectAsState()
            val lanProxyHealth by dependencies.vpnManager.lanProxyHealth.collectAsState()
            val lanAddresses by dependencies.vpnManager.lanAddresses.collectAsState()

            fun reloadLocationsAfterImport(onComplete: () -> Unit = {}) {
                dependencies.locationViewModel.loadLocations {
                    dependencies.homeViewModel.loadCurrentConfig(onComplete)
                }
            }

            // A link from a bot or a panel goes through the same import as a
            // paste; the notice says what came of it.
            LaunchedEffect(Unit) {
                pendingImportLink.collect { link ->
                    if (link == null) return@collect
                    pendingImportLink.value = null
                    dependencies.homeViewModel.onImportLink(
                        uri = link,
                        onComplete = { reloadLocationsAfterImport { scope.launch { desktopNotice = DesktopText.ServerListAdded.load() } } },
                        onError = { message -> desktopNotice = message }
                    )
                }
            }

            // Read here, where composition can; the file dialogs open from callbacks.
            val importDialogTitle = DesktopText.ImportConfigDialog.text()
            val saveLogsDialogTitle = DesktopText.SaveLogsDialog.text()
            Box(modifier = Modifier.fillMaxSize()) {
                OlcboxAppContent(
                    homeViewModel = dependencies.homeViewModel,
                    locationViewModel = dependencies.locationViewModel,
                    currentScreen = currentScreen,
                    onNavigate = { screen ->
                        currentScreen = screen
                    },
                    onToggleClick = {
                        dependencies.homeViewModel.ToggleVpn()
                    },
                    onImportFileRequested = {
                        chooseConfigFile(window, importDialogTitle)?.let { file ->
                            dependencies.homeViewModel.onFileSelected(file) {
                                reloadLocationsAfterImport()
                            }
                        }
                    },
                    onImportFromClipboardRequested = { onImported, onError ->
                        dependencies.homeViewModel.onPasteFromClipboard(
                            onComplete = {
                                reloadLocationsAfterImport(onImported)
                            },
                            onError = onError
                        )
                    },
                    onScanQrRequested = {},
                    onShareLocationRequested = { config ->
                        scope.launch { sharePayload = DesktopText.LocationQr.load() to ConfigShareService.olcRtcUri(config) }
                    },
                    onSaveLogsRequested = { onSaved, onError ->
                        chooseSaveFile(
                            owner = window,
                            defaultName = dependencies.homeViewModel.suggestedLogsFileName(),
                            title = saveLogsDialogTitle
                        )?.let { file ->
                            dependencies.homeViewModel.onSaveLogsToFile(
                                target = file,
                                onSaved = onSaved,
                                onError = onError
                            )
                        }
                    },
                    showAppSettingsButton = true,
                    // The call to action to go and buy a subscription is gone from
                    // every platform, not only the one that was made to remove it.
                    // It went first on iOS because App Review 3.1.1 required it
                    // there; leaving it standing on desktop and Android meant the
                    // same app asked for money in two places and not in a third,
                    // which is a difference nobody chose.
                    showGetSubscription = false,
                    onGetSubscriptionClick = {
                        runCatching {
                            if (Desktop.isDesktopSupported() &&
                                Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
                            ) {
                                Desktop.getDesktop().browse(URI(PkBrand.siteUrl))
                            } else {
                                error("browser unavailable")
                            }
                        }.onFailure {
                            scope.launch { desktopNotice = DesktopText.OpenInBrowser.load(PkBrand.siteUrl) }
                        }
                    },
                    showSplitTunnelingButton = false,
                    canScanQr = false,
                    onAppSettingsClick = { showDesktopSettings = true },
                    onSplitTunnelingClick = {}
                )

                if (showDesktopSettings) {
                    ApplicationSettingsSheet(
                        onReplayOnboarding = { dependencies.homeViewModel.replayOnboarding() },
                        updateSettings = updateSettings,
                        updateStatusText = updateMessage,
                        updateDownloadProgress = updateProgress,
                        updateOffer = updateOffer,
                        subscriptions = subscriptionShareItems(dependencies.locationViewModel.locations.toList()),
                        logs = logs,
                        connectionSummary = effectiveConnectionMode?.summary?.let { localizedDesktopText(it) }.orEmpty(),
                        connectionModeTitle = effectiveConnectionMode?.title?.let { localizedDesktopText(it) }.orEmpty(),
                        connectionModeSummary = effectiveConnectionMode?.summary?.let { localizedDesktopText(it) }.orEmpty(),
                        connectionModeOptions = connectionModeOptions.map { option ->
                            ApplicationConnectionModeOption(
                                id = option.mode.id,
                                title = localizedDesktopText(option.title),
                                summary = localizedDesktopText(option.summary),
                                enabled = option.enabled,
                                disabledReason = option.disabledReason?.let { localizedDesktopText(it) }
                            )
                        },
                        selectedConnectionModeId = effectiveConnectionMode?.mode?.id,
                        onConnectionModeSelected = { id ->
                            val mode = DesktopConnectionMode.fromId(id)
                            if (mode != selectedConnectionMode) {
                                DesktopConnectionModePreference.select(mode)
                                selectedConnectionMode = mode
                                // Restart rather than wait for the next connect: a
                                // mode that takes effect at some unannounced later
                                // time is exactly what this screen was already
                                // confusing people with. Same call Android makes
                                // on the same gesture.
                                dependencies.homeViewModel.restartVpnIfRunning()
                            }
                        },
                        // The PAC server is how the proxy mode delivers traffic and
                        // has nothing to do with a tun. Listing its URL under a
                        // system-wide tunnel invites someone to point a browser at
                        // a component that is not in the path.
                        connectionDetails = if (effectiveConnectionMode?.mode == DesktopConnectionMode.Proxy) {
                            listOf(
                                "PAC URL" to "http://127.0.0.1:10809/proxy.pac",
                                "PAC Target" to "SOCKS5 ${socksProxySettings.host}:${socksProxySettings.port}"
                            )
                        } else {
                            emptyList()
                        },
                        socksProxySettings = socksProxySettings.toApplicationSocksProxySettings(
                            lanAddresses = lanAddresses,
                            lanEndpoint = lanProxyEndpoint,
                            lanHealth = lanProxyHealth,
                            lanSecurityNotice = dependencies.vpnManager.lanSecurityNotice
                        ),
                        tunnelDaemonSummary = tunnelDaemonSummary,
                        onTunnelDaemonClick = {
                            // Approval is a trip to System Settings that only the
                            // user can make; anything else is ours to attempt.
                            if (MacOsTunnelDaemon.status() ==
                                MacOsTunnelDaemon.Registration.RequiresApproval
                            ) {
                                MacOsTunnelDaemon.openLoginItemsSettings()
                            } else {
                                MacOsTunnelDaemon.register()
                            }
                            tunnelDaemonSummary = MacOsTunnelDaemon.settingsSummary()
                            // Verbatim, not a paraphrase: "Operation not
                            // permitted" and "already registered" both mean
                            // nothing happened, and only Apple's own text says
                            // which of them it was.
                            desktopNotice = MacOsTunnelDaemon.message().ifBlank { null }
                        },
                        isConnectionActive = homeState.isVpnConnected,
                        subscriptionSettings = subscriptionSettings,
                        routingSettings = routingSettings,
                        onRoutingSettingsChanged = dependencies.homeViewModel::updateRoutingSettings,
                        routingModes = desktopRoutingModes(),
                        routingUnavailableReason = desktopRoutingUnavailableReason(effectiveConnectionMode?.mode)?.let { localizedDesktopText(it) },
                        routingNote = desktopRoutingNote()?.let { localizedDesktopText(it) },
                        onSubscriptionSettingsChanged =
                            dependencies.homeViewModel::updateSubscriptionSettings,
                        onDismiss = { showDesktopSettings = false },
                        onSaveLogsClick = {
                            chooseSaveFile(
                                owner = window,
                                defaultName = dependencies.homeViewModel.suggestedLogsFileName(),
                                title = saveLogsDialogTitle
                            )?.let { file ->
                                dependencies.homeViewModel.onSaveLogsToFile(
                                    target = file,
                                    onSaved = { message -> updateMessage = message },
                                    onError = { message -> updateMessage = message }
                                )
                            }
                        },
                        onShareLogsClick = {
                            dependencies.homeViewModel.onShareLogs(
                                onShared = { message -> updateMessage = message },
                                onError = { message -> updateMessage = message }
                            )
                        },
                        onUpdateIntervalSelected = { hours ->
                            scope.launch {
                                saveUpdateSettings(updateSettings.copy(intervalHours = hours))
                            }
                        },
                        onCheckUpdatesClick = { checkUpdate(manual = true) },
                        onDownloadUpdateClick = { info -> downloadUpdate(info) },
                        onLaterUpdateClick = { info -> postponeUpdate(info) },
                        onSubscriptionRouterClick = { url -> routerExportUrl = url },
                        onSubscriptionShareClick = { url ->
                            scope.launch { sharePayload = DesktopText.ServerListQr.load() to ConfigShareService.subscriptionQrText(url) }
                        },
                        onSubscriptionRefreshClick = { url ->
                            dependencies.homeViewModel.refreshSubscription(url) { report ->
                                reloadLocationsAfterImport {
                                    dependencies.homeViewModel.restartVpnIfRunning()
                                    scope.launch { updateMessage = report.localizedSingleMessage() }
                                }
                            }
                        },
                        onSubscriptionDeleteClick = { url ->
                            dependencies.homeViewModel.deleteSubscription(url) { removed ->
                                reloadLocationsAfterImport {
                                    scope.launch {
                                        updateMessage = (if (removed > 0) DesktopText.ServerListRemoved else DesktopText.ServerListNotFound).load()
                                    }
                                }
                            }
                        },
                        onSocksProxySettingsSaved = { username, password, port ->
                            val settings = socksProxySettings.copy(
                                port = port,
                                username = username,
                                password = password
                            ).normalized()
                            dependencies.vpnManager.updateSocksProxySettings(settings)
                            scope.launch {
                                dependencies.socksProxySettingsStore.save(settings)
                            }
                            scope.launch { desktopNotice = DesktopText.SocksSaved.load() }
                            if (homeState.isVpnConnected) {
                                dependencies.homeViewModel.restartVpnIfRunning()
                            }
                        },
                        onLanSharingChanged = { enabled ->
                            dependencies.vpnManager.refreshLanAddresses()
                            val addresses = dependencies.vpnManager.lanAddresses.value
                            var settings = socksProxySettings
                            if (settings.lanUsername.isBlank() || settings.lanPassword.isBlank()) {
                                settings = settings.withGeneratedLanCredentials()
                            }
                            settings = settings.copy(
                                shareOnLan = enabled && settings.lanAddress in addresses
                            ).normalized()
                            dependencies.vpnManager.applyLanSharingSettings(settings)
                            scope.launch { dependencies.socksProxySettingsStore.save(settings) }
                            val notice = when {
                                settings.shareOnLan -> DesktopText.LanEnabled
                                enabled -> DesktopText.LanSelectInterfaceFirst
                                else -> DesktopText.LanDisabled
                            }
                            scope.launch { desktopNotice = notice.load() }
                        },
                        onLanAddressSelected = { address ->
                            val settings = dependencies.vpnManager.withTrustedLanAddress(
                                socksProxySettings, address
                            )
                            dependencies.vpnManager.applyLanSharingSettings(settings)
                            scope.launch { dependencies.socksProxySettingsStore.save(settings) }
                        },
                        onLanCredentialsRegenerated = {
                            val settings = socksProxySettings.withGeneratedLanCredentials().normalized()
                            dependencies.vpnManager.applyLanSharingSettings(settings)
                            scope.launch { dependencies.socksProxySettingsStore.save(settings) }
                            scope.launch { desktopNotice = DesktopText.LanCredentialsRegenerated.load() }
                        },
                        onSocksProxyPasswordRegenerated = {
                            val settings = socksProxySettings.copy(
                                password = generateDesktopProxyPassword()
                            ).normalized()
                            dependencies.vpnManager.updateSocksProxySettings(settings)
                            scope.launch {
                                dependencies.socksProxySettingsStore.save(settings)
                            }
                            scope.launch { desktopNotice = DesktopText.PasswordRegenerated.load() }
                            if (homeState.isVpnConnected) {
                                dependencies.homeViewModel.restartVpnIfRunning()
                            }
                        }
                    )
                }

                updateOffer?.let { info ->
                    ApplicationUpdateOfferSheet(
                        info = info,
                        downloadProgress = updateProgress,
                        onLater = { postponeUpdate(info) },
                        onDownload = { downloadUpdate(info) }
                    )
                }

                routerExportUrl?.let { url ->
                    RouterExportSheet(
                        entries = routerEntries(dependencies.locationViewModel.locations.toList(), url),
                        onCopy = dependencies.homeViewModel::copyToClipboard,
                        onDismiss = { routerExportUrl = null }
                    )
                }

                sharePayload?.let { (title, payload) ->
                    DesktopConfigShareOverlay(
                        title = title,
                        payload = payload,
                        onCopy = {
                            dependencies.configImporter.copyToClipboard(payload)
                            scope.launch { desktopNotice = DesktopText.Copied.load() }
                        },
                        onDismiss = {
                            sharePayload = null
                        }
                    )
                }

                desktopNotice?.let { notice ->
                    DesktopNotice(
                        text = notice,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 24.dp)
                    )
                }
            }
        }
    }
}

/**
 * [AppTheme], plus the room the window's own title bar takes over the content —
 * on macOS the app runs up under a transparent one, so every screen keeps its
 * first row below it while its background carries on to the top edge.
 */
@Composable
private fun DesktopAppTheme(
    windowState: WindowState,
    content: @Composable () -> Unit
) {
    AppTheme {
        CompositionLocalProvider(
            LocalWindowTitleBarInset provides DesktopWindowChrome.titleBarInset(
                fullscreen = windowState.placement == WindowPlacement.Fullscreen
            ),
            content = content
        )
    }
}

@Composable
private fun DesktopConfigShareOverlay(
    title: String,
    payload: String,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    var copied by remember(payload) { mutableStateOf(false) }
    val qrMatrix = remember(payload) {
        runCatching {
            MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, 128, 128)
        }.getOrNull()
    }

    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismiss
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.28f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            val noOpInteraction = remember { MutableInteractionSource() }

            Surface(
                modifier = Modifier
                    .padding(24.dp)
                    .widthIn(max = 440.dp)
                    .clickable(
                        interactionSource = noOpInteraction,
                        indication = null,
                        onClick = {}
                    ),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 12.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = title,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = (if (copied) DesktopText.CopiedToClipboard else DesktopText.ScanOrCopy).text(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                    }

                    if (qrMatrix != null) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .size(240.dp),
                            shape = RoundedCornerShape(20.dp),
                            color = Color.White,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            DesktopQrCode(
                                matrix = qrMatrix,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        SelectionContainer {
                            Text(
                                text = payload,
                                modifier = Modifier.padding(14.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                maxLines = 5,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(DesktopText.Close.text())
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                onCopy()
                                copied = true
                            }
                        ) {
                            Text(DesktopText.Copy.text())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopQrCode(
    matrix: BitMatrix,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(Color.White)
        val cellSize = min(size.width / matrix.width, size.height / matrix.height)
        val qrWidth = cellSize * matrix.width
        val qrHeight = cellSize * matrix.height
        val left = (size.width - qrWidth) / 2f
        val top = (size.height - qrHeight) / 2f

        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                if (matrix[x, y]) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(left + x * cellSize, top + y * cellSize),
                        size = Size(cellSize, cellSize)
                    )
                }
            }
        }
    }
}

@Composable
private fun DesktopNotice(
    text: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            color = MaterialTheme.colorScheme.inverseOnSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun DesktopSocksProxySettings.toApplicationSocksProxySettings(
    lanAddresses: List<String>,
    lanEndpoint: String?,
    lanHealth: String?,
    lanSecurityNotice: String?
): ApplicationSocksProxySettings {
    return ApplicationSocksProxySettings(
        host = host,
        port = port,
        username = username,
        password = password,
        lanSharingSupported = true,
        shareOnLan = shareOnLan,
        lanAddress = lanAddress,
        lanPort = lanPort,
        lanUsername = lanUsername,
        lanPassword = lanPassword,
        lanAddresses = lanAddresses,
        lanEndpoint = lanEndpoint,
        lanHealth = lanHealth,
        lanSecurityNotice = lanSecurityNotice
    )
}

private fun generateDesktopProxyPassword(length: Int = 24): String {
    val random = SecureRandom()
    return buildString(length) {
        repeat(length) {
            append(DESKTOP_PROXY_PASSWORD_ALPHABET[random.nextInt(DESKTOP_PROXY_PASSWORD_ALPHABET.length)])
        }
    }
}

private const val DESKTOP_PROXY_PASSWORD_ALPHABET =
    "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

private fun chooseConfigFile(owner: Frame, title: String): File? {
    val dialog = FileDialog(owner, title, FileDialog.LOAD)
    dialog.isVisible = true

    return dialog.files.firstOrNull()
}

private fun chooseSaveFile(owner: Frame, defaultName: String, title: String): File? {
    val dialog = FileDialog(owner, title, FileDialog.SAVE)
    dialog.file = defaultName
    dialog.isVisible = true

    val fileName = dialog.file ?: return null
    val directory = dialog.directory ?: return File(fileName)

    return File(directory, fileName)
}
